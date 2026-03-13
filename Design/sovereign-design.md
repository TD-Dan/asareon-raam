# Sovereign Strategy Stabilization: Final Design

## Status: FOR RATIFICATION

## Document Version: 4.0 (Final)

---

## 1. Goal

Get Sovereign agents to function. This requires:

1. A universal context collapse system so agents can navigate large HKGs without overloading the context window
2. Rock-solid private session lifecycle for Sovereign's cognition session
3. Working HKG integration with default-collapsed context delivery
4. All of the above composed into a stabilized SovereignStrategy

---

## 2. Design Principles

### 2.1 Agent-First, Pipeline Safety Net

The agent proactively manages its own context budget using `agent.CONTEXT_UNCOLLAPSE` and `agent.CONTEXT_COLLAPSE` commands. The Kotlin pipeline enforces an absolute maximum — if the agent's choices would overflow, the pipeline auto-collapses to fit. The pipeline is the safety net, not the primary mechanism. However, the safety net must be rock solid because LLMs are unreliable at proactive resource management.

### 2.2 Tokens as the Universal Unit

All user-facing and agent-facing numbers use **tokens**. Character-based estimates use the `≈4 chars/token` heuristic and are always labeled with `~` (approximate). Provider-reported values (from `countTokens()`) are shown without qualifier. We do not spam provider token-counting endpoints for per-partition estimates.

### 2.3 Absolute Strategy Decoupling

Every strategy implementation is self-contained. SovereignStrategy imports nothing from VanillaStrategy, PrivateSessionStrategy, HKGStrategy, or any other strategy. If two strategies need identical logic, the code is duplicated. Shared infrastructure lives in the `app.auf.feature.agent` package as pipeline-level utilities (e.g., `HkgContextFormatter`, `ContextCollapseLogic`) — never in the `strategies` subpackage.

### 2.4 Three Files, Three Concerns

Each agent has three separate persistence files:

| File | Owner | Contents |
|------|-------|----------|
| `agent.json` | Operator / CRUD | Identity, config, model, subscriptions, strategy |
| `nvram.json` | Agent (via UPDATE_NVRAM) | Cognitive state: phase, task, posture, turn count |
| `context.json` | Context collapse system | Collapse overrides: which partitions/holons are expanded |

These are independent systems. NVRAM is never involved in context management. Context state is never involved in cognitive state.

---

## 3. Context Collapse System

### 3.1 Two-State Collapse

```kotlin
enum class CollapseState {
    EXPANDED,   // Show full content
    COLLAPSED   // Show summary/header only
}
```

There is no TRUNCATED architectural state. Truncation is a **pipeline sentinel** — when a single expanded partial exceeds a sanity threshold (e.g., 200k chars), the pipeline truncates it and injects a diagnostic message:

```
⚠ PIPELINE SENTINEL: This content partial is very large (~50,000 tokens) and
has been truncated to the first ~10,000 tokens. Use targeted uncollapse commands
to navigate to the section you need, or collapse this partial and work from the
index.
```

This preserves useful partial content while preventing a single runaway partial from consuming the entire budget. Future work can add "view window" navigation for truncated content.

### 3.2 Token Budget Configuration

Per-agent, operator-configured:

```kotlin
// In AgentInstance
val contextBudgetChars: Int = 50_000    // ~12,500 tokens — optimal soft target
val contextMaxBudgetChars: Int = 150_000 // ~37,500 tokens — hard maximum (safety net)
val contextMaxPartialChars: Int = 20_000 // ~5,000 tokens — maximum partial size for the truncation treshold
```

The UI displays these as approximate tokens. Strategies can recommend defaults.

### 3.3 Context Partition Model

```kotlin
data class ContextPartition(
    val key: String,              // e.g., "HOLON_KNOWLEDGE_GRAPH_INDEX"
    val fullContent: String,      // Complete content when EXPANDED
    val collapsedContent: String, // Summary when COLLAPSED
    val charCount: Int,           // fullContent.length
    val state: CollapseState,     // Resolved state after overrides + budget
    val priority: Int = 0         // Higher = collapse last. Constitution = MAX_VALUE
)
```

### 3.4 Auto-Collapse Algorithm

Runs in `executeTurn()` after all context is gathered, before `prepareSystemPrompt()`:

```
1. Build all ContextPartitions from contextMap
2. Apply agent's sticky overrides (from AgentStatusInfo.contextCollapseOverrides)
3. Calculate total chars using each partition's current state
4. If total ≤ contextMaxBudgetChars → proceed
5. If total > contextMaxBudgetChars:
   a. LOG WARNING to file: "Agent '$agentId' context ($total chars) exceeds
      max budget ($max chars). Auto-collapsing."
   b. Sort auto-collapsible partitions by priority ASC, then charCount DESC
      (lowest priority, largest size collapses first)
   c. Collapse each until total ≤ max. Skip agent-overridden partitions.
   d. If STILL over after all auto partitions collapsed:
      Force-collapse agent-expanded partitions (lowest priority first)
      LOG ERROR: "Agent '$agentId': forced override of agent context choices."
6. Check each expanded partition for oversized sentinel threshold
7. Build final contextMap from resolved states
8. Inject CONTEXT_BUDGET_REPORT partition
```

### 3.5 Context Budget Report

Always present as a gathered context partition:

```
--- CONTEXT BUDGET ---
Optimal: ~12,500 tokens | Maximum: ~37,500 tokens
Current load: ~8,200 tokens (approx.)

Manage your context proactively. Uncollapse only what you need for the current
task. Collapse partitions you are done with. The system enforces the maximum
automatically, but operating near your optimal produces better coherence and
lower cost.

Partitions:
  HOLON_KNOWLEDGE_GRAPH_INDEX: EXPANDED (~200 tokens) [always present]
  HOLON_KNOWLEDGE_GRAPH_FILES: COLLAPSED — 0 of 30 files open
  WORKSPACE_FILES: EXPANDED (~300 tokens)
  SESSION_METADATA: EXPANDED (~200 tokens)
  AVAILABLE_ACTIONS: EXPANDED (~2,100 tokens)

To manage:
  agent.CONTEXT_UNCOLLAPSE { "partitionKey": "...", "scope": "single|subtree|full" }
  agent.CONTEXT_COLLAPSE { "partitionKey": "..." }
```

When auto-collapse fires, the report includes a warning:

```
⚠ AUTOMATIC COLLAPSE: Your context exceeded the maximum budget. The following
partitions were automatically collapsed:
  AVAILABLE_ACTIONS: EXPANDED → COLLAPSED (saved ~2,100 tokens)
Review your expanded partitions and collapse what you no longer need.
```

### 3.6 Agent Context Control Actions

```json
{
  "action_name": "agent.CONTEXT_UNCOLLAPSE",
  "payload_schema": {
    "properties": {
      "agentId": { "type": "string" },
      "partitionKey": { "type": "string",
        "description": "Partition key (e.g., 'AVAILABLE_ACTIONS') or 'hkg:<holonId>' for a specific holon." },
      "scope": { "type": "string", "enum": ["full", "subtree", "single"], "default": "single",
        "description": "'single': one holon. 'subtree': holon + reveal children in INDEX. 'full': entire partition." }
    },
    "required": ["agentId", "partitionKey"]
  },
  "required_permissions": ["agent:cognition"]
}

{
  "action_name": "agent.CONTEXT_COLLAPSE",
  "payload_schema": {
    "properties": {
      "agentId": { "type": "string" },
      "partitionKey": { "type": "string" }
    },
    "required": ["agentId", "partitionKey"]
  },
  "required_permissions": ["agent:cognition"]
}
```

### 3.7 Sticky Overrides

Agent collapse/uncollapse choices persist across turns until explicitly changed. Stored in `AgentStatusInfo` and persisted to `context.json`:

```kotlin
// In AgentStatusInfo
val contextCollapseOverrides: Map<String, CollapseState> = emptyMap()
// key = partition key or "hkg:<holonId>"
// value = agent's chosen state
```

The pipeline respects sticky overrides up to the hard maximum. If sticky choices exceed `contextMaxBudgetChars`, the pipeline force-collapses with a warning (§3.4 step 5d).

### 3.8 Persistence: context.json

```json
{
  "version": 1,
  "collapseOverrides": {
    "hkg:meridian-20260312T120000Z": "EXPANDED",
    "hkg:shared-knowledge-base-seed-20260312T120000Z": "COLLAPSED",
    "AVAILABLE_ACTIONS": "EXPANDED"
  }
}
```

**Lifecycle:**
- Loaded at startup alongside agent.json and nvram.json
- Written on change (debounced, same pattern as nvram.json)
- Missing or corrupt file → empty overrides + warning log (graceful degradation)

---

## 4. HKG View: Index + Files

### 4.1 Rationale

The HKG view is split into two context partitions: an INDEX (navigational map) and a FILES section (open file contents). This mirrors the agent's mental model — HKGs are collections of JSON files, both inside the AUF App and in manual mode.

- **INDEX** is always present, never collapsed by the budget system. It is the agent's navigational awareness of its entire knowledge graph. Graph hygiene (pruning sprawling trees) is the agent's responsibility, not the pipeline's.
- **FILES** carries all the token weight and is subject to the budget algorithm. Default: all files closed.

### 4.2 INDEX Partition

Built from holon headers only (id, type, name, summary, sub_holons). Uses 2-space indentation per depth level:

```
--- HOLON_KNOWLEDGE_GRAPH_INDEX ---
Persona: Meridian | Total holons: 30

meridian-20260312T120000Z (AI_Persona_Root) — "Meridian" [EXPANDED]
  A cartographic intelligence oriented toward rigorous analysis...

  meridian-foundational-core-20260312T120000Z (Project) [COLLAPSED]
    Meridian's origin story, first memories, and self-model emergence.
    <contains 4 sub-holons>

  meridian-session-logs-20260312T120000Z (Project) [COLLAPSED]
    A master project and chronicle for all sessions.
    <contains 152 sub-holons>

  shared-knowledge-base-seed-20260312T120000Z (Project) [EXPANDED]
    The objective, universal knowledge artifacts for safe operation.

    system-holon-definition-20250809T101500Z (System_File) [COLLAPSED]
      The canonical definition of a Holon...

    cognitive-toolkit-core-20250805T180124Z (System_File) [COLLAPSED]
      A verified set of mature cognitive techniques...

    ... (9 more sub-holons)

  processes-seed-20260312T120000Z (Project) [COLLAPSED]
    The defined processes and lifecycle protocols.
    <contains 3 sub-holons>
```

**Rules:**
- COLLAPSED branch: shows holon ID + type + summary + `<contains N sub-holons>` badge. All children are hidden.
- EXPANDED branch (in INDEX): shows its immediate children (with their summaries). Children themselves may be COLLAPSED.
- `[EXPANDED]` tag means the holon's file is also open in the FILES section.
- `[COLLAPSED]` means the file is not open. Uncollapsing it would add it to FILES.
- Sub-holon count is recursive (counts grandchildren, etc.).

### 4.3 FILES Partition

Lists all EXPANDED holons as complete JSON files:

```
--- HOLON_KNOWLEDGE_GRAPH_FILES ---
Files currently open: 2 of 30

--- START OF FILE meridian-20260312T120000Z.json ---
{
  "header": { ... },
  "execute": { ... },
  "payload": { ... }
}
--- END OF FILE meridian-20260312T120000Z.json ---

--- START OF FILE shared-knowledge-base-seed-20260312T120000Z.json ---
{
  "header": { ... },
  "payload": { ... }
}
--- END OF FILE shared-knowledge-base-seed-20260312T120000Z.json ---
```

When no files are open:

```
--- HOLON_KNOWLEDGE_GRAPH_FILES ---
No files open. Use agent.CONTEXT_UNCOLLAPSE to open holon files.
```

### 4.4 Collapse Granularity

**Holon level (preferred):**
```
agent.CONTEXT_UNCOLLAPSE { "partitionKey": "hkg:<holonId>", "scope": "single" }
→ Opens one holon file in FILES. Expands its branch in INDEX.

agent.CONTEXT_COLLAPSE { "partitionKey": "hkg:<holonId>" }
→ Closes holon file in FILES. Collapses branch in INDEX (hides children).
```

**Partition level (coarse):**
```
agent.CONTEXT_UNCOLLAPSE { "partitionKey": "HOLON_KNOWLEDGE_GRAPH_FILES", "scope": "full" }
→ Opens ALL holon files. Expensive — agent should prefer per-holon.

agent.CONTEXT_COLLAPSE { "partitionKey": "HOLON_KNOWLEDGE_GRAPH_FILES" }
→ Closes all holon files.
```

### 4.5 Write Guard

When an agent command targets `knowledgegraph.UPDATE_HOLON_CONTENT` (or any HKG write action), the agent feature checks the target holon's collapse state before forwarding:

```kotlin
// In AgentRuntimeFeature, in the command forwarding path:
val targetHolonId = resolvedPayload["holonId"]?.jsonPrimitive?.contentOrNull
val holonCollapseState = statusInfo.contextCollapseOverrides["hkg:$targetHolonId"]

if (holonCollapseState != CollapseState.EXPANDED) {
    // Block — post sentinel error to agent's output session
    postToSession(agent.outputSessionId, "system", """
SYSTEM SENTINEL: Error: Write blocked! You are attempting to modify holon
'$targetHolonId' which is not fully expanded in your context. Expand the file:
```auf_agent.CONTEXT_UNCOLLAPSE
{ "partitionKey": "hkg:$targetHolonId", "scope": "single" }
```
Then retry your write to ensure you are not omitting data.""".trimIndent())
    return // Do not forward
}
```

This lives in the agent feature's command forwarding path — the same code path where sandbox rules are already enforced. CommandBot dispatches `commandbot.ACTION_CREATED`, the agent feature reads it and decides what to do. The write guard is part of that decision.

### 4.6 HkgContextFormatter

Pipeline-level utility in `app.auf.feature.agent`:

```kotlin
object HkgContextFormatter {

    data class HolonSummary(
        val id: String,
        val type: String,
        val name: String,
        val summary: String?,
        val subHolonRefs: List<SubRef>,
        val depth: Int
    )

    data class SubRef(val id: String, val type: String, val summary: String)

    /** Parse holon headers from raw JSON strings. */
    fun parseHolonHeaders(hkgContext: Map<String, String>): Map<String, HolonSummary>

    /** Build the INDEX tree string. */
    fun buildIndexTree(
        headers: Map<String, HolonSummary>,
        collapseOverrides: Map<String, CollapseState>
    ): String

    /** Build the FILES section string (expanded holons only). */
    fun buildFilesSection(
        hkgContext: Map<String, String>,
        collapseOverrides: Map<String, CollapseState>
    ): String

    /** Count sub-holons recursively for badge display. */
    fun countSubHolons(holonId: String, headers: Map<String, HolonSummary>): Int
}
```

---

## 5. Private Session Lifecycle

### 5.1 New: session.SESSION_CREATED Broadcast

Added to the session feature. Fires in the `CORE_RETURN_REGISTER_IDENTITY` side-effect handler, after the session is persisted:

```json
{
  "action_name": "session.SESSION_CREATED",
  "summary": "Broadcast when a new session is fully created and registered.",
  "public": false,
  "broadcast": true,
  "payload_schema": {
    "properties": {
      "uuid": { "type": "string" },
      "name": { "type": "string" },
      "handle": { "type": "string" },
      "localHandle": { "type": "string" },
      "isHidden": { "type": "boolean" },
      "isPrivateTo": { "type": ["string", "null"],
        "description": "Identity handle of the owner, if private." }
    },
    "required": ["uuid", "name", "handle", "localHandle"]
  }
}
```

### 5.2 Pending Session Guard

```kotlin
// In AgentStatusInfo
val pendingPrivateSessionCreation: Boolean = false
```

### 5.3 Private Session Flow

```
ensureInfrastructure():
  1. agent.outputSessionId != null → DONE (trust the pointer)
  2. statusInfo.pendingPrivateSessionCreation == true → DONE (waiting)
  3. Search identity registry for session with isPrivateTo == agent handle
  4. Found with UUID → dispatch AGENT_UPDATE_CONFIG to link → DONE
  5. Not found → set pending flag, dispatch SESSION_CREATE with isPrivateTo

SESSION_CREATED handler in AgentRuntimeFeature:
  1. Read isPrivateTo from payload
  2. Find agent with matching identityHandle
  3. Dispatch AGENT_UPDATE_CONFIG to set outputSessionId
  4. Clear pending flag
```

Matching by `isPrivateTo` is deterministic — no fragile name conventions.

---

## 6. SovereignStrategy Upgrade

### 6.1 Changes

| Area | Change |
|------|--------|
| `ensureInfrastructure()` | Replace name-lookup with pending guard pattern (§5.3) |
| `prepareSystemPrompt()` | No change — HKG context arrives pre-formatted |
| `postProcessResponse()` | No change — boot sentinel is correct |
| `requestAdditionalContext()` | No change — HKG request is correct |
| `validateConfig()` | No change — permits out-of-band outputSessionId |

### 6.2 Boot Phase Auto-Uncollapse

During BOOTING, the pipeline auto-expands the persona root holon so the boot sentinel can verify constitutional embodiment:

```kotlin
// In pipeline, when building HKG context:
val phase = cognitiveState["phase"]?.jsonPrimitive?.contentOrNull
if (phase == "BOOTING") {
    val rootId = hkgHeaders.values.find { it.type == "AI_Persona_Root" }?.id
    if (rootId != null) {
        effectiveOverrides["hkg:$rootId"] = CollapseState.EXPANDED
    }
}
```

After boot succeeds (AWAKE), the root can be collapsed and the agent navigates via explicit commands.

### 6.3 Response Routing

- Gateway response → private cognition session (outputSessionId). **No change.**
- Public posts → agent emits `session.POST` commands → CommandBot dispatches. **Already working.**

---

## 7. Implementation Phases

### Phase A: Supporting Infrastructure

Plumbing upgrades that later phases depend on.

**Deliverables:**
1. `session.SESSION_CREATED` broadcast in SessionFeature
2. `session_actions.json` updated
3. `AgentState.kt`: add to AgentInstance: `contextBudgetChars`, `contextMaxBudgetChars`, `contextMaxPartialChars`. Add to AgentStatusInfo: `contextCollapseOverrides`, `pendingPrivateSessionCreation`
4. `AgentCrudLogic.kt`: accept budget fields in CREATE/UPDATE_CONFIG
5. `AgentRuntimeReducer.kt`: handle `CONTEXT_UNCOLLAPSE`, `CONTEXT_COLLAPSE`, `CONTEXT_STATE_LOADED`, `SET_PENDING_PRIVATE_SESSION`
6. `AgentRuntimeFeature.kt`: `context.json` load/save scaffolding, `SESSION_CREATED` handler
7. `agent_actions.json`: add new actions
8. `CollapseState` enum (can live in `AgentState.kt` or `ContextCollapseLogic.kt`)

**Success gate — unit tests:**
- SessionFeature: SESSION_CREATED broadcast fires with correct payload including isPrivateTo
- SessionFeature: SESSION_CREATED for non-private session has isPrivateTo = null
- Reducer: CONTEXT_UNCOLLAPSE with scope "single" sets override to EXPANDED
- Reducer: CONTEXT_UNCOLLAPSE with scope "subtree" sets override to EXPANDED
- Reducer: CONTEXT_COLLAPSE sets override to COLLAPSED
- Reducer: CONTEXT_STATE_LOADED populates overrides from loaded data
- Reducer: SET_PENDING_PRIVATE_SESSION toggles flag
- CrudLogic: CREATE with budget fields → agent has correct budget values
- CrudLogic: UPDATE_CONFIG with budget fields → agent updated
- context.json round-trip: save → load → overrides match
- context.json missing on load → empty overrides, no crash
- context.json corrupt on load → empty overrides, warning logged

### Phase B: PrivateSessionStrategy

Test private session lifecycle in isolation.

**Deliverables:**
1. `PrivateSessionStrategy.kt` — reference strategy
2. Registered in `AgentRuntimeFeature.init()`

**Success gate — unit tests:**
- ensureInfrastructure: no outputSessionId, no pending → dispatches SESSION_CREATE + sets pending
- ensureInfrastructure: pending flag set → no-op
- ensureInfrastructure: outputSessionId set → no-op
- SESSION_CREATED with matching isPrivateTo → UPDATE_CONFIG dispatched, pending cleared
- SESSION_CREATED with non-matching isPrivateTo → no-op
- Full lifecycle: create agent → ensureInfrastructure → SESSION_CREATE → SESSION_CREATED → linked
- Restart resilience: outputSessionId already set → ensureInfrastructure is no-op
- prepareSystemPrompt: includes private session as primary output
- postProcessResponse: always PROCEED (no sentinel)

### Phase C: HKGStrategy

Test HKG integration in isolation.

**Deliverables:**
1. `HKGStrategy.kt` — reference strategy
2. `HkgContextFormatter.kt` — INDEX tree builder, FILES formatter
3. Registered in `AgentRuntimeFeature.init()`

**Success gate — unit tests:**
- requestAdditionalContext: dispatches KNOWLEDGEGRAPH_REQUEST_CONTEXT with correct personaId
- needsAdditionalContext: true when knowledgeGraphId is set, false otherwise
- HkgContextFormatter: INDEX tree from Meridian-like headers
- HkgContextFormatter: COLLAPSED branch shows badge, hides children
- HkgContextFormatter: EXPANDED branch shows immediate children
- HkgContextFormatter: sub-holon count is recursive
- HkgContextFormatter: FILES section lists only EXPANDED holons with START/END markers
- HkgContextFormatter: empty FILES (all collapsed) shows appropriate message
- HkgContextFormatter: mixed collapse states render correctly
- prepareSystemPrompt: includes INDEX (always) and FILES (expanded holons only)
- Write guard: write to collapsed holon → sentinel error
- Write guard: write to expanded holon → forwarded

### Phase D: Context Collapse Pipeline Integration

Universal budget management.

**Deliverables:**
1. `ContextCollapseLogic.kt` — partition model, auto-collapse algorithm, budget report
2. `AgentCognitivePipeline.kt` — integrate collapse into `executeTurn()`
3. Pipeline sentinel for oversized partials

**Success gate — unit tests:**

*ContextCollapseLogic:*
- Under budget → no collapse applied
- Over max budget → lowest-priority, largest partitions collapsed first
- Agent sticky EXPANDED respected when under max
- Agent sticky EXPANDED force-collapsed when over max, with warning
- Priority ordering: high-priority partitions collapse last
- Budget report generated with correct approximate token counts
- Budget report includes auto-collapse warning when triggered
- Oversized partial sentinel: single partition > threshold → truncated with message

*Pipeline integration:*
- executeTurn with HKG context → INDEX + FILES partitions built correctly
- executeTurn with collapsed HKG → FILES section empty, INDEX present
- executeTurn with mixed overrides → correct partitions in prompt
- Budget report present in all turns
- Auto-collapse fires and budget report reflects it

*Cross-strategy verification (all non-Sovereign strategies pass):*
- MinimalStrategy: existing tests still pass
- VanillaStrategy: existing tests still pass
- StateMachineStrategy: existing tests still pass
- PrivateSessionStrategy: Phase B tests still pass with pipeline changes
- HKGStrategy: Phase C tests still pass with pipeline changes

### Phase E: Sovereign Stabilization

Compose all capabilities.

**Deliverables:**
1. `SovereignStrategy.kt` — updated `ensureInfrastructure()` with guard pattern
2. Boot phase auto-uncollapse integration

**Success gate — unit tests:**

*Infrastructure:*
- ensureInfrastructure: guard pattern (same tests as PrivateSessionStrategy, own copies)
- ensureInfrastructure: HKG reservation (existing tests)
- No duplicate SESSION_CREATE on rapid heartbeat ticks

*Boot cycle:*
- Boot turn: persona root auto-expanded in FILES
- Boot turn: SUCCESS_CODE → BOOTING → AWAKE transition
- Boot turn: FAILURE_CODE → stays in BOOTING, response posted
- Boot turn: no sentinel token → stays in BOOTING, response posted

*AWAKE operation:*
- AWAKE turn: HKG collapsed by default
- AWAKE turn: agent CONTEXT_UNCOLLAPSE → holon visible in next turn
- AWAKE turn: agent CONTEXT_COLLAPSE → holon hidden in next turn
- AWAKE turn: budget report accurate

*Response routing:*
- Gateway response posted to private cognition session
- Agent session.POST routed to public session via CommandBot

*Write guard:*
- HKG write to collapsed holon → sentinel error
- HKG write to expanded holon → succeeds

*Full lifecycle:*
- Create → infrastructure (HKG + private session) → boot → awake → HKG navigation → write cycle

---

## 8. File Change Summary

### New Files

| File | Package | Phase |
|------|---------|-------|
| `PrivateSessionStrategy.kt` | `strategies` | B |
| `HKGStrategy.kt` | `strategies` | C |
| `HkgContextFormatter.kt` | `agent` | C |
| `ContextCollapseLogic.kt` | `agent` | D |

### Modified Files

| File | Changes | Phase |
|------|---------|-------|
| `AgentState.kt` | Budget fields on AgentInstance. CollapseOverrides + pending flag on AgentStatusInfo. CollapseState enum. | A |
| `AgentRuntimeReducer.kt` | CONTEXT_UNCOLLAPSE, CONTEXT_COLLAPSE, CONTEXT_STATE_LOADED, SET_PENDING_PRIVATE_SESSION | A |
| `AgentRuntimeFeature.kt` | context.json load/save, SESSION_CREATED handler, register new strategies, write guard | A+B+C |
| `AgentCrudLogic.kt` | Budget fields in CREATE/UPDATE_CONFIG | A |
| `AgentCognitivePipeline.kt` | Collapse integration in executeTurn(), HKG INDEX+FILES split | D |
| `SovereignStrategy.kt` | Updated ensureInfrastructure(), boot auto-uncollapse | E |
| `SessionFeature.kt` | SESSION_CREATED broadcast | A |
| `session_actions.json` | SESSION_CREATED | A |
| `agent_actions.json` | CONTEXT_UNCOLLAPSE, CONTEXT_COLLAPSE, CONTEXT_STATE_LOADED, SET_PENDING_PRIVATE_SESSION | A |
| `AgentManagerView.kt` | Budget config UI | A or later |

### Unchanged (Decoupled)

| File | Reason |
|------|--------|
| `VanillaStrategy.kt` | No shared code |
| `MinimalStrategy.kt` | No shared code |
| `StateMachineStrategy.kt` | No shared code |
| `KnowledgeGraphFeature.kt` | buildContextForPersona unchanged — collapse is pipeline-side |
| `KnowledgeGraphState.kt` | No changes |
| `SovereignDefaults.kt` | Constitution + boot sentinel unchanged |
| `CognitiveStrategy.kt` | Interface unchanged |
| `CognitiveStrategyRegistry.kt` | No structural changes (just new registrations) |

---

## 9. Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                      AgentCognitivePipeline                            │
│                                                                        │
│  1. Gather Context                                                     │
│     ├── Ledger (SessionFeature)                                        │
│     ├── HKG raw holons (KnowledgeGraphFeature — unchanged)             │
│     ├── Workspace (FilesystemFeature)                                  │
│     └── Strategy-specific (polymorphic)                                │
│                                                                        │
│  2. Context Collapse [Phase D]                                         │
│     ├── Load overrides (context.json → AgentStatusInfo)                │
│     ├── HkgContextFormatter → INDEX partition (always full)            │
│     ├── HkgContextFormatter → FILES partition (expanded holons only)   │
│     ├── ContextCollapseLogic.collapse(partitions, budget, overrides)   │
│     ├── Oversized partial sentinel                                     │
│     └── CONTEXT_BUDGET_REPORT partition                                │
│                                                                        │
│  3. Strategy.prepareSystemPrompt(context, cognitiveState)              │
│     └── Receives pre-collapsed contextMap. No collapse logic.          │
│                                                                        │
│  4. Gateway dispatch                                                   │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│  Persistence (per agent)                                               │
│                                                                        │
│  {agentUUID}/                                                          │
│  ├── agent.json      ← config (CRUD)                                  │
│  ├── nvram.json      ← cognitive state (agent-written)                 │
│  ├── context.json    ← collapse overrides (context system)             │
│  └── workspace/      ← scratch files (filesystem feature)              │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│  Strategies (absolute decoupling)                                      │
│                                                                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐                           │
│  │ Minimal  │ │ Vanilla  │ │ StateMachine │  ← existing, unchanged    │
│  └──────────┘ └──────────┘ └──────────────┘                           │
│  ┌────────────────┐ ┌───────────┐                                      │
│  │PrivateSession  │ │    HKG    │  ← reference test harnesses         │
│  └────────────────┘ └───────────┘                                      │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ Sovereign (duplicates private session + HKG code internally)    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│  Pipeline Utilities (shared, in app.auf.feature.agent)                 │
│                                                                        │
│  ┌─────────────────────┐  ┌────────────────────┐                      │
│  │ ContextCollapseLogic │  │ HkgContextFormatter │                     │
│  │ - CollapseState      │  │ - parseHolonHeaders │                     │
│  │ - ContextPartition   │  │ - buildIndexTree    │                     │
│  │ - collapse()         │  │ - buildFilesSection │                     │
│  │ - buildBudgetReport  │  │ - countSubHolons    │                     │
│  └─────────────────────┘  └────────────────────┘                      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Resolved Design Questions

| # | Question | Resolution |
|---|----------|------------|
| 1 | Collapse persistence | `context.json` per agent. Separate from NVRAM. Survives restarts. |
| 2 | Truncation | Not an architectural state. Pipeline sentinel for oversized partials. |
| 3 | Budget enforcement | Auto-collapse: lowest priority + largest first. Log + warn in report. |
| 4 | Write guard | Agent feature's command forwarding path (where sandbox rules already live). |
| 5 | HKG view format | Index + Files (two partitions). INDEX always present. FILES collapsible. |
| 6 | INDEX always full? | Yes. Graph hygiene is the agent's responsibility. |
| 7 | Code sharing | Absolute decoupling. Sovereign duplicates code. Shared utilities are pipeline-level. |
| 8 | Units | Tokens everywhere (approx. when estimated). Chars internally. |
| 9 | Agent vs system | Agent primary, pipeline safety net. Safety net must be rock solid. |
| 10 | Deep/wide trees | Collapsed branches hidden behind count badge. Agent's janitoring concern. |
| 11 | Reference strategies | PrivateSessionStrategy + HKGStrategy mandatory test harnesses. |
| 12 | Sticky overrides | Persisted in context.json. Respected up to hard max. |
| 13 | HKG vs Filesystem | Not duplicative. Filesystem = sandboxed I/O. HKG = semantic knowledge layer on top. Agent workspace is scratch; HKG is persona identity. |
| 14 | Phase ordering | A (plumbing) → B (private session) → C (HKG) → D (collapse pipeline) → E (Sovereign). Each gated by tests. |