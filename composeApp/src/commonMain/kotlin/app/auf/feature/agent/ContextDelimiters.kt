package app.auf.feature.agent

import kotlin.math.pow

/**
 * Shared delimiter conventions for agent context partitions.
 *
 * Pipeline-level utility in `app.auf.feature.agent`. Used by the pipeline,
 * formatters, and strategies to produce structurally consistent context.
 *
 * ## Delimiter Hierarchy
 *
 * These delimiters are chosen to be unique and not naturally occurring in any
 * partial content. They avoid markdown (`#`, `##`) and XML (`<tag>`) conventions
 * because both appear routinely in agent-generated content and knowledge graphs.
 *
 * ```
 * [[[ - SYSTEM PROMPT - ]]]              → Outermost wrapper (pipeline-owned)
 *
 * - [ PARTIAL NAME ] (~tokens) [STATE] - → h1: Top-level partition boundary
 * - [ END OF PARTIAL NAME ] -            → h1 closing tag
 *
 * --- TEXT (~tokens) [STATE] ---          → h2: Sub-partition (e.g., session, file)
 * --- END OF TEXT ---                     → h2 closing tag
 *
 *   --- TEXT (~tokens) [STATE] ---        → h3: Entry-level (2-space indent)
 *   ---                                   → h3 closing tag
 *
 *     --- TEXT (~tokens) [STATE] ---      → h4: Sub-entry (4-space indent)
 *     ---                                 → h4 closing tag
 * ```
 *
 * Spacing rules:
 * - h1 tags: triple `\n` before, double `\n` after (own line, surrounded by blanks)
 * - h1 closing, h2–h4 tags: double `\n` before and after
 * - Content sits at zero indent between delimiters
 *
 * ## Ownership
 *
 * - **Pipeline** owns: `[[[ ]]]` outer wrapper, h1 headers on each contextMap entry
 * - **Formatters** own: internal h2/h3/h4 structure within a partition
 * - **Strategies** own: ordering of partitions, strategy-specific h1 sections
 *   (IDENTITY, INSTRUCTIONS, NAVIGATION, etc.)
 *
 * ## Collapse State Badges
 *
 * - `PROTECTED`: Never collapsed by the budget system. Always present.
 * - `EXPANDED`: Showing full content. Agent can collapse it.
 * - `COLLAPSED`: Showing summary only. Agent can expand it.
 * - `TRUNCATED`: Was truncated by the pipeline sentinel.
 *
 * ## Token Estimation (§2.2)
 *
 * All token counts use the `≈4 chars/token` heuristic. Values are rounded UP
 * to 2 significant figures for display (e.g., 2389→2400, 551→560, 22476→23000).
 */
object ContextDelimiters {

    const val CHARS_PER_TOKEN = 4

    // State badge constants
    const val PROTECTED = "PROTECTED"
    const val EXPANDED = "EXPANDED"
    const val COLLAPSED = "COLLAPSED"
    const val TRUNCATED = "TRUNCATED"

    // =========================================================================
    // System Prompt Wrapper (pipeline-owned)
    // =========================================================================

    /** Wraps a complete system prompt with the outermost delimiters. */
    fun wrapSystemPrompt(content: String): String =
        "\n[[[ - SYSTEM PROMPT - ]]]\n\n${content}\n[[[ - END OF SYSTEM PROMPT - ]]]\n"

    // =========================================================================
    // h1 — Top-level partition boundary
    // =========================================================================

    /**
     * Opens an h1 section. Triple newline before, double after.
     *
     * @param name Partition name (e.g., "CONVERSATION_LOG", "YOUR IDENTITY AND ROLE").
     * @param chars Content character count for token estimation. Null to omit.
     * @param state Collapse state badge. Null to omit.
     */
    fun h1(name: String, chars: Int? = null, state: String? = null): String {
        val tokenPart = chars?.let { " (~${approxTokens(it)} tokens)" } ?: ""
        val statePart = state?.let { " [$it]" } ?: ""
        return "\n\n- [ $name ]${tokenPart}${statePart} -\n"
    }

    /** Closes an h1 section. Double newline before and after. */
    fun h1End(name: String): String = "\n- [ END OF $name ] -\n"

    // =========================================================================
    // h2 — Sub-partition boundary (no indent)
    // =========================================================================

    /**
     * Opens an h2 section. Double newline before and after.
     *
     * @param text Section label (e.g., "SESSION: Chat | uuid: ... | 5 messages").
     * @param chars Content character count. Null to omit token estimate.
     * @param state Collapse state badge. Null to omit.
     */
    fun h2(text: String, chars: Int? = null, state: String? = null): String {
        val tokenPart = chars?.let { " (~${approxTokens(it)} tokens)" } ?: ""
        val statePart = state?.let { " [$it]" } ?: ""
        return "\n--- ${text}${tokenPart}${statePart} ---\n"
    }

    /** Closes an h2 section. Double newline before and after. */
    fun h2End(text: String): String = "\n\n--- END OF $text ---\n\n"

    // =========================================================================
    // h3 — Entry-level boundary (2-space indent)
    // =========================================================================

    fun h3(text: String, chars: Int? = null, state: String? = null): String {
        val tokenPart = chars?.let { " (~${approxTokens(it)} tokens)" } ?: ""
        val statePart = state?.let { " [$it]" } ?: ""
        return "\n  --- ${text}${tokenPart}${statePart} ---\n"
    }

    /** Closes an h3 section. */
    fun h3End(): String = "\n  ---\n"

    // =========================================================================
    // h4 — Sub-entry boundary (4-space indent)
    // =========================================================================

    fun h4(text: String, chars: Int? = null, state: String? = null): String {
        val tokenPart = chars?.let { " (~${approxTokens(it)} tokens)" } ?: ""
        val statePart = state?.let { " [$it]" } ?: ""
        return "\n    --- ${text}${tokenPart}${statePart} ---\n"
    }

    /** Closes an h4 section. */
    fun h4End(): String = "\n    ---\n"

    // =========================================================================
    // Token estimation (§2.2)
    // =========================================================================

    /**
     * Converts a character count to an approximate token string, rounded UP
     * to 2 significant figures with comma formatting.
     *
     * Examples: 9556 chars → "2,400", 2204 chars → "560", 89904 chars → "23,000"
     */
    fun approxTokens(chars: Int): String {
        val rawTokens = (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        val rounded = roundTokensUp(rawTokens)
        return formatWithCommas(rounded)
    }

    /**
     * Rounds a token count UP to 2 significant figures, with a minimum
     * rounding granularity of 10.
     *
     * Examples: 2389→2400, 551→560, 22476→23000, 12→20, 8→10, 0→0
     */
    fun roundTokensUp(tokens: Int): Int {
        if (tokens <= 0) return 0
        val digits = tokens.toString().length
        // Minimum factor of 10 ensures low-end values like 12→20, 8→10
        val factor = maxOf(10, 10.0.pow(digits - 2).toInt())
        return ((tokens + factor - 1) / factor) * factor
    }

    /** Formats an integer with comma separators. E.g., 12500 → "12,500". */
    fun formatWithCommas(value: Int): String {
        return value.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}