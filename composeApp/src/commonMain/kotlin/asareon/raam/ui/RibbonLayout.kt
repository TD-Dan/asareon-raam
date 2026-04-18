package asareon.raam.ui

import asareon.raam.core.RibbonEntry

/**
 * Pure-data result of splitting a priority-sorted list of [RibbonEntry]s into
 * a visible section and an overflow section, given a slot budget. Extracted
 * from [GlobalActionRibbon] so the layout logic can be unit-tested without
 * a Compose runtime.
 */
internal data class RibbonLayout(
    val visible: List<RibbonEntry>,
    val overflow: List<RibbonEntry>,
) {
    /** Whether the overflow slot should render (counts as one visible slot). */
    val overflowVisible: Boolean get() = overflow.isNotEmpty()
}

/**
 * Priority-sort [entries] (higher first, ties by input order) and split into
 * a visible section of up to [slotBudget] entries plus an overflow section
 * for the remainder. When spill-over is needed, one slot is reserved for the
 * overflow icon itself. Negative budgets are coerced to 0.
 */
internal fun computeRibbonLayout(
    entries: List<RibbonEntry>,
    slotBudget: Int,
): RibbonLayout {
    if (entries.isEmpty()) return RibbonLayout(emptyList(), emptyList())

    val sorted = entries
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<RibbonEntry>> { it.value.priority }
                .thenBy { it.index }
        )
        .map { it.value }

    val budget = slotBudget.coerceAtLeast(0)
    val allFit = sorted.size <= budget
    val visibleCount = if (allFit) sorted.size else (budget - 1).coerceAtLeast(0)

    return RibbonLayout(
        visible = sorted.take(visibleCount),
        overflow = sorted.drop(visibleCount),
    )
}
