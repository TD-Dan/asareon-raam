package app.auf.feature.agent

import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies

/**
 * Pipeline-level utility for enforcing the agent's context budget.
 *
 * Knows nothing about specific content types that partials carry, works purely on the data provided in ContextPartition
 *
 * TODO: MAGIC STRINGS: this is referencing partials with strings in partition defaults to set importance and protections. These should be set by anyone who created the partition, not by this utility.
 */
object ContextCollapseLogic {

    private const val LOG_TAG = "ContextCollapseLogic"

    /**
     * A single partition of the agent's context window.
     *
     * @param key The partition key (e.g., "HOLON_KNOWLEDGE_GRAPH", "SESSIONS").
     * @param fullContent Complete content when EXPANDED.
     * @param collapsedContent Summary shown when COLLAPSED. Empty if partition disappears.
     * @param charCount [fullContent].length — cached for budget calculations.
     * @param collapsedCharCount [collapsedContent].length — cached for budget calculations.
     * @param state Resolved collapse state after overrides + budget enforcement.
     * @param priority Higher = collapse last. Protected partitions = [Int.MAX_VALUE].
     * @param isAutoCollapsible If false, the budget algorithm never touches this partition.
     * @param isAgentOverridden True if the agent has a sticky override for this partition.
     * @param truncateFromStart If true, oversized sentinel truncates oldest content (start).
     *   If false (default), truncates newest content (end). Sessions use start-truncation
     *   because recent messages are more relevant than old ones.
     * @param parentKey Parent partition key if this is a child in a [PromptSection.Group].
     *   Null for top-level partitions. Used only for display grouping and h2 wrapping
     *   in Phases 3/4 — the collapse algorithm operates on the flat list identically
     *   regardless of this field.
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
        val isAgentOverridden: Boolean = false,
        val truncateFromStart: Boolean = false,
        val parentKey: String? = null
    ) {
        /** The char count for the partition's current state. */
        val effectiveCharCount: Int
            get() = if (state == CollapseState.EXPANDED) charCount else collapsedCharCount
    }

    /**
     * Result of the collapse algorithm.
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
     * 1. Calculate total chars using each partition's current state.
     * 2. If total ≤ maxBudgetChars → proceed (no collapse needed).
     * 3. If total > maxBudgetChars:
     *    a. Collapse auto-collapsible, non-agent-overridden partitions (lowest priority, largest first).
     *    b. If STILL over: force-collapse agent-overridden partitions (lowest priority first).
     * 4. Apply oversized partial sentinel (directional truncation).
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
            val (sentinelPartitions, sentinelTruncated) = applySentinel(mutablePartitions, maxPartialChars, platformDependencies, agentId)
            val finalTotal = sentinelPartitions.sumOf { it.effectiveCharCount }
            return CollapseResult(
                partitions = sentinelPartitions,
                totalChars = finalTotal,
                truncatedKeys = sentinelTruncated
            )
        }

        // Step 3a: Over budget
        platformDependencies?.log(
            LogLevel.WARN, LOG_TAG,
            "Agent '${agentId ?: "unknown"}' context ($totalChars chars, ~${totalChars / ContextDelimiters.CHARS_PER_TOKEN} tokens) " +
                    "exceeds max budget ($maxBudgetChars chars, ~${maxBudgetChars / ContextDelimiters.CHARS_PER_TOKEN} tokens). Auto-collapsing."
        )

        // Step 3b: Collapse non-agent-overridden first
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

        // Step 3c: Force-collapse agent-overridden if STILL over
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

        // Step 4: Oversized partial sentinel
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
     * Builds the CONTEXT_BUDGET partition content (§3.5).
     *
     */
    fun buildBudgetReport(
        result: CollapseResult,
        softBudgetChars: Int,
        maxBudgetChars: Int
    ): String = buildString {
        val softTokens = ContextDelimiters.approxTokens(softBudgetChars)
        val maxTokens = ContextDelimiters.approxTokens(maxBudgetChars)
        val currentTokens = ContextDelimiters.approxTokens(result.totalChars)

        appendLine("Optimal: ~$softTokens tokens | Maximum: ~$maxTokens tokens")
        appendLine("Current load: ~$currentTokens tokens (approx.)")
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
                val savedTokens = partition?.let { ContextDelimiters.approxTokens(it.charCount - it.collapsedCharCount) } ?: "?"
                appendLine("  $key: EXPANDED → COLLAPSED (saved ~$savedTokens tokens)")
            }
            for (key in result.forceCollapsedKeys) {
                val partition = result.partitions.find { it.key == key }
                val savedTokens = partition?.let { ContextDelimiters.approxTokens(it.charCount - it.collapsedCharCount) } ?: "?"
                appendLine("  $key: EXPANDED → COLLAPSED [FORCED — overrode your choice] (saved ~$savedTokens tokens)")
            }
            appendLine("Review your expanded partitions and collapse what you no longer need.")
        }

        // Truncation warning
        if (result.truncatedKeys.isNotEmpty()) {
            appendLine()
            appendLine("⚠ TRUNCATION: The following partitions exceeded the single-partial size limit")
            appendLine("and were truncated:")
            for (key in result.truncatedKeys) {
                val partition = result.partitions.find { it.key == key }
                val direction = if (partition?.truncateFromStart == true) "oldest content removed" else "end removed"
                appendLine("  $key: truncated ($direction)")
            }
        }

        // Partition summary
        appendLine()
        appendLine("Partitions:")
        for (partition in result.partitions) {
            val tokens = ContextDelimiters.approxTokens(partition.effectiveCharCount)
            val stateTag = when {
                !partition.isAutoCollapsible -> ContextDelimiters.PROTECTED
                partition.key in result.truncatedKeys -> ContextDelimiters.TRUNCATED
                partition.state == CollapseState.EXPANDED -> ContextDelimiters.EXPANDED
                else -> ContextDelimiters.COLLAPSED
            }
            appendLine("  ${partition.key}: [$stateTag] (~$tokens tokens)")
        }

        appendLine()
        appendLine("To manage:")
        appendLine("  agent.CONTEXT_UNCOLLAPSE { \"partitionKey\": \"...\", \"scope\": \"single|subtree|full\" }")
        appendLine("  agent.CONTEXT_COLLAPSE { \"partitionKey\": \"...\" }")
    }

    /**
     * Builds a [ContextPartition] from a context map entry with standard defaults.
     */
    fun buildPartition(
        key: String,
        content: String,
        agentOverrides: Map<String, CollapseState> = emptyMap()
    ): ContextPartition {
        val defaults = resolvePartitionDefaults(key, content)
        val isOverridden = key in agentOverrides
        val state = if (isOverridden) {
            agentOverrides[key] ?: CollapseState.EXPANDED
        } else {
            CollapseState.EXPANDED
        }

        return ContextPartition(
            key = key,
            fullContent = content,
            collapsedContent = defaults.collapsedContent,
            state = state,
            priority = defaults.priority,
            isAutoCollapsible = defaults.isAutoCollapsible,
            isAgentOverridden = isOverridden,
            truncateFromStart = defaults.truncateFromStart
        )
    }

    // =========================================================================
    // =========================================================================
    // Partition Defaults
    // =========================================================================

    data class PartitionDefaults(
        val priority: Int,
        val isAutoCollapsible: Boolean,
        val collapsedContent: String,
        val truncateFromStart: Boolean = false
    )

    /**
     * Resolves defaults for well-known partition keys.
     *
     * Used by [buildPartition] (internal) and by [CognitivePipeline.mergeIntoPartitions]
     * to assign collapse/priority properties when converting contextMap entries to
     * [PromptSection.Section] objects.
     *
     * Priority scale:
     * - 1000: Never-collapse (METADATA, SESSIONS, HKG) — isAutoCollapsible = false
     * - 100:  High priority (session children)
     * - 50:   Medium priority (WORKSPACE_INDEX, WORKSPACE_NAVIGATION)
     * - 10:   Standard (AVAILABLE_ACTIONS, WORKSPACE_FILES)
     * - 0:    Low priority — collapse first (HKG FILES, unknown partitions)
     */
    fun resolvePartitionDefaults(key: String, content: String): PartitionDefaults {
        val tokens = ContextDelimiters.approxTokens(content.length)
        return when (key) {
            // Never auto-collapse — protected containers
            "METADATA" -> PartitionDefaults(1000, false, content)
            "SESSIONS" -> PartitionDefaults(1000, false, content)
            "HOLON_KNOWLEDGE_GRAPH" -> PartitionDefaults(1000, false, content)
            "WORKSPACE_INDEX" -> PartitionDefaults(50, false, content)
            "WORKSPACE_NAVIGATION" -> PartitionDefaults(50, false, content)
            "CONTEXT_BUDGET" -> PartitionDefaults(1000, false, content)

            // Standard priority
            "AVAILABLE_ACTIONS" -> PartitionDefaults(
                10, true,
                "[Available actions collapsed — ~$tokens tokens. Use agent.CONTEXT_UNCOLLAPSE to expand.]"
            )
            "WORKSPACE_FILES" -> PartitionDefaults(
                10, true,
                "[Workspace files collapsed. Use agent.CONTEXT_UNCOLLAPSE to open specific files.]"
            )

            // Unknown — lowest priority
            else -> PartitionDefaults(0, true, "[$key collapsed — ~$tokens tokens]")
        }
    }

    /**
     * Applies the oversized partial sentinel. Respects [ContextPartition.truncateFromStart]:
     * - `false` (default): Truncates from the END, appends sentinel message.
     * - `true`: Truncates from the START (oldest content removed), prepends sentinel message.
     *   Used for session children where recent messages are more relevant.
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
                val originalTokens = ContextDelimiters.approxTokens(partition.charCount)
                val truncatedTokens = ContextDelimiters.approxTokens(maxPartialChars)

                val newContent = if (partition.truncateFromStart) {
                    // START truncation: keep the LAST maxPartialChars, prepend sentinel
                    val kept = partition.fullContent.takeLast(maxPartialChars)
                    val sentinel = "⚠ PIPELINE SENTINEL: This content partial is very large (~$originalTokens tokens). " +
                            "The starting part has been removed, keeping the most recent ~$truncatedTokens tokens. " +
                            "Use uncollapse commands to manage visibility.\n\n"
                    sentinel + kept
                } else {
                    // END truncation (default): keep the FIRST maxPartialChars, append sentinel
                    val kept = partition.fullContent.take(maxPartialChars)
                    val sentinel = "\n\n⚠ PIPELINE SENTINEL: This content partial is very large (~$originalTokens tokens) and " +
                            "has been truncated to the first ~$truncatedTokens tokens. " +
                            "Use uncollapse commands to manage visibility.\n\n"
                    kept + sentinel
                }

                partitions[i] = partition.copy(
                    fullContent = newContent,
                    charCount = newContent.length
                )
                truncatedKeys.add(partition.key)

                val direction = if (partition.truncateFromStart) "start (oldest removed)" else "end"
                platformDependencies?.log(
                    LogLevel.WARN, LOG_TAG,
                    "Agent '${agentId ?: "unknown"}': Partition '${partition.key}' exceeds max partial size " +
                            "(${partition.charCount} chars > $maxPartialChars). Truncated from $direction."
                )
            }
        }

        return partitions.toList() to truncatedKeys
    }
}