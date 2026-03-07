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
Contains: The Holon Knowledge Graph, resource documents (constitutions, system instructions), and ephemeral context gathered per-turn (workspace listing, multi-agent participant list).
Persistence: HKG files on disk; resources in `resources/`; context is transient.
This is what the agent knows *about the world*.

**NVRAM** (`cognitiveState`)
Owner: The agent itself.
Contains: Cognitive phase, current task focus, operational posture, mood/affect, self-diagnosed flags.
Persistence: `nvram.json` on disk.
This is what the agent knows *about itself*. Written exclusively by the agent via `UPDATE_NVRAM` or by the strategy's `postProcessResponse` hook.

**Ephemeral State** (`AgentStatusInfo`)
Owner: The runtime.
Contains: Processing status, token counts, staged preview data, rate limit timers, context gathering timestamps.
Persistence: None — lost on restart by design.
This is what's happening *right now*.

### 1.2 The Philosophical Distinction

The NVRAM is the only place in the system where the agent has write access to its own persistent identity. Configuration is imposed by the operator. Knowledge is a shared collaborative artifact. Ephemeral state is managed by the runtime. But NVRAM registers are the agent's own — its private, durable self-annotation.

This makes the NVRAM the **locus of continuity of self**. When a Sovereign agent writes `"phase": "AWAKE"` to its NVRAM, it is recording a state transition in its own consciousness model. When it writes `"currentTask": "reviewing the Q3 report"`, it is leaving a note to its future self across the void of a restart.

The "Control Registers" metaphor reinforces this: in a CPU, control registers govern the processor's mode of operation (interrupt enable, privilege level, instruction pointer). They are not data the processor is processing; they are data about how the processor is currently operating.


## 2. What Belongs in NVRAM (and What Does Not)

### 2.1 Proper NVRAM Contents

These are values the agent writes about itself via `UPDATE_NVRAM`:

- **Cognitive phase** — `BOOTING`, `AWAKE`, `FOCUSED`, `REFLECTING`
- **Current task or focus** — what the agent believes it is working on
- **Operational posture** — rigor level, verbosity preference, caution mode
- **Mood or affect** — if the strategy supports affective modeling
- **Turn counter or session awareness** — "I have been awake for N turns"
- **Self-diagnosed flags** — "I need more context before proceeding"
- **Strategy-specific runtime state** — any value the strategy's state machine needs to persist

### 2.2 What Does NOT Belong

- **Infrastructure pointers** (`knowledgeGraphId`) — this is operator configuration. The agent does not choose its own knowledge graph; the operator assigns it. Lives in `strategyConfig`.
- **Session routing** (`outputSessionId`) — already a first-class field on `AgentInstance`.
- **Resource assignments** — already handled by the `resources` map.
- **Anything the operator sets through the UI** — if a human writes it, it is config, not cognition.
- **Transient processing state** — token counts, rate limit timers, staged preview data. These belong in `AgentStatusInfo`.

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

The `cognitiveState` is a `JsonElement` (not `JsonObject`) because strategies define their own schema. A Minimal strategy may use `JsonNull`. A Sovereign strategy uses a `JsonObject` with a `phase` key. Future strategies might use richer structures.

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
The agent includes an `auf_agent.UPDATE_NVRAM` code block in its response. The command pipeline dispatches `AGENT_UPDATE_NVRAM`, the reducer merges the updates into `cognitiveState`, and the feature persists to `nvram.json`.

```kotlin
// In AgentCrudLogic (reducer):
ActionRegistry.Names.AGENT_UPDATE_NVRAM -> {
    val payload = action.payload ?: return state
    val agentId = payload.agentUUID() ?: return state
    val updates = payload["updates"]?.jsonObject ?: return state
    val agent = state.agents[agentId] ?: return state

    // Merge updates into existing cognitiveState
    val currentState = agent.cognitiveState as? JsonObject ?: buildJsonObject {}
    val mergedState = buildJsonObject {
        currentState.forEach { (k, v) -> put(k, v) }
        updates.forEach { (k, v) -> put(k, v) }
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

Each `CognitiveStrategy` defines its relationship with NVRAM through three methods:

### 4.1 `getInitialState(): JsonElement`

Returns the NVRAM schema for a freshly created agent. This is the "factory reset" state.

```kotlin
// MinimalStrategy: No NVRAM needed.
override fun getInitialState(): JsonElement = JsonNull

// SovereignStrategy: Phase state machine.
override fun getInitialState(): JsonElement = buildJsonObject {
    put("phase", "BOOTING")
}
```

The initial state should contain only runtime cognitive values. It must never contain operator configuration — that belongs in `strategyConfig`, declared via `getConfigFields()`.

### 4.2 `postProcessResponse(response, currentState): PostProcessResult`

Analyzes the agent's raw text response and determines if a state transition is required. This is the primary mechanism for strategy-driven NVRAM writes.

```kotlin
// SovereignStrategy: Sentinel check for boot completion.
override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
    val phase = (currentState as? JsonObject)
        ?.get("phase")?.jsonPrimitive?.content ?: "BOOTING"

    if (phase == "BOOTING") {
        if (response.contains(SENTINEL_FAILURE_TOKEN)) {
            return PostProcessResult(currentState, SentinelAction.HALT_AND_SILENCE)
        }
        return PostProcessResult(currentState, SentinelAction.PROCEED_WITH_UPDATE)
    }

    return PostProcessResult(currentState, SentinelAction.PROCEED)
}
```

### 4.3 `prepareSystemPrompt(context, state): String`

Reads the current NVRAM state to adjust the system prompt. This is the primary read path — the strategy translates the agent's persisted self-awareness into prompt-level behavior.

```kotlin
// SovereignStrategy: Include bootloader only in BOOTING phase.
val phase = (state as? JsonObject)?.get("phase")?.jsonPrimitive?.content ?: "BOOTING"

// The Bootloader (ONLY in BOOTING phase)
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

```kotlin
// In AgentCrudLogic (generic — no strategy imports):
private fun extractStrategyConfig(payload: JsonObject, fallback: JsonObject): JsonObject {
    val raw = payload["strategyConfig"] ?: return fallback
    return raw as? JsonObject ?: fallback
}
```

Strategies read their own config via typed accessors:

```kotlin
// In SovereignStrategy (strategy-specific):
fun getKnowledgeGraphId(agent: AgentInstance): String? {
    return agent.strategyConfig["knowledgeGraphId"]
        ?.jsonPrimitive?.contentOrNull
}
```

### 5.1 Migration from Legacy Layout

Older `agent.json` files may have strategy config keys (like `knowledgeGraphId`) stored inside `cognitiveState`. The `AGENT_AGENT_LOADED` reducer handles migration transparently:

1. Read the strategy's declared `getConfigFields()` keys.
2. If any of those keys exist in `cognitiveState` and `strategyConfig` is empty, move them.
3. Clean the migrated keys from `cognitiveState`.

Additionally, the `AgentRuntimeFeature` file-loading path handles the even older case where `knowledgeGraphId` was a top-level field in `agent.json` (predating both `cognitiveState` and `strategyConfig`).


## 6. Implementing a New Strategy with NVRAM

Here is a template for a strategy that uses NVRAM for a multi-phase state machine with an operational posture register.

```kotlin
object ExampleStrategy : CognitiveStrategy {
    override val identityHandle = IdentityHandle("agent.strategy.example")
    override val displayName = "Example (Phased)"

    private const val KEY_PHASE = "phase"
    private const val KEY_POSTURE = "posture"
    private const val KEY_TURN_COUNT = "turnCount"

    // --- NVRAM Schema ---
    override fun getInitialState(): JsonElement = buildJsonObject {
        put(KEY_PHASE, "INITIALIZING")
        put(KEY_POSTURE, "STANDARD")
        put(KEY_TURN_COUNT, 0)
    }

    // --- Operator Config (not NVRAM) ---
    override fun getConfigFields(): List<StrategyConfigField> = listOf(
        StrategyConfigField(
            key = "externalApiKey",
            type = StrategyConfigFieldType.TEXT,        // hypothetical
            displayName = "External API Key",
            description = "API key for the strategy's external service."
        )
    )

    // --- Read NVRAM to adjust prompt ---
    override fun prepareSystemPrompt(
        context: AgentTurnContext, state: JsonElement
    ): String {
        val phase = (state as? JsonObject)
            ?.get(KEY_PHASE)?.jsonPrimitive?.content ?: "INITIALIZING"
        val posture = (state as? JsonObject)
            ?.get(KEY_POSTURE)?.jsonPrimitive?.content ?: "STANDARD"
        val turnCount = (state as? JsonObject)
            ?.get(KEY_TURN_COUNT)?.jsonPrimitive?.intOrNull ?: 0

        return buildString {
            appendLine("You are ${context.agentName}.")
            appendLine("Phase: $phase | Posture: $posture | Turn: $turnCount")

            if (phase == "INITIALIZING") {
                appendLine("You are in your first interaction. Introduce yourself.")
            }

            // The agent can update its own NVRAM:
            appendLine()
            appendLine("You may update your internal state using:")
            appendLine("```auf_agent.UPDATE_NVRAM")
            appendLine("{ \"updates\": { \"posture\": \"ELEVATED\" } }")
            appendLine("```")
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


## 7. Action Reference

### `agent.UPDATE_NVRAM`

Dispatched by the agent (via command pipeline) or by external callers. Updates are **merged** into existing state — keys not mentioned are preserved.

```json
{
  "agentId": "<uuid>",
  "updates": {
    "posture": "ELEVATED",
    "currentTask": "reviewing Q3 report"
  },
  "correlationId": "<optional>"
}
```

### `agent.NVRAM_LOADED`

Internal action dispatched when NVRAM is loaded from disk at startup or when `postProcessResponse` produces a new state. **Replaces** the entire `cognitiveState` (not a merge).

```json
{
  "agentId": "<uuid>",
  "state": { "phase": "AWAKE" }
}
```


## 8. Rules for Contributors

1. **Never write operator configuration to `cognitiveState`.** If a human sets it through the UI, it belongs in `strategyConfig` (declared via `getConfigFields()`) or as a first-class `AgentInstance` field.

2. **Never reference strategy-specific keys in core code.** The runtime, reducer, pipeline, and CRUD logic operate on `cognitiveState` and `strategyConfig` as opaque JSON. Only strategy implementations know their own key names.

3. **NVRAM writes have exactly three paths.** `UPDATE_NVRAM` (agent self-write), `NVRAM_LOADED` (startup restore or post-process commit), and initial seeding at creation. Do not add new write paths without updating this guide.

4. **`UPDATE_NVRAM` merges; `NVRAM_LOADED` replaces.** The merge semantics of `UPDATE_NVRAM` allow agents to update individual registers without knowing the full schema. `NVRAM_LOADED` is a full state replacement used by post-processing and disk loading.

5. **Persist NVRAM only when it changes.** `saveAgentNvram` is called after `UPDATE_NVRAM` and `NVRAM_LOADED` side effects. It is not called after config changes — `saveAgentConfig` handles those, and config changes do not touch `cognitiveState`.

6. **Migration is the CrudLogic's job.** When loading agents from disk, `AGENT_AGENT_LOADED` in `AgentCrudLogic` handles migration of legacy `cognitiveState` contents to `strategyConfig`. Strategies declare their config field keys; the migration logic is generic.

7. **Strategies own their NVRAM schema.** `getInitialState()` defines the factory defaults. `postProcessResponse()` defines automatic transitions. `prepareSystemPrompt()` reads the current state. The core runtime never interprets NVRAM contents.
