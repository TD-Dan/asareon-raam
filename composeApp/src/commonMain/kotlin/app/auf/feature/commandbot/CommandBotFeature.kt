package app.auf.feature.commandbot

import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
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
 * (`auf_` code blocks) and validates them against guardrails. For agent-originated
 * commands, it publishes [ActionRegistry.Names.COMMANDBOT_ACTION_CREATED] so that
 * the owning feature (e.g., agent) can apply its own sandboxing and dispatch.
 * For human-originated commands, it dispatches directly.
 *
 * ## Guardrails
 * - **CAG-001 (Self-Reaction Prevention)**: Ignores messages from itself.
 * - **CAG-002 (Causality Tracking)**: Attributes dispatched actions to the original sender.
 * - **CAG-003 (Robust Error Handling)**: Posts parse errors back to the session.
 * - **CAG-004 (Agent Action Restriction)**: Agent-originated commands are restricted to the
 *   build-time [ExposedActions.allowedActionNames] allowlist. Human users are unrestricted.
 * - **CAG-006 (Approval Gate)**: Actions in [ExposedActions.requiresApproval] are staged
 *   and require explicit user approval before dispatch.
 * - **CAG-007 (Auto-Fill)**: Payload fields declared in [ExposedActions.autoFillRules] are
 *   injected before publishing (e.g., senderId = agentId for session.POST).
 *
 * ## Decoupling
 * This feature does NOT import any types from the session or agent packages. It reads
 * published payloads via raw JSON traversal, treating the published schema as a contract boundary.
 * It does NOT dispatch domain actions on behalf of agents — it only validates and broadcasts
 * via ACTION_CREATED. Sandbox rewrites are the responsibility of the owning feature.
 */
class CommandBotFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "commandbot", localHandle = "commandbot", name="CommandBot")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Tracks known agent IDs, updated via the Proactive Broadcast pattern.
     * Used to distinguish agent-originated commands from human-originated commands
     * for enforcement of [ExposedActions] restrictions (CAG-004).
     */
    private val knownAgentIds = mutableSetOf<String>()

    /**
     * Tracks known agent names, keyed by agent ID.
     * Used for display in approval cards and ACTION_CREATED attribution.
     */
    private val knownAgentNames = mutableMapOf<String, String>()

    // ========================================================================
    // Slice 2: ComposableProvider — renders approval cards via PartialView
    // ========================================================================

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = emptyMap()

        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            when (partId) {
                "commandbot.approval" -> {
                    // context is the approvalId (set as senderId when the card entry is posted)
                    val approvalId = context as? String ?: return
                    ApprovalCard(store, approvalId = approvalId)
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
            ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL -> {
                val payload = action.payload ?: return currentState
                val approval = PendingApproval(
                    approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    cardMessageId = payload["cardMessageId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    requestingAgentId = payload["requestingAgentId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    requestingAgentName = payload["requestingAgentName"]?.jsonPrimitive?.contentOrNull ?: "Unknown Agent",
                    actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    payload = payload["payload"]?.jsonObject ?: buildJsonObject {},
                    requestedAt = platformDependencies.currentTimeMillis()
                )
                currentState.copy(
                    pendingApprovals = currentState.pendingApprovals + (approval.approvalId to approval)
                )
            }

            ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL -> {
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
                    resolvedAt = platformDependencies.currentTimeMillis(),
                    sessionId = pending.sessionId,
                    cardMessageId = pending.cardMessageId
                )

                currentState.copy(
                    pendingApprovals = currentState.pendingApprovals - approvalId,
                    resolvedApprovals = currentState.resolvedApprovals + (approvalId to resolved)
                )
            }

            // Clean up resolved approvals when their card message is deleted
            ActionRegistry.Names.SESSION_MESSAGE_DELETED -> {
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return currentState
                val matchingApprovalId = currentState.resolvedApprovals.values
                    .firstOrNull { it.cardMessageId == messageId }
                    ?.approvalId
                if (matchingApprovalId != null) {
                    currentState.copy(resolvedApprovals = currentState.resolvedApprovals - matchingApprovalId)
                } else {
                    currentState
                }
            }

            else -> currentState
        }
    }

    // ========================================================================
    // Slice 3: onAction — core command processing + approval gate
    // ========================================================================

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            // --- Track known agents via Proactive Broadcast ---
            ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED -> {
                val namesMap = action.payload?.get("names")?.jsonObject ?: return
                knownAgentIds.clear()
                knownAgentIds.addAll(namesMap.keys)
                knownAgentNames.clear()
                namesMap.forEach { (id, nameElement) ->
                    knownAgentNames[id] = nameElement.jsonPrimitive.contentOrNull ?: id
                }
                platformDependencies.log(
                    LogLevel.DEBUG, identity.handle,
                    "Updated known agent IDs: ${knownAgentIds.joinToString(", ")}"
                )
            }
            ActionRegistry.Names.AGENT_AGENT_DELETED -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.content ?: return
                knownAgentIds.remove(agentId)
                knownAgentNames.remove(agentId)
            }

            // --- Approval Resolution ---
            ActionRegistry.Names.COMMANDBOT_APPROVE -> {
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: return
                // Read the pending approval from CURRENT state (reducer doesn't handle APPROVE).
                val commandBotState = newState as? CommandBotState ?: return
                val approval = commandBotState.pendingApprovals[approvalId]
                if (approval == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "APPROVE: No pending approval found for '$approvalId'.")
                    return
                }

                // 1. Dispatch the internal state transition: pending → resolved
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
                    put("approvalId", approvalId)
                    put("resolution", Resolution.APPROVED.name)
                }))

                // 2. Publish ACTION_CREATED — the owning feature will dispatch the domain action
                publishActionCreated(
                    actionName = approval.actionName,
                    payload = approval.payload,
                    sessionId = approval.sessionId,
                    originatorId = approval.requestingAgentId,
                    store = store
                )
                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "Approval '$approvalId' APPROVED. Publishing ACTION_CREATED for '${approval.actionName}' on behalf of '${approval.requestingAgentId}'."
                )

                // 3. Make the card clearable now that it's resolved (flip doNotClear → false)
                makeCardClearable(approval.sessionId, approval.cardMessageId, store)
            }

            ActionRegistry.Names.COMMANDBOT_DENY -> {
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: return
                // Read the pending approval from CURRENT state (reducer doesn't handle DENY).
                val commandBotState = newState as? CommandBotState ?: return
                val approval = commandBotState.pendingApprovals[approvalId]
                if (approval == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DENY: No pending approval found for '$approvalId'.")
                    return
                }

                // 1. Dispatch the internal state transition: pending → resolved
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
                    put("approvalId", approvalId)
                    put("resolution", Resolution.DENIED.name)
                }))

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "Approval '$approvalId' DENIED. Action '${approval.actionName}' from '${approval.requestingAgentId}' will not be dispatched."
                )

                // 2. Make the card clearable now that it's resolved
                makeCardClearable(approval.sessionId, approval.cardMessageId, store)

                // 3. Post denial feedback to the originating session
                postFeedbackToSession(
                    approval.sessionId,
                    "[COMMAND BOT] Action '${approval.actionName}' requested by '${approval.requestingAgentName}' was denied.",
                    store
                )
            }

            // --- Core Command Processing ---
            ActionRegistry.Names.SESSION_MESSAGE_POSTED -> {
                val payload = action.payload ?: return
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val entry = payload["entry"]?.jsonObject ?: return
                val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull ?: return

                // Guardrail (CAG-001): Self-Reaction Prevention.
                if (senderId == identity.handle) return

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
                        LogLevel.WARN, identity.handle,
                        "Agent '$originalSenderId' attempted disallowed action '$actionName'. Blocked."
                    )
                    postFeedbackToSession(
                        sessionId,
                        "[COMMAND BOT] Action '$actionName' is not available to agents.",
                        store
                    )
                    return
                }

                // --- CAG-007: Auto-Fill ---
                val autoFill = ExposedActions.autoFillRules[actionName]
                if (autoFill != null) {
                    val mutablePayload = payloadJson.toMutableMap()
                    autoFill.forEach { (key, template) ->
                        val value = template
                            .replace("{agentId}", originalSenderId)
                            .replace("{sessionId}", sessionId)
                        mutablePayload[key] = JsonPrimitive(value)
                    }
                    payloadJson = JsonObject(mutablePayload)
                }

                // --- CAG-006: Approval Gate ---
                if (actionName in ExposedActions.requiresApproval) {
                    stageApproval(actionName, payloadJson, sessionId, originalSenderId, store)
                    return
                }

                // --- Publish validated action for the owning feature to dispatch ---
                publishActionCreated(
                    actionName = actionName,
                    payload = payloadJson,
                    sessionId = sessionId,
                    originatorId = originalSenderId,
                    store = store
                )
                return
            }

            // === HUMAN USER PATH (unchanged) ===
            // Guardrail (CAG-002): Causality Tracking. Dispatched on BEHALF of the original sender.
            val commandAction = Action(name = actionName, payload = payloadJson)
            store.deferredDispatch(originalSenderId, commandAction)

        } catch (e: Exception) {
            // Guardrail (CAG-003): Robust Error Handling with feedback loop.
            platformDependencies.log(
                LogLevel.ERROR, identity.handle,
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
    // ACTION_CREATED Publishing
    // ========================================================================

    /**
     * Publishes a validated command for the owning feature to pick up and dispatch.
     * CommandBot does NOT dispatch domain actions itself — it only validates and broadcasts.
     */
    private fun publishActionCreated(
        actionName: String,
        payload: JsonObject,
        sessionId: String,
        originatorId: String,
        store: Store
    ) {
        val correlationId = platformDependencies.generateUUID()
        val agentName = knownAgentNames[originatorId] ?: originatorId

        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED,
            buildJsonObject {
                put("correlationId", correlationId)
                put("originatorId", originatorId)
                put("originatorName", agentName)
                put("sessionId", sessionId)
                put("actionName", actionName)
                put("actionPayload", payload)
            }
        ))

        platformDependencies.log(
            LogLevel.INFO, identity.handle,
            "Published ACTION_CREATED for '$actionName' from '$originatorId' (correlationId=$correlationId)."
        )
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

        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", sessionId)
            put("senderId", approvalId) // senderId doubles as the contextId for PartialView
            put("messageId", cardMessageId)
            put("metadata", cardMetadata)
            put("doNotClear", true)
        }))

        // 2. Stage the approval in CommandBotState
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", approvalId)
            put("sessionId", sessionId)
            put("cardMessageId", cardMessageId)
            put("requestingAgentId", agentId)
            put("requestingAgentName", agentName)
            put("actionName", actionName)
            put("payload", payload)
        }))

        platformDependencies.log(
            LogLevel.INFO, identity.handle,
            "Staged approval '$approvalId' for agent '$agentId' action '$actionName'. Awaiting user decision."
        )
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    /**
     * After approval resolution, flips the card's `doNotClear` to false so that
     * `SESSION_CLEAR` can remove the resolved card.
     */
    private fun makeCardClearable(sessionId: String, cardMessageId: String, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_UPDATE_MESSAGE, buildJsonObject {
            put("session", sessionId)
            put("messageId", cardMessageId)
            put("doNotClear", false)
        }))
    }

    /**
     * Posts a feedback message to the originating session with CommandBot as the sender.
     */
    private fun postFeedbackToSession(sessionId: String, message: String, store: Store) {
        val formattedMessage = "```text\n$message\n```"
        val feedbackAction = Action(
            name = ActionRegistry.Names.SESSION_POST,
            payload = buildJsonObject {
                put("session", sessionId)
                put("senderId", identity.handle)
                put("message", formattedMessage)
            }
        )
        store.deferredDispatch(identity.handle, feedbackAction)
    }
}