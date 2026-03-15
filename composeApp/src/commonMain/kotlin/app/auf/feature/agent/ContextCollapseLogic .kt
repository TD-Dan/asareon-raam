package app.auf.feature.agent

import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies

/**
 * Pipeline-level utility for enforcing the agent's context budget.
 *
 * Implements §3.3–3.5 of the Sovereign Stabilization design document:
 * - [ContextPartition] data model
 * - [collapse] auto-collapse algorithm
 * - [buildBudgetReport] context budget report generation
 * - Oversized partial sentinel (truncation with diagnostic message)
 *
 * Lives in `app.auf.feature.agent` (pipeline package), NOT in `strategies`.
 * Called by [AgentCognitivePipeline.executeTurn] after all context is gathered,
 * before [CognitiveStrategy.prepareSystemPrompt].
 *
 * Design invariants:
 * - Tokens use the `≈4 chars/token` heuristic (§2.2). All estimates are approximate.
 * - The pipeline is the safety net. Agent-driven collapse is the primary mechanism.
 * - Partitions with [ContextPartition.isAutoCollapsible] = false are never collapsed
 *   by the budget algorithm (e.g., INDEX partitions, SESSION_METADATA).
 * - Agent sticky overrides are respected up to the hard maximum. If overrides exceed
 *   the max, the algorithm force-collapses with a warning (§3.4 step 5d).
 */
object ContextCollapseLogic {

    private const val LOG_TAG = "ContextCollapseLogic"
    private const val CHARS_PER_TOKEN = 4

    /**
     * A single partition of the agent's context window.
     *
     * Built from the `contextMap` entries in [AgentCognitivePipeline.executeTurn].
     * Each entry in the context map becomes a partition with full and collapsed
     * content variants.
     *
     * @param key The partition key (e.g., "HOLON_KNOWLEDGE_GRAPH_FILES", "CONVERSATION_LOG").
     * @param fullContent Complete content when EXPANDED.
     * @param collapsedContent Summary/header shown when COLLAPSED. Empty string if
     *   the partition disappears entirely when collapsed.
     * @param charCount [fullContent].length — cached for budget calculations.
     * @param collapsedCharCount [collapsedContent].length — cached for budget calculations.
     * @param state Resolved collapse state after overrides + budget enforcement.
     * @param priority Higher = collapse last. Constitutions/INDEX = [Int.MAX_VALUE].
     * @param isAutoCollapsible If false, the budget algorithm never touches this partition.
     *   Used for partitions that must always be present (INDEX, SESSION_METADATA).
     * @param isAgentOverridden True if the agent has a sticky override for this partition.
     *   Force-collapse of agent-overridden partitions only happens as a last resort (§3.4 step 5d).
     */
    data class ContextPartition(
        val key: String,
        val fullContent: String,
        val collapsedContent: String,
        val charCount: Int = fullContent.length,
        val collapsedCharCount: Int = collapsedContent.length,
        val state: CollapseState = CollapseState.EXPANDED,
        val priority: Int = 0,
        val isAutoCollapsible: Boolean = true,
        val isAgentOverridden: Boolean = false
    ) {
        /** The char count for the partition's current state. */
        val effectiveCharCount: Int
            get() = if (state == CollapseState.EXPANDED) charCount else collapsedCharCount
    }

    /**
     * Result of the collapse algorithm.
     *
     * @param partitions The partitions with their final resolved states.
     * @param totalChars Total character count after collapse.
     * @param autoCollapseApplied True if any partitions were auto-collapsed by the budget algorithm.
     * @param forceCollapseApplied True if agent-overridden partitions were force-collapsed.
     * @param autoCollapsedKeys Keys of partitions that were auto-collapsed (for the budget report).
     * @param forceCollapsedKeys Keys of partitions that were force-collapsed (for the budget report).
     * @param truncatedKeys Keys of partitions that were truncated by the oversized sentinel.
     */
    data class CollapseResult(
        val partitions: List<ContextPartition>,
        val totalChars: Int,
        val autoCollapseApplied: Boolean = false,
        val forceCollapseApplied: Boolean = false,
        val autoCollapsedKeys: List<String> = emptyList(),
        val forceCollapsedKeys: List<String> = emptyList(),
        val truncatedKeys: List<String> = emptyList()
    )

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the auto-collapse algorithm on the given partitions.
     *
     * Implements §3.4 of the design document:
     * 1. Calculate total chars using each partition's current state.
     * 2. If total ≤ maxBudgetChars → proceed (no collapse needed).
     * 3. If total > maxBudgetChars:
     *    a. Sort auto-collapsible partitions by priority ASC, then charCount DESC.
     *    b. Collapse each until total ≤ max. Skip agent-overridden partitions.
     *    c. If STILL over: force-collapse agent-expanded partitions (lowest priority first).
     * 4. Apply oversized partial sentinel to remaining expanded partitions.
     *
     * @param partitions The context partitions to evaluate.
     * @param maxBudgetChars Hard maximum context size in characters (safety net ceiling).
     * @param maxPartialChars Maximum single-partial size before truncation sentinel fires.
     * @param platformDependencies For logging.
     * @param agentId For log messages.
     */
    fun collapse(
        partitions: List<ContextPartition>,
        maxBudgetChars: Int,
        maxPartialChars: Int,
        platformDependencies: PlatformDependencies? = null,
        agentId: String? = null
    ): CollapseResult {
        val mutablePartitions = partitions.toMutableList()
        val autoCollapsedKeys = mutableListOf<String>()
        val forceCollapsedKeys = mutableListOf<String>()
        val truncatedKeys = mutableListOf<String>()

        var totalChars = mutablePartitions.sumOf { it.effectiveCharCount }

        // Step 2: Under budget → no collapse needed
        if (totalChars <= maxBudgetChars) {
            // Still apply oversized partial sentinel
            val (sentinelPartitions, sentinelTruncated) = applySentinel(mutablePartitions, maxPartialChars, platformDependencies, agentId)
            val finalTotal = sentinelPartitions.sumOf { it.effectiveCharCount }
            return CollapseResult(
                partitions = sentinelPartitions,
                totalChars = finalTotal,
                truncatedKeys = sentinelTruncated
            )
        }

        // Step 3a: Over budget — sort candidates for collapse
        // Candidates: auto-collapsible, currently EXPANDED, NOT agent-overridden
        platformDependencies?.log(
            LogLevel.WARN, LOG_TAG,
            "Agent '${agentId ?: "unknown"}' context ($totalChars chars, ~${totalChars / CHARS_PER_TOKEN} tokens) " +
                    "exceeds max budget ($maxBudgetChars chars, ~${maxBudgetChars / CHARS_PER_TOKEN} tokens). Auto-collapsing."
        )

        // Step 3b: Collapse auto-collapsible, non-agent-overridden partitions first
        val autoCollapseCandidates = mutablePartitions.indices
            .filter { i ->
                val p = mutablePartitions[i]
                p.isAutoCollapsible && p.state == CollapseState.EXPANDED && !p.isAgentOverridden
            }
            .sortedWith(compareBy<Int> { mutablePartitions[it].priority }.thenByDescending { mutablePartitions[it].charCount })

        for (idx in autoCollapseCandidates) {
            if (totalChars <= maxBudgetChars) break

            val partition = mutablePartitions[idx]
            val savedChars = partition.charCount - partition.collapsedCharCount
            mutablePartitions[idx] = partition.copy(state = CollapseState.COLLAPSED)
            totalChars -= savedChars
            autoCollapsedKeys.add(partition.key)
        }

        // Step 3c: If STILL over, force-collapse agent-overridden partitions
        if (totalChars > maxBudgetChars) {
            val forceCollapseCandidates = mutablePartitions.indices
                .filter { i ->
                    val p = mutablePartitions[i]
                    p.isAutoCollapsible && p.state == CollapseState.EXPANDED && p.isAgentOverridden
                }
                .sortedWith(compareBy<Int> { mutablePartitions[it].priority }.thenByDescending { mutablePartitions[it].charCount })

            for (idx in forceCollapseCandidates) {
                if (totalChars <= maxBudgetChars) break

                val partition = mutablePartitions[idx]
                val savedChars = partition.charCount - partition.collapsedCharCount
                mutablePartitions[idx] = partition.copy(state = CollapseState.COLLAPSED)
                totalChars -= savedChars
                forceCollapsedKeys.add(partition.key)
            }

            if (forceCollapsedKeys.isNotEmpty()) {
                platformDependencies?.log(
                    LogLevel.ERROR, LOG_TAG,
                    "Agent '${agentId ?: "unknown"}': forced override of agent context choices. " +
                            "Force-collapsed: ${forceCollapsedKeys.joinToString(", ")}."
                )
            }
        }

        // Step 4: Apply oversized partial sentinel to remaining expanded partitions
        val (sentinelPartitions, sentinelTruncated) = applySentinel(mutablePartitions, maxPartialChars, platformDependencies, agentId)
        truncatedKeys.addAll(sentinelTruncated)

        val finalTotal = sentinelPartitions.sumOf { it.effectiveCharCount }

        return CollapseResult(
            partitions = sentinelPartitions,
            totalChars = finalTotal,
            autoCollapseApplied = autoCollapsedKeys.isNotEmpty(),
            forceCollapseApplied = forceCollapsedKeys.isNotEmpty(),
            autoCollapsedKeys = autoCollapsedKeys,
            forceCollapsedKeys = forceCollapsedKeys,
            truncatedKeys = truncatedKeys
        )
    }

    /**
     * Builds the CONTEXT_BUDGET_REPORT partition content (§3.5).
     *
     * Always present in the agent's context. Includes partition summary, current load,
     * and management instructions. When auto-collapse fires, includes a warning.
     *
     * @param result The collapse result from [collapse].
     * @param softBudgetChars The optimal soft target in characters.
     * @param maxBudgetChars The hard maximum in characters.
     */
    fun buildBudgetReport(
        result: CollapseResult,
        softBudgetChars: Int,
        maxBudgetChars: Int
    ): String = buildString {
        val softTokens = softBudgetChars / CHARS_PER_TOKEN
        val maxTokens = maxBudgetChars / CHARS_PER_TOKEN
        val currentTokens = result.totalChars / CHARS_PER_TOKEN

        appendLine("--- CONTEXT BUDGET ---")
        appendLine("Optimal: ~${formatTokenCount(softTokens)} tokens | Maximum: ~${formatTokenCount(maxTokens)} tokens")
        appendLine("Current load: ~${formatTokenCount(currentTokens)} tokens (approx.)")
        appendLine()
        appendLine("Manage your context proactively. Uncollapse only what you need for the current")
        appendLine("task. Collapse partitions you are done with. The system enforces the maximum")
        appendLine("automatically, but operating near your optimal produces better coherence and")
        appendLine("lower cost.")

        // Auto-collapse warning
        if (result.autoCollapseApplied || result.forceCollapseApplied) {
            appendLine()
            appendLine("⚠ AUTOMATIC COLLAPSE: Your context exceeded the maximum budget. The following")
            appendLine("partitions were automatically collapsed:")
            for (key in result.autoCollapsedKeys) {
                val partition = result.partitions.find { it.key == key }
                val savedTokens = partition?.let { (it.charCount - it.collapsedCharCount) / CHARS_PER_TOKEN } ?: 0
                appendLine("  $key: EXPANDED → COLLAPSED (saved ~${formatTokenCount(savedTokens)} tokens)")
            }
            for (key in result.forceCollapsedKeys) {
                val partition = result.partitions.find { it.key == key }
                val savedTokens = partition?.let { (it.charCount - it.collapsedCharCount) / CHARS_PER_TOKEN } ?: 0
                appendLine("  $key: EXPANDED → COLLAPSED [FORCED — overrode your choice] (saved ~${formatTokenCount(savedTokens)} tokens)")
            }
            appendLine("Review your expanded partitions and collapse what you no longer need.")
        }

        // Truncation warning
        if (result.truncatedKeys.isNotEmpty()) {
            appendLine()
            appendLine("⚠ TRUNCATION: The following partitions exceeded the single-partial size limit")
            appendLine("and were truncated:")
            for (key in result.truncatedKeys) {
                appendLine("  $key: truncated to fit budget")
            }
        }

        // Partition summary
        appendLine()
        appendLine("Partitions:")
        for (partition in result.partitions) {
            val tokens = partition.effectiveCharCount / CHARS_PER_TOKEN
            val stateTag = partition.state.name
            val detail = when {
                partition.key == "HOLON_KNOWLEDGE_GRAPH_INDEX" -> " [always present]"
                partition.key == "HOLON_KNOWLEDGE_GRAPH_FILES" -> {
                    // Count open files from content
                    if (partition.state == CollapseState.COLLAPSED) " — no files open" else ""
                }
                else -> ""
            }
            appendLine("  ${partition.key}: $stateTag (~${formatTokenCount(tokens)} tokens)$detail")
        }

        appendLine()
        appendLine("To manage:")
        appendLine("  agent.CONTEXT_UNCOLLAPSE { \"partitionKey\": \"...\", \"scope\": \"single|subtree|full\" }")
        appendLine("  agent.CONTEXT_COLLAPSE { \"partitionKey\": \"...\" }")
        appendLine("--- END OF CONTEXT BUDGET ---")
    }

    /**
     * Builds a [ContextPartition] from a context map entry with standard defaults.
     *
     * Assigns priority and collapsibility based on well-known partition keys.
     * Unknown keys get default priority 0 and are auto-collapsible.
     *
     * @param key The context map key.
     * @param content The full content string.
     * @param agentOverrides The agent's sticky collapse overrides.
     */
    fun buildPartition(
        key: String,
        content: String,
        agentOverrides: Map<String, CollapseState> = emptyMap()
    ): ContextPartition {
        val (priority, isAutoCollapsible, collapsedContent) = resolvePartitionDefaults(key, content)
        val isOverridden = key in agentOverrides
        val state = if (isOverridden) {
            agentOverrides[key] ?: CollapseState.EXPANDED
        } else {
            CollapseState.EXPANDED // Default: all partitions start expanded
        }

        return ContextPartition(
            key = key,
            fullContent = content,
            collapsedContent = collapsedContent,
            state = state,
            priority = priority,
            isAutoCollapsible = isAutoCollapsible,
            isAgentOverridden = isOverridden
        )
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Resolves default priority, collapsibility, and collapsed content for well-known
     * partition keys.
     *
     * Priority scale:
     * - 1000: Never-collapse (INDEX, SESSION_METADATA) — isAutoCollapsible = false
     * - 100:  High priority (CONVERSATION_LOG, MULTI_AGENT_CONTEXT)
     * - 50:   Medium priority (WORKSPACE_INDEX, WORKSPACE_NAVIGATION)
     * - 10:   Standard (AVAILABLE_ACTIONS, WORKSPACE_FILES)
     * - 0:    Low priority — collapse first (HKG FILES, unknown partitions)
     */
    private fun resolvePartitionDefaults(key: String, content: String): Triple<Int, Boolean, String> {
        return when (key) {
            // Never auto-collapse: navigational / identity partitions
            "HOLON_KNOWLEDGE_GRAPH_INDEX" -> Triple(1000, false, content) // Always present per §4.1
            "SESSION_METADATA" -> Triple(1000, false, content)

            // High priority: conversation and multi-agent awareness
            "CONVERSATION_LOG" -> Triple(100, true, "[CONVERSATION_LOG collapsed — ${content.length / CHARS_PER_TOKEN} tokens available. Use agent.CONTEXT_UNCOLLAPSE to expand.]")
            "MULTI_AGENT_CONTEXT" -> Triple(100, true, "[MULTI_AGENT_CONTEXT collapsed]")

            // Medium priority: workspace navigation
            "WORKSPACE_INDEX" -> Triple(50, false, content) // Always present when workspace exists
            "WORKSPACE_NAVIGATION" -> Triple(50, false, content)
            "CONTEXT_BUDGET" -> Triple(1000, false, content) // Budget report itself — never collapse

            // Standard priority: actions and workspace files
            "AVAILABLE_ACTIONS" -> Triple(10, true, "[AVAILABLE_ACTIONS collapsed — ~${content.length / CHARS_PER_TOKEN} tokens. Use agent.CONTEXT_UNCOLLAPSE to expand.]")
            "WORKSPACE_FILES" -> Triple(10, true, "[WORKSPACE_FILES collapsed. Use agent.CONTEXT_UNCOLLAPSE to open specific files.]")

            // Low priority: HKG files (heaviest partition, collapse first)
            "HOLON_KNOWLEDGE_GRAPH_FILES" -> Triple(0, true, "[HOLON_KNOWLEDGE_GRAPH_FILES collapsed. Use agent.CONTEXT_UNCOLLAPSE with \"hkg:<holonId>\" to open specific holon files.]")

            // Unknown partitions — lowest priority, auto-collapsible
            else -> Triple(0, true, "[$key collapsed]")
        }
    }

    /**
     * Applies the oversized partial sentinel to expanded partitions (§3.1).
     *
     * When a single expanded partition exceeds [maxPartialChars], its content is
     * truncated and a diagnostic message is injected. The partition remains EXPANDED
     * but with truncated content.
     *
     * @return Pair of (updated partition list, list of truncated keys).
     */
    private fun applySentinel(
        partitions: MutableList<ContextPartition>,
        maxPartialChars: Int,
        platformDependencies: PlatformDependencies?,
        agentId: String?
    ): Pair<List<ContextPartition>, List<String>> {
        val truncatedKeys = mutableListOf<String>()

        for (i in partitions.indices) {
            val partition = partitions[i]
            if (partition.state == CollapseState.EXPANDED && partition.charCount > maxPartialChars) {
                val truncatedContent = partition.fullContent.take(maxPartialChars)
                val originalTokens = partition.charCount / CHARS_PER_TOKEN
                val truncatedTokens = maxPartialChars / CHARS_PER_TOKEN
                val sentinel = "\n\n⚠ PIPELINE SENTINEL: This content partial is very large (~${formatTokenCount(originalTokens)} tokens) and " +
                        "has been truncated to the first ~${formatTokenCount(truncatedTokens)} tokens. Use targeted uncollapse commands " +
                        "to navigate to the section you need, or collapse this partial and work from the index."
                val newContent = truncatedContent + sentinel

                partitions[i] = partition.copy(
                    fullContent = newContent,
                    charCount = newContent.length
                )
                truncatedKeys.add(partition.key)

                platformDependencies?.log(
                    LogLevel.WARN, LOG_TAG,
                    "Agent '${agentId ?: "unknown"}': Partition '${partition.key}' exceeds max partial size " +
                            "(${partition.charCount} chars > $maxPartialChars). Truncated to ~${truncatedTokens} tokens."
                )
            }
        }

        return partitions.toList() to truncatedKeys
    }

    /**
     * Formats a token count for display. Examples: "1,234", "12,345".
     */
    private fun formatTokenCount(count: Int): String {
        return count.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}