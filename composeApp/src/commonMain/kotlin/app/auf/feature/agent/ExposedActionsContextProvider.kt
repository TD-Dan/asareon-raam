package app.auf.feature.agent

import app.auf.core.generated.ActionRegistry

/**
 * ## Mandate
 * Generates the "Available System Actions" context block for injection into an agent's
 * system prompt. This is fully data-driven: it reads from the build-time generated
 * [ActionRegistry], ensuring the prompt always matches the actual allowlist.
 *
 * The context teaches the agent:
 * 1. The `auf_` code block invocation syntax.
 * 2. The workspace sandboxing model.
 * 3. Each available action's name, purpose, and payload schema.
 */
object ExposedActionsContextProvider {

    /**
     * Generates a formatted context block describing all available agent actions.
     * Designed for injection into `AgentTurnContext.gatheredContexts["AVAILABLE_ACTIONS"]`.
     */
    fun generateContext(): String = buildString {
        appendLine("--- AVAILABLE SYSTEM ACTIONS ---")
        appendLine()
        appendLine("You can invoke system actions by including a fenced code block in your response.")
        appendLine("The code block's language tag must be 'auf_' followed by the full action name.")
        appendLine("The code block body must be a valid JSON payload, or empty for no-payload actions.")
        appendLine()
        appendLine("IMPORTANT CONSTRAINTS:")
        appendLine("- All file paths ('subpath') are relative to YOUR private workspace directory.")
        appendLine("- You CANNOT access files outside your workspace. Path traversal is blocked.")
        appendLine("- File paths must use '/' separators and include a file extension.")
        appendLine("- You may invoke multiple actions in a single response by including multiple code blocks.")
        appendLine()
        appendLine("EXAMPLE — Writing a file:")
        appendLine("```auf_filesystem.SYSTEM_WRITE")
        appendLine("{")
        appendLine("  \"subpath\": \"notes/summary.md\",")
        appendLine("  \"content\": \"# Meeting Summary\\nKey points discussed today...\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("EXAMPLE — Listing your workspace:")
        appendLine("```auf_filesystem.SYSTEM_LIST")
        appendLine("{")
        appendLine("  \"recursive\": true")
        appendLine("}")
        appendLine("```")
        appendLine()

        val agentActions = ActionRegistry.agentAllowedNames
            .mapNotNull { name -> ActionRegistry.byActionName[name] }
            .sortedBy { it.fullName }

        if (agentActions.isEmpty()) {
            appendLine("No system actions are currently available.")
            return@buildString
        }

        appendLine("AVAILABLE ACTIONS:")
        appendLine()

        agentActions.forEach { desc ->
            appendLine("### ${desc.fullName}")
            appendLine(desc.summary)

            if (desc.payloadFields.isNotEmpty()) {
                appendLine("Payload fields:")
                desc.payloadFields.forEach { field ->
                    val requiredTag = if (field.required) " (REQUIRED)" else ""
                    val defaultTag = field.default?.let { " [default: $it]" } ?: ""
                    appendLine("  - \"${field.name}\" (${field.type})$requiredTag$defaultTag: ${field.description}")
                }
            } else {
                appendLine("Payload: none (empty code block body)")
            }
            appendLine()
        }
    }
}