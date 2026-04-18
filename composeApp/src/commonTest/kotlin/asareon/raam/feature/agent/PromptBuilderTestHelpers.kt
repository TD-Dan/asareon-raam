package asareon.raam.feature.agent

/**
 * Test helper: renders a [PromptBuilder]'s sections to a flat string for content assertions.
 *
 * Strategy-owned [PromptSection.Section] nodes emit their content directly.
 * [PromptSection.GatheredRef] nodes emit `[GATHERED:KEY]` markers (the pipeline
 * resolves these against the contextMap at runtime — tests verify ordering and
 * presence, not content).
 * [PromptSection.RemainingGathered] emits `[REMAINING_GATHERED]`.
 * [PromptSection.Group] recurses into children.
 */
fun PromptBuilder.renderForTest(): String = buildString {
    renderSections(this@renderForTest.sections)
}

private fun StringBuilder.renderSections(sections: List<PromptSection>) {
    for (section in sections) {
        when (section) {
            is PromptSection.Section -> appendLine(section.content)
            is PromptSection.GatheredRef -> appendLine("[GATHERED:${section.key}]")
            is PromptSection.RemainingGathered -> appendLine("[REMAINING_GATHERED]")
            is PromptSection.Group -> {
                if (section.header.isNotBlank()) appendLine(section.header)
                renderSections(section.children)
            }
        }
    }
}

// =============================================================================
// Structural inspection helpers for contract-style tests
//
// Prefer these over `renderForTest().contains("KEY")` — tests that search the
// rendered output for section keys are fragile (the renderer may not include
// keys) and can silently pass when a feature is missing. Structural helpers
// let tests assert "a section with this key exists" and "its content contains
// this injected sentinel" as two independent, explicit checks.
// =============================================================================

/** Keys of all top-level [PromptSection.Section] entries on the builder. */
val PromptBuilder.sectionKeys: List<String>
    get() = sections.filterIsInstance<PromptSection.Section>().map { it.key }

/** Keys of all gathered-partition placeholders emitted by `place(key)`. */
val PromptBuilder.gatheredKeys: List<String>
    get() = sections.filterIsInstance<PromptSection.GatheredRef>().map { it.key }

/** First [PromptSection.Section] with the given key, or null. Recurses into groups. */
fun PromptBuilder.findSection(key: String): PromptSection.Section? =
    findSectionIn(sections, key)

private fun findSectionIn(sections: List<PromptSection>, key: String): PromptSection.Section? {
    for (s in sections) {
        when (s) {
            is PromptSection.Section -> if (s.key == key) return s
            is PromptSection.Group -> findSectionIn(s.children, key)?.let { return it }
            else -> {}
        }
    }
    return null
}

/** True if `place(key)` was called on the builder (whether top-level or inside a group). */
fun PromptBuilder.hasGathered(key: String): Boolean = hasGatheredIn(sections, key)

private fun hasGatheredIn(sections: List<PromptSection>, key: String): Boolean {
    for (s in sections) {
        when (s) {
            is PromptSection.GatheredRef -> if (s.key == key) return true
            is PromptSection.Group -> if (hasGatheredIn(s.children, key)) return true
            else -> {}
        }
    }
    return false
}