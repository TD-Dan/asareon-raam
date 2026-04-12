package asareon.raam.feature.agent.contextformatters

import asareon.raam.core.Identity
import asareon.raam.core.PermissionLevel
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.agent.CompressionConfig
import asareon.raam.feature.agent.PromptSection

/**
 * ## Mandate
 * Generates the "Available System Actions" context block for injection into an agent's
 * system prompt. This is fully data-driven: it reads from the build-time generated
 * [asareon.raam.core.generated.ActionRegistry] and filters by the agent's effective permissions, ensuring the
 * prompt always matches what the agent can actually do.
 *
 * Phase 2.B: Replaced the static `agentAllowedNames` allowlist with dynamic
 * permission-based filtering via [asareon.raam.core.Store.resolveEffectivePermissions].
 *
 * The context teaches the agent:
 * 1. The `raam_` code block invocation syntax.
 * 2. The workspace sandboxing model.
 * 3. Each available action's name, purpose, and payload schema.
 */
object ActionsContextFormatter {

    /**
     * Builds a [asareon.raam.feature.agent.PromptSection.Group] with per-feature children for the unified
     * partition model. Each feature namespace (agent, filesystem, session, etc.)
     * becomes an individually collapsible child [asareon.raam.feature.agent.PromptSection.Section].
     *
     * The shared preamble (syntax instructions, examples, constraints) lives in
     * the Group header. Feature groups are sorted alphabetically.
     *
     * @param store The Store instance for resolving effective permissions
     * @param agentIdentity The agent's Identity from the registry
     */
    fun buildSections(store: Store, agentIdentity: Identity, compressionConfig: CompressionConfig = CompressionConfig()): PromptSection.Group {
        var preamble = if (compressionConfig.terseSystemText || compressionConfig.terseActions) {
            TerseText.get("ACTIONS_PREAMBLE", true)
        } else {
            TerseText.get("ACTIONS_PREAMBLE", false)
        }
        if (compressionConfig.abbreviations) {
            preamble = TerseText.abbreviate(preamble)
        }
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
            val content = if (compressionConfig.terseActions) {
                buildTerseActionContent(actions)
            } else {
                buildString {
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

    /**
     * Builds compact reference-card format for actions (Strategy 4).
     * Format: `action.name — One-line description` with `paramName*: type — hint` lines.
     * Convention: `*` = required, `?` = optional.
     */
    private fun buildTerseActionContent(actions: List<ActionRegistry.ActionDescriptor>): String = buildString {
        actions.forEach { desc ->
            append("${desc.fullName} — ${desc.summary.take(80)}")
            appendLine()
            if (desc.payloadFields.isNotEmpty()) {
                desc.payloadFields.forEach { field ->
                    val marker = if (field.required) "*" else "?"
                    val defaultStr = field.default?.let { " (default: $it)" } ?: ""
                    val descStr = if (field.description.length > 60) field.description.take(57) + "..." else field.description
                    appendLine("  ${field.name}$marker: ${field.type}$defaultStr — $descStr")
                }
            }
            appendLine()
        }
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