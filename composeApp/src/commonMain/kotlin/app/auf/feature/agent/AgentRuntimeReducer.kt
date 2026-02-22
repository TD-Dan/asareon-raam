package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.core.stringIsUUID
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * Pure, testable reducer logic for the ephemeral runtime state of the AgentRuntimeFeature.
 *
 * All agent IDs extracted from payloads are wrapped in [IdentityUUID].
 * Session IDs are [IdentityUUID] (immutable, rename-safe). Map lookups use typed keys.
 */
object AgentRuntimeReducer {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- Boundary helpers ----
    private fun JsonObject.agentUUID(field: String = "agentId"): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    fun reduce(
        state: AgentRuntimeState,
        action: Action,
        platformDependencies: PlatformDependencies
    ): AgentRuntimeState {
        return when (action.name) {
            // --- Internal State Setters ---
            ActionRegistry.Names.AGENT_SET_STATUS -> handleSetStatus(action, state, platformDependencies)

            ActionRegistry.Names.AGENT_SET_PROCESSING_STEP -> {
                val payload = action.payload ?: return state
                val agentId = payload.agentUUID() ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val step = payload["step"]?.jsonPrimitive?.contentOrNull
                val updatedStatus = currentStatus.copy(processingStep = step)
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<StageTurnContextPayload>(it) } ?: return state
                val currentStatus = state.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedTurnContext = payload.messages)
                state.copy(agentStatuses = state.agentStatuses + (payload.agentId to updatedStatus))
            }

            ActionRegistry.Names.AGENT_SET_HKG_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHkgContextPayload>(it) } ?: return state
                val currentStatus = state.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(transientHkgContext = payload.context)
                state.copy(agentStatuses = state.agentStatuses + (payload.agentId to updatedStatus))
            }

            ActionRegistry.Names.AGENT_SET_WORKSPACE_CONTEXT -> {
                val agentId = action.payload?.agentUUID() ?: return state
                val context = action.payload?.get("context")?.jsonPrimitive?.contentOrNull ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(transientWorkspaceContext = context)
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED -> {
                val agentId = action.payload?.agentUUID() ?: return state
                val startedAt = action.payload?.get("startedAt")?.jsonPrimitive?.longOrNull ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(contextGatheringStartedAt = startedAt)
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            // CONTEXT_GATHERING_TIMEOUT: No state change needed — pure side-effect action
            ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT -> state

            // Atomic commit of avatar position
            ActionRegistry.Names.AGENT_AVATAR_MOVED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<AvatarMovedPayload>(it) } ?: return state
                val currentSessionMap = state.agentAvatarCardIds[payload.agentId] ?: emptyMap()
                val newSessionMap = currentSessionMap + (payload.sessionId to payload.messageId)
                state.copy(agentAvatarCardIds = state.agentAvatarCardIds + (payload.agentId to newSessionMap))
            }

            // --- Turn Lifecycle ---
            ActionRegistry.Names.AGENT_INITIATE_TURN -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<InitiateTurnPayload>(it) } ?: return state
                val agentId = payload.agentId
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()

                // Guard: Don't interrupt an active turn
                if (currentStatus.status == AgentStatus.PROCESSING) return state

                val updatedStatus = currentStatus.copy(
                    processingFrontierMessageId = currentStatus.lastSeenMessageId,
                    turnMode = if (payload.preview) TurnMode.PREVIEW else TurnMode.DIRECT,
                    stagedTurnContext = null,
                    transientHkgContext = null,
                    transientWorkspaceContext = null,
                    contextGatheringStartedAt = null
                )
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            ActionRegistry.Names.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.agentUUID() ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedPreviewData = null, processingStep = null)
                state.copy(
                    agentStatuses = state.agentStatuses + (agentId to updatedStatus),
                    viewingContextForAgentId = null
                )
            }

            ActionRegistry.Names.AGENT_SET_PREVIEW_DATA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetPreviewDataPayload>(it) } ?: return state
                val agentId = payload.agentId
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val previewData = StagedPreviewData(payload.agnosticRequest, payload.rawRequestJson, payload.estimatedInputTokens)
                val updatedStatus = currentStatus.copy(stagedPreviewData = previewData)
                state.copy(
                    agentStatuses = state.agentStatuses + (agentId to updatedStatus),
                    viewingContextForAgentId = agentId
                )
            }

            // --- External Events ---
            ActionRegistry.Names.SESSION_MESSAGE_POSTED -> handleMessagePosted(action, state, platformDependencies)

            ActionRegistry.Names.SESSION_MESSAGE_DELETED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return state
                var newAvatarCards = state.agentAvatarCardIds
                state.agentAvatarCardIds.forEach { (agentId, sessionMap) ->
                    val sessionEntry = sessionMap.entries.find { it.value == payload.messageId }
                    if (sessionEntry != null) {
                        val newSessionMap = sessionMap - sessionEntry.key
                        newAvatarCards = newAvatarCards + (agentId to newSessionMap)
                    }
                }
                state.copy(agentAvatarCardIds = newAvatarCards)
            }

            ActionRegistry.Names.SESSION_SESSION_DELETED -> {
                // Prefer sessionUUID (immutable); fall back to sessionId (localHandle) for legacy compat
                val uuidStr = action.payload?.get("sessionUUID")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: return state
                if (!stringIsUUID(uuidStr)) {
                    platformDependencies.log(LogLevel.ERROR, "AgentRuntimeReducer",
                        "SESSION_SESSION_DELETED: expected UUID but got '$uuidStr'. Session feature may not be sending UUIDs yet.")
                    return state
                }
                val deletedSessionId = IdentityUUID(uuidStr)

                val agentsToUpdate = state.agents.values
                    .filter { it.subscribedSessionIds.contains(deletedSessionId) || it.outputSessionId == deletedSessionId }

                // Cleanup deleted session from avatar map
                var newAvatarCards = state.agentAvatarCardIds
                state.agentAvatarCardIds.forEach { (agentId, sessionMap) ->
                    if (sessionMap.containsKey(deletedSessionId)) {
                        newAvatarCards = newAvatarCards + (agentId to (sessionMap - deletedSessionId))
                    }
                }

                if (agentsToUpdate.isEmpty()) return state.copy(agentAvatarCardIds = newAvatarCards)

                val newAgents = state.agents.mapValues { (_, agent) ->
                    if (agentsToUpdate.any { it.identityUUID == agent.identityUUID }) {
                        agent.copy(
                            subscribedSessionIds = agent.subscribedSessionIds - deletedSessionId,
                            outputSessionId = if (agent.outputSessionId == deletedSessionId) null else agent.outputSessionId
                        )
                    } else {
                        agent
                    }
                }
                // Note: We set agentsToPersist so the Feature knows to save these changes
                state.copy(
                    agents = newAgents,
                    agentAvatarCardIds = newAvatarCards,
                    agentsToPersist = agentsToUpdate.map { it.identityUUID }.toSet()
                )
            }

            ActionRegistry.Names.GATEWAY_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { action.payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                state.copy(availableModels = decodedModels ?: emptyMap())
            }

            ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED -> {
                // New format: "sessions" array with { uuid, handle, localHandle, name } objects
                val sessionsArray = action.payload?.get("sessions")?.jsonArray
                if (sessionsArray != null) {
                    val newNames = mutableMapOf<IdentityUUID, String>()
                    sessionsArray.forEach { element ->
                        val obj = element.jsonObject
                        val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        if (!stringIsUUID(uuid)) {
                            platformDependencies.log(LogLevel.ERROR, "AgentRuntimeReducer",
                                "SESSION_NAMES_UPDATED: entry has non-UUID id '$uuid', skipping.")
                            return@forEach
                        }
                        newNames[IdentityUUID(uuid)] = name
                    }
                    state.copy(subscribableSessionNames = newNames)
                } else {
                    platformDependencies.log(LogLevel.WARN, "AgentRuntimeReducer",
                        "SESSION_NAMES_UPDATED: missing 'sessions' array. Legacy 'names' map format no longer supported.")
                    state
                }
            }

            ActionRegistry.Names.KNOWLEDGEGRAPH_AVAILABLE_PERSONAS_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<GraphNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) state.copy(knowledgeGraphNames = decoded.names) else state
            }

            ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVATIONS_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ReservedIdsPayload>(it) }
                if (payload != null) state.copy(hkgReservedIds = payload.reservedIds) else state
            }

            ActionRegistry.Names.CORE_IDENTITIES_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentitiesUpdatedPayload>(it) }
                if (payload != null) state.copy(userIdentities = payload.identities) else state
            }

            // --- Identity Registration Responses ---
            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY -> {
                val payload = action.payload ?: return state
                val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
                val uuid = payload.agentUUID("uuid") ?: return state
                if (!success) return state

                val approvedLocalHandle = payload["approvedLocalHandle"]?.jsonPrimitive?.contentOrNull ?: return state
                val handle = payload["handle"]?.jsonPrimitive?.contentOrNull ?: return state
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: return state
                val parentHandle = payload["parentHandle"]?.jsonPrimitive?.contentOrNull

                val agent = state.agents[uuid] ?: return state
                val updatedAgent = agent.copy(
                    identity = agent.identity.copy(
                        localHandle = approvedLocalHandle,
                        handle = handle,
                        name = name,
                        parentHandle = parentHandle
                    )
                )
                state.copy(agents = state.agents + (uuid to updatedAgent))
            }

            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY -> {
                val payload = action.payload ?: return state
                val success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) return state

                val uuid = payload.agentUUID("uuid") ?: return state
                val newLocalHandle = payload["newLocalHandle"]?.jsonPrimitive?.contentOrNull ?: return state
                val newHandle = payload["newHandle"]?.jsonPrimitive?.contentOrNull ?: return state
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: return state

                val agent = state.agents[uuid] ?: return state
                val updatedAgent = agent.copy(
                    identity = agent.identity.copy(
                        localHandle = newLocalHandle,
                        handle = newHandle,
                        name = name
                    )
                )
                state.copy(agents = state.agents + (uuid to updatedAgent))
            }

            // ================================================================
            // Pending Command Tracking (ACTION_RESULT / DELIVER_TO_SESSION)
            // ================================================================
            ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RegisterPendingCommandPayload>(it) } ?: return state
                val pending = AgentPendingCommand(
                    correlationId = payload.correlationId,
                    agentId = payload.agentId,
                    agentName = payload.agentName,
                    sessionId = payload.sessionId,
                    actionName = payload.actionName,
                    createdAt = platformDependencies.currentTimeMillis()
                )
                state.copy(
                    pendingCommands = state.pendingCommands + (payload.correlationId to pending)
                )
            }
            ActionRegistry.Names.AGENT_CLEAR_PENDING_COMMAND -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ClearPendingCommandPayload>(it) } ?: return state
                state.copy(
                    pendingCommands = state.pendingCommands - payload.correlationId
                )
            }

            else -> state
        }
    }

    private fun handleSetStatus(action: Action, state: AgentRuntimeState, platformDependencies: PlatformDependencies): AgentRuntimeState {
        val payload = action.payload ?: return state
        val agentId = payload.agentUUID() ?: return state
        val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()

        val newStatusString = payload["status"]?.jsonPrimitive?.contentOrNull ?: return state
        val newStatus = try { AgentStatus.valueOf(newStatusString) } catch (e: Exception) { return state }
        val newErrorMessage = if (newStatus == AgentStatus.ERROR) payload["error"]?.jsonPrimitive?.contentOrNull else null

        // Extract token usage from payload (set when a generation completes)
        val payloadInputTokens = payload["lastInputTokens"]?.jsonPrimitive?.intOrNull
        val payloadOutputTokens = payload["lastOutputTokens"]?.jsonPrimitive?.intOrNull

        // State Transition Logic
        val clearTimers = currentStatus.status == AgentStatus.WAITING && newStatus != AgentStatus.WAITING
        val isStartingProcessing = newStatus == AgentStatus.PROCESSING && currentStatus.status != AgentStatus.PROCESSING
        val isStoppingProcessing = newStatus != AgentStatus.PROCESSING && currentStatus.status == AgentStatus.PROCESSING
        val shouldClearContext = isStoppingProcessing || newStatus == AgentStatus.IDLE || newStatus == AgentStatus.ERROR

        val updatedStatus = currentStatus.copy(
            status = newStatus,
            errorMessage = newErrorMessage,
            waitingSinceTimestamp = if (clearTimers) null else currentStatus.waitingSinceTimestamp,
            lastMessageReceivedTimestamp = if (clearTimers) null else currentStatus.lastMessageReceivedTimestamp,
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.currentTimeMillis() else if (isStoppingProcessing) null else currentStatus.processingSinceTimestamp,
            processingFrontierMessageId = if (isStoppingProcessing) null else currentStatus.processingFrontierMessageId,
            processingStep = if (isStoppingProcessing) null else currentStatus.processingStep,
            stagedTurnContext = if(shouldClearContext) null else currentStatus.stagedTurnContext,
            transientHkgContext = if (shouldClearContext) null else currentStatus.transientHkgContext,
            transientWorkspaceContext = if (shouldClearContext) null else currentStatus.transientWorkspaceContext,
            contextGatheringStartedAt = if (shouldClearContext) null else currentStatus.contextGatheringStartedAt,
            // Preserve previous token data unless new data is provided in this update
            lastInputTokens = payloadInputTokens ?: currentStatus.lastInputTokens,
            lastOutputTokens = payloadOutputTokens ?: currentStatus.lastOutputTokens
        )
        // Reset persistence flag as this is pure runtime state
        return state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus), agentsToPersist = null)
    }

    private fun handleMessagePosted(action: Action, state: AgentRuntimeState, platformDependencies: PlatformDependencies): AgentRuntimeState {
        val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return state
        val entry = payload.entry
        val messageId = entry["id"]?.jsonPrimitive?.contentOrNull ?: return state

        // Prefer sessionUUID; fall back to sessionId only if it's a valid UUID
        val sessionUUIDStr = payload.sessionUUID ?: payload.sessionId
        if (!stringIsUUID(sessionUUIDStr)) {
            platformDependencies.log(LogLevel.ERROR, "AgentRuntimeReducer",
                "SESSION_MESSAGE_POSTED: expected UUID but got '$sessionUUIDStr'. Cannot match to agent subscriptions.")
            return state
        }
        val sessionId = IdentityUUID(sessionUUIDStr)

        val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.currentTimeMillis()

        // Filter out avatar updates (metadata: render_as_partial) to prevent cycles
        val metadata = entry["metadata"]?.jsonObject
        val isAvatar = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
        if (isAvatar) {
            // Track avatar cards: Map<IdentityUUID, Map<IdentityUUID, String>>
            val avatarAgentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (avatarAgentId != null) {
                // Resolve to UUID: senderId may be UUID (old) or handle (new)
                val resolvedUuid = state.agents[IdentityUUID(avatarAgentId)]?.identityUUID  // direct UUID key match
                    ?: state.agents.values.find { it.identityHandle.handle == avatarAgentId }?.identityUUID
                if (resolvedUuid != null) {
                    val currentSessionMap = state.agentAvatarCardIds[resolvedUuid] ?: emptyMap()
                    val newSessionMap = currentSessionMap + (sessionId to messageId)
                    return state.copy(agentAvatarCardIds = state.agentAvatarCardIds + (resolvedUuid to newSessionMap))
                }
            }
            return state
        }

        // Sentinel Guard: Do NOT transition agent state if the sender is "system".
        if (senderId == "system") return state

        val updatedStatuses = state.agentStatuses.toMutableMap()
        state.agents.values.forEach { agent ->
            val agentUuid = agent.identityUUID
            val currentStatus = updatedStatuses[agentUuid] ?: AgentStatusInfo()
            val isRelevant = (agent.subscribedSessionIds.contains(sessionId) || agent.outputSessionId == sessionId)
            val isSelf = (agentUuid.uuid == senderId || agent.identityHandle.handle == senderId)

            if (isRelevant && !isSelf) {
                // Auto-Waiting Logic (Synchronous)
                val newStatus = if (currentStatus.status == AgentStatus.IDLE) AgentStatus.WAITING else currentStatus.status

                updatedStatuses[agentUuid] = currentStatus.copy(
                    status = newStatus,
                    lastSeenMessageId = messageId,
                    lastMessageReceivedTimestamp = currentTime,
                    // Only set waiting timestamp if not already waiting
                    waitingSinceTimestamp = currentStatus.waitingSinceTimestamp ?: currentTime
                )
            } else if (isSelf) {
                // Agent saw its own message
                updatedStatuses[agentUuid] = currentStatus.copy(lastSeenMessageId = messageId)
            }
        }

        return state.copy(agentStatuses = updatedStatuses)
    }
}