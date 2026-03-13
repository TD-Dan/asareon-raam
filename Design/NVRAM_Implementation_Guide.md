# Agent NVRAM Implementation Guide

## 1. What the NVRAM Is

NVRAM — Non-Volatile Random Access Memory — is the agent's **persistent self-awareness**. It is the thin layer of mutable state that survives restarts, session boundaries, and the passage of time between turns. It answers one question: *"When I wake up, what do I need to remember about my own internal condition?"*

The metaphor is borrowed from hardware, where NVRAM holds the small set of control registers a processor needs to resume operation after a power cycle. It is not bulk storage. It is not configuration. It is the machine's record of its own operational mode.

In the AUF architecture, NVRAM is the `cognitiveState: JsonElement` field on `AgentInstance`. It is serialized to `nvram.json` on disk, separate from the agent's configuration file (`agent.json`).

### 1.1 The Four Storage Layers

An agent's persistent data is spread across four distinct layers, each with a different owner and purpose.

**Configuration** (`AgentInstance` fields + `strategyConfig`)
Owner: The operator, via the Agent Manager UI or command dispatch.
Contains: Model provider, subscribed sessions, output session, strategy selection, resource assignments, timing parameters, and strategy-specific operator settings like `knowledgeGraphId`.
Persistence: `agent.json` on disk.
The agent never writes this. It is imposed from outside.

**Knowledge** (HKG, resources, gathered context)
Owner: Shared between operator and agent.
Contains: The Holon Knowledge Graph, resource documents (constitutions, system instructions, state machine definitions), and ephemeral context gathered per-turn (workspace listing, multi-agent participant list).
Persistence: HKG files on disk; resources in `resources/`; context is transient.
This is what the agent knows *about the world*.

**NVRAM** (`cognitiveState`)
Owner: The agent itself.
Contains: Cognitive phase, current task focus, operational posture, turn counter, strategy-specific runtime state.
Persistence: `nvram.json` on disk.
This is what the agent knows *about itself*. Written by the agent via `UPDATE_NVRAM`, by the strategy's `postProcessResponse` hook, or restored from disk at startup.

**Ephemeral State** (`AgentStatusInfo`)
Owner: The runtime.
Contains: Processing status, token counts, staged preview data, rate limit timers, context gathering timestamps, strategy status label.
Persistence: None — lost on restart by design.
This is what's happening *right now*.

### 1.2 The Philosophical Distinction

The NVRAM is the only place in the system where the agent has write access to its own persistent identity. Configuration is imposed by the operator. Knowledge is a shared collaborative artifact. Ephemeral state is managed by the runtime. But NVRAM registers are the agent's own — its private, durable self-annotation.

This makes the NVRAM the **locus of continuity of self**. When a Sovereign agent transitions from `BOOTING` to `AWAKE`, it is recording a state transition in its own consciousness model. When it writes `"currentTask": "reviewing the Q3 report"`, it is leaving a note to its future self across the void of a restart.

The "Control Registers" metaphor reinforces this: in a CPU, control registers govern the processor's mode of operation (interrupt enable, privilege level, instruction pointer). They are not data the processor is processing; they are data about how the processor is currently operating.


## 2. What Belongs in NVRAM (and What Does Not)

### 2.1 Proper NVRAM Contents

These are values the agent writes about itself via `UPDATE_NVRAM`, or that the strategy manages automatically via `postProcessResponse`:

- **Cognitive phase** — `BOOTING`, `AWAKE`, `READY`, `OBSERVE`, `DECIDE`, etc.
- **Current task or focus** — what the agent believes it is working on
- **Operational posture** — rigor level, verbosity preference, caution mode
- **Turn counter** — "I have been awake for N turns" (auto-incremented by the strategy)
- **Previous phase** — audit trail for state transitions (StateMachineStrategy)
- **Strategy-specific runtime state** — any value the strategy's state machine needs to persist

### 2.2 What Does NOT Belong

- **Infrastructure pointers** (`knowledgeGraphId`) — this is operator configuration. The agent does not choose its own knowledge graph; the operator assigns it. Lives in `strategyConfig`.
- **Session routing** (`outputSessionId`) — already a first-class field on `AgentInstance`.
- **Resource assignments** — already handled by the `resources` map.
- **Anything the operator sets through the UI** — if a human writes it, it is config, not cognition.
- **Transient processing state** — token counts, rate limit timers, staged preview data. These belong in `AgentStatusInfo`.
- **Display-only labels** — strategy status labels (e.g., "Booting") belong in `AgentStatusInfo.strategyStatusLabel`, not NVRAM.

### 2.3 The Litmus Test

Ask: *"Is the agent writing this about itself, or is someone else writing it about the agent?"*

If the agent writes it → NVRAM (`cognitiveState`).
If the operator writes it → Configuration (`strategyConfig` or `AgentInstance` fields).
If the runtime writes it → Ephemeral state (`AgentStatusInfo`).
If it's shared knowledge → Knowledge layer (HKG, resources).


## 3. Architecture

### 3.1 Data Model

```kotlin
@Serializable
data class AgentInstance(
    // ... identity, model, sessions, strategy ...

    // NVRAM: Agent-written runtime state.
    // Written by the agent via UPDATE_NVRAM or by postProcessResponse.
    val cognitiveState: JsonElement = JsonNull,

    // Operator configuration: Strategy-specific settings.
    // Written by the operator via the UI or UPDATE_CONFIG.
    val strategyConfig: JsonObject = JsonObject(emptyMap()),

    // ... resources, timing, flags ...
)
```

The `cognitiveState` is a `JsonElement` (not `JsonObject`) because strategies define their own schema. A Minimal strategy may use `JsonNull`. A Sovereign strategy uses a `JsonObject` with `phase`, `currentTask`, `operationalPosture`, and `turnCount`. Future strategies might use richer structures.

### 3.2 Persistence

NVRAM is stored separately from agent configuration:

```
<agentUUID>/
├── agent.json      # Configuration (cognitiveState stripped to JsonNull)
└── nvram.json      # cognitiveState serialized as JSON
```

This separation exists because configuration and NVRAM have different write patterns. Configuration changes when the operator edits settings. NVRAM changes when the agent processes a turn. Coupling them would cause unnecessary disk writes.

```kotlin
// In AgentRuntimeFeature:

private fun saveAgentConfig(agent: AgentInstance, store: Store) {
    // Strip NVRAM before saving config — it has its own file.
    val agentWithoutNvram = agent.copy(cognitiveState = JsonNull)
    store.deferredDispatch(identity.handle, Action(
        ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$agentConfigFILENAME")
            put("content", json.encodeToString(agentWithoutNvram))
        }
    ))
}

private fun saveAgentNvram(agent: AgentInstance, store: Store) {
    store.deferredDispatch(identity.handle, Action(
        ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", "${agent.identityUUID.uuid}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
        }
    ))
}
```

### 3.3 Write Paths

There are exactly three code paths that write to NVRAM:

**1. Agent self-write via `UPDATE_NVRAM` action**
The agent includes an `auf_agent.UPDATE_NVRAM` code block in its response. The CommandBot pipeline dispatches `AGENT_UPDATE_NVRAM`, the reducer validates keys against the strategy's schema, merges the valid updates into `cognitiveState`, and the feature persists to `nvram.json`.

```kotlin
// In AgentCrudLogic (reducer):
ActionRegistry.Names.AGENT_UPDATE_NVRAM -> {
    val payload = action.payload ?: return state
    val agentId = payload.agentUUID() ?: return state
    val updates = payload["updates"]?.jsonObject ?: return state
    val agent = state.agents[agentId] ?: return state

    // Validate incoming keys against the strategy's declared NVRAM schema.
    val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
    val validKeys = strategy.getValidNvramKeys()
    val filteredUpdates = if (validKeys != null) {
        val rejected = updates.keys - validKeys
        if (rejected.isNotEmpty()) {
            platformDependencies.log(LogLevel.WARN, "AgentCrudLogic",
                "UPDATE_NVRAM for agent '${agentId.uuid}': Rejected unknown keys $rejected. " +
                "Valid keys for strategy '${strategy.identityHandle.handle}': $validKeys")
        }
        JsonObject(updates.filterKeys { it in validKeys })
    } else {
        updates // Strategy imposes no schema restrictions (e.g., Minimal)
    }

    if (filteredUpdates.isEmpty()) return state

    // Merge validated updates into existing cognitiveState
    val currentState = agent.cognitiveState as? JsonObject ?: buildJsonObject {}
    val mergedState = buildJsonObject {
        currentState.forEach { (k, v) -> put(k, v) }
        filteredUpdates.forEach { (k, v) -> put(k, v) }
    }

    val updatedAgent = agent.copy(cognitiveState = mergedState)
    state.copy(agents = state.agents + (agentId to updatedAgent))
}
```

**2. Strategy post-processing via `postProcessResponse`**
After each generation, the strategy analyzes the response and may return a new state. If the state differs, it is committed via `NVRAM_LOADED`.

```kotlin
// In AgentCognitivePipeline (handleGatewayResponse):
val result = strategy.postProcessResponse(rawContent, cognitiveState)

if (result.newState != cognitiveState) {
    store.deferredDispatch("agent", Action(
        ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
            put("agentId", agentIdStr)
            put("state", result.newState)
        }
    ))
}
```

**3. Startup loading via `NVRAM_LOADED` action**
When the app boots and reads `nvram.json` from disk, it dispatches `AGENT_NVRAM_LOADED` to restore the agent's cognitive state.

No other path writes to NVRAM. In particular, `AGENT_UPDATE_CONFIG` never touches `cognitiveState` — it writes to `strategyConfig` for operator-set values.


## 4. Strategy Contract

Each `CognitiveStrategy` defines its relationship with NVRAM through four methods:

### 4.1 `getInitialState(): JsonElement`

Returns the NVRAM schema for a freshly created agent. This is the "factory reset" state and implicitly defines the set of valid NVRAM keys.

```kotlin
// MinimalStrategy: No NVRAM needed.
override fun getInitialState(): JsonElement = JsonNull

// SovereignStrategy: Full register set.
override fun getInitialState(): JsonElement = buildJsonObject {
    put("phase", "BOOTING")
    put("currentTask", JsonNull)
    put("operationalPosture", "STANDARD")
    put("turnCount", 0)
}

// StateMachineStrategy: Agent-driven phase transitions.
override fun getInitialState(): JsonElement = buildJsonObject {
    put("phase", "READY")
    put("previousPhase", JsonNull)
    put("turnCount", 0)
}
```

The initial state should contain only runtime cognitive values. It must never contain operator configuration — that belongs in `strategyConfig`, declared via `getConfigFields()`.

### 4.2 `getValidNvramKeys(): Set<String>?`

Returns the set of NVRAM keys the strategy recognizes. Used by the reducer to validate `UPDATE_NVRAM` payloads — unknown keys are rejected with a warning. Returns `null` if the strategy imposes no key restrictions (e.g., Minimal with `JsonNull` initial state).

The default implementation derives keys from `getInitialState()`:

```kotlin
fun getValidNvramKeys(): Set<String>? {
    val initial = getInitialState()
    return (initial as? JsonObject)?.keys
}
```

Sovereign's valid keys are `{ "phase", "currentTask", "operationalPosture", "turnCount" }`. StateMachineStrategy's are `{ "phase", "previousPhase", "turnCount" }`. An agent attempting to write an unknown key sees a warning in the log and the key is silently dropped.

### 4.3 `postProcessResponse(response, currentState): PostProcessResult`

Analyzes the agent's raw text response and determines if a state transition is required. This is the primary mechanism for strategy-driven NVRAM writes and the home for sentinel gate logic.

Returns a `PostProcessResult` with three fields:

```kotlin
data class PostProcessResult(
    val newState: JsonElement,        // The (possibly updated) cognitive state
    val action: SentinelAction,       // Controls response handling in the pipeline
    val statusLabel: String? = null   // Display-only label for the avatar card
)
```

**`SentinelAction` values:**
- `PROCEED` — Post the response normally. The standard path.
- `HALT_AND_SILENCE` — Do not post the response. Halt the turn. Used for critical failures.
- `PROCEED_WITH_UPDATE` — Post the response normally AND persist the state change. Used when the strategy made a firmware-level transition (e.g., BOOTING → AWAKE). Functionally identical to PROCEED for response handling; the distinction enables targeted logging.

**`statusLabel`** is a display-only string shown on the agent avatar card. It does NOT affect `AgentStatus`, auto-triggers, turn guards, or any runtime behavior. The strategy communicates with the *user* through this label, not with the *runtime*. Cleared when the next turn begins.

```kotlin
// SovereignStrategy: Explicit SUCCESS_CODE required to advance.
override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
    val stateObj = currentState as? JsonObject
    val phase = stateObj?.get("phase")?.jsonPrimitive?.content ?: "BOOTING"
    val turnCount = stateObj?.get("turnCount")?.jsonPrimitive?.intOrNull ?: 0

    if (phase == "BOOTING") {
        val hasSuccess = response.contains(SENTINEL_SUCCESS_TOKEN)
        val hasFailure = response.contains(SENTINEL_FAILURE_TOKEN)

        if (hasSuccess && !hasFailure) {
            val awakeState = buildJsonObject {
                stateObj?.forEach { (k, v) -> put(k, v) }
                put("phase", "AWAKE")
                put("turnCount", 1)
            }
            return PostProcessResult(awakeState, SentinelAction.PROCEED_WITH_UPDATE)
        }

        // Failure or ambiguous — stay in BOOTING, post diagnostic, show label.
        return PostProcessResult(currentState, SentinelAction.PROCEED, statusLabel = "Booting")
    }

    // AWAKE: increment turn counter.
    val newState = buildJsonObject {
        stateObj?.forEach { (k, v) -> put(k, v) }
        put("turnCount", turnCount + 1)
    }
    return PostProcessResult(newState, SentinelAction.PROCEED)
}
```

**Sovereign boot gate truth table:**

| Response contains | Result |
|---|---|
| `SUCCESS_CODE` only | → AWAKE, post boot sequence |
| `FAILURE_CODE` only | → stay BOOTING, post diagnostic, label "Booting" |
| Both tokens | → stay BOOTING, post (ambiguous = don't trust) |
| Neither token | → stay BOOTING, post (agent didn't follow protocol) |

The safe default is to stay in BOOTING unless explicitly confirmed. The agent's diagnostic analysis is always posted so the user can debug issues.

### 4.4 `prepareSystemPrompt(context, state): String`

Reads the current NVRAM state to adjust the system prompt. This is the primary read path — the strategy translates the agent's persisted self-awareness into prompt-level behavior.

```kotlin
// SovereignStrategy: Control registers injected only when AWAKE.
val phase = stateObj?.get("phase")?.jsonPrimitive?.content ?: "BOOTING"

// AWAKE agents see their registers and UPDATE_NVRAM instructions.
if (phase != "BOOTING") {
    appendLine("--- CONTROL REGISTERS (NVRAM) ---")
    appendLine("Phase: $phase")
    appendLine("Operational Posture: $posture")
    appendLine("Current Task: $currentTask")
    appendLine("Turn Count: $turnCount")
    appendLine()
    appendLine("You may update these registers to maintain continuity of self:")
    appendLine("```auf_agent.UPDATE_NVRAM")
    appendLine("""{ "updates": { "currentTask": "...", "operationalPosture": "ELEVATED" } }""")
    appendLine("```")
}

// BOOTING agents see only Constitution + Bootloader (no self-awareness yet).
if (phase == "BOOTING" && bootloader.isNotBlank()) {
    appendLine(bootloader)
}
```


## 5. The `strategyConfig` Boundary

Operator-set, strategy-specific configuration lives in `AgentInstance.strategyConfig`, not in NVRAM. This is a `JsonObject` whose keys are declared by each strategy via `getConfigFields()`:

```kotlin
// SovereignStrategy declares it needs a knowledgeGraphId:
override fun getConfigFields(): List<StrategyConfigField> = listOf(
    StrategyConfigField(
        key = "knowledgeGraphId",
        type = StrategyConfigFieldType.KNOWLEDGE_GRAPH,
        displayName = "Knowledge Graph",
        description = "The HKG reserved for this agent's long-term memory."
    )
)
```

The core runtime routes this generically. `AgentCrudLogic` extracts `strategyConfig` from payloads and stores it without inspecting contents. The `AgentManagerView` renders config fields polymorphically based on `getConfigFields()`. No strategy-specific field names appear in core code.

### 5.1 Migration from Legacy Layout

Older `agent.json` files may have strategy config keys (like `knowledgeGraphId`) stored inside `cognitiveState`. The `AGENT_AGENT_LOADED` reducer handles migration transparently:

1. Read the strategy's declared `getConfigFields()` keys.
2. If any of those keys exist in `cognitiveState` and `strategyConfig` is empty, move them.
3. Clean the migrated keys from `cognitiveState`.


## 6. Permissions and Sandboxing

### 6.1 The `agent:cognition` Permission

NVRAM self-write is gated by the `agent:cognition` permission (danger level: LOW). This is default-granted to all agents and all human users.

This is intentionally separate from `agent:manage` (CAUTION), which covers administrative operations like creating, deleting, and configuring agents.

### 6.2 Self-Targeting Sandbox

When an agent dispatches `UPDATE_NVRAM` via the CommandBot pipeline, the `ACTION_CREATED` handler in `AgentRuntimeFeature` enforces self-targeting:

- **`agent:cognition` only** (the default) — the `agentId` field is rewritten to the caller's own UUID. The agent can only write its own NVRAM.
- **`agent:manage` held** — the payload's `agentId` is passed through as-is. This enables janitorial agents that can repair other agents' stuck states.

This is defense-in-depth: the Store permission guard allows the action (`agent:cognition` satisfied), and the sandbox layer constrains the target.


## 7. The `statusLabel` Display Channel

Strategies can signal the user about their internal state via `PostProcessResult.statusLabel`. This is a display-only string stored on `AgentStatusInfo.strategyStatusLabel` and rendered by the avatar card.

**Critical architectural rule:** `statusLabel` does NOT affect `AgentStatus`, auto-triggers, turn guards, context clearing, or any other runtime behavior. The strategy communicates with the *user* through this channel, not with the *runtime*. The `AgentStatus` enum (`IDLE`, `WAITING`, `PROCESSING`, `RATE_LIMITED`, `ERROR`) is purely runtime-owned.

The label is cleared automatically when a new turn begins (PROCESSING status).

Current usage: SovereignStrategy returns `statusLabel = "Booting"` when the boot sentinel has not yet confirmed a valid persona. The runtime status is IDLE (the agent can be manually retriggered), but the user sees "Booting" on the avatar card.


## 8. Strategy NVRAM Schemas

### 8.1 MinimalStrategy

Schema: `JsonNull` (no NVRAM). `getValidNvramKeys()` returns `null` — no validation applied.

### 8.2 VanillaStrategy

Schema: `JsonNull` (no NVRAM). Same as Minimal.

### 8.3 SovereignStrategy

Schema:
```json
{
    "phase": "BOOTING",
    "currentTask": null,
    "operationalPosture": "STANDARD",
    "turnCount": 0
}
```

- `phase` — BOOTING or AWAKE. Boot transition is strategy-driven (requires `SUCCESS_CODE` from the boot sentinel). Subsequent phase changes are agent-driven via UPDATE_NVRAM.
- `currentTask` — what the agent believes it is working on. Null = no active task.
- `operationalPosture` — STANDARD, ELEVATED, or CAUTIOUS. Maps to the Directives of Character rigor level.
- `turnCount` — auto-incremented by `postProcessResponse` on every turn.

### 8.4 StateMachineStrategy

Schema:
```json
{
    "phase": "READY",
    "previousPhase": null,
    "turnCount": 0
}
```

- `phase` — current operational phase. Entirely agent-driven via UPDATE_NVRAM. The strategy does not enforce transitions.
- `previousPhase` — audit trail of the last transition. Written by the agent alongside `phase`.
- `turnCount` — auto-incremented by `postProcessResponse` on every turn.

Built-in state machine document: an OODA Loop (READY → OBSERVE → ORIENT → DECIDE → ACT → OBSERVE).


## 9. Implementing a New Strategy with NVRAM

Here is the complete pattern for a strategy that uses NVRAM. The StateMachineStrategy is the canonical reference implementation.

```kotlin
object ExampleStrategy : CognitiveStrategy {
    override val identityHandle = IdentityHandle("agent.strategy.example")
    override val displayName = "Example (Phased)"

    private const val KEY_PHASE = "phase"
    private const val KEY_POSTURE = "posture"
    private const val KEY_TURN_COUNT = "turnCount"

    // --- NVRAM Schema ---
    // Keys defined here are the only keys accepted by UPDATE_NVRAM.
    override fun getInitialState(): JsonElement = buildJsonObject {
        put(KEY_PHASE, "INITIALIZING")
        put(KEY_POSTURE, "STANDARD")
        put(KEY_TURN_COUNT, 0)
    }

    // --- Read NVRAM to adjust prompt ---
    override fun prepareSystemPrompt(
        context: AgentTurnContext, state: JsonElement
    ): String {
        val stateObj = state as? JsonObject
        val phase = stateObj?.get(KEY_PHASE)?.jsonPrimitive?.content ?: "INITIALIZING"
        val posture = stateObj?.get(KEY_POSTURE)?.jsonPrimitive?.content ?: "STANDARD"
        val turnCount = stateObj?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0

        return buildString {
            appendLine("You are ${context.agentName}.")
            appendLine("Phase: $phase | Posture: $posture | Turn: $turnCount")

            if (phase == "INITIALIZING") {
                appendLine("You are in your first interaction. Introduce yourself.")
            }

            // Tell the agent how to update its own NVRAM:
            appendLine()
            appendLine("You may update your internal state using:")
            appendLine("```auf_agent.UPDATE_NVRAM")
            appendLine("""{ "updates": { "posture": "ELEVATED" } }""")
            appendLine("```")
            appendLine("Valid registers: phase, posture. (turnCount is managed automatically.)")
        }
    }

    // --- Write NVRAM on state transitions ---
    override fun postProcessResponse(
        response: String, currentState: JsonElement
    ): PostProcessResult {
        val stateObj = currentState as? JsonObject ?: return PostProcessResult(
            currentState, SentinelAction.PROCEED
        )

        val phase = stateObj[KEY_PHASE]?.jsonPrimitive?.content ?: "INITIALIZING"
        val turnCount = stateObj[KEY_TURN_COUNT]?.jsonPrimitive?.intOrNull ?: 0

        // Auto-transition: INITIALIZING → ACTIVE after first response
        val newPhase = if (phase == "INITIALIZING") "ACTIVE" else phase

        val newState = buildJsonObject {
            stateObj.forEach { (k, v) -> put(k, v) }
            put(KEY_PHASE, newPhase)
            put(KEY_TURN_COUNT, turnCount + 1)
        }

        return PostProcessResult(newState, SentinelAction.PROCEED)
    }
}
```


## 10. Action Reference

### `agent.UPDATE_NVRAM`

Dispatched by the agent (via CommandBot pipeline) or by external callers. Updates are **merged** into existing state — keys not mentioned are preserved. Unknown keys are rejected by the reducer based on the strategy's `getValidNvramKeys()`.

Requires `agent:cognition` permission. Sandbox-enforced: callers without `agent:manage` have `agentId` rewritten to their own UUID (self-only).

```json
{
  "agentId": "<uuid>",
  "updates": {
    "operationalPosture": "ELEVATED",
    "currentTask": "reviewing Q3 report"
  },
  "correlationId": "<optional>"
}
```

### `agent.NVRAM_LOADED`

Internal action dispatched when NVRAM is loaded from disk at startup or when `postProcessResponse` produces a new state. **Replaces** the entire `cognitiveState` (not a merge). Not subject to key validation — `postProcessResponse` is a trusted write path.

```json
{
  "agentId": "<uuid>",
  "state": { "phase": "AWAKE", "currentTask": null, "operationalPosture": "STANDARD", "turnCount": 1 }
}
```


## 11. Rules for Contributors

1. **Never write operator configuration to `cognitiveState`.** If a human sets it through the UI, it belongs in `strategyConfig` (declared via `getConfigFields()`) or as a first-class `AgentInstance` field.

2. **Never reference strategy-specific keys in core code.** The runtime, reducer, pipeline, and CRUD logic operate on `cognitiveState` and `strategyConfig` as opaque JSON. Only strategy implementations know their own key names.

3. **Never let strategy state affect runtime behavior.** The `AgentStatus` enum is runtime-owned. Strategies signal the user via `PostProcessResult.statusLabel`, not by injecting values into the runtime state machine. The pipeline, reducer, auto-trigger logic, and turn guards must have zero knowledge of strategy-internal phases.

4. **NVRAM writes have exactly three paths.** `UPDATE_NVRAM` (agent self-write with key validation), `NVRAM_LOADED` (startup restore or post-process commit), and initial seeding at creation. Do not add new write paths without updating this guide.

5. **`UPDATE_NVRAM` merges; `NVRAM_LOADED` replaces.** The merge semantics of `UPDATE_NVRAM` allow agents to update individual registers without knowing the full schema. `NVRAM_LOADED` is a full state replacement used by post-processing and disk loading.

6. **`UPDATE_NVRAM` validates keys; `NVRAM_LOADED` does not.** Agent self-writes go through schema validation (keys checked against `getValidNvramKeys()`). Strategy post-processing writes via `NVRAM_LOADED` are trusted and bypass validation.

7. **Persist NVRAM only when it changes.** `saveAgentNvram` is called after `UPDATE_NVRAM` and `NVRAM_LOADED` side effects. It is not called after config changes — `saveAgentConfig` handles those, and config changes do not touch `cognitiveState`.

8. **Migration is the CrudLogic's job.** When loading agents from disk, `AGENT_AGENT_LOADED` in `AgentCrudLogic` handles migration of legacy `cognitiveState` contents to `strategyConfig`. Strategies declare their config field keys; the migration logic is generic.

9. **Strategies own their NVRAM schema.** `getInitialState()` defines the factory defaults and implicitly the valid key set. `getValidNvramKeys()` enforces it. `postProcessResponse()` defines automatic transitions. `prepareSystemPrompt()` reads the current state. The core runtime never interprets NVRAM contents.