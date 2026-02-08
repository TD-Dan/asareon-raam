package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * To provide pure, testable reducer logic for the **ephemeral runtime state**
 * of the AgentRuntimeFeature.
 */
object AgentRuntimeReducer {

    private val json = Json { ignoreUnknownKeys = true }

    fun reduce(
        state: AgentRuntimeState,
        action: Action,
        platformDependencies: PlatformDependencies
    ): AgentRuntimeState {
        return when (action.name) {
            // --- Internal State Setters ---
            ActionNames.AGENT_INTERNAL_SET_STATUS -> handleSetStatus(action, state, platformDependencies)

            ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP -> {
                val payload = action.payload ?: return state
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val step = payload["step"]?.jsonPrimitive?.contentOrNull
                val updatedStatus = currentStatus.copy(processingStep = step)
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<StageTurnContextPayload>(it) } ?: return state
                val currentStatus = state.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedTurnContext = payload.messages)
                state.copy(agentStatuses = state.agentStatuses + (payload.agentId to updatedStatus))
            }

            ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHkgContextPayload>(it) } ?: return state
                val currentStatus = state.agentStatuses[payload.agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(transientHkgContext = payload.context)
                state.copy(agentStatuses = state.agentStatuses + (payload.agentId to updatedStatus))
            }

            // [NEW] Atomic commit of avatar position
            ActionNames.AGENT_INTERNAL_AVATAR_MOVED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<AvatarMovedPayload>(it) } ?: return state
                val currentSessionMap = state.agentAvatarCardIds[payload.agentId] ?: emptyMap()
                val newSessionMap = currentSessionMap + (payload.sessionId to payload.messageId)
                state.copy(agentAvatarCardIds = state.agentAvatarCardIds + (payload.agentId to newSessionMap))
            }

            // --- Turn Lifecycle ---
            ActionNames.AGENT_INITIATE_TURN -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<InitiateTurnPayload>(it) } ?: return state
                val agentId = payload.agentId
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()

                // Guard: Don't interrupt an active turn
                if (currentStatus.status == AgentStatus.PROCESSING) return state

                val updatedStatus = currentStatus.copy(
                    processingFrontierMessageId = currentStatus.lastSeenMessageId,
                    turnMode = if (payload.preview) TurnMode.PREVIEW else TurnMode.DIRECT,
                    stagedTurnContext = null,
                    transientHkgContext = null
                )
                state.copy(agentStatuses = state.agentStatuses + (agentId to updatedStatus))
            }

            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return state
                val currentStatus = state.agentStatuses[agentId] ?: AgentStatusInfo()
                val updatedStatus = currentStatus.copy(stagedPreviewData = null, processingStep = null)
                state.copy(
                    agentStatuses = state.agentStatuses + (agentId to updatedStatus),
                    viewingContextForAgentId = null
                )
            }

            ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA -> {
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
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> handleMessagePosted(action, state, platformDependencies)

            ActionNames.SESSION_PUBLISH_MESSAGE_DELETED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<MessageDeletedPayload>(it) } ?: return state
                // REFACTORED: Iterate Map<AgentId, Map<SessionId, MessageId>>
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

            ActionNames.SESSION_PUBLISH_SESSION_DELETED -> {
                val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return state
                val agentsToUpdate = state.agents.values
                    .filter { it.subscribedSessionIds.contains(deletedSessionId) || it.privateSessionId == deletedSessionId }

                // Cleanup deleted session from avatar map
                var newAvatarCards = state.agentAvatarCardIds
                state.agentAvatarCardIds.forEach { (agentId, sessionMap) ->
                    if (sessionMap.containsKey(deletedSessionId)) {
                        newAvatarCards = newAvatarCards + (agentId to (sessionMap - deletedSessionId))
                    }
                }

                if (agentsToUpdate.isEmpty()) return state.copy(agentAvatarCardIds = newAvatarCards)

                val newAgents = state.agents.mapValues { (_, agent) ->
                    if (agentsToUpdate.any { it.id == agent.id }) {
                        agent.copy(
                            subscribedSessionIds = agent.subscribedSessionIds - deletedSessionId,
                            privateSessionId = if (agent.privateSessionId == deletedSessionId) null else agent.privateSessionId
                        )
                    } else {
                        agent
                    }
                }
                // Note: We set agentsToPersist so the Feature knows to save these changes
                state.copy(agents = newAgents, agentAvatarCardIds = newAvatarCards, agentsToPersist = agentsToUpdate.map { it.id }.toSet())
            }

            ActionNames.GATEWAY_PUBLISH_AVAILABLE_MODELS_UPDATED -> {
                val decodedModels: Map<String, List<String>>? = try { action.payload?.let { json.decodeFromJsonElement(it) } } catch (e: Exception) { null }
                state.copy(availableModels = decodedModels ?: emptyMap())
            }

            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<SessionNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) state.copy(sessionNames = decoded.names) else state
            }

            ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED -> {
                val decoded = try { action.payload?.let { json.decodeFromJsonElement<GraphNamesPayload>(it) } } catch(e: Exception) { null }
                if (decoded != null) state.copy(knowledgeGraphNames = decoded.names) else state
            }

            ActionNames.KNOWLEDGEGRAPH_PUBLISH_RESERVATIONS_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ReservedIdsPayload>(it) }
                if (payload != null) state.copy(hkgReservedIds = payload.reservedIds) else state
            }

            ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<IdentitiesUpdatedPayload>(it) }
                if (payload != null) state.copy(userIdentities = payload.identities) else state
            }

            else -> state
        }
    }

    private fun handleSetStatus(action: Action, state: AgentRuntimeState, platformDependencies: PlatformDependencies): AgentRuntimeState {
        val payload = action.payload ?: return state
        val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
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
            processingSinceTimestamp = if (isStartingProcessing) platformDependencies.getSystemTimeMillis() else if (isStoppingProcessing) null else currentStatus.processingSinceTimestamp,
            processingFrontierMessageId = if (isStoppingProcessing) null else currentStatus.processingFrontierMessageId,
            processingStep = if (isStoppingProcessing) null else currentStatus.processingStep,
            stagedTurnContext = if(shouldClearContext) null else currentStatus.stagedTurnContext,
            transientHkgContext = if (shouldClearContext) null else currentStatus.transientHkgContext,
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
        val sessionId = payload.sessionId
        val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull
        val currentTime = platformDependencies.getSystemTimeMillis()

        // Filter out avatar updates (metadata: render_as_partial) to prevent cycles
        val metadata = entry["metadata"]?.jsonObject
        val isAvatar = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
        if (isAvatar) {
            // Track avatar cards: Map<AgentId, Map<SessionId, MessageId>>
            val avatarAgentId = entry["senderId"]?.jsonPrimitive?.contentOrNull
            if (avatarAgentId != null && state.agents.containsKey(avatarAgentId)) {
                val currentSessionMap = state.agentAvatarCardIds[avatarAgentId] ?: emptyMap()
                val newSessionMap = currentSessionMap + (sessionId to messageId)
                return state.copy(agentAvatarCardIds = state.agentAvatarCardIds + (avatarAgentId to newSessionMap))
            }
            return state
        }

        // Sentinel Guard: Do NOT transition agent state if the sender is "system".
        if (senderId == "system") return state

        val updatedStatuses = state.agentStatuses.toMutableMap()
        state.agents.values.forEach { agent ->
            val currentStatus = updatedStatuses[agent.id] ?: AgentStatusInfo()
            val isRelevant = (agent.subscribedSessionIds.contains(sessionId) || agent.privateSessionId == sessionId)

            if (isRelevant && agent.id != senderId) {
                // NEW: Auto-Waiting Logic (Synchronous)
                val newStatus = if (currentStatus.status == AgentStatus.IDLE) AgentStatus.WAITING else currentStatus.status

                updatedStatuses[agent.id] = currentStatus.copy(
                    status = newStatus,
                    lastSeenMessageId = messageId,
                    lastMessageReceivedTimestamp = currentTime,
                    // Only set waiting timestamp if not already waiting
                    waitingSinceTimestamp = currentStatus.waitingSinceTimestamp ?: currentTime
                )
            } else if (agent.id == senderId) {
                // Agent saw its own message
                updatedStatuses[agent.id] = currentStatus.copy(lastSeenMessageId = messageId)
            }
        }

        return state.copy(agentStatuses = updatedStatuses)
    }
}