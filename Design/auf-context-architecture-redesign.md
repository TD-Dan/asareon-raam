# AUF Context Architecture Redesign

## Design Document — Final Draft

**Version:** 1.0
**Status:** Final Draft for Review
**Scope:** Unified partition model, context manager UI, strategy builder API, Lua scripting, action bus integration

---

## Table of Contents

1. Problem Statement
2. Design Overview
3. Unified Partition Model
4. Strategy Builder API (Kotlin)
5. Pipeline Redesign
6. Context Manager UI
7. Lua Scripting API
8. Action Bus Integration
9. State Model Changes
10. Action Inventory
11. Security Model
12. Migration Plan
13. Files Affected
14. Risks, Mitigations, and Red Team Findings
15. Open Questions

---

## 1. Problem Statement

The current agent context system has three independent subsystems that don't know about each other.

### 1.1 System 1 — Pipeline Partitions (managed)

Built in `executeTurn()`, stored in `contextMap`, processed by `ContextCollapseLogic.collapse()`. These have collapse states, token counts, budget participation, and truncation sentinels.

Partition keys: `CONVERSATION_LOG`, `SESSION_METADATA`, `AVAILABLE_ACTIONS`, `HOLON_KNOWLEDGE_GRAPH_INDEX`, `HOLON_KNOWLEDGE_GRAPH_FILES`, `WORKSPACE_INDEX`, `WORKSPACE_FILES`, `WORKSPACE_NAVIGATION`, `MULTI_AGENT_CONTEXT`, `CONTEXT_BUDGET`.

### 1.2 System 2 — Strategy Sections (unmanaged)

Built directly inside `prepareSystemPrompt()` using manual `ContextDelimiters.h1()` calls. Invisible to the collapse algorithm, the budget report, and any management UI.

Per strategy: Vanilla has 3 sections, HKG has 4, PrivateSession has 4, StateMachine has 5, Sovereign has 7. Total: ~30 `ContextDelimiters.h1()` calls across 6 strategies, with significant boilerplate duplication.

### 1.3 System 3 — Formatter Sub-Partitions (internal collapse)

Built inside `HkgContextFormatter`, `WorkspaceContextFormatter`, and `ConversationLogFormatter` using h2/h3 delimiters. Each formatter resolves its own collapse state from `contextCollapseOverrides` using prefixed keys (`hkg:<holonId>`, `ws:<relativePath>`). The budget algorithm sees these as atomic blobs.

### 1.4 Consequences

**Budget accounting is wrong.** Strategy sections (potentially 5,000–10,000+ tokens for Sovereign) are invisible to `ContextCollapseLogic`. The budget report understates actual prompt size. Auto-collapse decisions are based on incomplete data.

**No operator visibility.** No UI for the operator to see what consumes context, toggle partitions, or understand why auto-collapse fired. The "Preview Turn" view shows only the final assembled prompt as a string.

**Sub-partition waste.** The budget can only collapse `HKG_FILES` as a whole. It cannot shed individual holons while keeping others.

**Strategy authoring is error-prone.** Each strategy manually builds h1-delimited strings, interleaves gathered contexts by key, and duplicates boilerplate across 6 implementations.

**Scriptability barrier.** A future Lua/Python strategy author must understand three subsystems, delimiter conventions, pre-wrapped vs raw content, and the `gatheredContexts` map contract.

---

## 2. Design Overview

The redesign unifies all three systems:

**One data type** (`PromptSection`) describes every piece of the prompt — strategy sections, gathered partitions, and sub-items within groups.

**One builder** (`PromptBuilder`) provides a fluent API that both Kotlin strategies and Lua scripts use. Strategies express *what* content exists and *where* it goes. The pipeline handles *how*.

**One collapse pass.** `ContextCollapseLogic` operates on the flattened partition list with cascade semantics. It sees everything. Budget accounting is accurate.

**One assembly function** (`assembleContext()`) extracted from `executeTurn()`, returning a `ContextAssemblyResult` that feeds both the Context Manager UI and the gateway.

**One management UI** ("Manage Context") with partition-level and sub-partition-level visibility and toggles, with instant local reassembly on toggle and debounced gateway preview for token counting.

**One scripting surface** for Lua strategies, with the agent identity as the sandbox boundary and `on_action` listeners for reactive bus participation.

---

## 3. Unified Partition Model

### 3.1 PromptSection Sealed Class

```kotlin
sealed class PromptSection {

    /**
     * A named section of the prompt. Leaf node.
     * Used for strategy-owned content (identity, instructions, navigation)
     * and for individual items within a Group (single holon, single file).
     */
    data class Section(
        val key: String,
        val content: String,
        val isProtected: Boolean = true,
        val isCollapsible: Boolean = false,
        val priority: Int = 1000,
        val collapsedSummary: String? = null,
        val truncateFromStart: Boolean = false
    ) : PromptSection()

    /**
     * A group of child sections. Enables per-child collapse and budgeting.
     *
     * COLLAPSE CASCADE SEMANTICS (Red Team Fix F1):
     * - Group COLLAPSED → entire group replaced by collapsedSummary.
     *   Children are EXCLUDED from the flat partition list.
     *   Budget sees only the summary's char count.
     * - Group EXPANDED → each child rendered per its own collapse state.
     *   Budget can manage children individually.
     */
    data class Group(
        val key: String,
        val header: String = "",
        val children: List<PromptSection>,
        val isProtected: Boolean = false,
        val isCollapsible: Boolean = true,
        val priority: Int = 0,
        val collapsedSummary: String? = null,
        val truncateFromStart: Boolean = false
    ) : PromptSection()

    /**
     * Reference to a pipeline-gathered partition by key. Resolved to
     * Section or Group during the merge step. Silently skipped if absent.
     */
    data class GatheredRef(
        val key: String,
        val formatOverrides: FormatOverrides? = null
    ) : PromptSection()

    /**
     * "All gathered partitions not explicitly placed via GatheredRef."
     * MULTI_AGENT_CONTEXT first among remaining, then alphabetical.
     */
    data object RemainingGathered : PromptSection()
}
```

### 3.2 FormatOverrides

Allows strategies to customize how gathered partitions format their internal content without rebuilding the formatter.

```kotlin
data class FormatOverrides(
    val formatMessage: ((GatewayMessage) -> String?)? = null,
    val formatSession: ((ConversationLogFormatter.SessionLedgerSnapshot, String) -> String?)? = null,
    val formatEntry: ((key: String, content: String) -> String?)? = null
)
```

Callbacks are pure transforms: frozen input, string output. Return null to skip an entry. Same interface for Kotlin and Lua (bridge translates Lua functions to lambdas).

### 3.3 Content Mapping

**Strategy-owned sections** become `Section` entries via `PromptBuilder`:

| Builder Call | Produces |
|-------------|----------|
| `identity()` | `Section("YOUR IDENTITY AND ROLE", ..., isProtected=true)` |
| `instructions()` | `Section("SYSTEM INSTRUCTIONS", ..., isProtected=true)` |
| `sessions()` | `Section("SUBSCRIBED SESSIONS", ..., isProtected=true)` |
| `section("NAME", content)` | `Section("NAME", content, isProtected=true)` |
| `resource("slot", "NAME")` | `Section("NAME", resourceContent, isProtected=true)` |

**Pipeline-gathered partitions with sub-items** become `Group` entries during the merge step:

| Partition | Structure |
|-----------|-----------|
| `CONVERSATION_LOG` (multi-session) | `Group(priority=100, truncateFromStart=true)` with per-session `Section` children |
| `HOLON_KNOWLEDGE_GRAPH_FILES` | `Group(priority=0, isCollapsible=true)` with per-holon `Section` children |
| `WORKSPACE_FILES` | `Group(priority=10, isCollapsible=true)` with per-file `Section` children |

**Pipeline-gathered partitions without sub-items** become flat `Section` entries:

| Partition | Properties |
|-----------|-----------|
| `SESSION_METADATA` | `isProtected=true` |
| `AVAILABLE_ACTIONS` | `isCollapsible=true, priority=10` |
| `WORKSPACE_INDEX` | `isProtected=true` |
| `HOLON_KNOWLEDGE_GRAPH_INDEX` | `isProtected=true` |

---

## 4. Strategy Builder API (Kotlin)

### 4.1 Interface Change

```kotlin
interface CognitiveStrategy {
    // REMOVED: fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String
    // NEW:
    fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder

    // All other methods unchanged
}
```

### 4.2 PromptBuilder

```kotlin
class PromptBuilder(private val context: AgentTurnContext) {
    internal val sections = mutableListOf<PromptSection>()
    private val emittedKeys = mutableSetOf<String>()  // Red Team C3: duplicate detection

    // ── Built-in sections ───────────────────────────────────────────

    /** Standard 3-line identity block with optional extra lines appended. */
    fun identity(vararg extraLines: String)

    /** Full replacement of the identity section. */
    fun identityCustom(content: String)

    /** Emits the system_instruction resource as SYSTEM INSTRUCTIONS. */
    fun instructions()

    /** Standard or private-format session subscription list. */
    fun sessions(format: SessionFormat = SessionFormat.STANDARD)

    /** Private session routing explanation block. */
    fun privateSessionRouting()

    /** HKG navigation instructions. */
    fun hkgNavigation()

    /** Workspace navigation instructions. */
    fun workspaceNavigation()

    // ── Custom sections ─────────────────────────────────────────────

    /** Named section. Protected and non-collapsible by default. */
    fun section(
        key: String, content: String,
        isProtected: Boolean = true, isCollapsible: Boolean = false,
        priority: Int = 1000, collapsedSummary: String? = null,
        truncateFromStart: Boolean = false
    )

    /** Emits a named resource as a section. */
    fun resource(slotId: String, sectionName: String? = null)

    // ── Gathered partition placement ────────────────────────────────

    /** Place a pipeline-gathered partition at this position. */
    fun place(key: String, formatOverrides: FormatOverrides? = null)

    /** True if the pipeline gathered a partition with this key. */
    fun has(key: String): Boolean

    /** All remaining gathered partitions not explicitly placed. */
    fun everythingElse()

    // ── Groups ──────────────────────────────────────────────────────

    /** Group with individually collapsible children. */
    fun group(
        key: String, header: String = "",
        isProtected: Boolean = false, isCollapsible: Boolean = true,
        priority: Int = 0, collapsedSummary: String? = null,
        build: PromptBuilder.() -> Unit
    )
}

enum class SessionFormat { STANDARD, PRIVATE }
```

### 4.3 Strategy Examples

**VanillaStrategy** (7 lines of prompt logic):

```kotlin
override fun buildPrompt(context: AgentTurnContext, state: JsonElement) =
    PromptBuilder(context).apply {
        identity()
        instructions()
        sessions()
        place("MULTI_AGENT_CONTEXT")
        everythingElse()
    }
```

**SovereignStrategy** (48 lines, down from 136):

```kotlin
override fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder {
    val phase = (state as? JsonObject)?.get(KEY_PHASE)?.jsonPrimitive?.content ?: PHASE_BOOTING

    return PromptBuilder(context).apply {
        if (phase == PHASE_BOOTING) {
            identityCustom(buildBootingIdentity(context))
        } else {
            identity(
                "You operate under a constitutional framework.",
                "Your HKG is your persistent memory and identity layer."
            )
        }
        resource("constitution", "CONSTITUTION")
        if (phase != PHASE_BOOTING) {
            section("CONTROL REGISTERS (NVRAM)", buildNvramContent(state as? JsonObject))
        }
        privateSessionRouting()
        sessions(format = SessionFormat.PRIVATE)
        place("HOLON_KNOWLEDGE_GRAPH_INDEX")
        place("HOLON_KNOWLEDGE_GRAPH_FILES")
        if (phase != PHASE_BOOTING && has("HOLON_KNOWLEDGE_GRAPH_INDEX")) {
            hkgNavigation()
        }
        place("MULTI_AGENT_CONTEXT")
        everythingElse()
        if (phase == PHASE_BOOTING) {
            resource("bootloader", "BOOT SENTINEL")
        }
    }
}
```

### 4.4 AgentTurnContext Change

```kotlin
data class AgentTurnContext(
    val agentName: String,
    val resolvedResources: Map<String, String>,
    val gatheredContextKeys: Set<String>,  // CHANGED: was gatheredContexts: Map<String, String>
    val subscribedSessions: List<SessionInfo> = emptyList(),
    val outputSessionUUID: String? = null,
    val outputSessionHandle: String? = null
)
```

The pipeline retains the full `contextMap` internally for the merge step.

---

## 5. Pipeline Redesign

### 5.1 Extract assembleContext()

Split `executeTurn()` into two paths:

```kotlin
/**
 * Fast path: partition metadata only. No string assembly.
 * Used by Context Manager Tab 0 for instant reassembly on toggle.
 */
fun assemblePartitions(
    agent: AgentInstance,
    sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
    hkgContext: JsonObject?,
    agentState: AgentRuntimeState,
    store: Store
): PartitionAssemblyResult?

/**
 * Full path: partition metadata + assembled prompt string.
 * Used by debounced gateway preview (Tabs 1+2) and execution.
 */
fun assembleContext(
    agent: AgentInstance,
    sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
    hkgContext: JsonObject?,
    agentState: AgentRuntimeState,
    store: Store
): ContextAssemblyResult?
```

The split addresses Red Team R1 (performance): toggle clicks invoke `assemblePartitions()` (fast — no 200K+ char string building), while the debounced gateway preview invokes `assembleContext()` (full assembly).

### 5.2 State Freeze (Red Team Fix F3)

Before every `buildPrompt()` call, the pipeline deep-copies the cognitive state:

```kotlin
val frozenState = Json.parseToJsonElement(Json.encodeToString(cognitiveState))
val builder = strategy.buildPrompt(context, frozenState)
```

This prevents prompt building from mutating NVRAM. Strategies that modify state during `buildPrompt()` modify the copy, which is discarded after the call. Only `postProcessResponse()` / `after_response()` can produce persistent state changes.

### 5.3 Assembly Pipeline

```
1. Build contextMap (gathered partitions — raw, no wrapping)

2. Build structured gathered partitions (formatters return Group/Section)
     HKG_FILES → Group with per-holon children
     CONVERSATION_LOG → Group with per-session children
     WORKSPACE_FILES → Group with per-file children

3. strategy.buildPrompt(context, frozenState) → PromptBuilder.sections

4. mergeIntoPartitions(sections, contextMap, overrides)
     Resolve GatheredRef → Section/Group from contextMap
     Resolve RemainingGathered → remaining entries

5. flattenWithCascade(mergedSections, overrides)         ← Red Team Fix F1
     Group COLLAPSED → emit summary only, EXCLUDE children
     Group EXPANDED → emit header + children individually

6. ContextCollapseLogic.collapse(flatPartitions, budget)
     Sees everything. Budget is accurate.

7. (Full path only) h1/h2 wrap + concatenate in declared order
     h1 for top-level, h2 for children within Groups

8. (Full path only) ContextDelimiters.wrapSystemPrompt()

9. Return ContextAssemblyResult / PartitionAssemblyResult
```

### 5.4 flattenWithCascade() — Red Team Fix F1

The critical function that enforces parent-child collapse semantics:

```kotlin
fun flattenWithCascade(
    sections: List<PromptSection>,
    overrides: Map<String, CollapseState>,
    parentKey: String? = null
): List<ContextPartition> {
    val result = mutableListOf<ContextPartition>()

    for (section in sections) {
        when (section) {
            is PromptSection.Section -> {
                result.add(toPartition(section, parentKey, overrides))
            }
            is PromptSection.Group -> {
                val groupState = overrides[section.key] ?: CollapseState.EXPANDED

                if (groupState == CollapseState.COLLAPSED) {
                    // CASCADE: parent collapsed → emit summary only, children excluded
                    val summary = section.collapsedSummary
                        ?: "[${section.key} collapsed — use CONTEXT_UNCOLLAPSE to expand]"
                    result.add(ContextPartition(
                        key = section.key,
                        fullContent = summary,
                        collapsedContent = summary,
                        state = CollapseState.COLLAPSED,
                        priority = section.priority,
                        isCollapsible = section.isCollapsible,
                        parentKey = parentKey
                    ))
                } else {
                    // EXPANDED: emit header (if any) + recurse into children
                    if (section.header.isNotBlank()) {
                        result.add(ContextPartition(
                            key = "${section.key}:header",
                            fullContent = section.header,
                            collapsedContent = section.header,
                            state = CollapseState.EXPANDED,
                            isCollapsible = false,
                            priority = section.priority,
                            parentKey = parentKey
                        ))
                    }
                    // Recurse — children carry this group as their parent
                    result.addAll(flattenWithCascade(
                        section.children, overrides, parentKey = section.key
                    ))
                }
            }
            is PromptSection.GatheredRef -> { /* already resolved by merge step */ }
            is PromptSection.RemainingGathered -> { /* already resolved by merge step */ }
        }
    }
    return result
}
```

### 5.5 ContextPartition Addition

```kotlin
data class ContextPartition(
    // ... all existing fields unchanged ...
    /** Parent partition key if child in a Group. Null for top-level. */
    val parentKey: String? = null
)
```

`ContextCollapseLogic.collapse()` requires no algorithm changes — it operates on the flat list identically. The `parentKey` is used only for display grouping and h2 wrapping.

### 5.6 Formatter Changes

Formatters return `Group`/`Section` instead of strings:

```kotlin
// ConversationLogFormatter
fun buildSections(sessions, platformDeps, formatOverrides?): PromptSection.Group

// HkgContextFormatter
fun buildFilesSections(hkgContext, collapseOverrides, formatOverrides?): PromptSection.Group

// WorkspaceContextFormatter
fun buildFilesSections(fileContents, collapseOverrides, formatOverrides?): PromptSection.Group
```

Old `format() → String` methods deprecated during migration, removed after validation.

### 5.7 ContextAssemblyResult

```kotlin
data class ContextAssemblyResult(
    val partitions: List<ContextCollapseLogic.ContextPartition>,
    val collapseResult: ContextCollapseLogic.CollapseResult,
    val budgetReport: String,
    val systemPrompt: String,
    val gatewayRequest: GatewayRequest,
    val softBudgetChars: Int,
    val maxBudgetChars: Int,
    /** Snapshot of transient data at assembly time (Red Team Fix F2). */
    val transientDataSnapshot: TransientDataSnapshot
)

/**
 * Frozen copy of the transient data needed for reassembly.
 * Decoupled from AgentStatusInfo's mutable lifecycle so that
 * IDLE transitions don't destroy it during manage context sessions.
 */
data class TransientDataSnapshot(
    val sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
    val hkgContext: JsonObject?,
    val workspaceListing: JsonArray?,
    val workspaceFileContents: Map<String, String>
)
```

### 5.8 PartitionAssemblyResult (fast path)

```kotlin
data class PartitionAssemblyResult(
    val partitions: List<ContextCollapseLogic.ContextPartition>,
    val collapseResult: ContextCollapseLogic.CollapseResult,
    val totalChars: Int,
    val softBudgetChars: Int,
    val maxBudgetChars: Int
)
```

No system prompt string. No gateway request. Just partition metadata for the UI.

---

## 6. Context Manager UI

### 6.1 Structure

"Preview Turn" → "Manage Context". Three tabs:

| Tab | Name | Data Source | Updates |
|-----|------|-------------|---------|
| 0 | **Context Management** | `managedContext.partitions` | Instant via `assemblePartitions()` |
| 1 | **API Preview** | `managedContext.systemPrompt` + `managedContextEstimatedTokens` | Debounced 5s via `assembleContext()` + gateway |
| 2 | **Raw JSON Payload** | `managedContextRawJson` | Same debounce |

### 6.2 Budget Bar

Golden-angle hue rotation (~137.508°) from the material primary hue. Each top-level partition gets a unique hue. Children within Groups use luminance variations of the parent's hue (cap rotation to top-level partitions per Red Team C1). The bar is a stacked segment visualization where width is proportional to effective token count.

### 6.3 Partition Cards

```
┌─────────────────────────────────────────────────────────┐
│ █ CONVERSATION_LOG                       ~12,400 tokens  │
│   [EXPANDED ▾]                          [View Content]   │
│                                                          │
│   ┌─ session:abc (Chat) ─────────── ~8,200 tokens ──┐   │
│   │  [EXPANDED ▾]                   [View Content]   │   │
│   └──────────────────────────────────────────────────┘   │
│   ┌─ session:def (Private) ──────── ~4,200 tokens ──┐   │
│   │  [EXPANDED ▾]                   [View Content]   │   │
│   └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ █ CONSTITUTION                            ~3,200 tokens  │
│   PROTECTED                                [View Content] │
└─────────────────────────────────────────────────────────┘
```

Toggle buttons: `[EXPANDED ▾]` / `[COLLAPSED ▸]` — the word IS the button. Only for `isCollapsible` partitions. PROTECTED partitions show a plain label.

Group collapse cascade: collapsing a group hides all children from the UI. Expanding restores per-child states (Red Team Q4: child states retained, not cascaded).

### 6.4 Toggle → Instant Reassembly Flow

```
User clicks [EXPANDED ▾] on CONVERSATION_LOG
  │
  ├─ Dispatch: CONTEXT_COLLAPSE { agentId, partitionKey: "CONVERSATION_LOG" }
  │    ├─ Reducer: contextCollapseOverrides["CONVERSATION_LOG"] = COLLAPSED
  │    └─ Side-effect:
  │         ├─ saveContextState()
  │         └─ if (managedContext != null):
  │              ├─ result = assemblePartitions(  ← FAST PATH (no string assembly)
  │              │     agent, snapshot.sessionLedgers, snapshot.hkgContext, ...)
  │              ├─ dispatch SET_MANAGED_PARTITIONS(result)  → UI recomposes instantly
  │              └─ resetDebouncedPreview(agentId)  → restart 5s timer
  │
  └─ (5s later, debounce fires):
       ├─ result = assembleContext(agent, snapshot data, ...)  ← FULL PATH
       └─ dispatch GATEWAY_PREPARE_PREVIEW → updates token estimate + raw JSON
```

The snapshot (`TransientDataSnapshot`) was captured at manage-context entry and lives on `managedContext`. It's decoupled from `AgentStatusInfo`'s mutable transient fields. IDLE transitions triggered by discard do NOT destroy it (Red Team Fix F2).

### 6.5 Debounce Management

```kotlin
private val previewDebounceJobs = mutableMapOf<IdentityUUID, Job>()

private fun resetDebouncedPreview(agentId: IdentityUUID, store: Store) {
    previewDebounceJobs[agentId]?.cancel()
    previewDebounceJobs[agentId] = coroutineScope.launch {
        delay(5_000L)
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return@launch
        val managedContext = state.agentStatuses[agentId]?.managedContext ?: return@launch
        // Full assembly + gateway preview dispatch using snapshot data
        // ...
    }
}
```

Discard cancels the debounce job (Red Team C4):

```kotlin
ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT -> {
    previewDebounceJobs[agentId]?.cancel()
    previewDebounceJobs.remove(agentId)
    // ... status transition to IDLE, clear managedContext ...
}
```

### 6.6 Content Viewer Dialog

Extends the dialog system with a `ContentViewer` variant:

```kotlin
sealed class DialogRequest {
    data class Confirmation(
        val title: String, val text: String,
        val confirmButtonText: String = "Confirm",
        val cancelButtonText: String? = "Cancel",
        val isDestructive: Boolean = false,
        val onConfirmAction: Action,
    ) : DialogRequest()

    data class ContentViewer(
        val title: String, val content: String,
        val copyButtonText: String = "Copy",
        val dismissButtonText: String = "Close",
        val isMonospace: Boolean = true,
    ) : DialogRequest()
}
```

Rendered as a scrollable, selectable-text `AlertDialog` with a copy button. Phase 1 alternative: local dialog state within `ManageContextView`.

### 6.7 Persistence Notice

Collapse overrides are saved to `context.json` and apply to all future turns. The Context Management tab shows: "Collapse settings persist across turns for this agent."

---

## 7. Lua Scripting API

### 7.1 Strategy Definition

```lua
strategy {
    name    = "My Assistant",
    version = 1,

    resources = {
        instructions = { type = "system_instruction", required = true },
    },

    settings = {
        output_session = { type = "output_session", name = "Primary Session" },
    },

    state = { turn_count = 0 },

    prompt = function(context)
        context:identity()
        context:instructions()
        context:sessions()
        context:everything_else()
    end,

    after_response = function(response, state)
        state.turn_count = state.turn_count + 1
        return "proceed"
    end,
}
```

### 7.2 Prompt Builders

```lua
-- Built-in sections
context:identity()                              -- standard 3-line block
context:identity_append("extra line", ...)      -- standard + appended lines
context:identity("full replacement content")    -- complete replacement
context:instructions()                          -- system_instruction resource
context:sessions()                              -- standard session list
context:sessions { format = "private" }         -- private-session variant
context:private_session_routing()               -- private session explanation
context:hkg_navigation()                        -- HKG navigation instructions
context:workspace_navigation()                  -- workspace navigation instructions

-- Custom sections
context:section("NAME", "content")
context:section("NAME", "content", { collapsible = true, priority = 50 })

-- Resources by slot
context:resource("slot_id")
context:resource("slot_id", { name = "DISPLAY NAME" })

-- Gathered partitions
context:place("PARTITION_KEY")
context:place("PARTITION_KEY", { format_message = function(msg) ... end })
context:has("KEY")
context:everything_else()

-- Groups
context:group("NAME", function()
    context:section("child-1", "content", { collapsible = true })
    context:section("child-2", "content", { collapsible = true })
end, { collapsible = true })
```

### 7.3 Reactive Listener: on_action

```lua
strategy {
    -- ... prompt, after_response, etc. ...

    -- Subscription filter (Red Team Fix R2): declare which actions to observe.
    -- Without this, on_action never fires (default: no subscriptions).
    on_action_filter = {
        "session.MESSAGE_POSTED",
        "agent.ACTION_RESULT",
    },

    -- Rate limiting (Red Team Fix C5)
    on_action_rate_limit = {
        max_per_second = 5,
        cooldown_after_dispatch_ms = 2000,
    },

    on_action = function(action, context)
        if action.name == "session.MESSAGE_POSTED" then
            if context.is_subscribed(action.payload.sessionId) then
                if action.payload.content:find("URGENT") then
                    context.agent:initiate_turn()
                end
            end
        end
    end,
}
```

The agent identity IS the sandbox. `on_action` receives only broadcast actions that the agent's effective permissions allow it to observe (same filter as `ExposedActionsContextProvider`). Self-dispatched actions are excluded.

`on_action` is an **observer, not an interceptor** — it fires in `handleSideEffects` after the reducer has processed the action. It can dispatch new actions and trigger turns, but cannot block, modify, or reorder the original action.

### 7.4 Lifecycle Hooks

```lua
on_registered = function(agent) ... end
on_config_changed = function(old_agent, new_agent) ... end
ensure_infrastructure = function(agent) ... end
needs_additional_context = function(agent) ... end
request_additional_context = function(agent) ... end
```

Lifecycle hooks receive `agent:dispatch(name, payload)` — stamps originator as the agent's identity handle, goes through `store.deferredDispatch()` with full Store security pipeline.

### 7.5 Context Queries

```lua
context.agent_name               -- "Kael"
context.agent_handle             -- "agent.kael"
context.agent_uuid               -- "abc-123-..."
context.state                    -- NVRAM table (frozen in prompt, mutable in after_response)
context.resources                -- table: slot_id → content string
context.sessions                 -- list of session info tables
context.output_session           -- { uuid, handle, name } or nil
context.actions                  -- filtered action catalog (same as ExposedActionsContextProvider)
context.model_provider           -- "anthropic"
context.model_name               -- "claude-sonnet-4-20250514"
context.last_input_tokens        -- from last turn (nil if first)
```

### 7.6 Kotlin / Lua Symmetry

| Concept | Kotlin | Lua |
|---------|--------|-----|
| Identity + extras | `identity("extra")` | `context:identity_append("extra")` |
| Identity replace | `identityCustom(content)` | `context:identity(content)` |
| Instructions | `instructions()` | `context:instructions()` |
| Sessions | `sessions(SessionFormat.PRIVATE)` | `context:sessions { format = "private" }` |
| Section | `section("K", content)` | `context:section("K", content)` |
| Resource | `resource("slot", "NAME")` | `context:resource("slot", { name = "NAME" })` |
| Place | `place("KEY")` | `context:place("KEY")` |
| Exists? | `has("KEY")` | `context:has("KEY")` |
| Rest | `everythingElse()` | `context:everything_else()` |
| Group | `group("K") { ... }` | `context:group("K", function() ... end)` |
| Formatter | `place("K", FormatOverrides(...))` | `context:place("K", { format_message = ... })` |

### 7.7 auf Runtime Library

```lua
auf.helpers.build_session_list(context)
auf.helpers.build_private_session_list(context)
auf.helpers.build_private_routing()
auf.helpers.build_hkg_navigation()
auf.helpers.build_workspace_navigation()

auf.has_gathered(key)
auf.get_workspace_files()
auf.hkg_reserved(persona_id)
auf.time()                          -- epoch millis

auf.log.debug(tag, message)
auf.log.info(tag, message)
auf.log.warn(tag, message)
auf.log.error(tag, message)
```

---

## 8. Action Bus Integration

### 8.1 Principle: No New Action System

The existing action architecture handles everything. A Lua strategy does not bypass any security layer:

| Concern | Handler | Lua's Role |
|---------|---------|------------|
| Which actions exist | ActionRegistry | Reads the catalog via `context.actions` |
| Which actions the LLM can invoke | Permissions + ExposedActionsContextProvider | Controls placement via `context:place("AVAILABLE_ACTIONS")` |
| Parsing auf_ code blocks | CommandBot | None — strategy is transparent to this pipeline |
| Permission enforcement | Store.processAction() Step 2b | None — enforced regardless of strategy |
| Sandbox rewrites | AgentRuntimeFeature | None — applied regardless of strategy |
| Infrastructure actions | Lifecycle hooks via `agent:dispatch()` | Same `store.deferredDispatch()` with agent originator |

### 8.2 LuaDispatchCallback

```kotlin
class LuaDispatchCallback(
    private val agent: AgentInstance,
    private val store: Store,
    private val platformDependencies: PlatformDependencies
) {
    fun dispatch(actionName: String, payload: Map<String, Any?>) {
        val descriptor = ActionRegistry.byActionName[actionName]
        if (descriptor == null) {
            platformDependencies.log(LogLevel.ERROR, "LuaStrategy",
                "Unknown action '$actionName' from agent '${agent.identityUUID}'. Rejected.")
            return
        }
        val jsonPayload = luaTableToJsonObject(payload)
        store.deferredDispatch(agent.identityHandle.handle, Action(
            name = actionName, payload = jsonPayload
        ))
    }
}
```

### 8.3 on_action Routing

`AgentRuntimeFeature.handleSideEffects()` gains a final step for broadcast actions:

```kotlin
if (descriptor?.broadcast == true && descriptor.public) {
    forwardToLuaStrategies(action, agentState, store)
}
```

Per-agent filter: active, Lua strategy with on_action, action name in `on_action_filter`, agent has required permissions, not self-originated.

Pre-computed observable action set (Red Team R2): when agent permissions change, recompute the set of observable action names. The filter becomes a set lookup per agent per action.

---

## 9. State Model Changes

### 9.1 AgentStatusInfo

```kotlin
data class AgentStatusInfo(
    // ... existing fields ...

    // REMOVED: stagedPreviewData: StagedPreviewData?

    // NEW: Managed context session
    /** Full assembly result. @Transient. Available while Manage Context view is open. */
    val managedContext: ContextAssemblyResult? = null,
    /** Fast-path partition data for Tab 0. Updated on every toggle. */
    val managedPartitions: PartitionAssemblyResult? = null,
    /** Raw JSON from debounced gateway preview. */
    val managedContextRawJson: String? = null,
    /** Provider-estimated input tokens from debounced gateway preview. */
    val managedContextEstimatedTokens: Int? = null,
)
```

### 9.2 StagedPreviewData

**Deleted.** Subsumed by `ContextAssemblyResult`.

### 9.3 ContextPartition

Addition of `parentKey: String?` for display grouping. No other changes.

---

## 10. Action Inventory

### Renamed (not new)

| Old | New |
|-----|-----|
| `agent.SET_PREVIEW_DATA` | `agent.SET_MANAGED_CONTEXT` |
| `agent.DISCARD_PREVIEW` | `agent.DISCARD_MANAGED_CONTEXT` |
| `agent.EXECUTE_PREVIEWED_TURN` | `agent.EXECUTE_MANAGED_TURN` |

### New (2)

| Action | Internal | Purpose |
|--------|----------|---------|
| `agent.UPDATE_MANAGED_PREVIEW` | Yes | Stores rawRequestJson + estimatedInputTokens from debounced gateway |
| `agent.SET_MANAGED_PARTITIONS` | Yes | Stores fast-path partition data for Tab 0 |

### Modified Behavior

| Action | Change |
|--------|--------|
| `agent.CONTEXT_UNCOLLAPSE` | Side-effect: if managedContext non-null, `assemblePartitions()` + `SET_MANAGED_PARTITIONS` + reset debounce |
| `agent.CONTEXT_COLLAPSE` | Same |
| `agent.INITIATE_TURN` | When `preview: true`, snapshot transient data, run `assembleContext()`, store as managedContext |

---

## 11. Security Model

### 11.1 Existing Layers (unchanged, apply to Lua)

| Layer | Mechanism |
|-------|-----------|
| Action schema validation | `ActionRegistry.byActionName` lookup |
| Originator validation | Store Step 1c |
| Authorization (public flag) | Store Step 2 |
| Permission guard | Store Step 2b |
| Lifecycle guard | Store Step 3 |
| CommandBot parsing | auf_ code block extraction + validation |
| Sandbox rewrites | AgentRuntimeFeature ACTION_CREATED handler |

### 11.2 Additional Layer: Lua Sandbox (Red Team Fix R3)

The Lua sandbox is a **security engineering task**, not "just remove os/io":

**Whitelisted globals only:** `auf.*`, `table.*` (read operations), `string.*` (minus `dump`), `math.*`, `pairs`, `ipairs`, `tostring`, `tonumber`, `type`, `error`, `pcall`, `select`, `unpack`. Everything else is nil.

**Memory limit:** Per-VM ceiling (10MB). Enforced via custom allocator.

**Instruction count limit:** Instead of wall-clock timeout (which races with GC), limit VM instructions per invocation. `prompt()`: 100K instructions. `on_action()`: 10K instructions. `after_response()`: 50K instructions.

**Separate VM per agent.** No shared state between VMs.

**Frozen copies.** All context tables passed to Lua are read-only snapshots. `context.state` is frozen during `prompt()` (writable only in `after_response()`).

**Dispatch is callback-gated.** Only available in lifecycle hooks and `on_action`. Routed through `LuaDispatchCallback` which stamps originator and goes through Store.

### 11.3 Trust Model

Strategy scripts have full control over their agent's prompt. The trust boundary is: "the user who places the .lua file in the strategy directory is trusted." Documentation states this clearly. This is the same trust model as any plugin system.

---

## 12. Migration Plan

### Phase 1: Foundation (no behavior change)

1. **Extract `assembleContext()` and `assemblePartitions()`** from `executeTurn()`. `executeTurn()` becomes a thin wrapper. Integration tests capture exact output before/after.

2. **Define `PromptSection`, `PromptBuilder`, `FormatOverrides`, `ContextAssemblyResult`, `PartitionAssemblyResult`, `TransientDataSnapshot`.** No code uses them yet.

3. **Add `buildPrompt()` to `CognitiveStrategy`** with default implementation that wraps `prepareSystemPrompt()` as a single opaque `Section("STRATEGY_PROMPT", ...)`.

### Phase 2: Strategy Migration (one at a time)

4. **Migrate VanillaStrategy.** Override `buildPrompt()` using `PromptBuilder`. Verify output matches.

5. **Migrate remaining strategies:** HKG, PrivateSession, StateMachine, Sovereign, Minimal. Each is mechanical.

6. **Update `AgentTurnContext`.** Replace `gatheredContexts` with `gatheredContextKeys`.

### Phase 3: Pipeline Unification

7. **Update formatters** to return `Group`/`Section`. Deprecate `format() → String` methods.

8. **Implement `mergeIntoPartitions()` and `flattenWithCascade()`.** Update `assembleContext()` / `assemblePartitions()` to use the new flow.

9. **Remove `prepareSystemPrompt()`** from `CognitiveStrategy`. Delete deprecated method.

10. **Stub `forwardToLuaStrategies()`** as a no-op extension point in `handleSideEffects()`.

### Phase 4: Context Manager UI

11. **Rename "Preview Turn" → "Manage Context."** Update view, tabs, action names.

12. **Build Context Management tab.** Budget bar + partition cards + content dialog.

13. **Wire instant reassembly.** Side-effect on collapse toggles calls `assemblePartitions()` + `SET_MANAGED_PARTITIONS`. Snapshot transient data on manage-context entry (Red Team Fix F2).

14. **Wire debounced gateway preview.** `previewDebounceJobs`, 5s delay, `UPDATE_MANAGED_PREVIEW`.

### Phase 5: Lua Runtime

15. **Implement Lua runtime.** Embed LuaJ (or alternative). `LuaStrategyBridge` translates via `PromptBuilder`. Security sandbox per §11.2.

16. **`auf` runtime library.** Shared helpers, context queries, logging.

17. **`on_action` listener.** `forwardToLuaStrategies()` implementation. Subscription filter, pre-computed observable sets, rate limiting.

18. **Strategy loader.** File watcher for `.lua` files. Hot-reload on next turn.

---

## 13. Files Affected

| File | Phase | Change |
|------|-------|--------|
| `CognitiveStrategy.kt` | 1, 2, 3 | Add `PromptSection`, `PromptBuilder`, `FormatOverrides`. Add `buildPrompt()`. Remove `prepareSystemPrompt()`. |
| `AgentCognitivePipeline.kt` | 1, 3 | Extract `assembleContext()` / `assemblePartitions()`. `mergeIntoPartitions()`. `flattenWithCascade()`. State freeze. |
| `AgentState.kt` | 1, 4 | Add result types. Update `AgentStatusInfo`. Add `parentKey` to `ContextPartition`. Update `AgentTurnContext`. |
| `VanillaStrategy.kt` | 2 | Migrate to `buildPrompt()`. Extract shared helpers. |
| `HKGStrategy.kt` | 2 | Same. |
| `PrivateSessionStrategy.kt` | 2 | Same. |
| `StateMachineStrategy.kt` | 2 | Same. |
| `SovereignStrategy.kt` | 2 | Same. |
| `MinimalStrategy.kt` | 2 | Same. |
| `ConversationLogFormatter.kt` | 3 | Return `Group` with per-session children. |
| `HKGContextFormatter.kt` | 3 | Return `Group` with per-holon children. |
| `WorkspaceContextFormatter.kt` | 3 | Return `Group` with per-file children. |
| `AgentRuntimeReducer.kt` | 4 | Rename preview actions. Handle new managed context actions. |
| `AgentRuntimeFeature.kt` | 3, 4, 5 | Reassembly on toggles. Debounce. Snapshot lifecycle. `forwardToLuaStrategies()`. |
| `PreviewContextView.kt` → `ManageContextView.kt` | 4 | Rename. Three tabs. Budget bar. Partition cards. |
| `CoreDialogs.kt` | 4 | Add `DialogRequest.ContentViewer`. |
| `agent_actions.json` | 4 | Rename 3 actions, add 2. |
| `ContextCollapseLogic_.kt` | 3 | Add `parentKey` to `ContextPartition`. No algorithm changes. |
| `ContextDelimiters.kt` | — | No changes. |

---

## 14. Risks, Mitigations, and Red Team Findings

### 14.1 Fatal Flaws (Fixed in Design)

**F1: Flatten cascade.** Without cascade semantics, a collapsed Group's children still appear in the flat partition list. Budget sees 28,000 chars instead of 50. **Fixed:** `flattenWithCascade()` excludes children when parent is COLLAPSED (§5.4).

**F2: Status lifecycle leak.** After discarding managed context, agent is stuck in PROCESSING. Transitioning to IDLE triggers `shouldClearContext`, destroying transient data needed for reassembly. **Fixed:** `TransientDataSnapshot` captured on manage-context entry, stored on `ContextAssemblyResult`. Decoupled from `AgentStatusInfo`'s mutable lifecycle (§5.7).

**F3: Mutable state during reassembly.** `buildPrompt()` called on every toggle can mutate NVRAM. **Fixed:** Deep-copy state before every `buildPrompt()` call. Lua gets frozen table with read-only metatable (§5.2).

### 14.2 Serious Risks (Mitigated)

**R1: Reassembly performance.** Full string assembly on every toggle is expensive. **Mitigated:** Split into `assemblePartitions()` (fast, for Tab 0) and `assembleContext()` (full, debounced for Tabs 1+2) (§5.1).

**R2: on_action scaling.** O(agents × actions) for every broadcast. **Mitigated:** Subscription filter (`on_action_filter`) in strategy manifest. Pre-computed observable action sets invalidated on permission change. Runtime rate limiting (§7.3).

**R3: Lua sandbox.** "Remove os/io" is not a security boundary. **Mitigated:** Whitelisted globals, memory limits, instruction count limits, separate VM per agent, frozen copies, callback-gated dispatch (§11.2).

### 14.3 Concerns (Accepted)

**R4: FormatOverrides prompt manipulation.** Same trust model as strategy authoring. Documentation. **R5: Phase 1 not zero-risk.** Integration tests before/after extraction. **C1: Color collision at 31+ partitions.** Cap rotation to top-level. **C2: Key orphaning.** Key conventions preserved; orphans harmless. **C3: Duplicate sections.** Detection in builder. **C4: Debounce timer leak.** Cancel job on discard. **C5: on_action debouncing.** Built-in rate limiting at runtime level. **C6: Stale context.actions.** Reassembly re-reads permissions.

---

## 15. Open Questions

1. **Manage Context without preview turn?** `assemblePartitions()` as a pure function enables a "Gather Context" button that doesn't require `INITIATE_TURN`. Recommend: follow-up after Phase 4.

2. **Formatter callback scope.** Should `format_message` have access to full session context or single message only? Recommend: single message for v1, full session for v2.

3. **Lua hot-reload semantics.** Recommend: new code applies on next turn (no mid-turn swap).

4. **Group collapse cascade in UI.** Recommend: child states retained when parent collapses, restored when parent expands.

5. **Maximum nesting depth.** Recommend: cap at 2 in UI (Group → Section), allow deeper in model.

6. **Custom action registration (Lua).** Future capability via existing `REGISTER_ACTION` mechanism. Phase 5+ concern.

7. **Kotlin `onAction` hook.** Should `CognitiveStrategy` gain an `onAction` method for Kotlin strategies? Lower priority since Kotlin strategies are internal code. Recommend: defer to Phase 5.
