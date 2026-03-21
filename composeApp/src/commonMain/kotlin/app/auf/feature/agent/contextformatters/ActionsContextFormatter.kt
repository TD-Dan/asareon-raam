package app.auf.feature.agent.contextformatters

import app.auf.core.Identity
import app.auf.core.PermissionLevel
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.feature.agent.PromptSection

/**
 * ## Mandate
 * Generates the "Available System Actions" context block for injection into an agent's
 * system prompt. This is fully data-driven: it reads from the build-time generated
 * [app.auf.core.generated.ActionRegistry] and filters by the agent's effective permissions, ensuring the
 * prompt always matches what the agent can actually do.
 *
 * Phase 2.B: Replaced the static `agentAllowedNames` allowlist with dynamic
 * permission-based filtering via [app.auf.core.Store.resolveEffectivePermissions].
 *
 * The context teaches the agent:
 * 1. The `auf_` code block invocation syntax.
 * 2. The workspace sandboxing model.
 * 3. Each available action's name, purpose, and payload schema.
 */
object ActionsContextFormatter {

    /**
     * Builds a [app.auf.feature.agent.PromptSection.Group] with per-feature children for the unified
     * partition model. Each feature namespace (agent, filesystem, session, etc.)
     * becomes an individually collapsible child [app.auf.feature.agent.PromptSection.Section].
     *
     * The shared preamble (syntax instructions, examples, constraints) lives in
     * the Group header. Feature groups are sorted alphabetically.
     *
     * @param store The Store instance for resolving effective permissions
     * @param agentIdentity The agent's Identity from the registry
     */
    fun buildSections(store: Store, agentIdentity: Identity): PromptSection.Group {
        val preamble = buildPreamble()
        val agentActions = resolveAgentActions(store, agentIdentity)

        if (agentActions.isEmpty()) {
            return PromptSection.Group(
                key = "AVAILABLE_ACTIONS",
                header = preamble,
                children = listOf(
                    PromptSection.Section(
                        key = "AVAILABLE_ACTIONS:empty",
                        content = "No system actions are currently available.",
                        isProtected = true,
                        isCollapsible = false
                    )
                ),
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[Available actions collapsed — no actions available]"
            )
        }

        // Group actions by feature prefix (everything before the first '.')
        val byFeature = agentActions.groupBy { desc ->
            desc.fullName.substringBefore('.', "other")
        }.toSortedMap()

        val children = byFeature.map { (feature, actions) ->
            val content = buildString {
                actions.forEach { desc ->
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
            PromptSection.Section(
                key = "actions:$feature",
                content = content,
                isProtected = false,
                isCollapsible = true,
                priority = 10,
                collapsedSummary = "[$feature — ${actions.size} action${if (actions.size != 1) "s" else ""} collapsed]"
            )
        }

        val totalActions = agentActions.size
        return PromptSection.Group(
            key = "AVAILABLE_ACTIONS",
            header = preamble + "\nAVAILABLE ACTIONS (${totalActions} across ${byFeature.size} features):\n",
            children = children,
            isCollapsible = true,
            priority = 10,
            collapsedSummary = "[Available actions collapsed — $totalActions actions across ${byFeature.size} features. " +
                    "Use agent.CONTEXT_UNCOLLAPSE to expand.]"
        )
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Builds the shared preamble: syntax instructions, examples, constraints. */
    private fun buildPreamble(): String = buildString {
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
    }

    /** Resolves and filters the actions available to the given agent identity. */
    private fun resolveAgentActions(store: Store, agentIdentity: Identity) =
        store.resolveEffectivePermissions(agentIdentity).let { effectivePerms ->
            ActionRegistry.byActionName.values
                .filter { desc ->
                    desc.public &&
                            !desc.hidden &&
                            desc.requiredPermissions != null &&
                            desc.requiredPermissions!!.all { permKey ->
                                effectivePerms[permKey]?.level == PermissionLevel.YES
                            }
                }
                .sortedBy { it.fullName }
        }
}