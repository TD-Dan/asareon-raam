# Sovereign Strategy Stabilization: Design Document

## Status: IN PROGRESS — Phases A, B, C & D Complete

## Document Version: 8.0

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

Every strategy implementation is self-contained. SovereignStrategy imports nothing from VanillaStrategy, PrivateSessionStrategy, HKGStrategy, or any other strategy. If two strategies need identical logic, the code is duplicated. Shared infrastructure lives in the `app.auf.feature.agent` package as pipeline-level utilities (e.g., `HkgContextFormatter`, `ContextCollapseLogic`, `ConversationLogFormatter`) — never in the `strategies` subpackage.

### 2.4 Three Files, Three Concerns

Each agent has three separate persistence files:

| File | Owner | Contents |
|------|-------|----------|
| `agent.json` | Operator / CRUD | Identity, config, model, subscriptions, strategy |
| `nvram.json` | Agent (via UPDATE_NVRAM) | Cognitive state: phase, task, posture, turn count |
| `context.json` | Context collapse system | Collapse overrides: which partitions/holons are expanded |

These are independent systems. NVRAM is never involved in context management. Context state is never involved in cognitive state.

### 2.5 System-Prompt-Only Gateway Architecture *(Added in Phase B)*

All conversation context lives in the system prompt. The gateway `contents` (messages array) is always empty. Each provider injects a minimal trigger message (`role: user`) to satisfy API requirements. This architecture:

- Eliminates the user/assistant role-mapping problem for multi-user, multi-session conversations
- Makes conversation logs a first-class context partition that participates in collapse/budget
- Provides a universal format across all providers (Anthropic, OpenAI, Gemini, Inception)

### 2.6 Structured Delimiter Convention *(Revised in Phase D)*

All context partitions use a unique, non-naturally-occurring delimiter hierarchy.
Markdown (`#`) and XML (`<tag>`) conventions are avoided because both appear
routinely in agent-generated content and knowledge graphs.

```
[[[ - SYSTEM PROMPT - ]]]                  → Outermost wrapper (pipeline-owned)

- [ PARTIAL NAME ] (~tokens) [STATE] -     → h1: Top-level partition boundary
- [ END OF PARTIAL NAME ] -                → h1 closing tag

--- TEXT (~tokens) [STATE] ---             → h2: Sub-partition (session, file)
--- END OF TEXT ---                        → h2 closing tag

  --- TEXT (~tokens) [STATE] ---           → h3: Entry-level (2-space indent)
  ---                                      → h3 closing tag

    --- TEXT (~tokens) [STATE] ---         → h4: Sub-entry (4-space indent)
    ---                                    → h4 closing tag
```

Spacing rules:
- h1 tags: triple `\n` before, double `\n` after (own line, surrounded by blanks)
- h1 closing, h2–h4 tags: double `\n` before and after
- Content sits at zero indent between delimiters

Collapse state badges:
- `[PROTECTED]`: Never collapsed by the budget system. Always present.
- `[EXPANDED]`: Showing full content. Agent can collapse it.
- `[COLLAPSED]`: Showing summary only. Agent can expand it.
- `[TRUNCATED]`: Was truncated by the pipeline sentinel.

Per-header token estimates use the `≈4 chars/token` heuristic, rounded UP to
2 significant figures with a minimum granularity of 10 (e.g., 2389→2400,
551→560, 8→10). Implemented by `ContextDelimiters.roundTokensUp()`.

Ownership:
- **Pipeline** owns: `[[[ ]]]` outer wrapper, h1 headers on each contextMap entry
- **Formatters** own: internal h2/h3/h4 structure within a partition
- **Strategies** own: ordering of partitions, strategy-specific h1 sections
  (IDENTITY, INSTRUCTIONS, NAVIGATION, etc.)

All delimiter logic lives in `ContextDelimiters.kt` — a pipeline-level utility
in `app.auf.feature.agent`.

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

## 5. Private Session Lifecycle *(Implemented — Phase B)*

### 5.1 session.SESSION_CREATED Broadcast

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

The implementation uses a two-step guard. The original v4.0 design included a registry recovery step (searching the identity registry for sessions with `isPrivateTo`). This was dropped during implementation — restart resilience relies on `outputSessionId` being persisted in `agent.json`. The narrow crash window between SESSION_CREATED and agent.json save is accepted; a duplicate private session is recoverable by the operator.

```
ensureInfrastructure():
  1. agent.outputSessionId != null → DONE (trust the persisted pointer)
  2. statusInfo.pendingPrivateSessionCreation == true → DONE (waiting)
  3. Set pending flag, dispatch SESSION_CREATE with isPrivateTo + isHidden

SESSION_CREATED handler in AgentRuntimeFeature:
  1. Read isPrivateTo from payload
  2. Find agent with matching identityHandle
  3. Dispatch AGENT_UPDATE_CONFIG to set outputSessionId
  4. Dispatch ADD_SESSION_SUBSCRIPTION to subscribe agent to its own private session
  5. Clear pending flag
```

Matching by `isPrivateTo` is deterministic — no fragile name conventions.

Step 4 is new compared to v4.0: the agent is auto-subscribed to its own private session. This means the agent sees its private session in the conversation log — it functions as an internal monologue and staging ground.

### 5.4 Private Session Routing in System Prompt

PrivateSessionStrategy's system prompt includes a dedicated `PRIVATE SESSION ROUTING` section that:
- Explains that direct responses go to the invisible private session
- Instructs the agent to use `session.POST` for public communication
- Provides a concrete fenced code block example (without senderId — auto-filled from originator)
- Tags sessions as `[PRIVATE]` or `[PUBLIC]` in the subscription listing
- Lists per-session participants with their type and message count

### 5.5 session.POST senderId Auto-Fill

`session.POST` no longer requires `senderId`. If omitted, the session feature falls back to `action.originator` — the identity handle of the dispatching entity. The resolution chain is: explicit payload `senderId` → `action.originator` → `"unknown"`. This eliminates a common agent error (wrong senderId) and simplifies the prompt.

### 5.6 Default System Instruction

The built-in default system instruction for PrivateSessionStrategy agents:

```
You are a helpful assistant with a private output session.
Your responses are routed to your private session.
You observe messages from your subscribed public sessions.
Use your private session as your internal voice and staging ground before you answer to any sessions. You can also decide to not answer when there is nothing to say or you are the one that replied last.
```

---

## 6. Multi-Session Conversation Architecture *(Added in Phase B)*

### 6.1 System-Prompt-Only Mode

All LLM APIs (Anthropic, OpenAI, Gemini, Inception) are designed for binary user/assistant conversations. Multi-user, multi-session conversations cannot be naturally represented in alternating message roles.

The conversation log moves from the gateway `contents` (messages array) into the system prompt as a structured context partition (`CONVERSATION_LOG`). The `contents` array is always empty. Each provider detects empty contents and injects a minimal trigger message to satisfy API requirements:

- **Anthropic**: `[{"role": "user", "content": "[Turn initiated. Respond based on your system prompt.]"}]` — separate `system` field carries the system prompt
- **OpenAI**: system prompt as first message, then `[{"role": "user", "content": "[Turn initiated...]"}]`
- **Inception**: identical to OpenAI (OpenAI-compatible API)
- **Gemini**: `{"role": "user", "parts": [{"text": "[Turn initiated...]"}]}` — system prompt goes in `system_instruction`

The legacy path (populated `contents`) is preserved but deprecated in all four providers.

### 6.2 Multi-Session Ledger Accumulation

The pipeline requests ledger content from ALL subscribed sessions in parallel:

```
startCognitiveCycle():
  1. Collect all sessions to request: subscribedSessionIds (or outputSessionId fallback)
  2. Validate all UUIDs are in the identity registry
  3. Dispatch SET_PENDING_LEDGER_SESSIONS [s1, s2, s3, ...]
  4. For each session: dispatch REQUEST_LEDGER_CONTENT with
     compound correlationId "agentUUID::sessionUUID"

handleLedgerResponse():
  1. Parse compound correlationId to extract agentId + sessionId
  2. Enrich messages (sender names, roles)
  3. Dispatch ACCUMULATE_SESSION_LEDGER {agentId, sessionId, messages}
  4. Legacy fallback: if no "::" in correlationId, dispatches STAGE_TURN_CONTEXT

Reducer (ACCUMULATE_SESSION_LEDGER):
  - Stores messages in accumulatedSessionLedgers[sessionId]
  - Removes sessionId from pendingLedgerSessionIds

Side-effect (ACCUMULATE_SESSION_LEDGER):
  - If pendingLedgerSessionIds is empty → all arrived → evaluateTurnContext()
```

New state fields on `AgentStatusInfo`:

```kotlin
/** Session UUIDs whose ledger responses have not yet arrived. Empty = all received. */
val pendingLedgerSessionIds: Set<IdentityUUID> = emptySet()

/** Accumulated per-session ledger messages. Key = session UUID, value = enriched messages. */
val accumulatedSessionLedgers: Map<IdentityUUID, List<GatewayMessage>> = emptyMap()
```

Both fields are cleared in INITIATE_TURN (reducer) and on SET_STATUS when `shouldClearContext` is true.

### 6.3 CONVERSATION_LOG Format

Uses the delimiter convention (§2.6). The h1 wrapper is pipeline-owned.
Internal structure uses h2 for sessions (with token counts and state badges)
and h3 for individual messages:

```
- [ CONVERSATION_LOG ] (~2,500 tokens) [EXPANDED] -

--- SESSION: Private testing session | uuid: xxx | 3 messages (~1,600 tokens) [EXPANDED] ---

  --- Daniel (user.daniel) @ 2026-03-13T17:18:08Z ---

PeepBoob answer with a boop!

  ---

  --- Ryan (agent.ryan-2) @ 2026-03-13T17:18:12Z ---

Hello! I'm Ryan, ready to assist.

  ---

--- END OF SESSION ---

--- SESSION: Pet language studies | uuid: yyy | 1 message (~400 tokens) [EXPANDED] ---

  --- Daniel (user.daniel) @ 2026-03-13T17:13:56Z ---

What is your favourite animal?

  ---

--- END OF SESSION ---

- [ END OF CONVERSATION_LOG ] -
```

When all sessions are empty:

```
- [ CONVERSATION_LOG ] (~20 tokens) [PROTECTED] -
No messages in any subscribed session.
- [ END OF CONVERSATION_LOG ] -
```

### 6.4 SUBSCRIBED SESSIONS with Participants

Each session listing includes a per-session participant roster derived from the conversation log:

```
--- SUBSCRIBED SESSIONS ---
 --- Chat (session.chat) [PUBLIC — Use session.POST to communicate here] | 5 messages ---
  - Daniel (user.daniel): Human User, 3 messages
  - Ryan (agent.ryan-2): YOU (this agent), 2 messages
 ---
 --- Ryan-private-session (session.ryan-private) [PRIVATE — Your direct output is routed here, invisible to others] | 1 message ---
  - Ryan (agent.ryan-2): YOU (this agent), 1 messages
 ---
```

Data model:

```kotlin
data class SessionParticipant(
    val senderId: String,
    val senderName: String,
    val type: String,       // "Human User", "AI Agent", "YOU (this agent)", "User/System"
    val messageCount: Int
)

data class SessionInfo(
    val uuid: String,
    val handle: String,
    val name: String,
    val isOutput: Boolean,
    val participants: List<SessionParticipant> = emptyList(),
    val messageCount: Int = 0
)
```

The `participants` and `messageCount` fields have defaults, so existing `SessionInfo` construction sites (Vanilla, StateMachine, Minimal) are unaffected.

### 6.5 ConversationLogFormatter

Pipeline-level utility in `app.auf.feature.agent`:

```kotlin
object ConversationLogFormatter {

    data class SessionLedgerSnapshot(
        val sessionName: String,
        val sessionUUID: String,
        val sessionHandle: String,
        val messages: List<GatewayMessage>,
        val isOutputSession: Boolean = false
    )

    /** Formats multiple session ledgers into a single structured conversation log. */
    fun format(
        sessions: List<SessionLedgerSnapshot>,
        platformDependencies: PlatformDependencies
    ): String

    /** Extracts unique participants across all sessions for multi-agent context building. */
    fun extractParticipants(sessions: List<SessionLedgerSnapshot>): List<Pair<String, String>>
}
```

---

## 7. SovereignStrategy Upgrade

### 7.1 Changes

| Area | Change |
|------|--------|
| `ensureInfrastructure()` | Replace name-lookup with pending guard pattern (§5.3) |
| `prepareSystemPrompt()` | No change — HKG context arrives pre-formatted |
| `postProcessResponse()` | No change — boot sentinel is correct |
| `requestAdditionalContext()` | No change — HKG request is correct |
| `validateConfig()` | No change — permits out-of-band outputSessionId |

### 7.2 Boot Phase Auto-Uncollapse

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

### 7.3 Response Routing

- Gateway response → private cognition session (outputSessionId). **No change.**
- Public posts → agent emits `session.POST` commands → CommandBot dispatches. **Already working.**

---

## 8. Implementation Phases

### Phase A: Supporting Infrastructure ✅ COMPLETE

Plumbing upgrades that later phases depend on.

**Deliverables (all shipped):**
1. `session.SESSION_CREATED` broadcast in SessionFeature
2. `session_actions.json` updated (SESSION_CREATED + senderId optional on POST)
3. `AgentState.kt`: budget fields on AgentInstance, CollapseOverrides + pendingPrivateSessionCreation + multi-session ledger fields on AgentStatusInfo, CollapseState enum
4. `AgentCrudLogic.kt`: accept budget fields in CREATE/UPDATE_CONFIG
5. `AgentRuntimeReducer.kt`: handle CONTEXT_UNCOLLAPSE, CONTEXT_COLLAPSE, CONTEXT_STATE_LOADED, SET_PENDING_PRIVATE_SESSION, SET_PENDING_LEDGER_SESSIONS, ACCUMULATE_SESSION_LEDGER
6. `AgentRuntimeFeature.kt`: context.json load/save scaffolding, SESSION_CREATED handler (with private session subscription), ACCUMULATE_SESSION_LEDGER side-effect handler
7. `agent_actions.json`: all new actions including multi-session ledger actions
8. `CollapseState` enum in `AgentState.kt`
9. `SessionFeature.kt`: senderId auto-fill from originator on session.POST

**Success gate — unit tests:**
- SessionFeature: SESSION_CREATED broadcast fires with correct payload including isPrivateTo
- SessionFeature: SESSION_CREATED for non-private session has isPrivateTo = null
- Reducer: CONTEXT_UNCOLLAPSE with scope "single" sets override to EXPANDED
- Reducer: CONTEXT_UNCOLLAPSE with scope "subtree" sets override to EXPANDED
- Reducer: CONTEXT_COLLAPSE sets override to COLLAPSED
- Reducer: CONTEXT_STATE_LOADED populates overrides from loaded data
- Reducer: SET_PENDING_PRIVATE_SESSION toggles flag
- Reducer: SET_PENDING_LEDGER_SESSIONS initializes pending set and clears accumulated map
- Reducer: ACCUMULATE_SESSION_LEDGER stores messages and removes from pending set
- CrudLogic: CREATE with budget fields → agent has correct budget values
- CrudLogic: UPDATE_CONFIG with budget fields → agent updated
- context.json round-trip: save → load → overrides match
- context.json missing on load → empty overrides, no crash
- context.json corrupt on load → empty overrides, warning logged

### Phase B: PrivateSessionStrategy ✅ COMPLETE

Private session lifecycle validated in isolation and integrated with multi-session pipeline.

**Deliverables (all shipped):**
1. `PrivateSessionStrategy.kt` — reference strategy with private session routing prompt
2. `PrivateSessionStrategyT1LogicTest.kt` — 50 tests (needs updating for latest SessionInfo/participant changes)
3. `ConversationLogFormatter.kt` — pipeline utility for structured multi-session conversation logs
4. `CognitiveStrategy.kt` — SessionInfo extended with participants, SessionParticipant data class
5. `AgentCognitivePipeline.kt` — system-prompt-only architecture, multi-session ledger accumulation, ConversationLogFormatter integration, empty gateway contents
6. Gateway providers updated: `AnthropicProvider.kt`, `OpenAIProvider.kt`, `InceptionProvider.kt`, `GeminiProvider.kt` — empty contents handling with trigger injection

**Architectural decisions made during Phase B:**
- System-prompt-only gateway (§2.5): All conversation moved from `contents` to system prompt. Deprecated the old message-passing path across all 4 providers.
- Multi-session ledger accumulation (§6.2): Pipeline dispatches N ledger requests, accumulates per-session, builds conversation log from map.
- Private session auto-subscription (§5.3 step 4): Agent subscribes to its own private session for internal monologue visibility.
- No registry recovery (§5.3): Restart resilience relies on agent.json persistence, not identity registry search. Eliminates cross-feature dependency.
- senderId auto-fill (§5.5): session.POST defaults senderId to action.originator.
- Participant-aware session listing (§6.4): SUBSCRIBED SESSIONS includes per-session participant roster.

**Remaining for Phase B:** Update test file for latest SessionInfo/SessionParticipant changes and session.POST senderId removal. Scheduled for next session.

**Success gate — unit tests (50 tests defined):**
- identityHandle: in agent.strategy.* namespace, no collisions
- getInitialState: returns JsonNull
- getResourceSlots: declares system_instruction slot
- getConfigFields: declares outputSessionId field
- getBuiltInResources: returns default system instruction with unique ID
- validateConfig: does NOT reset out-of-band outputSessionId
- validateConfig: does NOT auto-assign when null
- validateConfig: preserves valid outputSessionId, preserves null with no subscriptions
- validateConfig: does not modify subscribedSessionIds
- prepareSystemPrompt: agent name, identity section, system instructions, multi-user awareness
- prepareSystemPrompt: private session routing section with session.POST example (no senderId)
- prepareSystemPrompt: tags output session as PRIVATE, non-output as PUBLIC
- prepareSystemPrompt: explains private session is invisible to others
- prepareSystemPrompt: lists participants per session with details
- prepareSystemPrompt: shows "no messages yet" for empty sessions
- prepareSystemPrompt: multi-agent context before other contexts, no duplication
- prepareSystemPrompt: omits sections when empty (no sessions, no contexts, no instructions)
- prepareSystemPrompt: routing section appears before subscribed sessions
- postProcessResponse: always PROCEED, no state modification, no displayHint
- getValidNvramKeys: returns null for stateless strategy
- ensureInfrastructure: dispatches SESSION_CREATE when no outputSessionId and not pending
- ensureInfrastructure: sets pending flag, targets correct agent, sets isHidden
- ensureInfrastructure: dispatches pending flag BEFORE session create
- ensureInfrastructure: dispatches exactly two actions when creating
- ensureInfrastructure: no-op when outputSessionId set
- ensureInfrastructure: no-op when pending flag set
- needsAdditionalContext: returns false
- requestAdditionalContext: returns false
- onAgentRegistered / onAgentConfigChanged: no crash
- strategy is singleton object

### Phase C: HKGStrategy ✅ COMPLETE

HKG integration validated in isolation. Two-partition INDEX + FILES view working. Write guard operational.

**Deliverables (all shipped):**
1. `HkgContextFormatter.kt` — pipeline utility: parseHolonHeaders (tree depth/parent BFS), buildIndexTree (collapse tags, sub-holon badges, 2-space indentation), buildFilesSection (expanded holons with START/END markers), countSubHolons (recursive), resolveCollapseState
2. `HKGStrategy.kt` — reference strategy: system_instruction + HKG, knowledgeGraphId in strategyConfig, requestAdditionalContext/needsAdditionalContext lifecycle, vanilla-style validateConfig, navigation instructions in prompt
3. `AgentCognitivePipeline.kt` — executeTurn: flat HKG dump replaced with INDEX + FILES via HkgContextFormatter, root auto-uncollapse (effective overrides), null HKG context warning
4. `AgentRuntimeFeature.kt` — HKGStrategy registration in init(), agentId injection for CONTEXT_UNCOLLAPSE/COLLAPSE commands, HKG write guard (§4.5) in ACTION_CREATED handler, ACTION_RESULT feedback for context commands
5. `AgentRuntimeReducer.kt` — WARN logging on all silent failure paths in context collapse actions
6. `HkgContextFormatterT1Test.kt` — 22 tests (NEW)
7. `HKGStrategyT1LogicTest.kt` — 25 tests (NEW)
8. `AgentRuntimeFeatureT1RuntimeReducerTest.kt` — 4 new tests added for failure-path diagnostics

**Architectural decisions made during Phase C:**

- **Root auto-uncollapse**: Root holons (parentId == null) default to EXPANDED in the pipeline's effective overrides, so the agent always sees its persona root on first turn. Agent sticky overrides take priority — if the agent explicitly collapses the root, that persists. This is pipeline-level behavior, not formatter-level.
- **agentId injection for context commands**: When agents dispatch CONTEXT_UNCOLLAPSE/COLLAPSE via CommandBot, the payload has no agentId (the agent doesn't know its own UUID). The ACTION_CREATED handler injects it, same self-targeting pattern as NVRAM. This was a live bug discovered during Meridian testing — commands silently no-oped because the reducer couldn't find the agent.
- **ACTION_RESULT feedback**: Context collapse/uncollapse commands now publish ACTION_RESULT when they arrive via CommandBot (have correlationId), giving agents visible confirmation (e.g., "OK ✓ agent.CONTEXT_UNCOLLAPSE — Expanded partition 'hkg:meridian-foundational-core-20260312T120000Z'.").
- **9 silent failure points diagnosed and fixed**: WARN/DEBUG logging added across reducer (3 guards + bad CollapseState), formatter (missing header, malformed sub_holons, unreadable FILES content), pipeline (null HKG context, no root holon), and feature (write guard null holonId bypass, deleted agent race in side-effect handler).
- **FakePlatformDependencies.formatIsoTimestamp fixed**: Was returning `"ISO_TIMESTAMP_$timestamp"` which crashed `normalizeHolonId` timestamp parsing. Now returns valid ISO 8601 (`"2026-01-01T00:00:05Z"` style).
- **KnowledgeGraphFeatureT2CoreTest tech debt fixed**: 8 pre-existing test failures from Store originator validation — agent handles registered via `TestEnvironment.withIdentity()` with explicit `knowledgegraph:read` + `knowledgegraph:write` permission grants.

**Success gate — unit tests (51 tests, all passing):**

*HkgContextFormatterT1Test (22 tests):*
- parseHolonHeaders: extracts all holons, correct depth, correct parentId, sub-holon refs, empty context, missing header skipped, malformed sub_holon graceful
- countSubHolons: recursive counting, unknown ID returns 0
- resolveCollapseState: default COLLAPSED, override convention with hkg: prefix
- buildIndexTree: all-collapsed with badges, EXPANDED reveals children, COLLAPSED hides children, mixed states, empty context, 2-space indentation
- buildFilesSection: only EXPANDED with markers, empty message, JSON content present, single expanded

*HKGStrategyT1LogicTest (25 tests):*
- Identity: handle in agent.strategy.* namespace, singleton object
- getInitialState: JsonObject with turnCount 0
- getResourceSlots: system_instruction slot
- getConfigFields: knowledgeGraphId + outputSessionId
- getBuiltInResources: unique ID, KG content
- getValidNvramKeys: contains turnCount
- validateConfig: 4 tests (same invariant as Vanilla)
- prepareSystemPrompt: 8 tests (name + HKG awareness, instructions, INDEX, FILES, no-nav without INDEX, HKG excluded from CONTEXT, sessions, multi-agent ordering, navigation instructions)
- postProcessResponse: PROCEED, turnCount increment, no displayHint
- needsAdditionalContext: true/false based on knowledgeGraphId
- requestAdditionalContext: false without KG, false without KG feature
- getKnowledgeGraphId: extracts from strategyConfig, null when absent
- Lifecycle hooks: no-crash, ensureInfrastructure is no-op

*AgentRuntimeFeatureT1RuntimeReducerTest (4 new tests):*
- CONTEXT_UNCOLLAPSE without agentId: no-op (the Meridian bug)
- CONTEXT_COLLAPSE without agentId: no-op
- CONTEXT_UNCOLLAPSE without partitionKey: no-op
- CONTEXT_STATE_LOADED with invalid CollapseState: defaults to COLLAPSED

### Phase D: Context Collapse Pipeline Integration ✅ COMPLETE

Universal budget management, structured delimiter standardization, and operator UI.

**Deliverables (all shipped):**
1. `ContextDelimiters.kt` — shared delimiter convention: `h1()`–`h4()` with token counts and state badges, closing tags, `wrapSystemPrompt()` for `[[[ ]]]` outer wrapper, `roundTokensUp()` (2 sig figs, min granularity 10), `approxTokens()` (chars→rounded token display), `formatWithCommas()`. State badge constants: PROTECTED, EXPANDED, COLLAPSED, TRUNCATED.
2. `ContextCollapseLogic.kt` — pipeline utility: `ContextPartition` model with `truncateFromStart` for directional truncation, `collapse()` two-pass auto-collapse algorithm, `buildBudgetReport()` with token estimates and directional truncation warnings, `buildPartition()` factory with well-known key defaults, oversized partial sentinel (sessions truncate from START, others from END)
3. `AgentCognitivePipeline.kt` — Phase D integration: builds partitions from contextMap, runs collapse against agent budget config, wraps each entry with `ContextDelimiters.h1()` + closing tag and state badge, injects CONTEXT_BUDGET partition, wraps final system prompt with `[[[ - SYSTEM PROMPT - ]]]` via `ContextDelimiters.wrapSystemPrompt()`
4. `AgentManagerView.kt` — Context Budget UI section: three operator-configurable token fields (Optimal, Maximum, Max Partial) with chars↔tokens conversion at the boundary, supporting text hints, digit-only input filtering
5. `ConversationLogFormatter.kt` — updated: removed self-wrapping h1 (pipeline adds it), sessions use `ContextDelimiters.h2()` with token counts and state badges, messages use `ContextDelimiters.h3()` with closing tags
6. `HkgContextFormatter.kt` — updated: removed self-wrapping h1, individual files use `ContextDelimiters.h2()` with token counts and `[EXPANDED]` state
7. `VanillaStrategy.kt` — updated: strategy-owned sections use `ContextDelimiters.h1()` with PROTECTED badge and closing tags, gathered contexts arrive pre-wrapped and appended directly, extracted shared `buildSubscribedSessionsContent()` and `buildPrivateSubscribedSessionsContent()` helpers
8. `MinimalStrategy.kt` — updated: ContextDelimiters h1 for SYSTEM INSTRUCTIONS, gathered contexts pre-wrapped
9. `PrivateSessionStrategy.kt` — updated: ContextDelimiters h1 for IDENTITY, INSTRUCTIONS, PRIVATE SESSION ROUTING, SUBSCRIBED SESSIONS with participant-aware h2 sessions
10. `HKGStrategy.kt` — updated: ContextDelimiters h1, explicit ordering of HKG INDEX/FILES before other contexts, HKG NAVIGATION section
11. `StateMachineStrategy.kt` — updated: ContextDelimiters h1 for IDENTITY, INSTRUCTIONS, STATE MACHINE, PHASE TRANSITION
12. `ContextDelimitersT1Test.kt` — 19 tests (NEW)
13. `ContextCollapseLogicT1Test.kt` — 30 tests (UPDATED for directional truncation)

**Architectural decisions made during Phase D:**

- **Pipeline-owned h1 wrapping**: The pipeline wraps each contextMap entry with `ContextDelimiters.h1("KEY", chars, STATE)` + `h1End("KEY")` after collapse resolution. Strategies receive pre-wrapped gathered contexts and include them directly. This guarantees consistent formatting with accurate token counts and state badges.
- **Pipeline-owned system prompt wrapper**: `[[[ - SYSTEM PROMPT - ]]]` is added by the pipeline after `prepareSystemPrompt()` returns. Strategies never see the outer wrapper.
- **Strategy-owned internal sections**: Strategies use `ContextDelimiters.h1()` for their own sections (IDENTITY, INSTRUCTIONS, NAVIGATION, etc.) with `[PROTECTED]` badge. These are not collapsible by the budget system.
- **Unique delimiters**: `- [ ] -` for h1 and `--- ---` for h2+ are chosen to never naturally occur in agent-generated content, HKG JSON, or markdown. Avoids false-positive parsing.
- **Token rounding**: All display token counts use `roundTokensUp()` — 2 significant figures with minimum granularity of 10. Examples: 2389→2400, 551→560, 8→10. Avoids false precision.
- **Directional truncation**: `CONVERSATION_LOG` has `truncateFromStart = true` — oldest messages removed first, keeping recent context. All other partitions truncate from the END. The sentinel message indicates direction.
- **Partition priority scale**: 1000 (never-collapse: INDEX, SESSION_METADATA), 100 (high: CONVERSATION_LOG, MULTI_AGENT_CONTEXT), 50 (medium: WORKSPACE_INDEX, WORKSPACE_NAVIGATION), 10 (standard: AVAILABLE_ACTIONS, WORKSPACE_FILES), 0 (low: HKG FILES, unknown). Higher priority = collapses last.
- **Two-pass collapse with force**: First pass skips agent-overridden EXPANDED partitions. Second pass force-collapses them only if still over budget. Force-collapse logs ERROR.
- **Collapsed content preserved in contextMap**: Collapsed partitions emit a summary string wrapped with h1 headers into the contextMap. Strategies see the summary. Blank collapsed content is omitted.
- **Shared strategy helpers**: `buildSubscribedSessionsContent()` and `buildPrivateSubscribedSessionsContent()` extracted to VanillaStrategy.kt (internal visibility) for reuse across strategies, avoiding duplication while maintaining strategy decoupling.

**Success gate — unit tests (49 tests, all passing):**

*ContextDelimitersT1Test (19 tests):*
- roundTokensUp: zero, single digits round to 10, 2-digit values to nearest 10, 3-digit to nearest 10, 4-digit to nearest 100, 5-digit to nearest 1000
- approxTokens: chars to rounded tokens with commas, small char counts round to 10
- formatWithCommas: correct formatting
- h1: includes name, tokens, state; triple newline before; omits when null
- h1End: closing tag format
- h2: includes text, tokens, state; h2End closing tag
- h3: 2-space indent; h4: 4-space indent
- wrapSystemPrompt: outer delimiters
- state badge constants

*ContextCollapseLogicT1Test (30 tests):*
- ContextPartition: effectiveCharCount for EXPANDED and COLLAPSED
- Under budget: no collapse applied
- Over max budget: lowest-priority largest first, multiple partitions until under budget
- Agent sticky overrides: respected under max, force-collapsed over max, skipped in first pass
- Priority ordering: high priority last, non-auto-collapsible never collapsed
- Budget report: correct tokens, auto-collapse warning, force-collapse warning, truncation direction
- Oversized sentinel: end truncation (default), start truncation (sessions), normal/collapsed not truncated
- buildPartition: 7 well-known key defaults (including CONVERSATION_LOG truncateFromStart), agent override, unknown key
- Edge cases: empty list, already collapsed, exactly at budget, one over budget

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

## 9. File Change Summary

### New Files

| File | Package | Phase | Status |
|------|---------|-------|--------|
| `PrivateSessionStrategy.kt` | `strategies` | B | ✅ Shipped |
| `PrivateSessionStrategyT1LogicTest.kt` | `strategies` (test) | B | ✅ Shipped (needs test update) |
| `ConversationLogFormatter.kt` | `agent` | B | ✅ Shipped |
| `HKGStrategy.kt` | `strategies` | C | ✅ Shipped |
| `HkgContextFormatter.kt` | `agent` | C | ✅ Shipped |
| `HkgContextFormatterT1Test.kt` | `agent` (test) | C | ✅ Shipped |
| `HKGStrategyT1LogicTest.kt` | `strategies` (test) | C | ✅ Shipped |
| `ContextCollapseLogic.kt` | `agent` | D | ✅ Shipped |
| `ContextCollapseLogicT1Test.kt` | `agent` (test) | D | ✅ Shipped |
| `ContextDelimiters.kt` | `agent` | D | ✅ Shipped |
| `ContextDelimitersT1Test.kt` | `agent` (test) | D | ✅ Shipped |

### Modified Files

| File | Changes | Phase | Status |
|------|---------|-------|--------|
| `AgentState.kt` | Budget fields on AgentInstance. CollapseOverrides + pending flag + multi-session ledger fields on AgentStatusInfo. CollapseState enum. | A+B | ✅ Shipped |
| `AgentRuntimeReducer.kt` | CONTEXT_UNCOLLAPSE, CONTEXT_COLLAPSE, CONTEXT_STATE_LOADED, SET_PENDING_PRIVATE_SESSION, SET_PENDING_LEDGER_SESSIONS, ACCUMULATE_SESSION_LEDGER. Cleanup in INITIATE_TURN + SET_STATUS. Phase C: WARN logging on all context collapse silent failure paths, LOG_TAG constant. | A+B+C | ✅ Shipped |
| `AgentRuntimeFeature.kt` | context.json load/save, SESSION_CREATED handler (with private session subscription), ACCUMULATE_SESSION_LEDGER side-effect, register new strategies, write guard. Phase C: HKGStrategy registration, agentId injection for CONTEXT_UNCOLLAPSE/COLLAPSE, HKG write guard (§4.5), ACTION_RESULT feedback for context commands, WARN/DEBUG logging on side-effect failure paths. | A+B+C | ✅ Shipped |
| `AgentCognitivePipeline.kt` | System-prompt-only mode, multi-session ledger accumulation, ConversationLogFormatter, empty gateway contents, multi-session SESSION_METADATA. Phase C: flat HKG dump → INDEX + FILES via HkgContextFormatter, root auto-uncollapse, null HKG context WARN, no-root-holon WARN, platformDependencies passed to buildFilesSection. Phase D: ContextCollapseLogic + ContextDelimiters integration — builds partitions, runs collapse, wraps each entry with h1 headers and state badges, injects CONTEXT_BUDGET, wraps final prompt with `[[[ - SYSTEM PROMPT - ]]]`. | B+C+D | ✅ Shipped |
| `CognitiveStrategy.kt` | SessionParticipant data class, SessionInfo extended with participants + messageCount | B | ✅ Shipped |
| `ConversationLogFormatter.kt` | Phase B: multi-session structured conversation logs. Phase D: removed self-wrapping h1 (pipeline adds it), sessions use ContextDelimiters.h2() with token counts and state badges, messages use ContextDelimiters.h3(). | B+D | ✅ Shipped |
| `AnthropicProvider.kt` | Empty contents → trigger message injection, legacy path preserved. Also in buildCountTokensPayload. | B | ✅ Shipped |
| `OpenAIProvider.kt` | Empty contents → trigger message injection, legacy path preserved | B | ✅ Shipped |
| `InceptionProvider.kt` | Empty contents → trigger message injection, legacy path preserved | B | ✅ Shipped |
| `GeminiProvider.kt` | Empty contents → trigger message injection, legacy path preserved | B | ✅ Shipped |
| `SessionFeature.kt` | SESSION_CREATED broadcast. senderId optional on POST with originator auto-fill. | A+B | ✅ Shipped |
| `session_actions.json` | SESSION_CREATED. senderId not required on POST. | A+B | ✅ Shipped |
| `agent_actions.json` | CONTEXT_UNCOLLAPSE, CONTEXT_COLLAPSE, CONTEXT_STATE_LOADED, SET_PENDING_PRIVATE_SESSION, SET_PENDING_LEDGER_SESSIONS, ACCUMULATE_SESSION_LEDGER | A+B | ✅ Shipped |
| `AgentCrudLogic.kt` | Budget fields in CREATE/UPDATE_CONFIG | A | ✅ Shipped |
| `FakePlatformDependencies.kt` | formatIsoTimestamp: valid ISO 8601 output (was crashing normalizeHolonId). parseIsoTimestamp: backward-compatible. | C (tech debt) | ✅ Shipped |
| `KnowledgeGraphFeatureT2CoreTest.kt` | Pre-seeded agent identities with KG permissions via withIdentity() (was failing on Store originator validation). DELETE_HOLON NPE fix. | C (tech debt) | ✅ Shipped |
| `AgentRuntimeFeatureT1ConversationLogFormatterTest.kt` | Timestamp assertion updated for new ISO format. | C (tech debt) | ✅ Shipped |
| `AgentRuntimeFeatureT1RuntimeReducerTest.kt` | 4 new tests for context collapse failure paths. HKGStrategy registered in setUp. | C | ✅ Shipped |
| `SovereignStrategy.kt` | Updated ensureInfrastructure(), boot auto-uncollapse | E | Planned |
| `AgentManagerView.kt` | Phase D: Context Budget UI section — three operator-configurable token fields (Optimal, Maximum, Max Partial) with chars↔tokens conversion, supporting text, digit-only filtering. Included in AGENT_UPDATE_CONFIG save payload. | D | ✅ Shipped |

### Unchanged (Decoupled)

| File | Reason |
|------|--------|
| `KnowledgeGraphFeature.kt` | buildContextForPersona unchanged — collapse is pipeline-side |
| `KnowledgeGraphState.kt` | No changes |
| `SovereignDefaults.kt` | Constitution + boot sentinel unchanged |
| `CognitiveStrategyRegistry.kt` | No structural changes (just new registrations) |

### Modified by Delimiter Standardization (Phase D)

| File | Changes | Phase | Status |
|------|---------|-------|--------|
| `VanillaStrategy.kt` | Phase D: ContextDelimiters h1 for IDENTITY, INSTRUCTIONS, SUBSCRIBED SESSIONS with PROTECTED badges and closing tags. Gathered contexts pre-wrapped. Extracted shared `buildSubscribedSessionsContent()` and `buildPrivateSubscribedSessionsContent()` helpers. | D | ✅ Shipped |
| `MinimalStrategy.kt` | Phase D: ContextDelimiters h1 for SYSTEM INSTRUCTIONS with PROTECTED badge. Gathered contexts pre-wrapped. | D | ✅ Shipped |
| `StateMachineStrategy.kt` | Phase D: ContextDelimiters h1 for IDENTITY, INSTRUCTIONS, STATE MACHINE, PHASE TRANSITION with PROTECTED badges and closing tags. Gathered contexts pre-wrapped. | D | ✅ Shipped |
| `PrivateSessionStrategy.kt` | Phase B: private session lifecycle (unchanged). Phase D: ContextDelimiters h1 for IDENTITY, INSTRUCTIONS, PRIVATE SESSION ROUTING, SUBSCRIBED SESSIONS with participant-aware h2. | B+D | ✅ Shipped |
| `HKGStrategy.kt` | Phase C: HKG integration (unchanged). Phase D: ContextDelimiters h1, explicit ordering of HKG INDEX/FILES, HKG NAVIGATION section, gathered contexts pre-wrapped. | C+D | ✅ Shipped |
| `HkgContextFormatter.kt` | Phase C: two-partition INDEX+FILES view. Phase D: removed self-wrapping h1 (pipeline adds it), individual files use ContextDelimiters.h2() with token counts and [EXPANDED] state. | C+D | ✅ Shipped |

---

## 10. Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                      AgentCognitivePipeline                            │
│                                                                        │
│  1. Gather Context (Multi-Session)                                     │
│     ├── Ledger × N (one per subscribed session, accumulated)           │
│     ├── HKG raw holons (KnowledgeGraphFeature — unchanged)             │
│     ├── Workspace (FilesystemFeature)                                  │
│     └── Strategy-specific (polymorphic)                                │
│                                                                        │
│  2. Context Assembly                                                   │
│     ├── ConversationLogFormatter → CONVERSATION_LOG partition           │
│     │   (multi-session, delimiter convention, per-session snapshots)    │
│     ├── HkgContextFormatter → INDEX + FILES partitions [Phase C ✅]  │
│     ├── ContextCollapseLogic.collapse() [Phase D ✅]                    │
│     ├── SESSION_METADATA (multi-session aware)                         │
│     ├── AVAILABLE_ACTIONS (exposed actions)                            │
│     └── WORKSPACE_FILES                                                │
│                                                                        │
│  3. Strategy.prepareSystemPrompt(context, cognitiveState)              │
│     └── Receives assembled contextMap. Strategy owns prompt structure. │
│                                                                        │
│  4. Gateway dispatch (system-prompt-only mode)                         │
│     ├── contents = [] (empty — conversation is in system prompt)       │
│     └── Provider injects minimal trigger to satisfy API requirements   │
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
│  ┌──────────────────┐ ┌───────────┐                                    │
│  │PrivateSession ✅ │ │  HKG ✅   │  ← reference test harnesses       │
│  └──────────────────┘ └───────────┘                                    │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ Sovereign (duplicates private session + HKG code internally)    │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│  Pipeline Utilities (shared, in app.auf.feature.agent)                 │
│                                                                        │
│  ┌─────────────────────────┐  ┌────────────────────┐                  │
│  │ ConversationLogFormatter │  │ HkgContextFormatter │                 │
│  │ - SessionLedgerSnapshot  │  │ - parseHolonHeaders │                 │
│  │ - format()               │  │ - buildIndexTree    │                 │
│  │ - extractParticipants()  │  │ - buildFilesSection │                 │
│  └─────────────────────────┘  │ - countSubHolons    │                 │
│  ┌─────────────────────┐      └────────────────────┘                  │
│  │ ContextCollapseLogic │                                              │
│  │ - ContextPartition   │                                              │
│  │ - collapse()         │                                              │
│  │ - buildBudgetReport  │                                              │
│  │ - buildPartition()   │                                              │
│  └─────────────────────┘                                               │
│  ┌─────────────────────┐                                               │
│  │ ContextDelimiters    │                                              │
│  │ - h1/h2/h3/h4()     │                                              │
│  │ - wrapSystemPrompt() │                                              │
│  │ - approxTokens()     │                                              │
│  │ - roundTokensUp()    │                                              │
│  └─────────────────────┘                                               │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│  Gateway Providers (system-prompt-only mode)                           │
│                                                                        │
│  ┌───────────┐ ┌────────┐ ┌───────────┐ ┌────────┐                   │
│  │ Anthropic │ │ OpenAI │ │ Inception │ │ Gemini │                    │
│  │ ✅ Updated │ │✅ Upd. │ │ ✅ Updated │ │✅ Upd. │                   │
│  └───────────┘ └────────┘ └───────────┘ └────────┘                    │
│  Empty contents → inject trigger. Legacy path preserved (deprecated). │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Resolved Design Questions

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
| 15 | Multi-session conversations | Conversation moves from gateway contents to system prompt. LLM APIs only support binary user/assistant — multi-user context must be in the system prompt. |
| 16 | Private session restart recovery | Relies on agent.json persistence, NOT identity registry search. No cross-feature dependency. Duplicate session on narrow crash window is accepted. |
| 17 | Private session subscription | Agent is auto-subscribed to its private session via ADD_SESSION_SUBSCRIPTION in SESSION_CREATED handler. Private session serves as internal monologue. |
| 18 | session.POST senderId | Optional. Auto-filled from action.originator. Eliminates common agent error. |
| 19 | Context delimiter convention | `---` / ` ---` / `  ---` indentation hierarchy. Content at zero indent between delimiters. |
| 20 | Session participant metadata | SessionInfo carries per-session participant roster (name, type, message count). Rendered in SUBSCRIBED SESSIONS section. |
| 21 | Root holon default state | Root holons (parentId == null) auto-expand in effective overrides. Agent sticky overrides take priority. Pipeline-level, not formatter-level. |
| 22 | Context command agentId | Agents don't include their own agentId in CONTEXT_UNCOLLAPSE/COLLAPSE payloads. The ACTION_CREATED handler injects it (same self-targeting pattern as NVRAM). |
| 23 | Context command feedback | CONTEXT_UNCOLLAPSE/COLLAPSE publish ACTION_RESULT when dispatched via CommandBot (have correlationId). Agents see visible confirmation. |
| 24 | HKG partition keys | Two partitions: `HOLON_KNOWLEDGE_GRAPH_INDEX` (always present, navigational) and `HOLON_KNOWLEDGE_GRAPH_FILES` (expanded holons only, carries token weight). Collapse overrides use `hkg:<holonId>` key convention. |
| 25 | Formatter vs pipeline responsibility | HkgContextFormatter is a pure renderer — no side effects, no defaults. Root auto-uncollapse and effective override computation are pipeline responsibilities in executeTurn(). |
| 26 | Silent failure logging | All `?: return state` / `?: continue` / `catch` paths in context collapse flow now log WARN (operational) or DEBUG (defensive). 9 paths diagnosed and fixed in Phase C. |
| 27 | Partition priority scale | 1000 (never-collapse), 100 (high), 50 (medium), 10 (standard), 0 (low/default). Higher priority = collapses last. HKG FILES is priority 0 — heaviest partition, collapses first. |
| 28 | Two-pass collapse with force | First pass skips agent-overridden EXPANDED partitions. Second pass force-collapses them only if still over budget. Force-collapse logs ERROR — it means the agent's context choices are incompatible with its budget. |
| 29 | Collapsed content in contextMap | Collapsed partitions emit a summary string into the contextMap (not removed). Strategies see "[PARTITION_NAME collapsed — use CONTEXT_UNCOLLAPSE to expand.]" and can reference it. Blank collapsed content is omitted. |
| 30 | Budget report self-protection | CONTEXT_BUDGET partition has priority 1000 and isAutoCollapsible = false. The report is never collapsed by the system it reports on. |
| 31 | Budget UI token convention | Agent Manager shows all three budget fields as approximate tokens. Conversion (×4 / ÷4) happens at the UI boundary. Internal state and persistence remain in chars. Consistent with §2.2. |
| 32 | Delimiter uniqueness | `- [ ] -` for h1 and `--- ---` for h2+ are chosen to never naturally occur in agent-generated content, HKG JSON, or markdown. Avoids false-positive parsing by both pipeline and agent. |
| 33 | Pipeline-owned h1 wrapping | The pipeline wraps each contextMap entry with `ContextDelimiters.h1()` + `h1End()` after collapse. Strategies receive pre-wrapped gathered contexts. Eliminates formatting duplication and guarantees accurate token counts. |
| 34 | Pipeline-owned system prompt wrapper | `[[[ - SYSTEM PROMPT - ]]]` is added by the pipeline after `prepareSystemPrompt()`. Strategies never see the outer wrapper. No duplication across strategies. |
| 35 | Strategy-owned internal sections | Strategies use `ContextDelimiters.h1()` for their own sections (IDENTITY, INSTRUCTIONS, etc.) with `[PROTECTED]` badge. Not collapsible by the budget system. |
| 36 | Session truncation direction | CONVERSATION_LOG truncates from the START (oldest messages removed). All other partitions truncate from the END. Direction indicated in sentinel message and budget report. |
| 37 | Token rounding spec | `roundTokensUp()` rounds to 2 significant figures with minimum granularity of 10. Examples: 2389→2400, 551→560, 8→10, 0→0. Used everywhere tokens are displayed. |
| 38 | Shared strategy helpers | `buildSubscribedSessionsContent()` and `buildPrivateSubscribedSessionsContent()` extracted to VanillaStrategy.kt with `internal` visibility. Reused by all strategies that show session awareness. Avoids duplication while maintaining strategy decoupling (helpers are in `strategies` package, not imported cross-strategy). |