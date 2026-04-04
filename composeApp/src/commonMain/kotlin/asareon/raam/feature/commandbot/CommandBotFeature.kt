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
 * (`raam_` code blocks) and validates them against guardrails. For agent-originated
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
        displayColor = app.auf.ui.components.colorToHex(app.auf.ui.tertiaryDark),
        displayIcon = "terminal"
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        /** TTL for pending result entries: 5 minutes. */
        const val PENDING_RESULT_TTL_MS = 30 * 1000L
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
                    val approvalId = context as? String ?: run {
                        platformDependencies.log(LogLevel.WARN, identity.handle,
                            "PartialView: Invalid or missing context for approval card (partId='$partId', context=$context).")
                        return
                    }
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
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing payload. Approval not staged.")
                    return currentState
                }
                val approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing 'approvalId'. Approval not staged.")
                    return currentState
                }
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing 'sessionId' for approvalId='$approvalId'. Approval not staged.")
                    return currentState
                }
                val cardMessageId = payload["cardMessageId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing 'cardMessageId' for approvalId='$approvalId'. Approval not staged.")
                    return currentState
                }
                val requestingAgentId = payload["requestingAgentId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing 'requestingAgentId' for approvalId='$approvalId'. Approval not staged.")
                    return currentState
                }
                val actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "STAGE_APPROVAL reducer: Missing 'actionName' for approvalId='$approvalId'. Approval not staged.")
                    return currentState
                }
                val approval = PendingApproval(
                    approvalId = approvalId,
                    sessionId = sessionId,
                    cardMessageId = cardMessageId,
                    requestingAgentId = requestingAgentId,
                    requestingAgentName = payload["requestingAgentName"]?.jsonPrimitive?.contentOrNull ?: "Unknown Agent",
                    actionName = actionName,
                    payload = payload["payload"]?.jsonObject ?: buildJsonObject {},
                    requestedAt = platformDependencies.currentTimeMillis()
                )
                currentState.copy(
                    pendingApprovals = currentState.pendingApprovals + (approval.approvalId to approval)
                )
            }

            ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL -> {
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RESOLVE_APPROVAL reducer: Missing payload. Resolution dropped.")
                    return currentState
                }
                val approvalId = payload["approvalId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RESOLVE_APPROVAL reducer: Missing 'approvalId'. Resolution dropped.")
                    return currentState
                }
                val resolutionStr = payload["resolution"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RESOLVE_APPROVAL reducer: Missing 'resolution' for approvalId='$approvalId'. Resolution dropped.")
                    return currentState
                }
                val resolution = try { Resolution.valueOf(resolutionStr) } catch (_: Exception) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RESOLVE_APPROVAL reducer: Invalid resolution value '$resolutionStr' for approvalId='$approvalId'. Expected APPROVED or DENIED.")
                    return currentState
                }

                val pending = currentState.pendingApprovals[approvalId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RESOLVE_APPROVAL reducer: No pending approval found for approvalId='$approvalId'. May have already been resolved.")
                    return currentState
                }

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
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT reducer: Missing payload. Pending result not tracked.")
                    return currentState
                }
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT reducer: Missing 'correlationId'. Pending result not tracked.")
                    return currentState
                }
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT reducer: Missing 'sessionId' for correlationId='$correlationId'. Pending result not tracked.")
                    return currentState
                }
                val originatorId = payload["originatorId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT reducer: Missing 'originatorId' for correlationId='$correlationId'. Pending result not tracked.")
                    return currentState
                }
                val actionName = payload["actionName"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT reducer: Missing 'actionName' for correlationId='$correlationId'. Pending result not tracked.")
                    return currentState
                }
                val pendingResult = PendingResult(
                    correlationId = correlationId,
                    sessionId = sessionId,
                    originatorId = originatorId,
                    originatorName = payload["originatorName"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                    actionName = actionName,
                    createdAt = platformDependencies.currentTimeMillis()
                )
                currentState.copy(
                    pendingResults = currentState.pendingResults + (correlationId to pendingResult)
                )
            }

            ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CLEAR_PENDING_RESULT reducer: Missing 'correlationId'. Cannot clear pending result.")
                    return currentState
                }
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
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "APPROVE: Missing 'approvalId' in payload. Cannot process approval.")
                    return
                }
                // Read the pending approval from CURRENT state (reducer doesn't handle APPROVE).
                val commandBotState = newState as? CommandBotState ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "APPROVE: CommandBotState is null or wrong type. Feature state corrupted?")
                    return
                }
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
                val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DENY: Missing 'approvalId' in payload. Cannot process denial.")
                    return
                }
                // Read the pending approval from CURRENT state (reducer doesn't handle DENY).
                val commandBotState = newState as? CommandBotState ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "DENY: CommandBotState is null or wrong type. Feature state corrupted?")
                    return
                }
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
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_MESSAGE_POSTED: Missing payload. Cannot scan for commands.")
                    return
                }
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_MESSAGE_POSTED: Missing 'sessionId'. Cannot scan for commands.")
                    return
                }
                val entry = payload["entry"]?.jsonObject ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_MESSAGE_POSTED: Missing 'entry' in payload for session '$sessionId'. Cannot scan for commands.")
                    return
                }
                val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_MESSAGE_POSTED: Missing 'senderId' in entry for session '$sessionId'. Cannot attribute commands.")
                    return
                }

                // Guardrail (CAG-001): Self-Reaction Prevention.
                if (senderId == identity.handle) return

                // Read pre-parsed content blocks directly from the JSON payload.
                // Messages without content blocks are normal (e.g., metadata-only entries).
                val contentBlocks = entry["content"]?.jsonArray ?: return

                contentBlocks.forEach { blockElement ->
                    val block = blockElement.jsonObject
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!type.contains("CodeBlock")) return@forEach

                    val language = block["language"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!language.startsWith(Version.APP_TOOL_PREFIX)) return@forEach

                    val code = block["code"]?.jsonPrimitive?.contentOrNull ?: ""

                    processCommandBlock(language, code, sessionId, senderId, store)
                }
            }

            // --- TTL Scheduling for Pending Results ---
            ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REGISTER_PENDING_RESULT side-effect: Missing 'correlationId'. TTL cleanup not scheduled.")
                    return
                }
                // Schedule TTL cleanup after 5 minutes.
                store.scheduleDelayed(PENDING_RESULT_TTL_MS, identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject { put("correlationId", correlationId) }
                ))
            }

            // --- TTL Timeout Feedback ---
            // When a pending result is cleared, check if it expired (wasn't consumed by a
            // matching ACTION_RESULT). If so, post timeout feedback to the originating session
            // so the agent knows its command was not handled.
            ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CLEAR_PENDING_RESULT side-effect: Missing 'correlationId'.")
                    return
                }

                // If the clear was triggered by a successful ACTION_RESULT or PERMISSION_DENIED
                // match, reason="matched" — no timeout feedback needed.
                val reason = action.payload?.get("reason")?.jsonPrimitive?.contentOrNull
                if (reason == "matched") return

                // If the entry existed in previousState, it means the reducer just removed it
                // due to TTL expiry. Post timeout feedback so the agent knows its command was
                // not handled.
                val prevState = previousState as? CommandBotState
                val expiredResult = prevState?.pendingResults?.get(correlationId)
                if (expiredResult != null) {
                    val feedbackMessage = "TIMEOUT ⏱ ${expiredResult.actionName} — " +
                            "No response received within ${PENDING_RESULT_TTL_MS / 1000}s. " +
                            "The command may not have been handled by any feature."
                    postFeedbackToSession(expiredResult.sessionId, feedbackMessage, store)
                    platformDependencies.log(
                        LogLevel.WARN, identity.handle,
                        "Pending result EXPIRED for '${expiredResult.actionName}' " +
                                "(correlationId=$correlationId, originator=${expiredResult.originatorId}). " +
                                "Timeout feedback posted to session '${expiredResult.sessionId}'."
                    )
                }
            }

            // --- Data Delivery from Core/Agent ---
            ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION -> {
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELIVER_TO_SESSION: Missing payload. Cannot deliver message.")
                    return
                }
                val sessionId = payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELIVER_TO_SESSION: Missing 'sessionId'. Cannot deliver message.")
                    return
                }
                val message = payload["message"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELIVER_TO_SESSION: Missing 'message' for session '$sessionId'. Nothing to deliver.")
                    return
                }
                postRawToSession(sessionId, message, store)
            }

            // --- Permission Denial Feedback ---
            ActionRegistry.Names.CORE_PERMISSION_DENIED -> {
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PERMISSION_DENIED: Missing payload. Cannot provide denial feedback.")
                    return
                }
                val blockedAction = payload["blockedAction"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PERMISSION_DENIED: Missing 'blockedAction'. Cannot provide denial feedback.")
                    return
                }
                val originatorHandle = payload["originatorHandle"]?.jsonPrimitive?.contentOrNull ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PERMISSION_DENIED: Missing 'originatorHandle' for blockedAction='$blockedAction'. Cannot match to pending result.")
                    return
                }
                val missingPermissions = payload["missingPermissions"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                val commandBotState = newState as? CommandBotState ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PERMISSION_DENIED: CommandBotState unavailable. Cannot match to pending result for blockedAction='$blockedAction'.")
                    return
                }

                // Match the denied action against pending results by originator + action name
                val matchingEntry = commandBotState.pendingResults.entries.find { (_, pending) ->
                    pending.actionName == blockedAction && pending.originatorId == originatorHandle
                } ?: run {
                    platformDependencies.log(LogLevel.DEBUG, identity.handle,
                        "PERMISSION_DENIED: No matching pending result for blockedAction='$blockedAction', originator='$originatorHandle'. " +
                                "Denial may have originated from a non-CommandBot dispatch path.")
                    return
                }

                val pendingResult = matchingEntry.value
                val permList = missingPermissions.joinToString(", ")
                val feedbackMessage = "ERROR ✗ ${pendingResult.actionName} — Permission denied for '${pendingResult.originatorName}'. Missing permission: $permList"

                postFeedbackToSession(pendingResult.sessionId, feedbackMessage, store)

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject {
                        put("correlationId", matchingEntry.key)
                        put("reason", "matched")
                    }
                ))

                platformDependencies.log(
                    LogLevel.INFO, identity.handle,
                    "PERMISSION_DENIED matched for '${pendingResult.actionName}' (originator=$originatorHandle). Feedback posted to session '${pendingResult.sessionId}'."
                )
            }

            // --- ACTION_RESULT Interception ---
            else -> {
                if (!action.name.endsWith(".ACTION_RESULT")) return
                val payload = action.payload ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_RESULT (${action.name}): Missing payload. Cannot match to pending result.")
                    return
                }
                val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return // No correlationId = not a CommandBot-originated action; silent return is correct.
                val commandBotState = newState as? CommandBotState ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "ACTION_RESULT (${action.name}): CommandBotState unavailable. Cannot match correlationId='$correlationId'.")
                    return
                }
                val pendingResult = commandBotState.pendingResults[correlationId] ?: return // Not our correlationId — another consumer's result; silent return is correct.

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

                // Clear with reason="matched" so the TTL side-effect handler knows
                // this was consumed by a real ACTION_RESULT, not an expiry timeout.
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
                    buildJsonObject {
                        put("correlationId", correlationId)
                        put("reason", "matched")
                    }
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
        val actionName = language.removePrefix(Version.APP_TOOL_PREFIX)
        val isAgent = isAgent(originalSenderId, store)

        // --- Descriptor lookup & hidden guard ---
        // Reject unknown and hidden actions before any further processing.
        // Hidden actions are public (dispatchable by features) but not user/agent-invocable.
        val descriptor = ActionRegistry.byActionName[actionName]
        if (descriptor == null) {
            postFeedbackToSession(
                sessionId,
                "[COMMAND BOT ERROR] Unknown action: '$actionName'",
                store
            )
            return
        }
        if (descriptor.hidden) {
            platformDependencies.log(
                LogLevel.WARN, identity.handle,
                "Blocked hidden action '$actionName' from '$originalSenderId'. Hidden actions are not user/agent-invocable."
            )
            // Hide the action completely so existing actions cannot be discovered via probing
            postFeedbackToSession(
                sessionId,
                "[COMMAND BOT ERROR] Unknown action: '$actionName'",
                store
            )
            return
        }

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