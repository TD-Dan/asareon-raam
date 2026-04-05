package asareon.raam.feature.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase D: ContextCollapseLogic unit tests.
 *
 * Tests the partition model, auto-collapse algorithm, budget report,
 * oversized partial sentinel, and directional truncation.
 */
class ContextCollapseLogicT1Test {

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makePartition(
        key: String,
        chars: Int,
        priority: Int = 0,
        isAutoCollapsible: Boolean = true,
        isAgentOverridden: Boolean = false,
        state: CollapseState = CollapseState.EXPANDED,
        collapsedChars: Int = 20,
        truncateFromStart: Boolean = false
    ): ContextCollapseLogic.ContextPartition {
        val fullContent = "x".repeat(chars)
        val collapsedContent = "c".repeat(collapsedChars)
        return ContextCollapseLogic.ContextPartition(
            key = key,
            fullContent = fullContent,
            collapsedContent = collapsedContent,
            charCount = chars,
            collapsedCharCount = collapsedChars,
            state = state,
            priority = priority,
            isAutoCollapsible = isAutoCollapsible,
            isAgentOverridden = isAgentOverridden,
            truncateFromStart = truncateFromStart
        )
    }

    // =========================================================================
    // ContextPartition model tests
    // =========================================================================

    @Test
    fun `effectiveCharCount returns charCount when EXPANDED`() {
        val p = makePartition("TEST", chars = 1000)
        assertEquals(1000, p.effectiveCharCount)
    }

    @Test
    fun `effectiveCharCount returns collapsedCharCount when COLLAPSED`() {
        val p = makePartition("TEST", chars = 1000, state = CollapseState.COLLAPSED, collapsedChars = 50)
        assertEquals(50, p.effectiveCharCount)
    }

    // =========================================================================
    // Under budget → no collapse applied
    // =========================================================================

    @Test
    fun `under budget - no collapse applied`() {
        val partitions = listOf(
            makePartition("A", chars = 1000),
            makePartition("B", chars = 2000)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertFalse(result.autoCollapseApplied)
        assertFalse(result.forceCollapseApplied)
        assertTrue(result.autoCollapsedKeys.isEmpty())
        assertEquals(2, result.partitions.count { it.state == CollapseState.EXPANDED })
    }

    // =========================================================================
    // Over max budget → lowest-priority, largest partitions collapsed first
    // =========================================================================

    @Test
    fun `over max budget - collapses lowest priority largest first`() {
        val partitions = listOf(
            makePartition("HIGH_PRI", chars = 5000, priority = 100),
            makePartition("LOW_PRI_SMALL", chars = 2000, priority = 0),
            makePartition("LOW_PRI_LARGE", chars = 8000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertTrue("LOW_PRI_LARGE" in result.autoCollapsedKeys)
        assertFalse("HIGH_PRI" in result.autoCollapsedKeys)

        val highPri = result.partitions.find { it.key == "HIGH_PRI" }!!
        assertEquals(CollapseState.EXPANDED, highPri.state)
    }

    @Test
    fun `over max budget - collapses multiple partitions until under budget`() {
        val partitions = listOf(
            makePartition("A", chars = 4000, priority = 10),
            makePartition("B", chars = 4000, priority = 5),
            makePartition("C", chars = 4000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 5_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertTrue("C" in result.autoCollapsedKeys)
        assertTrue("B" in result.autoCollapsedKeys)
        assertFalse("A" in result.autoCollapsedKeys)
    }

    // =========================================================================
    // Agent sticky overrides
    // =========================================================================

    @Test
    fun `agent sticky EXPANDED respected when under max`() {
        val partitions = listOf(
            makePartition("AGENT_EXPANDED", chars = 3000, priority = 0, isAgentOverridden = true),
            makePartition("NORMAL", chars = 3000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertFalse(result.autoCollapseApplied)
        assertEquals(CollapseState.EXPANDED, result.partitions.find { it.key == "AGENT_EXPANDED" }!!.state)
    }

    @Test
    fun `agent sticky EXPANDED force-collapsed when over max`() {
        val partitions = listOf(
            makePartition("AGENT_EXPANDED", chars = 8000, priority = 0, isAgentOverridden = true),
            makePartition("NORMAL", chars = 1000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 2_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.forceCollapseApplied)
        assertTrue("AGENT_EXPANDED" in result.forceCollapsedKeys)
    }

    @Test
    fun `agent-overridden skipped in first pass collapsed in second pass`() {
        val partitions = listOf(
            makePartition("AGENT_CHOICE", chars = 5000, priority = 0, isAgentOverridden = true),
            makePartition("AUTO_CHOICE", chars = 5000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertFalse(result.forceCollapseApplied)
        assertTrue("AUTO_CHOICE" in result.autoCollapsedKeys)
        assertEquals(CollapseState.EXPANDED, result.partitions.find { it.key == "AGENT_CHOICE" }!!.state)
    }

    // =========================================================================
    // Priority ordering
    // =========================================================================

    @Test
    fun `priority ordering - high priority collapses last`() {
        val partitions = listOf(
            makePartition("IMPORTANT", chars = 5000, priority = 100),
            makePartition("CHEAP", chars = 5000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        assertTrue("CHEAP" in result.autoCollapsedKeys)
        assertFalse("IMPORTANT" in result.autoCollapsedKeys)
    }

    @Test
    fun `non-auto-collapsible partitions never collapsed`() {
        val partitions = listOf(
            makePartition("INDEX", chars = 5000, priority = 1000, isAutoCollapsible = false),
            makePartition("FILES", chars = 5000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        assertEquals(CollapseState.EXPANDED, result.partitions.find { it.key == "INDEX" }!!.state)
        assertEquals(CollapseState.COLLAPSED, result.partitions.find { it.key == "FILES" }!!.state)
    }

    // =========================================================================
    // Budget report
    // =========================================================================

    @Test
    fun `budget report generated with correct approximate token counts`() {
        val partitions = listOf(
            makePartition("A", chars = 4000),
            makePartition("B", chars = 8000)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 50_000,
            maxPartialChars = 50_000
        )
        val report = ContextCollapseLogic.buildBudgetReport(
            result = result,
            softBudgetChars = 50_000,
            maxBudgetChars = 150_000
        )
        // Uses ContextDelimiters.approxTokens for rounded display
        assertTrue(report.contains("Optimal: ~"))
        assertTrue(report.contains("Maximum: ~"))
        assertTrue(report.contains("Current load: ~"))
        assertTrue(report.contains("Partitions:"))
        assertTrue(report.contains("A:"))
        assertTrue(report.contains("B:"))
        // No h1 wrapper — pipeline adds it
        assertFalse(report.contains("- [ CONTEXT_BUDGET ]"))
        assertFalse(report.contains("AUTOMATIC COLLAPSE"))
    }

    @Test
    fun `budget report includes auto-collapse warning when triggered`() {
        val partitions = listOf(
            makePartition("KEEP", chars = 3000, priority = 100),
            makePartition("COLLAPSE_ME", chars = 8000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 5_000,
            maxPartialChars = 50_000
        )
        val report = ContextCollapseLogic.buildBudgetReport(
            result = result,
            softBudgetChars = 50_000,
            maxBudgetChars = 5_000
        )
        assertTrue(report.contains("AUTOMATIC COLLAPSE"))
        assertTrue(report.contains("COLLAPSE_ME: EXPANDED → COLLAPSED"))
    }

    @Test
    fun `budget report includes force-collapse warning`() {
        val partitions = listOf(
            makePartition("FORCED", chars = 8000, priority = 0, isAgentOverridden = true)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 1_000,
            maxPartialChars = 50_000
        )
        val report = ContextCollapseLogic.buildBudgetReport(
            result = result,
            softBudgetChars = 50_000,
            maxBudgetChars = 1_000
        )
        assertTrue(report.contains("FORCED — overrode your choice"))
    }

    @Test
    fun `budget report includes truncation direction`() {
        val partitions = listOf(
            makePartition("CONVERSATION_LOG", chars = 200_000, truncateFromStart = true)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 20_000
        )
        val report = ContextCollapseLogic.buildBudgetReport(
            result = result,
            softBudgetChars = 50_000,
            maxBudgetChars = 500_000
        )
        assertTrue(report.contains("oldest content removed"))
    }

    // =========================================================================
    // Oversized partial sentinel
    // =========================================================================

    @Test
    fun `oversized partial sentinel - end truncation (default)`() {
        val partitions = listOf(
            makePartition("HUGE", chars = 200_000)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 20_000
        )
        assertTrue("HUGE" in result.truncatedKeys)
        val huge = result.partitions.find { it.key == "HUGE" }!!
        assertTrue(huge.fullContent.contains("PIPELINE SENTINEL"))
        assertTrue(huge.fullContent.contains("truncated to the first"))
        // Content ends with sentinel (appended)
        assertTrue(huge.fullContent.endsWith("work from the index."))
    }

    @Test
    fun `oversized partial sentinel - start truncation for sessions`() {
        val content = "OLD_MESSAGE_1\nOLD_MESSAGE_2\nNEW_MESSAGE_3\nNEW_MESSAGE_4"
        val partitions = listOf(
            ContextCollapseLogic.ContextPartition(
                key = "CONVERSATION_LOG",
                fullContent = content.repeat(5000), // Make it big
                collapsedContent = "[collapsed]",
                truncateFromStart = true
            )
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 1_000
        )
        assertTrue("CONVERSATION_LOG" in result.truncatedKeys)
        val conv = result.partitions.find { it.key == "CONVERSATION_LOG" }!!
        assertTrue(conv.fullContent.contains("PIPELINE SENTINEL"))
        assertTrue(conv.fullContent.contains("oldest messages have been removed"))
        // Sentinel is prepended, content starts with it
        assertTrue(conv.fullContent.startsWith("⚠ PIPELINE SENTINEL"))
    }

    @Test
    fun `oversized partial sentinel - normal partition not truncated`() {
        val partitions = listOf(makePartition("NORMAL", chars = 5000))
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 20_000
        )
        assertTrue(result.truncatedKeys.isEmpty())
    }

    @Test
    fun `oversized partial sentinel - collapsed partition not truncated`() {
        val partitions = listOf(
            makePartition("BIG_BUT_COLLAPSED", chars = 200_000, state = CollapseState.COLLAPSED)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 20_000
        )
        assertTrue(result.truncatedKeys.isEmpty())
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `empty partition list returns empty result`() {
        val result = ContextCollapseLogic.collapse(
            partitions = emptyList(),
            maxBudgetChars = 10_000,
            maxPartialChars = 20_000
        )
        assertEquals(0, result.totalChars)
        assertTrue(result.partitions.isEmpty())
        assertFalse(result.autoCollapseApplied)
    }

    @Test
    fun `already collapsed partitions are not re-collapsed`() {
        val partitions = listOf(
            makePartition("ALREADY_COLLAPSED", chars = 5000, state = CollapseState.COLLAPSED, collapsedChars = 50)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 100,
            maxPartialChars = 50_000
        )
        assertFalse(result.autoCollapseApplied)
        assertEquals(CollapseState.COLLAPSED, result.partitions[0].state)
    }

    @Test
    fun `exactly at max budget - no collapse`() {
        val partitions = listOf(makePartition("EXACT", chars = 10_000))
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertFalse(result.autoCollapseApplied)
        assertEquals(CollapseState.EXPANDED, result.partitions[0].state)
    }

    @Test
    fun `one char over max budget triggers collapse`() {
        val partitions = listOf(
            makePartition("SLIGHTLY_OVER", chars = 10_001, priority = 0),
            makePartition("KEEP", chars = 100, priority = 100)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertTrue("SLIGHTLY_OVER" in result.autoCollapsedKeys)
    }
}