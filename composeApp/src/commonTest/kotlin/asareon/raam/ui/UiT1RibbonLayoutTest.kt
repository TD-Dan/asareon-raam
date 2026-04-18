package asareon.raam.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import asareon.raam.core.RibbonEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1 Unit Tests for the pure layout logic behind `GlobalActionRibbon`.
 *
 * Tests `computeRibbonLayout` in isolation: no Compose UI, no Store. Verifies
 * the priority sort and slot-budget contract.
 */
class UiT1RibbonLayoutTest {

    private fun entry(id: String, priority: Int = 0): RibbonEntry =
        RibbonEntry(
            id = id,
            label = id,
            icon = Icons.Default.Home,
            priority = priority,
            onClick = {},
        )

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun empty_input_produces_empty_layout() {
        val layout = computeRibbonLayout(emptyList(), slotBudget = 5)

        assertTrue(layout.visible.isEmpty())
        assertTrue(layout.overflow.isEmpty())
        assertFalse(layout.overflowVisible)
    }

    @Test
    fun zero_budget_pushes_everything_to_overflow() {
        val entries = listOf(entry("a"), entry("b"))

        val layout = computeRibbonLayout(entries, slotBudget = 0)

        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("a", "b"), layout.overflow.map { it.id })
        assertTrue(layout.overflowVisible)
    }

    @Test
    fun negative_budget_is_clamped_to_zero() {
        val entries = listOf(entry("a"))

        val layout = computeRibbonLayout(entries, slotBudget = -3)

        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("a"), layout.overflow.map { it.id })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fit behaviour
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun all_entries_fit_and_no_overflow_slot_is_rendered() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val layout = computeRibbonLayout(entries, slotBudget = 5)

        assertEquals(3, layout.visible.size)
        assertTrue(layout.overflow.isEmpty())
        assertFalse(layout.overflowVisible)
    }

    @Test
    fun exact_fit_does_not_reserve_an_overflow_slot() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val layout = computeRibbonLayout(entries, slotBudget = 3)

        assertEquals(3, layout.visible.size)
        assertFalse(layout.overflowVisible)
    }

    @Test
    fun one_too_many_reserves_overflow_and_hides_two() {
        // 4 entries, budget 3 → 2 visible + overflow slot, 2 in overflow.
        val entries = listOf(entry("a"), entry("b"), entry("c"), entry("d"))

        val layout = computeRibbonLayout(entries, slotBudget = 3)

        assertEquals(listOf("a", "b"), layout.visible.map { it.id })
        assertEquals(listOf("c", "d"), layout.overflow.map { it.id })
        assertTrue(layout.overflowVisible)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Priority ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun higher_priority_wins_when_budget_is_tight() {
        val entries = listOf(
            entry("low", priority = 0),
            entry("high", priority = 10),
            entry("mid", priority = 5),
        )

        val layout = computeRibbonLayout(entries, slotBudget = 2)

        // 2 slots, 3 entries → 1 visible + overflow slot. "high" wins.
        assertEquals(listOf("high"), layout.visible.map { it.id })
        assertEquals(listOf("mid", "low"), layout.overflow.map { it.id })
    }

    @Test
    fun ties_break_by_input_order() {
        val entries = listOf(
            entry("first", priority = 5),
            entry("second", priority = 5),
            entry("third", priority = 5),
        )

        val layout = computeRibbonLayout(entries, slotBudget = 5)

        assertEquals(listOf("first", "second", "third"), layout.visible.map { it.id })
    }

    @Test
    fun priority_reorders_even_when_everything_fits() {
        // Input: A(1), B(5), C(3) → visible reflects sort: B, C, A.
        val entries = listOf(
            entry("A", priority = 1),
            entry("B", priority = 5),
            entry("C", priority = 3),
        )

        val layout = computeRibbonLayout(entries, slotBudget = 5)

        assertEquals(listOf("B", "C", "A"), layout.visible.map { it.id })
        assertFalse(layout.overflowVisible)
    }

    @Test
    fun overflow_preserves_priority_order_of_spilled_entries() {
        val entries = listOf(
            entry("spill_low", priority = 0),
            entry("spill_mid", priority = 2),
            entry("keep", priority = 10),
        )

        val layout = computeRibbonLayout(entries, slotBudget = 2)

        // 1 visible + overflow slot. "keep" wins the slot.
        // Overflow entries must retain the priority sort: spill_mid before spill_low.
        assertEquals(listOf("keep"), layout.visible.map { it.id })
        assertEquals(listOf("spill_mid", "spill_low"), layout.overflow.map { it.id })
    }
}
