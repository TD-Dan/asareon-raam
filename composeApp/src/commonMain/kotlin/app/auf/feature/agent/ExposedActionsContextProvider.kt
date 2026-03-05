package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.core.PermissionLevel
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry

/**
 * ## Mandate
 * Generates the "Available System Actions" context block for injection into an agent's
 * system prompt. This is fully data-driven: it reads from the build-time generated
 * [ActionRegistry] and filters by the agent's effective permissions, ensuring the
 * prompt always matches what the agent can actually do.
 *
 * Phase 2.B: Replaced the static `agentAllowedNames` allowlist with dynamic
 * permission-based filtering via [Store.resolveEffectivePermissions].
 *
 * The context teaches the agent:
 * 1. The `auf_` code block invocation syntax.
 * 2. The workspace sandboxing model.
 * 3. Each available action's name, purpose, and payload schema.
 */
object ExposedActionsContextProvider {

    /**
     * Generates a formatted context block describing all actions available to
     * the specified agent identity, filtered by its effective permissions.
     *
     * @param store The Store instance for resolving effective permissions
     * @param agentIdentity The agent's Identity from the registry
     */
    fun generateContext(store: Store, agentIdentity: Identity): String = buildString {
        appendLine("--- AVAILABLE SYSTEM ACTIONS ---")
        appendLine()
        appendLine("You can invoke system actions by including a fenced code block in your response.")
        appendLine("The code block's language tag must be 'auf_' followed by the full action name.")
        appendLine("The code block body must be a valid JSON payload, or empty for no-payload actions.")
        appendLine()
        appendLine("IMPORTANT CONSTRAINTS:")
        appendLine("- All file paths ('path') are relative to YOUR private workspace directory.")
        appendLine("- You CANNOT access files outside your workspace. Path traversal is blocked.")
        appendLine("- File paths must use '/' separators and include a file extension.")
        appendLine("- You may invoke multiple actions in a single response by including multiple code blocks.")
        appendLine()
        appendLine("EXAMPLE — Writing a file:")
        appendLine("```auf_filesystem.WRITE")
        appendLine("{")
        appendLine("  \"path\": \"notes/summary.md\",")
        appendLine("  \"content\": \"# Meeting Summary\\nKey points discussed today...\"")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("EXAMPLE — Listing your workspace:")
        appendLine("```auf_filesystem.LIST")
        appendLine("{")
        appendLine("  \"recursive\": true")
        appendLine("}")
        appendLine("```")
        appendLine()

        // Resolve the agent's effective permissions through the inheritance chain
        val effectivePerms = store.resolveEffectivePermissions(agentIdentity)

        // Filter to public actions where the agent has all required permissions
        val agentActions = ActionRegistry.byActionName.values
            .filter { desc ->
                // Must be public (agents dispatch cross-feature)
                desc.public &&
                        // Must have declared required_permissions
                        desc.requiredPermissions != null &&
                        // Agent must have YES for all required permissions
                        desc.requiredPermissions!!.all { permKey ->
                            effectivePerms[permKey]?.level == PermissionLevel.YES
                        }
            }
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