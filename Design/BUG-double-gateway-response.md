# BUG: Context-Gathering Timeout Races Against Gateway Response, Causing Double Execution

**Severity:** High  
**Component:** `AgentCognitivePipeline.evaluateFullContext` / `AgentRuntimeReducer`  
**Affected Agents:** Any agent whose gateway round-trip exceeds the 10-second context-gathering timeout  
**Observed On:** Gekko (openai/gpt-5-nano) — the only agent in the test session with >10s generation latency

---

## Symptom

Agent Gekko produces two distinct responses to a single user message. The logs show a single `gateway.GENERATE_CONTENT` dispatch at 12:35:43 but **two** `gateway.RETURN_RESPONSE` deliveries (12:35:49 and 12:36:01) with different content and different output token counts (1602 vs 1769). All other agents (Nano, Haiku, Flash, Dog, Squirrel) respond exactly once.

## Root Cause

A race condition between the context-gathering timeout and the gateway response path. The convergence gate in `evaluateFullContext` has no protection against being called a second time after it has already dispatched `executeTurn`.

### Detailed Trace

#### 1. Context gathering starts (~12:35:40)

`evaluateTurnContext` fires for Gekko. It:

- Sets `contextGatheringStartedAt = T` in agent status (via `AGENT_SET_CONTEXT_GATHERING_STARTED`)
- Dispatches a workspace listing request
- Schedules a 10-second timeout via `store.scheduleDelayed(10_000L, ...)` carrying `startedAt = T`

#### 2. First `executeTurn` (~12:35:42)

The workspace listing returns quickly. `evaluateFullContext` is invoked via the `AGENT_SET_WORKSPACE_CONTEXT` side effect. For Vanilla strategy, `needsAdditionalContext()` returns `false`, so all gate conditions are satisfied. `executeTurn` dispatches `GATEWAY_GENERATE_CONTENT`. The OpenAI API call is logged at 12:35:43.

**Critical gap:** Neither `evaluateFullContext` nor `executeTurn` clears `contextGatheringStartedAt`. The only mechanism that clears it is `handleSetStatus` when `shouldClearContext` evaluates to `true` — which won't happen until the gateway response arrives and triggers `SET_STATUS(IDLE)`.

#### 3. The race at ~12:35:50

The first API response arrives at 12:35:49. `handleGatewayResponse` **defers** a burst of actions into the store queue: `session.POST`, `SET_STATUS(IDLE)`, avatar operations, and `SET_STATUS(IDLE, tokens)`.

Before these deferred actions are fully reduced, the 10-second timeout fires (~12:35:50). The timeout handler in `AgentRuntimeFeature.handleSideEffects` runs the stale-timeout guard:

```kotlin
if (statusInfo.contextGatheringStartedAt != startedAt) {
    return  // stale — ignore
}
AgentCognitivePipeline.evaluateFullContext(agentId, store, isTimeout = true)
```

Because `SET_STATUS(IDLE)` is still queued and hasn't cleared `contextGatheringStartedAt` yet, the timestamps match. The guard passes.

#### 4. Second `executeTurn`

`evaluateFullContext(isTimeout = true)` re-enters the gate. All conditions still hold:

- `contextGatheringStartedAt != null` — not yet cleared
- `stagedTurnContext != null` — not yet cleared
- `workspaceReady` — still in state
- `additionalContextReady` — Vanilla always true

The `isTimeout` path calls `executeTurn` unconditionally ("proceed without missing context"). A second `GATEWAY_GENERATE_CONTENT` is dispatched with the same `correlationId`. The `GatewayFeature` silently overwrites the job in `activeRequests` without cancelling the first (which has already completed by now), and the second API call runs to completion, producing a second `RETURN_RESPONSE` at 12:36:01.

### Why Only Gekko

All other agents complete their gateway round-trip well under 10 seconds. By the time their timeout fires, `SET_STATUS(IDLE)` has already been processed and `contextGatheringStartedAt` has been nulled, so the stale-timeout guard correctly discards it. Gekko's ~6-second generation time plus queue processing pushes the total just past the 10-second window.

## Impact

- Duplicate messages posted to the session from the same agent for the same turn
- Double token consumption (both API calls run to completion)
- Confusing UX — the agent appears to respond twice with different content
- The second response includes additional context (other agents' replies that arrived during Gekko's first generation), which makes the duplicate non-obvious

---

## Proposed Fix

### Primary Fix: Clear the gate on entry in `evaluateFullContext`

Once `evaluateFullContext` determines all conditions are met and calls `executeTurn`, it should immediately clear `contextGatheringStartedAt` **before** the deferred gateway dispatch. This neutralises the timeout's stale-guard:

```kotlin
// AgentCognitivePipeline.kt — evaluateFullContext

if (workspaceReady && additionalContextReady) {
    // FIX: Close the gate immediately so the timeout cannot re-enter
    store.deferredDispatch("agent", Action(
        ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED,
        buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", JsonNull)
        }
    ))
    executeTurn(agent, ledgerContext, statusInfo.transientHkgContext, state, store)
} else if (isTimeout) {
    // Same fix for the timeout path
    store.deferredDispatch("agent", Action(
        ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED,
        buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", JsonNull)
        }
    ))
    // ... existing timeout logging ...
    executeTurn(agent, ledgerContext, statusInfo.transientHkgContext, state, store)
}
```

The reducer for `AGENT_SET_CONTEXT_GATHERING_STARTED` should handle a null/JsonNull `startedAt` by clearing the field:

```kotlin
ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED -> {
    val agentId = action.payload?.agentUUID() ?: return state
    val startedAt = action.payload?.get("startedAt")?.jsonPrimitive?.longOrNull // null if JsonNull
    val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
    val updatedStatus = currentStatus.copy(contextGatheringStartedAt = startedAt)
    state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
}
```

### Secondary Fix (Defence-in-Depth): Duplicate correlationId guard in GatewayFeature

`handleGenerateContent` should reject or warn on a `GENERATE_CONTENT` dispatch whose `correlationId` is already in `activeRequests`:

```kotlin
// GatewayFeature.kt — handleGenerateContent, before launching the job

if (activeRequests.containsKey(correlationId)) {
    platformDependencies.log(
        LogLevel.WARN, identity.handle,
        "DROPPED GENERATE_CONTENT: correlationId '$correlationId' is already in-flight. " +
        "This indicates a duplicate dispatch from the caller."
    )
    return
}
```

This prevents any future race conditions from reaching the API layer, regardless of the upstream cause.

### Alternative Consideration

Instead of reusing `AGENT_SET_CONTEXT_GATHERING_STARTED` for clearing, a cleaner approach may be introducing a dedicated `AGENT_TURN_DISPATCHED` action or a `turnPhase` enum on `AgentStatusInfo` (`IDLE → GATHERING_CONTEXT → TURN_DISPATCHED → PROCESSING`). This would make the state machine explicit and provide a single, unambiguous guard for all re-entry paths. However, this is a larger refactor and the primary fix above addresses the immediate issue.
