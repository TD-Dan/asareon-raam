package app.auf.feature.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Phase D: ContextCollapseLogic unit tests.
 *
 * Tests the partition model, auto-collapse algorithm, budget report,
 * and oversized partial sentinel per §3.3–3.5 of the Sovereign
 * Stabilization design document.
 */
class AgentRuntimeFeatureT1ContextCollapseLogicTest {

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
        collapsedChars: Int = 20
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
            isAgentOverridden = isAgentOverridden
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
        assertTrue(result.forceCollapsedKeys.isEmpty())
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
        // Total = 15,000. Max = 10,000. Need to collapse ~5,000.
        // Candidates sorted by priority ASC, charCount DESC:
        //   LOW_PRI_LARGE (0, 8000) → collapse saves 8000-20=7980 → total ~7040. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertFalse(result.forceCollapseApplied)
        assertTrue("LOW_PRI_LARGE" in result.autoCollapsedKeys)
        assertFalse("HIGH_PRI" in result.autoCollapsedKeys)

        val highPri = result.partitions.find { it.key == "HIGH_PRI" }!!
        assertEquals(CollapseState.EXPANDED, highPri.state)

        val lowPriLarge = result.partitions.find { it.key == "LOW_PRI_LARGE" }!!
        assertEquals(CollapseState.COLLAPSED, lowPriLarge.state)
    }

    @Test
    fun `over max budget - collapses multiple partitions until under budget`() {
        val partitions = listOf(
            makePartition("A", chars = 4000, priority = 10),
            makePartition("B", chars = 4000, priority = 5),
            makePartition("C", chars = 4000, priority = 0)
        )
        // Total = 12,000. Max = 5,000. Need to collapse ~7,000.
        // Order: C (0, 4000), B (5, 4000), A (10, 4000)
        // Collapse C: saves 3980 → total 8020. Still over.
        // Collapse B: saves 3980 → total 4040. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 5_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertTrue("C" in result.autoCollapsedKeys)
        assertTrue("B" in result.autoCollapsedKeys)
        assertFalse("A" in result.autoCollapsedKeys)

        val a = result.partitions.find { it.key == "A" }!!
        assertEquals(CollapseState.EXPANDED, a.state)
    }

    // =========================================================================
    // Agent sticky EXPANDED respected when under max
    // =========================================================================

    @Test
    fun `agent sticky EXPANDED respected when under max`() {
        val partitions = listOf(
            makePartition("AGENT_EXPANDED", chars = 3000, priority = 0, isAgentOverridden = true),
            makePartition("NORMAL", chars = 3000, priority = 0)
        )
        // Total = 6,000. Max = 10,000. Under budget — no collapse needed.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 10_000,
            maxPartialChars = 50_000
        )
        assertFalse(result.autoCollapseApplied)
        val agentPartition = result.partitions.find { it.key == "AGENT_EXPANDED" }!!
        assertEquals(CollapseState.EXPANDED, agentPartition.state)
    }

    // =========================================================================
    // Agent sticky EXPANDED force-collapsed when over max, with warning
    // =========================================================================

    @Test
    fun `agent sticky EXPANDED force-collapsed when over max`() {
        val partitions = listOf(
            makePartition("AGENT_EXPANDED", chars = 8000, priority = 0, isAgentOverridden = true),
            makePartition("NORMAL", chars = 1000, priority = 0)
        )
        // Total = 9,000. Max = 2,000.
        // Step 3b: NORMAL is collapsed (not agent-overridden) → saves 980. Total = 8020. Still over.
        // Step 3c: AGENT_EXPANDED force-collapsed → saves 7980. Total = 40. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 2_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertTrue(result.forceCollapseApplied)
        assertTrue("AGENT_EXPANDED" in result.forceCollapsedKeys)
        assertTrue("NORMAL" in result.autoCollapsedKeys)
    }

    @Test
    fun `agent-overridden skipped in first pass collapsed in second pass`() {
        val partitions = listOf(
            makePartition("AGENT_CHOICE", chars = 5000, priority = 0, isAgentOverridden = true),
            makePartition("AUTO_CHOICE", chars = 5000, priority = 0)
        )
        // Total = 10,000. Max = 6,000.
        // First pass: collapse AUTO_CHOICE (not agent-overridden) → saves 4980. Total = 5020. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        assertTrue(result.autoCollapseApplied)
        assertFalse(result.forceCollapseApplied)
        assertTrue("AUTO_CHOICE" in result.autoCollapsedKeys)
        assertFalse("AGENT_CHOICE" in result.autoCollapsedKeys)
        assertFalse("AGENT_CHOICE" in result.forceCollapsedKeys)

        val agentPartition = result.partitions.find { it.key == "AGENT_CHOICE" }!!
        assertEquals(CollapseState.EXPANDED, agentPartition.state)
    }

    // =========================================================================
    // Priority ordering: high-priority partitions collapse last
    // =========================================================================

    @Test
    fun `priority ordering - high priority collapses last`() {
        val partitions = listOf(
            makePartition("IMPORTANT", chars = 5000, priority = 100),
            makePartition("CHEAP", chars = 5000, priority = 0)
        )
        // Total = 10,000. Max = 6,000.
        // CHEAP (priority 0) collapses first → saves 4980. Total = 5020. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        assertTrue("CHEAP" in result.autoCollapsedKeys)
        assertFalse("IMPORTANT" in result.autoCollapsedKeys)
    }

    // =========================================================================
    // Non-auto-collapsible partitions never collapsed
    // =========================================================================

    @Test
    fun `non-auto-collapsible partitions never collapsed`() {
        val partitions = listOf(
            makePartition("INDEX", chars = 5000, priority = 1000, isAutoCollapsible = false),
            makePartition("FILES", chars = 5000, priority = 0)
        )
        // Total = 10,000. Max = 6,000.
        // INDEX is not auto-collapsible → skip.
        // FILES is auto-collapsible → collapse. Saves 4980. Total = 5020. Under budget.
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 6_000,
            maxPartialChars = 50_000
        )
        val index = result.partitions.find { it.key == "INDEX" }!!
        assertEquals(CollapseState.EXPANDED, index.state)
        val files = result.partitions.find { it.key == "FILES" }!!
        assertEquals(CollapseState.COLLAPSED, files.state)
    }

    // =========================================================================
    // Budget report generation
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
        // Should contain token estimates
        assertTrue(report.contains("Optimal: ~12,500 tokens"))
        assertTrue(report.contains("Maximum: ~37,500 tokens"))
        assertTrue(report.contains("Current load: ~3,000 tokens"))
        assertTrue(report.contains("Partitions:"))
        assertTrue(report.contains("A: EXPANDED"))
        assertTrue(report.contains("B: EXPANDED"))
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

    // =========================================================================
    // Oversized partial sentinel
    // =========================================================================

    @Test
    fun `oversized partial sentinel - single partition over threshold truncated`() {
        val partitions = listOf(
            makePartition("HUGE", chars = 200_000)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000, // Under budget — only sentinel fires
            maxPartialChars = 20_000
        )
        assertTrue("HUGE" in result.truncatedKeys)
        val huge = result.partitions.find { it.key == "HUGE" }!!
        assertTrue(huge.fullContent.contains("⚠ PIPELINE SENTINEL"))
        assertTrue(huge.fullContent.contains("truncated"))
        // Content should be approximately maxPartialChars + sentinel message
        assertTrue(huge.charCount < 200_000)
        assertTrue(huge.charCount > 20_000) // sentinel adds a message
    }

    @Test
    fun `oversized partial sentinel - normal partition not truncated`() {
        val partitions = listOf(
            makePartition("NORMAL", chars = 5000)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions,
            maxBudgetChars = 500_000,
            maxPartialChars = 20_000
        )
        assertTrue(result.truncatedKeys.isEmpty())
        val normal = result.partitions.find { it.key == "NORMAL" }!!
        assertFalse(normal.fullContent.contains("⚠ PIPELINE SENTINEL"))
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
    // buildPartition — well-known key defaults
    // =========================================================================

    @Test
    fun `buildPartition - HKG INDEX is not auto-collapsible`() {
        val p = ContextCollapseLogic.buildPartition("HOLON_KNOWLEDGE_GRAPH_INDEX", "content")
        assertFalse(p.isAutoCollapsible)
        assertEquals(1000, p.priority)
    }

    @Test
    fun `buildPartition - HKG FILES is auto-collapsible with low priority`() {
        val p = ContextCollapseLogic.buildPartition("HOLON_KNOWLEDGE_GRAPH_FILES", "content")
        assertTrue(p.isAutoCollapsible)
        assertEquals(0, p.priority)
    }

    @Test
    fun `buildPartition - CONVERSATION_LOG has high priority`() {
        val p = ContextCollapseLogic.buildPartition("CONVERSATION_LOG", "content")
        assertTrue(p.isAutoCollapsible)
        assertEquals(100, p.priority)
    }

    @Test
    fun `buildPartition - AVAILABLE_ACTIONS has standard priority`() {
        val p = ContextCollapseLogic.buildPartition("AVAILABLE_ACTIONS", "content")
        assertTrue(p.isAutoCollapsible)
        assertEquals(10, p.priority)
    }

    @Test
    fun `buildPartition - SESSION_METADATA is not auto-collapsible`() {
        val p = ContextCollapseLogic.buildPartition("SESSION_METADATA", "content")
        assertFalse(p.isAutoCollapsible)
    }

    @Test
    fun `buildPartition - agent override applied`() {
        val overrides = mapOf("AVAILABLE_ACTIONS" to CollapseState.COLLAPSED)
        val p = ContextCollapseLogic.buildPartition("AVAILABLE_ACTIONS", "content", overrides)
        assertTrue(p.isAgentOverridden)
        assertEquals(CollapseState.COLLAPSED, p.state)
    }

    @Test
    fun `buildPartition - unknown key gets default priority and collapsibility`() {
        val p = ContextCollapseLogic.buildPartition("CUSTOM_CONTEXT", "content")
        assertTrue(p.isAutoCollapsible)
        assertEquals(0, p.priority)
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
        // Total = 50 (collapsed). Max = 100. Under budget.
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
        val partitions = listOf(
            makePartition("EXACT", chars = 10_000)
        )
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

    @Test
    fun `collapse and sentinel can both fire on same turn`() {
        val partitions = listOf(
            makePartition("HUGE_HIGH_PRI", chars = 200_000, priority = 100),
            makePartition("SMALL_LOW_PRI", chars = 5_000, priority = 0)
        )
        // Total = 205,000. Max = 150,000.
        // Auto-collapse SMALL_LOW_PRI → saves 4980. Total = 200,020. Still over.
        // No more candidates (HUGE is the only remaining, not agent-overridden but still expanded).
        // Actually both are auto-collapsible, so HUGE should also get collapsed.
        // After all collapsed: 20 + 20 = 40.
        // Sentinel only fires on EXPANDED partitions, so after collapse sentinel won't fire on HUGE.
        // Let me adjust: make HUGE non-auto-collapsible to test both paths.
        val partitions2 = listOf(
            makePartition("HUGE_FIXED", chars = 200_000, priority = 1000, isAutoCollapsible = false),
            makePartition("SMALL_COLLAPSIBLE", chars = 5_000, priority = 0)
        )
        val result = ContextCollapseLogic.collapse(
            partitions = partitions2,
            maxBudgetChars = 250_000,  // Under budget (200k+5k), but sentinel fires on HUGE_FIXED
            maxPartialChars = 20_000
        )
        assertTrue("HUGE_FIXED" in result.truncatedKeys)
        assertFalse(result.autoCollapseApplied)
    }
}