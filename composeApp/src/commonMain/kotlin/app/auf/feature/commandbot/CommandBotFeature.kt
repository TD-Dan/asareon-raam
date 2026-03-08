package app.auf.feature.commandbot

import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
 * - **CAG-004 (Agent Action Restriction)**: Agent-originated commands are validated against
 *   the originator's effective permissions (Phase 2.B). The Store permission guard is
 *   authoritative; this pre-check provides early rejection with session-visible feedback.
 * - **CAG-006 (Approval Gate)**: [DEFERRED] Previously driven by `agentRequiresApproval`.
 *   Will be re-activated by the ASK permission level system. Approval infrastructure
 *   (staging, cards, PendingApproval) is preserved for that integration.
 * - **CAG-007 (Auto-Fill)**: Payload fields declared in [ActionRegistry.agentAutoFillRules] are
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
    override val identity: Identity = Identity(
        uuid = null,
        handle = "commandbot",
        localHandle = "commandbot",
        name = "CommandBot",
        // Tied to the actual tertiary theme constant so it tracks palette changes.
        displayColor = app.auf.ui.components.colorToHex(app.auf.ui.tertiaryDark)
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        /** TTL for pending result entries: 5 minutes. */
        const val PENDING_RESULT_TTL_MS = 5 * 60 * 1000L
    }

    /**
     * Resolves whether a senderId belongs to a known agent by consulting the
     * identity registry. An identity is an agent if its parentHandle is "agent".
     *
     * Phase 4: Replaces the former knownAgentIds mutable set that was maintained
     * via the Proactive Broadcast pattern (AGENT_NAMES_UPDATED / AGENT_DELETED).
     * The identity registry is now the single source of truth.
     */
    private fun isAgent(senderId: String, store: Store): Boolean {
        val identity = store.state.value.identityRegistry[senderId] ?: return false
        return identity.parentHandle == "agent"
    }

    /**
     * Resolves a display name for a senderId by consulting the identity registry.
     * Falls back to the raw senderId if not found.
     */
    private fun resolveAgentName(senderId: String, store: Store): String {
        return store.state.value.identityRegistry[senderId]?.name ?: senderId
    }

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

            // --- Pending Result Tracking ---
            ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT -> {
                val payload = action.payload ?: return currentState
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return currentState
                val pendingResult = PendingResult(
                    correlationId = correlationId,
                    sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    originatorId = payload["originatorId"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    originatorName = payload["originatorName"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                    actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: return currentState,
                    createdAt = platformDependencies.currentTimeMillis()
                )
                currentState.copy(
                    pendingResults = currentState.pendingResults + (correlationId to pendingResult)
                )
            }

            ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: return currentState
                currentState.copy(
                    pendingResults = currentState.pendingResults - correlationId
                )
            }

            else -> currentState
        }
    }

    // ========================================================================
    // Slice 3: onAction — core command processing + approval gate
    // ========================================================================

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
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

            // --- TTL Scheduling for Pending Results ---
            ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: return
                // Schedule TTL cleanup after 5 minutes.
                store.scheduleDelayed(PENDING_RESULT_TTL_MS, identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject { put("correlationId", correlationId) }
                ))
            }

            // --- Data Delivery from Core/Agent ---
            ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION -> {
                val payload = action.payload ?: return
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val message = payload["message"]?.jsonPrimitive?.contentOrNull ?: return
                postRawToSession(sessionId, message, store)
            }

            // --- Permission Denial Feedback ---
            ActionRegistry.Names.CORE_PERMISSION_DENIED -> {
                val payload = action.payload ?: return
                val blockedAction = payload["blockedAction"]?.jsonPrimitive?.contentOrNull ?: return
                val originatorHandle = payload["originatorHandle"]?.jsonPrimitive?.contentOrNull ?: return
                val missingPermissions = payload["missingPermissions"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                val commandBotState = newState as? CommandBotState ?: return

                // Match the denied action against pending results by originator + action name
                val matchingEntry = commandBotState.pendingResults.entries.find { (_, pending) ->
                    pending.actionName == blockedAction && pending.originatorId == originatorHandle
                } ?: return

                val pendingResult = matchingEntry.value
                val permList = missingPermissions.joinToString(", ")
                val feedbackMessage = "ERROR ✗ ${pendingResult.actionName} — Permission denied for '${pendingResult.originatorName}'. Missing permission: $permList"

                postFeedbackToSession(pendingResult.sessionId, feedbackMessage, store)

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject { put("correlationId", matchingEntry.key) }
                ))

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "PERMISSION_DENIED matched for '${pendingResult.actionName}' (originator=$originatorHandle). Feedback posted to session '${pendingResult.sessionId}'."
                )
            }

            // --- ACTION_RESULT Interception ---
            else -> {
                if (!action.name.endsWith(".ACTION_RESULT")) return
                val payload = action.payload ?: return
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
                val commandBotState = newState as? CommandBotState ?: return
                val pendingResult = commandBotState.pendingResults[correlationId] ?: return

                // Security: validate source feature matches the feature that owns the action
                val expectedFeature = pendingResult.actionName.substringBefore('.')
                val sourceFeature = action.name.substringBefore('.')
                if (sourceFeature != expectedFeature) {
                    platformDependencies.log(
                        LogLevel.WARN, identity.handle,
                        "ACTION_RESULT from '$sourceFeature' but expected '$expectedFeature' for correlationId '$correlationId'. Ignoring."
                    )
                    return
                }

                // Security: validate requestAction matches
                val requestAction = payload["requestAction"]?.jsonPrimitive?.contentOrNull
                if (requestAction != null && requestAction != pendingResult.actionName) {
                    platformDependencies.log(
                        LogLevel.WARN, identity.handle,
                        "ACTION_RESULT requestAction '$requestAction' doesn't match expected '${pendingResult.actionName}'. Ignoring."
                    )
                    return
                }

                val success = payload["success"]?.jsonPrimitive?.boolean ?: false
                val summary = payload["summary"]?.jsonPrimitive?.contentOrNull
                val error = payload["error"]?.jsonPrimitive?.contentOrNull

                val icon = if (success) "OK ✓" else "ERROR ✗"
                val detail = when {
                    success && summary != null -> summary
                    !success && error != null -> error
                    success -> "completed."
                    else -> "failed."
                }
                val feedbackMessage = "$icon ${pendingResult.actionName} — $detail"

                postFeedbackToSession(pendingResult.sessionId, feedbackMessage, store)

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject { put("correlationId", correlationId) }
                ))

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "ACTION_RESULT matched for '${pendingResult.actionName}' (correlationId=$correlationId, success=$success)."
                )
            }
        }
    }

    // ========================================================================
    // Command Processing Pipeline
    // ========================================================================

    /**
     * Phase 2.B: Pre-dispatch permission check for agent-originated commands.
     *
     * Replaces the old `agentAllowedNames` allowlist (CAG-004). The Store's Step 2b
     * guard is authoritative, but this pre-check provides:
     *   1. Early rejection with user-facing feedback in the session
     *   2. Better error messages (names the missing permissions)
     *
     * @return null if all permissions are satisfied, or a user-facing denial message
     */
    private fun checkAgentPermissions(
        senderIdentityHandle: String,
        actionName: String,
        store: Store
    ): String? {
        val descriptor = ActionRegistry.byActionName[actionName]
            ?: return "Unknown action: '$actionName'"

        val requiredPerms = descriptor.requiredPermissions
        // Legacy actions without required_permissions declared — allow through
        // (the Store guard will also allow these since the field is null)
        if (requiredPerms == null || requiredPerms.isEmpty()) return null

        val identity = store.state.value.identityRegistry[senderIdentityHandle]
            ?: return "Identity '$senderIdentityHandle' not found in registry."

        // Feature identities (uuid == null) are trusted
        if (identity.uuid == null) return null

        val effective = store.resolveEffectivePermissions(identity)
        val missing = requiredPerms.filter { permKey ->
            val level = effective[permKey]?.level ?: PermissionLevel.NO
            level != PermissionLevel.YES
        }

        return if (missing.isEmpty()) null
        else "Permission denied: '$senderIdentityHandle' cannot execute '$actionName'. " +
                "Missing: ${missing.joinToString(", ")}. " +
                "An administrator can grant these in Identity Manager → Permissions."
    }

    private fun processCommandBlock(
        language: String,
        code: String,
        sessionId: String,
        originalSenderId: String,
        store: Store
    ) {
        val actionName = language.removePrefix("auf_")
        val isAgent = isAgent(originalSenderId, store)

        try {
            var payloadJson = if (code.isNotBlank()) {
                json.parseToJsonElement(code) as JsonObject
            } else {
                buildJsonObject {}
            }

            // === AGENT ENFORCEMENT ===
            if (isAgent) {
                // Guardrail (CAG-004): Permission-Based Action Restriction (Phase 2.B)
                // Replaces the former agentAllowedNames allowlist. The Store guard is
                // authoritative; this pre-check gives early feedback in the session.
                val denialMessage = checkAgentPermissions(originalSenderId, actionName, store)
                if (denialMessage != null) {
                    platformDependencies.log(
                        LogLevel.WARN, identity.handle,
                        "Agent '$originalSenderId' denied action '$actionName': $denialMessage"
                    )
                    postFeedbackToSession(
                        sessionId,
                        "[COMMAND BOT] $denialMessage",
                        store
                    )
                    return
                }

                // --- CAG-007: Auto-Fill ---
                val autoFill = ActionRegistry.agentAutoFillRules[actionName]
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
                // DEFERRED: Previously driven by ActionRegistry.agentRequiresApproval
                // (removed in Phase 2.B). Will be re-activated when the ASK permission
                // level system is implemented. The approval infrastructure (stageApproval,
                // PendingApproval, ApprovalCard) is preserved for that integration.
                // When ASK is live, the check will be:
                //   val askPerms = requiredPerms.filter { effective[it]?.level == PermissionLevel.ASK }
                //   if (askPerms.isNotEmpty()) { stageApproval(...); return }
            }

            publishActionCreated(
                actionName = actionName,
                payload = payloadJson,
                sessionId = sessionId,
                originatorId = originalSenderId,
                store = store
            )

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
        val agentName = resolveAgentName(originatorId, store)

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

        // Register the pending result so we can match the ACTION_RESULT broadcast later.
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT,
            buildJsonObject {
                put("correlationId", correlationId)
                put("sessionId", sessionId)
                put("originatorId", originatorId)
                put("originatorName", agentName)
                put("actionName", actionName)
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
        val agentName = resolveAgentName(agentId, store)

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

    /**
     * Posts a pre-formatted message to a session without additional wrapping.
     * Used by DELIVER_TO_SESSION where the sender (Core/Agent) controls formatting.
     */
    private fun postRawToSession(sessionId: String, message: String, store: Store) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.SESSION_POST,
            payload = buildJsonObject {
                put("session", sessionId)
                put("senderId", identity.handle)
                put("message", message)
            }
        ))
    }
}