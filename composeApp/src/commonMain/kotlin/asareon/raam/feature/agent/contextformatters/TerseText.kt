package asareon.raam.feature.agent.contextformatters

/**
 * Centralized registry of verbose/terse text pairs for system-generated content.
 * Used by context formatters when terse system text compression is enabled.
 *
 * Also contains the word abbreviation dictionary for Strategy 5.
 */
object TerseText {

    /**
     * Returns the terse version of a named text block if [isTerse] is true,
     * otherwise returns the verbose version.
     */
    fun get(key: String, isTerse: Boolean): String =
        if (isTerse) terseTexts[key] ?: verboseTexts[key] ?: "" else verboseTexts[key] ?: ""

    /**
     * Apply standard word abbreviations to text. Only uses universally understood
     * programming/tech abbreviations.
     */
    fun abbreviate(text: String): String {
        var result = text
        abbreviationDict.forEach { (full, abbrev) ->
            result = result.replace(full, abbrev, ignoreCase = false)
        }
        return result
    }

    // =========================================================================
    // Word Abbreviation Dictionary (Strategy 5)
    // Only universally recognized tech abbreviations — no invented forms.
    // =========================================================================

    private val abbreviationDict = listOf(
        "configuration" to "config",
        "authentication" to "auth",
        "authorization" to "authz",
        "description" to "desc",
        "implementation" to "impl",
        "automatically" to "auto",
        "information" to "info",
        "application" to "app",
        "directory" to "dir",
        "parameter" to "param",
        "identifier" to "id",
        "specification" to "spec",
        "repository" to "repo",
        "environment" to "env",
        "dependencies" to "deps",
        "development" to "dev",
    )

    // =========================================================================
    // Verbose / Terse Text Pairs
    // =========================================================================

    private val verboseTexts = mapOf(
        // --- Session Context Formatter ---
        "SESSION_ROUTING_PRIVATE" to buildString {
            appendLine("You observe messages from all subscribed sessions. Your direct response goes to your private session.")
            append("Use session.POST to publish to public sessions.")
        },
        "SESSION_ROUTING_STANDARD" to buildString {
            appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
        },
        "SESSION_ROUTING_STANDARD_MULTI" to buildString {
            appendLine("You observe messages from all subscribed sessions. Your responses are posted to the primary session.")
            append("Use session.POST to publish to other sessions.")
        },
        "SESSION_MSG_FORMAT" to buildString {
            appendLine("Each message in the conversation is wrapped with sender headers (name, id, timestamp).")
            append("When YOU respond, do NOT include these headers — the system adds them automatically.")
        },

        // --- HKG Context Formatter ---
        "HKG_NAVIGATION" to buildString {
            appendLine("Your Knowledge Graph is presented as an INDEX (tree overview) and holon files.")
            appendLine("By default, all files are closed. Use these commands to navigate:")
            appendLine()
            appendLine("Open a single holon file:")
            appendLine("```raam_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "single" }""")
            appendLine("```")
            appendLine()
            appendLine("Open a holon and all its children:")
            appendLine("```raam_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>", "scope": "subtree" }""")
            appendLine("```")
            appendLine()
            appendLine("Close a holon file:")
            appendLine("```raam_agent.CONTEXT_COLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<holonId>" }""")
            appendLine("```")
            appendLine()
            appendLine("IMPORTANT: You must expand a holon file before writing to it.")
            append("The system will block writes to collapsed holons to prevent data loss.")
        },

        // --- Actions Context Formatter ---
        "ACTIONS_PREAMBLE" to buildString {
            appendLine("--- AVAILABLE SYSTEM ACTIONS ---")
            appendLine()
            appendLine("You can invoke system actions by including a fenced code block in your response.")
            appendLine("The code block's language tag must be 'raam_' followed by the full action name.")
            appendLine("The code block body must be a valid JSON payload, or empty for no-payload actions.")
            appendLine()
            appendLine("IMPORTANT CONSTRAINTS:")
            appendLine("- All file paths ('path') are relative to YOUR private workspace directory.")
            appendLine("- You CANNOT access files outside your workspace. Path traversal is blocked.")
            appendLine("- File paths must use '/' separators and include a file extension.")
            appendLine("- You may invoke multiple actions in a single response by including multiple code blocks.")
            appendLine()
            appendLine("EXAMPLE — Writing a file:")
            appendLine("```raam_filesystem.WRITE")
            appendLine("{")
            appendLine("  \"path\": \"notes/summary.md\",")
            appendLine("  \"content\": \"# Meeting Summary\\nKey points discussed today...\"")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("EXAMPLE — Listing your workspace:")
            appendLine("```raam_filesystem.LIST")
            appendLine("{")
            appendLine("  \"recursive\": true")
            appendLine("}")
            appendLine("```")
        },
    )

    private val terseTexts = mapOf(
        // --- Session Context Formatter ---
        "SESSION_ROUTING_PRIVATE" to
            "Responses \u2192 PRIVATE session (only you). Use session.POST for public msgs. Context shows ALL subscribed sessions.",
        "SESSION_ROUTING_STANDARD" to
            "Responses \u2192 primary session. Context shows ALL subscribed sessions.",
        "SESSION_ROUTING_STANDARD_MULTI" to
            "Responses \u2192 primary session. Use session.POST for other sessions.",
        "SESSION_MSG_FORMAT" to
            "Msg headers auto-added. Don't include them in responses.",

        // --- HKG Context Formatter ---
        "HKG_NAVIGATION" to buildString {
            appendLine("HKG = INDEX + files. Default: closed. Expand before writing (writes blocked on closed holons).")
            appendLine("Open: ```raam_agent.CONTEXT_UNCOLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<id>", "scope": "single|subtree" }""")
            appendLine("```")
            appendLine("Close: ```raam_agent.CONTEXT_COLLAPSE")
            appendLine("""{ "partitionKey": "hkg:<id>" }""")
            append("```")
        },

        // --- Actions Context Formatter ---
        "ACTIONS_PREAMBLE" to buildString {
            appendLine("--- SYSTEM ACTIONS ---")
            appendLine("Invoke via fenced code block: lang tag = 'raam_' + action name, body = JSON payload.")
            appendLine("Paths relative to YOUR workspace. '/' separators. Multiple actions per response OK.")
            appendLine("Example: ```raam_filesystem.WRITE")
            appendLine("""{ "path": "notes/summary.md", "content": "..." }""")
            append("```")
        },
    )
}
