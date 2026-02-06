package app.auf.feature.commandbot

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.core.generated.ExposedActions
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * A headless agent that observes all session transcripts for command directives
 * (`auf_` code blocks) and dispatches them as universal Actions, making the application
 * universally scriptable.
 *
 * ## Guardrails
 * - **CAG-001 (Self-Reaction Prevention)**: Ignores messages from itself.
 * - **CAG-002 (Causality Tracking)**: Attributes dispatched actions to the original sender.
 * - **CAG-003 (Robust Error Handling)**: Posts parse errors back to the session.
 * - **CAG-004 (Agent Action Restriction)**: Agent-originated commands are restricted to the
 *   build-time [ExposedActions.allowedActionNames] allowlist. Human users are unrestricted.
 * - **CAG-005 (Agent Workspace Sandboxing)**: Agent file operations have their `subpath`
 *   prefixed with `{agentId}/workspace/` and are dispatched with originator `"agent"`,
 *   confining all I/O to the agent's private workspace directory.
 *
 * ## Decoupling
 * This feature does NOT import any types from the session package. It reads the
 * `session.publish.MESSAGE_POSTED` payload via raw JSON traversal, treating
 * the published schema as a contract boundary.
 */
class CommandBotFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "commandbot"
    override val composableProvider: Feature.ComposableProvider? = null

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Tracks known agent IDs, updated via the Proactive Broadcast pattern.
     * Used to distinguish agent-originated commands from human-originated commands
     * for enforcement of [ExposedActions] restrictions (CAG-004).
     */
    private val knownAgentIds = mutableSetOf<String>()

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            // --- Track known agents via Proactive Broadcast ---
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> {
                val namesMap = action.payload?.get("names")?.jsonObject ?: return
                knownAgentIds.clear()
                knownAgentIds.addAll(namesMap.keys)
                platformDependencies.log(
                    LogLevel.DEBUG, name,
                    "Updated known agent IDs: ${knownAgentIds.joinToString(", ")}"
                )
            }
            ActionNames.AGENT_PUBLISH_AGENT_DELETED -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.content ?: return
                knownAgentIds.remove(agentId)
            }

            // --- Core Command Processing ---
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload ?: return
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val entry = payload["entry"]?.jsonObject ?: return
                val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull ?: return

                // Guardrail (CAG-001): Self-Reaction Prevention.
                if (senderId == this.name) return

                // Read pre-parsed content blocks directly from the JSON payload.
                // Session already parses rawContent into structured ContentBlock objects.
                // We traverse the serialized form without importing Session types.
                val contentBlocks = entry["content"]?.jsonArray ?: return

                contentBlocks.forEach { blockElement ->
                    val block = blockElement.jsonObject
                    // ContentBlock is a sealed interface — the serialized form has a "type" discriminator.
                    // We only care about CodeBlock entries whose language starts with "auf_".
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!type.contains("CodeBlock")) return@forEach

                    val language = block["language"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!language.startsWith("auf_")) return@forEach

                    val code = block["code"]?.jsonPrimitive?.contentOrNull ?: ""

                    processCommandBlock(language, code, sessionId, senderId, store)
                }
            }
        }
    }

    private fun processCommandBlock(
        language: String,
        code: String,
        sessionId: String,
        originalSenderId: String,
        store: Store
    ) {
        val actionName = language.removePrefix("auf_")
        val isAgent = knownAgentIds.contains(originalSenderId)

        try {
            val payloadJson = if (code.isNotBlank()) {
                json.parseToJsonElement(code) as JsonObject
            } else {
                buildJsonObject {}
            }

            // === AGENT ENFORCEMENT ===
            if (isAgent) {
                // Guardrail (CAG-004): Agent Action Restriction
                if (actionName !in ExposedActions.allowedActionNames) {
                    platformDependencies.log(
                        LogLevel.WARN, name,
                        "Agent '$originalSenderId' attempted disallowed action '$actionName'. Blocked."
                    )
                    postFeedbackToSession(
                        sessionId,
                        "[COMMAND BOT] Action '$actionName' is not available to agents.",
                        store
                    )
                    return
                }

                // Guardrail (CAG-005): Agent Workspace Sandboxing
                val rule = ExposedActions.sandboxRules[actionName]
                if (rule != null && rule.strategy == "AGENT_WORKSPACE") {
                    val sandboxedPayload = applySandboxRewrite(payloadJson, rule, originalSenderId)
                    val sandboxedAction = Action(name = actionName, payload = sandboxedPayload)

                    // Dispatch with originator "agent" so FileSystem sandboxes to ~/.auf/v2/agent/
                    // The subpath is already prefixed with "{agentId}/workspace/..." by the rewrite.
                    store.deferredDispatch("agent", sandboxedAction)
                    platformDependencies.log(
                        LogLevel.INFO, name,
                        "Agent '$originalSenderId' dispatched sandboxed action '$actionName'."
                    )
                    return
                }

                // Exposed action without sandbox rule — dispatch with agent originator
                val commandAction = Action(name = actionName, payload = payloadJson)
                store.deferredDispatch("agent", commandAction)
                return
            }

            // === HUMAN USER PATH (unchanged) ===
            // Guardrail (CAG-002): Causality Tracking. Dispatched on BEHALF of the original sender.
            val commandAction = Action(name = actionName, payload = payloadJson)
            store.deferredDispatch(originalSenderId, commandAction)

        } catch (e: Exception) {
            // Guardrail (CAG-003): Robust Error Handling with feedback loop.
            platformDependencies.log(
                LogLevel.ERROR, name,
                "Failed to parse command '$actionName' due to invalid JSON payload.", e
            )
            postFeedbackToSession(
                sessionId,
                "[COMMAND BOT ERROR]\nAction Name: $actionName\nError: Failed to parse command JSON payload. Please check for syntax errors.\nDetails: ${e.message}",
                store
            )
        }
    }

    /**
     * Applies workspace sandboxing to an agent's action payload.
     *
     * Rewrites:
     * 1. Prefixes `subpath` with `{agentId}/workspace/` so FileSystem resolves to the agent's private workspace.
     * 2. Applies any `payloadRewrites` from the sandbox rule (e.g., forcing `encrypt = false`).
     */
    private fun applySandboxRewrite(
        payload: JsonObject,
        rule: ExposedActions.SandboxRule,
        agentId: String
    ): JsonObject {
        val mutablePayload = payload.toMutableMap()

        // Prefix the subpath
        val originalSubpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
        val prefix = rule.subpathPrefixTemplate.replace("{agentId}", agentId)
        val sandboxedSubpath = if (originalSubpath.isNotBlank()) "$prefix/$originalSubpath" else prefix
        mutablePayload["subpath"] = JsonPrimitive(sandboxedSubpath)

        // Apply payload rewrites (e.g., force encrypt=false)
        rule.payloadRewrites.forEach { (key, jsonLiteralValue) ->
            mutablePayload[key] = json.parseToJsonElement(jsonLiteralValue)
        }

        return JsonObject(mutablePayload)
    }

    /**
     * Posts a feedback message to the originating session with CommandBot as the sender.
     */
    private fun postFeedbackToSession(sessionId: String, message: String, store: Store) {
        val formattedMessage = "```text\n$message\n```"
        val feedbackAction = Action(
            name = ActionNames.SESSION_POST,
            payload = buildJsonObject {
                put("session", sessionId)
                put("senderId", this@CommandBotFeature.name)
                put("message", formattedMessage)
            }
        )
        store.deferredDispatch(this.name, feedbackAction)
    }
}