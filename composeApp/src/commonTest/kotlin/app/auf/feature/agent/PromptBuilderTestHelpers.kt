package app.auf.feature.agent

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