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
        val effectivePerms = store.resolveEffectivePermissions(agentIdentity)
        val grantedPermKeys = effectivePerms
            .filter { it.value.level == PermissionLevel.YES }
            .keys
        val agentActions = resolveAgentActions(effectivePerms)

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
                buildTerseActionContent(actions, grantedPermKeys)
            } else {
                buildString {
                    actions.forEach { desc ->
                        appendLine("### ${desc.fullName}")
                        appendLine(desc.summaryFor(ActionRegistry.Audience.AGENT))
                        val visibleFields = desc.payloadFields.filter { isFieldVisibleToAgent(it, grantedPermKeys) }
                        visibleFields.forEach { field ->
                            val requiredTag = if (field.required) " (REQUIRED)" else ""
                            val defaultTag = field.default?.let { " [default: $it]" } ?: ""
                            val agentDesc = field.descriptionFor(ActionRegistry.Audience.AGENT)
                            val descTail = if (agentDesc.isNotBlank()) ": $agentDesc" else ""
                            appendLine("  - \"${field.name}\" (${field.type})$requiredTag$defaultTag$descTail")
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
    private fun buildTerseActionContent(
        actions: List<ActionRegistry.ActionDescriptor>,
        grantedPermKeys: Set<String>,
    ): String = buildString {
        // No length truncation — authors control terse copy via agent_summary /
        // agent_description. Mechanical truncation here would silently drop the
        // tail of an author-trimmed description, which is precisely where
        // disambiguating detail tends to live (e.g. "...'sf:<sessionId>:<path>'").
        actions.forEach { desc ->
            val agentSummary = desc.summaryFor(ActionRegistry.Audience.AGENT)
            append("${desc.fullName} — $agentSummary")
            appendLine()
            val visibleFields = desc.payloadFields.filter { isFieldVisibleToAgent(it, grantedPermKeys) }
            visibleFields.forEach { field ->
                val marker = if (field.required) "*" else "?"
                val defaultStr = field.default?.let { " (default: $it)" } ?: ""
                val agentFieldDesc = field.descriptionFor(ActionRegistry.Audience.AGENT)
                val descTail = if (agentFieldDesc.isNotBlank()) " — $agentFieldDesc" else ""
                appendLine("  ${field.name}$marker: ${field.type}$defaultStr$descTail")
            }
            appendLine()
        }
    }

    /**
     * Field-level visibility: hide system-managed and auto-filled fields, plus
     * permission-gated fields the agent can't actually use. The same predicate
     * drives both the catalog and the strip pass on agent-originated payloads,
     * so what you see is what you can set.
     */
    private fun isFieldVisibleToAgent(
        field: ActionRegistry.PayloadField,
        grantedPermKeys: Set<String>,
    ): Boolean {
        if (field.agentInternal) return false
        if (field.agentAutofill != null) return false
        val gated = field.agentRequiresPermission
        if (gated != null && gated !in grantedPermKeys) return false
        return true
    }

    /** Resolves and filters the actions available to the given agent identity. */
    private fun resolveAgentActions(effectivePerms: Map<String, asareon.raam.core.PermissionGrant>) =
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