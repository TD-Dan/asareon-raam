package app.auf.feature.agent

import app.auf.core.IdentityUUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// =============================================================================
// Unified Partition Model + Builder API + Assembly Result Types
//
// This file defines the foundation types for the context architecture redesign.
// Phase 1: types defined, default CognitiveStrategy.buildPrompt() bridges to
//   prepareSystemPrompt() as a single opaque Section.
// Phase 2: all 6 strategies override buildPrompt() natively.
// Phase 3: pipeline switches to consume buildPrompt(); prepareSystemPrompt() removed.
//
// Contents:
//   §1  PromptSection        — sealed class hierarchy (§3.1 of design doc)
//   §2  FormatOverrides      — strategy-level formatting callbacks (§3.2)
//   §3  PromptBuilder        — fluent API for prompt construction (§4.2)
//   §4  SessionFormat        — enum for session rendering variants
//   §4b Session helpers      — shared subscription rendering (moved from VanillaStrategy)
//   §5  ContextAssemblyResult / PartitionAssemblyResult / TransientDataSnapshot (§5.7–5.8)
// =============================================================================


// =============================================================================
// §1  PromptSection — Unified partition model
// =============================================================================

/**
 * Describes every piece of the prompt — strategy sections, gathered partitions,
 * and sub-items within groups — using a single sealed hierarchy.
 */
sealed class PromptSection {

    /**
     * A named section of the prompt. Leaf node.
     *
     * Used for strategy-owned content (identity, instructions, navigation)
     * and for individual items within a [Group] (single holon, single file).
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
     * Collapse cascade (Red Team Fix F1):
     * - Group COLLAPSED → entire group replaced by [collapsedSummary].
     *   Children EXCLUDED from the flat partition list.
     * - Group EXPANDED → each child rendered per its own collapse state.
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
     * Reference to a pipeline-gathered partition by key.
     * Resolved to [Section] or [Group] during the merge step.
     * Silently skipped if absent.
     */
    data class GatheredRef(
        val key: String,
        val formatOverrides: FormatOverrides? = null
    ) : PromptSection()

    /**
     * "All gathered partitions not explicitly placed via [GatheredRef]."
     * MULTI_AGENT_CONTEXT first among remaining, then alphabetical.
     */
    data object RemainingGathered : PromptSection()
}


// =============================================================================
// §2  FormatOverrides — Strategy-level formatting callbacks
// =============================================================================

/**
 * Allows strategies to customize how gathered partitions format their internal
 * content without rebuilding the formatter.
 *
 * Callbacks are pure transforms: frozen input, string output. Return null to
 * skip an entry. Same interface for Kotlin and Lua (bridge translates Lua
 * functions to lambdas).
 */
data class FormatOverrides(
    /** Custom formatter for individual messages in a conversation log session. */
    val formatMessage: ((GatewayMessage) -> String?)? = null,
    /** Custom formatter for an entire session ledger snapshot. */
    val formatSession: ((ConversationLogFormatter.SessionLedgerSnapshot, String) -> String?)? = null,
    /** Custom formatter for a generic key-value entry (HKG holon, workspace file). */
    val formatEntry: ((key: String, content: String) -> String?)? = null
)


// =============================================================================
// §3  PromptBuilder — Fluent API for prompt construction
// =============================================================================

/**
 * Fluent API for declaring the structure and content of an agent's system prompt.
 *
 * Both Kotlin strategies and (future) Lua scripts use this builder. Strategies
 * express *what* content exists and *where* it goes. The pipeline handles *how*
 * (collapse, budgeting, wrapping, assembly).
 *
 * Duplicate detection (Red Team C3): [emittedKeys] tracks all keys; duplicates skipped.
 */
class PromptBuilder(private val context: AgentTurnContext) {
    internal val sections = mutableListOf<PromptSection>()
    private val emittedKeys = mutableSetOf<String>()

    // ── Built-in sections ───────────────────────────────────────────

    /** Standard 3-line identity block with optional extra lines appended. */
    fun identity(vararg extraLines: String) {
        val content = buildString {
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment.")
            appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")
            extraLines.forEach { appendLine(it) }
        }
        emitSection("YOUR IDENTITY AND ROLE", content, isProtected = true)
    }

    /** Full replacement of the identity section. */
    fun identityCustom(content: String) {
        emitSection("YOUR IDENTITY AND ROLE", content, isProtected = true)
    }

    /** Emits the system_instruction resource as SYSTEM INSTRUCTIONS. No-op if blank. */
    fun instructions() {
        val content = context.resolvedResources["system_instruction"]
            ?.takeIf { it.isNotBlank() }
            ?: return
        emitSection("SYSTEM INSTRUCTIONS", content, isProtected = true)
    }

    /** Standard or private-format session subscription list. */
    fun sessions(format: SessionFormat = SessionFormat.STANDARD) {
        if (context.subscribedSessions.isEmpty()) return
        val content = when (format) {
            SessionFormat.STANDARD -> buildSubscribedSessionsContent(context)
            SessionFormat.PRIVATE -> buildPrivateSubscribedSessionsContent(context)
        }
        emitSection("SUBSCRIBED SESSIONS", content, isProtected = true)
    }

    /** Private session routing explanation block. */
    fun privateSessionRouting() {
        val content = buildString {
            appendLine("Your responses are routed to your PRIVATE session. This session is your")
            appendLine("internal workspace — only you can see it. Other participants cannot read it.")
            appendLine()
            appendLine("To communicate with users and other agents, you MUST use the session.POST")
            appendLine("action to post messages to the public sessions you are subscribed to.")
            appendLine()
            appendLine("Example — posting to a public session:")
            appendLine("```auf_session.POST")
            appendLine("""{ "session": "<session name or handle>", "message": "Your message here." }""")
            appendLine("```")
            appendLine()
            appendLine("The conversation messages in your context come from ALL your subscribed sessions.")
            appendLine("Your direct response text goes to your private session (invisible to others).")
            appendLine("Always use session.POST when you want others to see your message.")
        }
        emitSection("PRIVATE SESSION ROUTING", content, isProtected = true)
    }

    /** HKG navigation instructions. Only emitted when HKG INDEX was gathered. */
    fun hkgNavigation() {
        if ("HOLON_KNOWLEDGE_GRAPH_INDEX" !in context.gatheredContextKeys) return
        val content = buildString {
            appendLine("Your Knowledge Graph is presented as an INDEX (tree overview) and FILES (open file contents).")
            appendLine("By default, all files are closed. Use these commands to navigate:")
            appendLine()
            appendLine("Open a single holon file:")
            appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "single" }""")
            appendLine("```")
            appendLine()
            appendLine("Open a holon and reveal its children in the INDEX:")
            appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "subtree" }""")
            appendLine("```")
            appendLine()
            appendLine("Close a holon file:")
            appendLine("```auf_agent.CONTEXT_COLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>" }""")
            appendLine("```")
            appendLine()
            appendLine("IMPORTANT: You must expand a holon file before writing to it.")
            appendLine("The system will block writes to collapsed holons to prevent data loss.")
        }
        emitSection("HKG NAVIGATION", content, isProtected = true)
    }

    /** Workspace navigation instructions. Only emitted when WORKSPACE_INDEX was gathered. */
    fun workspaceNavigation() {
        if ("WORKSPACE_INDEX" !in context.gatheredContextKeys) return
        val content = buildString {
            appendLine("Your workspace is presented as a WORKSPACE_INDEX (tree overview) and WORKSPACE_FILES (open file contents).")
            appendLine("By default, all files are closed. Use these commands to navigate:")
            appendLine()
            appendLine("Open a single workspace file:")
            appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "ws:<relativePath>", "scope": "single" }""")
            appendLine("```")
            appendLine()
            appendLine("Expand a directory (reveal its contents in the tree, without opening files):")
            appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "ws:<dirPath>/", "scope": "single" }""")
            appendLine("```")
            appendLine()
            appendLine("Expand a directory and all sub-directories (tree navigation only, no files opened):")
            appendLine("```auf_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "ws:<dirPath>/", "scope": "subtree" }""")
            appendLine("```")
            appendLine()
            appendLine("Close a workspace file or collapse a directory:")
            appendLine("```auf_agent.CONTEXT_COLLAPSE")
            appendLine("""{ "partitionKey": "ws:<relativePath>" }""")
            appendLine("```")
            appendLine()
            appendLine("IMPORTANT: The prefix is \"ws:\", not \"workspace:\". Directory paths end with \"/\".")
            appendLine("Example: \"ws:sovereign-design.md\", \"ws:src/\", \"ws:src/main.kt\"")
            appendLine("You must expand a workspace file before writing to it.")
            appendLine("The system will block writes to collapsed files to prevent data loss.")
        }
        emitSection("WORKSPACE NAVIGATION", content, isProtected = true)
    }

    // ── Custom sections ─────────────────────────────────────────────

    /** Named section. Protected and non-collapsible by default. */
    fun section(
        key: String, content: String,
        isProtected: Boolean = true, isCollapsible: Boolean = false,
        priority: Int = 1000, collapsedSummary: String? = null,
        truncateFromStart: Boolean = false
    ) {
        emitSection(key, content, isProtected, isCollapsible, priority, collapsedSummary, truncateFromStart)
    }

    /** Emits a named resource as a section. No-op if slot is empty/blank. */
    fun resource(slotId: String, sectionName: String? = null) {
        val content = context.resolvedResources[slotId]
            ?.takeIf { it.isNotBlank() }
            ?: return
        val key = sectionName ?: slotId.uppercase()
        emitSection(key, content, isProtected = true)
    }

    // ── Gathered partition placement ────────────────────────────────

    /** Place a pipeline-gathered partition at this position. Silently skipped if absent. */
    fun place(key: String, formatOverrides: FormatOverrides? = null) {
        if (!checkDuplicate(key)) return
        sections.add(PromptSection.GatheredRef(key, formatOverrides))
    }

    /** True if the pipeline gathered a partition with this key. */
    fun has(key: String): Boolean = key in context.gatheredContextKeys

    /** All remaining gathered partitions not explicitly placed. */
    fun everythingElse() {
        sections.add(PromptSection.RemainingGathered)
    }

    // ── Groups ──────────────────────────────────────────────────────

    /** Group with individually collapsible children. */
    fun group(
        key: String, header: String = "",
        isProtected: Boolean = false, isCollapsible: Boolean = true,
        priority: Int = 0, collapsedSummary: String? = null,
        build: PromptBuilder.() -> Unit
    ) {
        if (!checkDuplicate(key)) return
        val childBuilder = PromptBuilder(context)
        childBuilder.build()
        sections.add(PromptSection.Group(
            key = key, header = header,
            children = childBuilder.sections.toList(),
            isProtected = isProtected, isCollapsible = isCollapsible,
            priority = priority, collapsedSummary = collapsedSummary
        ))
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun emitSection(
        key: String, content: String,
        isProtected: Boolean = true, isCollapsible: Boolean = false,
        priority: Int = 1000, collapsedSummary: String? = null,
        truncateFromStart: Boolean = false
    ) {
        if (!checkDuplicate(key)) return
        sections.add(PromptSection.Section(
            key = key, content = content,
            isProtected = isProtected, isCollapsible = isCollapsible,
            priority = priority, collapsedSummary = collapsedSummary,
            truncateFromStart = truncateFromStart
        ))
    }

    /** Returns true if key is new (OK to emit). Returns false on duplicate (Red Team C3). */
    private fun checkDuplicate(key: String): Boolean {
        if (key in emittedKeys) return false
        emittedKeys.add(key)
        return true
    }
}


// =============================================================================
// §4  SessionFormat
// =============================================================================

/** Format variant for session subscription rendering. */
enum class SessionFormat {
    /** Standard session list (Vanilla, HKG, StateMachine). */
    STANDARD,
    /** Private session variant with routing tags (PrivateSession, Sovereign). */
    PRIVATE
}


// =============================================================================
// §4b Shared session subscription helpers
//
// Pipeline-level utilities used by PromptBuilder.sessions() and by strategies
// that build session lists directly. Moved here from VanillaStrategy.kt to
// eliminate a cross-package dependency (strategies → agent, not agent → strategies).
// =============================================================================

/**
 * Builds the inner content for the SUBSCRIBED SESSIONS section.
 * Each session is listed with a PRIMARY tag for the output session.
 * Reused by all strategies that show session awareness.
 */
fun buildSubscribedSessionsContent(context: AgentTurnContext): String = buildString {
    appendLine("You are currently subscribed to the following sessions:")
    context.subscribedSessions.forEach { session ->
        val primaryTag = if (session.isOutput || (context.outputSessionHandle == null && session == context.subscribedSessions.first())) {
            " [PRIMARY — Your output and tool results are routed here]"
        } else {
            ""
        }
        appendLine("  - ${session.name} (${session.handle})$primaryTag")
    }
    appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
}

/**
 * Builds the inner content for the SUBSCRIBED SESSIONS section with
 * private session routing tags. Used by PrivateSessionStrategy and Sovereign.
 */
fun buildPrivateSubscribedSessionsContent(context: AgentTurnContext): String = buildString {
    context.subscribedSessions.forEach { session ->
        val tag = if (session.isOutput || (context.outputSessionHandle == null && session == context.subscribedSessions.first())) {
            "PRIVATE — Your direct output is routed here, invisible to others"
        } else {
            "PUBLIC — Use session.POST to communicate here"
        }

        val sessionHeader = "${session.name} (${session.handle}) [$tag] | ${session.messageCount} messages"
        append(ContextDelimiters.h2(sessionHeader))

        if (session.participants.isNotEmpty()) {
            session.participants.forEach { participant ->
                appendLine("  - ${participant.senderName} (${participant.senderId}): ${participant.type}, ${participant.messageCount} messages")
            }
        } else {
            appendLine("  (no messages yet)")
        }

        append(ContextDelimiters.h2End("SESSION"))
    }
}


// =============================================================================
// §5  Assembly Result Types
// =============================================================================

/**
 * Complete result of the context assembly pipeline (full path).
 *
 * Contains partitions, collapse result, budget report, assembled system prompt,
 * gateway request, and a frozen transient data snapshot.
 */
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
 * Fast-path result: partition metadata only (no string assembly).
 * Used by the Context Manager UI (Tab 0) for instant reassembly on toggle.
 */
data class PartitionAssemblyResult(
    val partitions: List<ContextCollapseLogic.ContextPartition>,
    val collapseResult: ContextCollapseLogic.CollapseResult,
    val totalChars: Int,
    val softBudgetChars: Int,
    val maxBudgetChars: Int
)

/**
 * Frozen copy of transient data needed for reassembly.
 * Captured at manage-context entry. Decoupled from [AgentStatusInfo]'s mutable
 * lifecycle so IDLE transitions don't destroy it (Red Team Fix F2).
 */
data class TransientDataSnapshot(
    val sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
    val hkgContext: JsonObject?,
    val workspaceListing: JsonArray?,
    val workspaceFileContents: Map<String, String>
)