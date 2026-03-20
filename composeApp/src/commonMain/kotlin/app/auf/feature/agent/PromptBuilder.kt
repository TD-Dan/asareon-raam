package app.auf.feature.agent

import app.auf.core.IdentityUUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// =============================================================================
// Unified Partition Model + Builder API + Assembly Result Types
//
// Contents:
//   §1  PromptSection        — sealed class hierarchy (§3.1)
//   §2  FormatOverrides      — strategy-level formatting callbacks (§3.2)
//   §3  PromptBuilder        — fluent API for prompt construction (§4.2)
//   §4  ContextAssemblyResult / PartitionAssemblyResult / TransientDataSnapshot (§5.7–5.8)
// =============================================================================


// =============================================================================
// §1  PromptSection
// =============================================================================

sealed class PromptSection {

    data class Section(
        val key: String,
        val content: String,
        val isProtected: Boolean = true,
        val isCollapsible: Boolean = false,
        val priority: Int = 1000,
        val collapsedSummary: String? = null,
        val truncateFromStart: Boolean = false,
        /**
         * If true, default collapse state is COLLAPSED when the key is absent from
         * the agent's collapse overrides. Used by Group children (individual holons,
         * workspace files) whose default is "closed until the agent opens them."
         * Strategy-owned sections and top-level partitions default to EXPANDED (false).
         */
        val defaultCollapsed: Boolean = false
    ) : PromptSection()

    /**
     * Collapse cascade (Red Team Fix F1):
     * - COLLAPSED → entire group replaced by [collapsedSummary], children excluded.
     * - EXPANDED → each child rendered per its own collapse state.
     *
     * @param defaultCollapsed If true, default state is COLLAPSED when the key is absent
     *   from the agent's collapse overrides. Used for sub-holon groups whose default is
     *   "closed until the agent opens them." Top-level Groups default to EXPANDED (false).
     */
    data class Group(
        val key: String,
        val header: String = "",
        val children: List<PromptSection>,
        val isProtected: Boolean = false,
        val isCollapsible: Boolean = true,
        val priority: Int = 0,
        val collapsedSummary: String? = null,
        val truncateFromStart: Boolean = false,
        val defaultCollapsed: Boolean = false
    ) : PromptSection()

    data class GatheredRef(
        val key: String,
        val formatOverrides: FormatOverrides? = null
    ) : PromptSection()

    data object RemainingGathered : PromptSection()
}


// =============================================================================
// §2  FormatOverrides
// =============================================================================

data class FormatOverrides(
    val formatMessage: ((GatewayMessage) -> String?)? = null,
    val formatSession: ((ConversationLogFormatter.SessionLedgerSnapshot, String) -> String?)? = null,
    val formatEntry: ((key: String, content: String) -> String?)? = null
)


// =============================================================================
// §3  PromptBuilder
// =============================================================================

/**
 * Fluent API for declaring the structure and content of an agent's system prompt.
 * Strategies express *what* content exists and *where* it goes. The pipeline
 * handles *how* (collapse, budgeting, wrapping, assembly).
 *
 * Duplicate detection (Red Team C3): [emittedKeys] tracks all keys; duplicates skipped.
 */
class PromptBuilder(private val context: AgentTurnContext) {
    internal val sections = mutableListOf<PromptSection>()
    private val emittedKeys = mutableSetOf<String>()

    // ── Built-in sections ───────────────────────────────────────────

    fun identity(vararg extraLines: String) {
        val content = buildString {
            appendLine("You are ${context.agentName}.")
            appendLine("You are a participant in a multi-user, multi-session agent environment.")
            appendLine("Maintain your own boundaries and role, do not respond on behalf of other participants.")
            extraLines.forEach { appendLine(it) }
        }
        emitSection("YOUR IDENTITY AND ROLE", content, isProtected = true)
    }

    fun identityCustom(content: String) {
        emitSection("YOUR IDENTITY AND ROLE", content, isProtected = true)
    }

    fun instructions() {
        val content = context.resolvedResources["system_instruction"]
            ?.takeIf { it.isNotBlank() } ?: return
        emitSection("SYSTEM INSTRUCTIONS", content, isProtected = true)
    }

    /** Places the gathered SESSIONS partition at this position. */
    fun sessions() {
        place("SESSIONS")
    }

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

    fun section(
        key: String, content: String,
        isProtected: Boolean = true, isCollapsible: Boolean = false,
        priority: Int = 1000, collapsedSummary: String? = null,
        truncateFromStart: Boolean = false
    ) {
        emitSection(key, content, isProtected, isCollapsible, priority, collapsedSummary, truncateFromStart)
    }

    fun resource(slotId: String, sectionName: String? = null) {
        val content = context.resolvedResources[slotId]
            ?.takeIf { it.isNotBlank() } ?: return
        emitSection(sectionName ?: slotId.uppercase(), content, isProtected = true)
    }

    // ── Gathered partition placement ────────────────────────────────

    fun place(key: String, formatOverrides: FormatOverrides? = null) {
        if (!checkDuplicate(key)) return
        sections.add(PromptSection.GatheredRef(key, formatOverrides))
    }

    fun has(key: String): Boolean = key in context.gatheredContextKeys

    fun everythingElse() {
        sections.add(PromptSection.RemainingGathered)
    }

    // ── Groups ──────────────────────────────────────────────────────

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

    private fun checkDuplicate(key: String): Boolean {
        if (key in emittedKeys) return false
        emittedKeys.add(key)
        return true
    }
}


// =============================================================================
// §4  Assembly Result Types
// =============================================================================

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

data class PartitionAssemblyResult(
    val partitions: List<ContextCollapseLogic.ContextPartition>,
    val collapseResult: ContextCollapseLogic.CollapseResult,
    val totalChars: Int,
    val softBudgetChars: Int,
    val maxBudgetChars: Int
)

/**
 * Frozen copy of transient data needed for reassembly.
 * Decoupled from [AgentStatusInfo]'s mutable lifecycle (Red Team Fix F2).
 */
data class TransientDataSnapshot(
    val sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
    val hkgContext: JsonObject?,
    val workspaceListing: JsonArray?,
    val workspaceFileContents: Map<String, String>
)