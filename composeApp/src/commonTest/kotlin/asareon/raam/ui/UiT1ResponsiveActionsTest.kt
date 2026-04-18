package asareon.raam.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderActionEmphasis
import asareon.raam.ui.components.topbar.computeHeaderActionLayout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1 Unit Tests for the pure layout logic behind `ResponsiveActions`.
 *
 * Tests `computeHeaderActionLayout` in isolation: no Compose UI, no runtime.
 * Verifies the priority/overflow contract documented on the function.
 */
class UiT1ResponsiveActionsTest {

    private fun action(
        id: String,
        priority: Int = 0,
        emphasis: HeaderActionEmphasis = HeaderActionEmphasis.Icon,
    ): HeaderAction =
        HeaderAction(
            id = id,
            label = id,
            icon = Icons.Default.Add,
            priority = priority,
            emphasis = emphasis,
            onClick = {},
        )

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun empty_input_produces_empty_layout() {
        val layout = computeHeaderActionLayout(emptyList(), maxVisibleSlots = 5)

        assertTrue(layout.visible.isEmpty())
        assertTrue(layout.overflow.isEmpty())
        assertFalse(layout.kebabVisible)
    }

    @Test
    fun zero_slots_pushes_everything_to_overflow() {
        val actions = listOf(action("a"), action("b"))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 0)

        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("a", "b"), layout.overflow.map { it.id })
        assertTrue(layout.kebabVisible)
    }

    @Test
    fun negative_slots_are_clamped_to_zero() {
        val actions = listOf(action("a"))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = -10)

        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("a"), layout.overflow.map { it.id })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fit behaviour
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun all_actions_fit_and_no_kebab_is_rendered() {
        val actions = listOf(action("a"), action("b"), action("c"))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 5)

        assertEquals(listOf("a", "b", "c"), layout.visible.map { it.id })
        assertTrue(layout.overflow.isEmpty())
        assertFalse(layout.kebabVisible)
    }

    @Test
    fun exact_fit_does_not_reserve_a_kebab_slot() {
        val actions = listOf(action("a"), action("b"), action("c"))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 3)

        assertEquals(3, layout.visible.size)
        assertFalse(layout.kebabVisible)
    }

    @Test
    fun one_too_many_reserves_kebab_and_hides_two() {
        // 4 actions, 3 slots → 2 visible + kebab (which counts as a slot),
        // and 2 actions fall into the overflow.
        val actions = listOf(action("a"), action("b"), action("c"), action("d"))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 3)

        assertEquals(listOf("a", "b"), layout.visible.map { it.id })
        assertEquals(listOf("c", "d"), layout.overflow.map { it.id })
        assertTrue(layout.kebabVisible)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Priority ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun higher_priority_wins_when_budget_is_tight() {
        val actions = listOf(
            action("low", priority = 0),
            action("high", priority = 10),
            action("mid", priority = 5),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 2)

        // 2 slots, 3 actions → 1 visible + kebab (1 slot), 2 in overflow.
        assertEquals(listOf("high"), layout.visible.map { it.id })
        assertEquals(listOf("mid", "low"), layout.overflow.map { it.id })
    }

    @Test
    fun ties_break_by_input_order() {
        val actions = listOf(
            action("first", priority = 5),
            action("second", priority = 5),
            action("third", priority = 5),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 5)

        assertEquals(listOf("first", "second", "third"), layout.visible.map { it.id })
    }

    @Test
    fun priority_reorders_even_when_everything_fits() {
        // Input order is A, B, C but priorities are 1, 5, 3 → visible order
        // should reflect sorted priority: B, C, A.
        val actions = listOf(
            action("A", priority = 1),
            action("B", priority = 5),
            action("C", priority = 3),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 5)

        assertEquals(listOf("B", "C", "A"), layout.visible.map { it.id })
        assertFalse(layout.kebabVisible)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Negative priority = overflow-only
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun negative_priority_is_always_overflow_even_with_room() {
        val actions = listOf(
            action("visible", priority = 0),
            action("hidden", priority = -1),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 10)

        // Kebab must render because one action is forced into overflow.
        assertEquals(listOf("visible"), layout.visible.map { it.id })
        assertEquals(listOf("hidden"), layout.overflow.map { it.id })
        assertTrue(layout.kebabVisible)
    }

    @Test
    fun mixed_priorities_preserve_negative_entries_at_tail_of_overflow() {
        val actions = listOf(
            action("overflow_neg", priority = -5),
            action("low_pos", priority = 0),
            action("high_pos", priority = 10),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 2)

        // 2 slots, 3 actions → 1 visible + kebab. high wins the slot.
        // Overflow = [low_pos (spilled candidate), overflow_neg (always-overflow)].
        assertEquals(listOf("high_pos"), layout.visible.map { it.id })
        assertEquals(listOf("low_pos", "overflow_neg"), layout.overflow.map { it.id })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Prominent actions (variable slot cost)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun prominent_action_costs_three_slots() {
        // 5 slots, one prominent (3) + two regulars (2) = 5 total → all fit.
        val actions = listOf(
            action("prom", priority = 10, emphasis = HeaderActionEmphasis.Prominent),
            action("a"),
            action("b"),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 5)

        assertEquals(listOf("prom", "a", "b"), layout.visible.map { it.id })
        assertFalse(layout.kebabVisible)
    }

    @Test
    fun prominent_that_does_not_fit_spills_to_kebab() {
        // 4 slots, prominent (3) + 2 regulars (2) = 5 total → kebab needed.
        // costBudget = 3 (one slot reserved for kebab). Prominent fits (3 ≤ 3),
        // but nothing else does.
        val actions = listOf(
            action("prom", priority = 10, emphasis = HeaderActionEmphasis.Prominent),
            action("a", priority = 5),
            action("b", priority = 5),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 4)

        assertEquals(listOf("prom"), layout.visible.map { it.id })
        assertEquals(listOf("a", "b"), layout.overflow.map { it.id })
        assertTrue(layout.kebabVisible)
    }

    @Test
    fun prominent_too_wide_for_budget_goes_entirely_to_overflow() {
        // 2 slots, prominent (3) — can't fit, goes to kebab.
        val actions = listOf(
            action("prom", priority = 10, emphasis = HeaderActionEmphasis.Prominent),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 2)

        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("prom"), layout.overflow.map { it.id })
        assertTrue(layout.kebabVisible)
    }

    @Test
    fun greedy_walk_stops_at_prominent_even_if_smaller_items_would_fit_later() {
        // Contract: priority order wins over fit-packing. A prominent high-
        // priority action that doesn't fit stops the walk — we do NOT skip it
        // to pack smaller lower-priority items. Users shouldn't be surprised
        // by "Add" appearing but "Permissions" (higher priority) missing.
        val actions = listOf(
            action("prom", priority = 10, emphasis = HeaderActionEmphasis.Prominent),
            action("small", priority = 5),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 3)

        // costBudget = 2 (kebab reserved). Prominent (3) > 2 → break.
        // "small" (1) would fit (1 ≤ 2), but we don't revisit after a break.
        assertTrue(layout.visible.isEmpty())
        assertEquals(listOf("prom", "small"), layout.overflow.map { it.id })
    }

    @Test
    fun prominent_with_exact_fit_does_not_reserve_kebab() {
        // 3 slots, single prominent (3) → exact fit, no kebab.
        val actions = listOf(
            action("prom", priority = 5, emphasis = HeaderActionEmphasis.Prominent),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 3)

        assertEquals(listOf("prom"), layout.visible.map { it.id })
        assertFalse(layout.kebabVisible)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Create emphasis: always sorts first, last to spill
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun create_sorts_first_even_with_lower_priority_than_prominent() {
        // Create has priority 0 but emphasis beats prominent's priority 100.
        val actions = listOf(
            action("prom", priority = 100, emphasis = HeaderActionEmphasis.Prominent),
            action("create", priority = 0, emphasis = HeaderActionEmphasis.Create),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 10)

        assertEquals(listOf("create", "prom"), layout.visible.map { it.id })
    }

    @Test
    fun create_is_last_to_spill_when_bar_narrows() {
        // 4 slots, kebab reserved → costBudget=3. Create (3) fits first; every
        // other candidate — prominent AND icons — spills to kebab.
        val actions = listOf(
            action("prom", priority = 100, emphasis = HeaderActionEmphasis.Prominent),
            action("icon_hi", priority = 50),
            action("create", priority = 0, emphasis = HeaderActionEmphasis.Create),
            action("icon_lo", priority = 10),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 4)

        assertEquals(listOf("create"), layout.visible.map { it.id })
        assertEquals(listOf("prom", "icon_hi", "icon_lo"), layout.overflow.map { it.id })
    }

    @Test
    fun multiple_create_actions_sort_among_themselves_by_priority() {
        val actions = listOf(
            action("c_low", priority = 1, emphasis = HeaderActionEmphasis.Create),
            action("c_high", priority = 5, emphasis = HeaderActionEmphasis.Create),
            action("icon", priority = 100),
        )

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 10)

        // Both Creates come before the high-priority icon; within Creates,
        // priority desc so c_high first.
        assertEquals(listOf("c_high", "c_low", "icon"), layout.visible.map { it.id })
    }

    @Test
    fun create_costs_three_slots_like_prominent() {
        // Same budget math — Create is labelled, so it takes 3 slots.
        val actions = listOf(action("create", emphasis = HeaderActionEmphasis.Create))

        val layout = computeHeaderActionLayout(actions, maxVisibleSlots = 3)

        assertEquals(listOf("create"), layout.visible.map { it.id })
        assertFalse(layout.kebabVisible)
    }
}
