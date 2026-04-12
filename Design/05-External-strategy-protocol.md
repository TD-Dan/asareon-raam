# ASAREON RAAM — External Strategy Protocol (DRAFT)

**Version 1.0.0-alpha** · Companion to 01-System-architecture, 04-Lua-scripting

---

## Overview

The External Strategy Protocol allows features outside the agent runtime to register cognitive strategies via the action bus. This enables scripting runtimes (Lua, future Python), plugins, or any feature to provide agent cognition without importing agent types or creating compile-time dependencies.

The protocol follows the **Absolute Decoupling** principle: the agent feature knows nothing about Lua, Python, or any specific runtime. It only knows how to communicate with "external strategy providers" through a generic action-based protocol.


## Architecture

```
CognitivePipeline                         External Feature (e.g., Lua)
    |                                         |
    |  (1) context gathering completes        |
    |  (2) assembleContext() builds prompt     |
    |                                         |
    |-- EXTERNAL_TURN_REQUEST --------------->|  (targeted to featureHandle)
    |   { agentId, correlationId,             |
    |     systemPrompt, state,                |  Feature processes the turn:
    |     modelProvider, modelName }           |    - inspects/modifies prompt
    |                                         |    - runs script logic
    |                                         |    - decides: advance/custom/error
    |                                         |
    |<-- EXTERNAL_TURN_RESULT ----------------|  (public hidden, back to agent)
    |   { correlationId, mode,                |
    |     systemPrompt?, response?,           |  Pipeline handles result:
    |     state?, error? }                    |    advance -> gateway
    |                                         |    custom -> post directly
    |                                         |    error -> abort turn
```


## Registration

### Action: `agent.REGISTER_EXTERNAL_STRATEGY`

Any feature dispatches this action during `SYSTEM_RUNNING` to register a cognitive strategy.

**Type:** Public, non-broadcast

**Payload:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `strategyId` | string | yes | Identity handle (e.g., `"agent.strategy.lua"`) |
| `displayName` | string | yes | Shown in the strategy selector UI |
| `featureHandle` | string | yes | The bus handle of the provider feature (e.g., `"lua"`) |
| `resourceSlots` | array | no | Resource slot declarations for the Agent Manager |
| `configFields` | array | no | Strategy-specific config fields for the UI |
| `initialState` | object | no | Default NVRAM state for new agents |

**Resource Slot Format:**

```json
{
  "slotId": "system_instruction",
  "type": "SYSTEM_INSTRUCTION",
  "displayName": "System Instructions",
  "description": "Context instructions for the script.",
  "isRequired": false
}
```

**Config Field Format:**

```json
{
  "key": "outputSessionId",
  "type": "OUTPUT_SESSION",
  "displayName": "Output Session",
  "description": "Where the agent's responses are posted."
}
```

**Example:**

```kotlin
store.deferredDispatch("lua", Action(
    name = "agent.REGISTER_EXTERNAL_STRATEGY",
    payload = buildJsonObject {
        put("strategyId", "agent.strategy.lua")
        put("displayName", "Lua Script")
        put("featureHandle", "lua")
        put("resourceSlots", buildJsonArray { ... })
        put("configFields", buildJsonArray { ... })
        put("initialState", buildJsonObject { put("phase", "READY") })
    }
))
```

### Response: `agent.RETURN_STRATEGY_REGISTERED`

Targeted response back to the registering feature.

| Field | Type | Description |
|-------|------|-------------|
| `strategyId` | string | The registered strategy handle |
| `success` | boolean | Whether registration succeeded |
| `error` | string? | Error message if failed |

### Unregistration: `agent.UNREGISTER_EXTERNAL_STRATEGY`

Removes a previously registered external strategy. Agents using it fall back to the default strategy.

| Field | Type | Description |
|-------|------|-------------|
| `strategyId` | string | The strategy handle to remove |


## ExternalStrategyProxy

When `REGISTER_EXTERNAL_STRATEGY` is handled by the agent feature, it creates an `ExternalStrategyProxy` instance and registers it in `CognitiveStrategyRegistry`. This proxy implements the `CognitiveStrategy` interface internally.

### Behavior

| Method | Behavior |
|--------|----------|
| `buildPrompt()` | Builds prompt normally (identity, instructions, sessions, everything else) |
| `postProcessResponse()` | Passthrough — returns state unchanged with `PROCEED` |
| `requestAdditionalContext()` | Returns `false` — no async context from strategy side |
| `needsAdditionalContext()` | Returns `false` |
| `getValidNvramKeys()` | Returns `null` — accepts all NVRAM keys |
| `validateConfig()` | Enforces `outputSessionId` is within subscribed sessions |

The proxy stores the `featureHandle` so the pipeline knows where to send the turn request.


## Turn Execution

### Pipeline Integration

In `CognitivePipeline.executeTurn()`, after context assembly:

```
if (strategy is ExternalStrategyProxy) {
    dispatch EXTERNAL_TURN_REQUEST targeted to strategy.featureHandle
} else {
    dispatch GATEWAY_GENERATE_CONTENT (normal path)
}
```

### Action: `agent.EXTERNAL_TURN_REQUEST`

Dispatched by the pipeline to the external feature. Targeted delivery.

| Field | Type | Description |
|-------|------|-------------|
| `agentId` | string | Agent UUID |
| `correlationId` | string | Agent UUID (used for response matching) |
| `agentHandle` | string | Agent's bus handle (e.g., `"agent.meridian"`) |
| `systemPrompt` | string | The fully assembled system prompt |
| `state` | object | Current agent NVRAM |
| `modelProvider` | string | Configured LLM provider |
| `modelName` | string | Configured model name |

### Action: `agent.EXTERNAL_TURN_RESULT`

Dispatched by the external feature after processing. Public hidden action.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `correlationId` | string | yes | Must match the request's correlationId |
| `mode` | string | yes | `"advance"`, `"custom"`, or `"error"` |
| `systemPrompt` | string | for advance | Modified system prompt (for gateway) |
| `response` | string | for custom | Direct response text to post |
| `state` | object | no | Updated NVRAM (any mode) |
| `error` | string | for error | Error message |


## Result Modes

### `advance`

The external feature approved the turn. The pipeline dispatches `GATEWAY_GENERATE_CONTENT` with the (possibly modified) system prompt. The gateway response flows through the normal post-processing path.

If `systemPrompt` is provided in the result, it overrides the assembled prompt. Otherwise the original assembled prompt is used.

### `custom`

The external feature handled the generation itself. The pipeline posts the `response` text directly to the agent's output session. No gateway call is made.

If the agent has no output session configured, the pipeline logs an error and sets the agent to ERROR status.

### `error`

The external feature aborted the turn. The agent is set to ERROR status with the provided error message.


## Graceful Degradation

### Feature Not Available

If the external feature is not loaded (e.g., Lua disabled), the `EXTERNAL_TURN_REQUEST` goes undelivered. The Store drops targeted actions to non-existent features silently. The agent's turn hangs until the context-gathering timeout fires (10 seconds), at which point the pipeline proceeds with an error.

### Feature Doesn't Respond

If the external feature receives the request but doesn't respond, the same timeout behavior applies. The agent shows "External Strategy Processing" during the wait.

### Strategy Not Registered

If a feature attempts to register a strategy with a missing `strategyId` or `featureHandle`, the agent feature logs an error and the registration is silently skipped. Agents cannot be configured to use an unregistered strategy.


## Lifecycle

### Boot Sequence

1. `Store.initFeatureLifecycles()` — features initialize (BOOTING phase)
2. `system.INITIALIZING` — settings registration
3. `system.RUNNING` — features can dispatch cross-feature actions
4. External feature dispatches `REGISTER_EXTERNAL_STRATEGY` (during SYSTEM_RUNNING handler)
5. Agent feature creates `ExternalStrategyProxy` and registers in `CognitiveStrategyRegistry`
6. Strategy appears in Agent Manager UI dropdown

**Important:** Registration must happen during or after `SYSTEM_RUNNING`. Dispatching during `init()` (BOOTING phase) will be rejected by the lifecycle guard.

### Shutdown

External strategies are not persisted. They exist only in the in-memory `CognitiveStrategyRegistry` and are re-registered on each app startup. Agents that reference a strategy handle that was never registered fall back to the default strategy (Vanilla) via the registry's `get()` fallback.


## Adding a New External Strategy Provider

To add a new scripting runtime (e.g., Python):

1. **Create the feature** (e.g., `PythonFeature`) implementing the `Feature` interface
2. **On `SYSTEM_RUNNING`**, dispatch `agent.REGISTER_EXTERNAL_STRATEGY` with your strategy metadata
3. **Handle `agent.EXTERNAL_TURN_REQUEST`** in your feature's `handleSideEffects`:
   - Read `systemPrompt`, `state`, etc. from the payload
   - Execute your runtime's turn logic
   - Dispatch `agent.EXTERNAL_TURN_RESULT` with the appropriate mode
4. **No agent imports needed** — the entire protocol is action-based

The agent feature, pipeline, and UI automatically support the new strategy without any modifications.
