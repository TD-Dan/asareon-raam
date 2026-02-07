package app.auf.feature.commandbot

import androidx.compose.runtime.Composable
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
 * A stateful agent that observes all session transcripts for command directives
 * (`auf_` code blocks) and dispatches them as universal Actions, making the application
 * universally scriptable. For actions marked as requiring approval, it stages the
 * request and presents an approval card to the user before dispatching.
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
 * - **CAG-006 (Approval Gate)**: Actions in [ExposedActions.requiresApproval] are staged
 *   and require explicit user approval before dispatch.
 * - **CAG-007 (Auto-Fill)**: Payload fields declared in [ExposedActions.autoFillRules] are
 *   injected before dispatch (e.g., senderId = agentId for session.POST).
 *
 * ## Decoupling
 * This feature does NOT import any types from the session or agent packages. It reads
 * published payloads via raw JSON traversal, treating the published schema as a contract boundary.
 */
class CommandBotFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "commandbot"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Tracks known agent IDs, updated via the Proactive Broadcast pattern.
     * Used to distinguish agent-originated commands from human-originated commands
     * for enforcement of [ExposedActions] restrictions (CAG-004).
     */
    private val knownAgentIds = mutableSetOf<String>()

    /**
     * Tracks known agent names, keyed by agent ID.
     * Used for display in approval cards.
     */
    private val knownAgentNames = mutableMapOf<String, String>()

    // ========================================================================
    // Slice 2: ComposableProvider — renders approval cards via PartialView
    // ========================================================================

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = emptyMap()

        @Composable
        override fun PartialView(store: Store, viewKey: String, contextId: String) {
            when (viewKey) {
                "commandbot.approval" -> {
                    // contextId is the approvalId (set as senderId when the card entry is posted)
                    ApprovalCard(store, approvalId = contextId)
                }
            }
        }
    }

    // ========================================================================
    // Slice 2: Reducer — manages CommandBotState
    // ========================================================================

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentState = state as? CommandBotState ?: CommandBotState()

        return when (action.name) {
            ActionNames.COMMANDBOT_INTERNAL_STAGE_APPROVAL -> {
                val payload = action.payload ?: return currentState
                val approval = PendingApproval(
                    approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    cardMessageId = payload["cardMessageId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    requestingAgentId = payload["requestingAgentId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    requestingAgentName = payload["requestingAgentName"]?.jsonPrimitive?.contentOrNull ?: "Unknown Agent",
                    actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    payload = payload["payload"]?.jsonObject ?: buildJsonObject {},
                    dispatchOriginator = payload["dispatchOriginator"]?.jsonPrimitive?.contentOrNull ?: "agent",
                    requestedAt = platformDependencies.getSystemTimeMillis()
                )
                currentState.copy(
                    pendingApprovals = currentState.pendingApprovals + (approval.approvalId to approval)
                )
            }

            ActionNames.COMMANDBOT_INTERNAL_RESOLVE_APPROVAL -> {
                val payload = action.payload ?: return currentState
                val approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: return currentState
                val resolutionStr = payload["resolution"]?.jsonPrimitive?.contentOrNull ?: return currentState
                val resolution = try { Resolution.valueOf(resolutionStr) } catch (_: Exception) { return currentState }

                val pending = currentState.pendingApprovals[approvalId] ?: return currentState

                val resolved = ApprovalResolution(
                    approvalId = approvalId,
                    actionName = pending.actionName,
                    requestingAgentName = pending.requestingAgentName,
                    resolution = resolution,
                    resolvedAt = platformDependencies.getSystemTimeMillis()
                )

                currentState.copy(
                    pendingApprovals = currentState.pendingApprovals - approvalId,
                    resolvedApprovals = currentState.resolvedApprovals + (approvalId to resolved)
                )
            }

            // Clean up resolved approvals when their card message is deleted
            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> {
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return currentState
                val matchingApprovalId = currentState.resolvedApprovals.values
                    .firstOrNull { /* We don't have cardMessageId in resolved state, so skip for now */ false }
                    ?.approvalId
                // For now, resolved approvals are lightweight and don't need aggressive cleanup.
                // They'll be cleared on next app restart since CommandBotState is not persisted.
                currentState
            }

            else -> currentState
        }
    }

    // ========================================================================
    // Slice 3: onAction — core command processing + approval gate
    // ========================================================================

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            // --- Track known agents via Proactive Broadcast ---
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> {
                val namesMap = action.payload?.get("names")?.jsonObject ?: return
                knownAgentIds.clear()
                knownAgentIds.addAll(namesMap.keys)
                knownAgentNames.clear()
                namesMap.forEach { (id, nameElement) ->
                    knownAgentNames[id] = nameElement.jsonPrimitive.contentOrNull ?: id
                }
                platformDependencies.log(
                    LogLevel.DEBUG, name,
                    "Updated known agent IDs: ${knownAgentIds.joinToString(", ")}"
                )
            }
            ActionNames.AGENT_PUBLISH_AGENT_DELETED -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.content ?: return
                knownAgentIds.remove(agentId)
                knownAgentNames.remove(agentId)
            }

            // --- Approval Resolution ---
            ActionNames.COMMANDBOT_APPROVE -> {
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: return
                val commandBotState = newState as? CommandBotState ?: return
                // The reducer has already moved it from pending → resolved.
                // We need the original PendingApproval from PREVIOUS state to dispatch.
                val previousBotState = previousState as? CommandBotState
                val approval = previousBotState?.pendingApprovals?.get(approvalId)
                if (approval == null) {
                    platformDependencies.log(LogLevel.WARN, name, "APPROVE: No pending approval found for '$approvalId'.")
                    return
                }

                // Dispatch the staged action
                val stagedAction = Action(name = approval.actionName, payload = approval.payload)
                store.deferredDispatch(approval.dispatchOriginator, stagedAction)
                platformDependencies.log(
                    LogLevel.INFO, name,
                    "Approval '$approvalId' APPROVED. Dispatching '${approval.actionName}' on behalf of '${approval.requestingAgentId}'."
                )

                // Post feedback to the agent
                val responseSessionId = resolveAgentResponseSession(approval.requestingAgentId, store)
                if (responseSessionId != null) {
                    postFeedbackToSession(
                        responseSessionId,
                        "[COMMAND BOT] Your action '${approval.actionName}' was approved and dispatched.",
                        store
                    )
                }
            }

            ActionNames.COMMANDBOT_DENY -> {
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: return
                val previousBotState = previousState as? CommandBotState
                val approval = previousBotState?.pendingApprovals?.get(approvalId)
                if (approval == null) {
                    platformDependencies.log(LogLevel.WARN, name, "DENY: No pending approval found for '$approvalId'.")
                    return
                }

                platformDependencies.log(
                    LogLevel.INFO, name,
                    "Approval '$approvalId' DENIED. Action '${approval.actionName}' from '${approval.requestingAgentId}' will not be dispatched."
                )

                // Post denial feedback to the agent
                val responseSessionId = resolveAgentResponseSession(approval.requestingAgentId, store)
                if (responseSessionId != null) {
                    postFeedbackToSession(
                        responseSessionId,
                        "[COMMAND BOT] Your action '${approval.actionName}' was denied by the user.",
                        store
                    )
                }
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
                val contentBlocks = entry["content"]?.jsonArray ?: return

                contentBlocks.forEach { blockElement ->
                    val block = blockElement.jsonObject
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

    // ========================================================================
    // Command Processing Pipeline
    // ========================================================================

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
            var payloadJson = if (code.isNotBlank()) {
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
                    // Sandbox actions are never approval-gated (they're already sandboxed)
                    store.deferredDispatch("agent", Action(name = actionName, payload = sandboxedPayload))
                    platformDependencies.log(
                        LogLevel.INFO, name,
                        "Agent '$originalSenderId' dispatched sandboxed action '$actionName'."
                    )
                    return
                }

                // --- CAG-007: Auto-Fill ---
                val autoFill = ExposedActions.autoFillRules[actionName]
                if (autoFill != null) {
                    val mutablePayload = payloadJson.toMutableMap()
                    autoFill.forEach { (key, template) ->
                        val value = template.replace("{agentId}", originalSenderId)
                        mutablePayload[key] = JsonPrimitive(value)
                    }
                    payloadJson = JsonObject(mutablePayload)
                }

                // --- CAG-006: Approval Gate ---
                if (actionName in ExposedActions.requiresApproval) {
                    stageApproval(actionName, payloadJson, sessionId, originalSenderId, store)
                    return
                }

                // Exposed action without sandbox or approval — dispatch with agent originator
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

    // ========================================================================
    // Slice 3: Approval Staging
    // ========================================================================

    /**
     * Stages an approval request: posts an approval card to the session and
     * dispatches an internal action to record the pending approval in state.
     */
    private fun stageApproval(
        actionName: String,
        payload: JsonObject,
        sessionId: String,
        agentId: String,
        store: Store
    ) {
        val approvalId = "approval-${platformDependencies.generateUUID()}"
        val cardMessageId = platformDependencies.generateUUID()
        val agentName = knownAgentNames[agentId] ?: agentId

        // 1. Post the approval card as a PartialView entry in the session
        val cardMetadata = buildJsonObject {
            put("render_as_partial", true)
            put("is_transient", true)
            put("partial_view_feature", "commandbot")
            put("partial_view_key", "commandbot.approval")
        }

        store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", sessionId)
            put("senderId", approvalId) // senderId doubles as the contextId for PartialView
            put("messageId", cardMessageId)
            put("metadata", cardMetadata)
            put("doNotClear", true)
        }))

        // 2. Stage the approval in CommandBotState
        store.deferredDispatch(this.name, Action(ActionNames.COMMANDBOT_INTERNAL_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", approvalId)
            put("sessionId", sessionId)
            put("cardMessageId", cardMessageId)
            put("requestingAgentId", agentId)
            put("requestingAgentName", agentName)
            put("actionName", actionName)
            put("payload", payload)
            put("dispatchOriginator", "agent")
        }))

        platformDependencies.log(
            LogLevel.INFO, name,
            "Staged approval '$approvalId' for agent '$agentId' action '$actionName'. Awaiting user decision."
        )
    }

    // ========================================================================
    // Slice 6: Response Routing
    // ========================================================================

    /**
     * Resolves the session ID where responses/feedback should be posted for a given agent.
     * Uses raw JSON traversal of agent state to avoid importing agent types (P-ARCH-002).
     *
     * Priority: privateSessionId → first subscribedSessionId → null
     */
    private fun resolveAgentResponseSession(agentId: String, store: Store): String? {
        try {
            val agentStateJson = store.state.value.featureStates["agent"]
            // FeatureState is serializable — we traverse its JSON representation
            // via the store's raw JSON access pattern (same as knownAgentIds caching).
            val agentStateObj = agentStateJson as? kotlinx.serialization.json.JsonObject
                ?: return null

            // Navigate: agentState.agents[agentId].privateSessionId
            val agents = agentStateObj["agents"]?.jsonObject ?: return null
            val agent = agents[agentId]?.jsonObject ?: return null

            val privateSessionId = agent["privateSessionId"]?.jsonPrimitive?.contentOrNull
            if (privateSessionId != null) return privateSessionId

            // Fallback: first subscribed session
            val subscribedSessions = agent["subscribedSessionIds"]?.jsonArray
            return subscribedSessions?.firstOrNull()?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, name, "Failed to resolve response session for agent '$agentId': ${e.message}")
            return null
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    /**
     * Applies workspace sandboxing to an agent's action payload.
     */
    private fun applySandboxRewrite(
        payload: JsonObject,
        rule: ExposedActions.SandboxRule,
        agentId: String
    ): JsonObject {
        val mutablePayload = payload.toMutableMap()

        val originalSubpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
        val prefix = rule.subpathPrefixTemplate.replace("{agentId}", agentId)
        val sandboxedSubpath = if (originalSubpath.isNotBlank()) "$prefix/$originalSubpath" else prefix
        mutablePayload["subpath"] = JsonPrimitive(sandboxedSubpath)

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