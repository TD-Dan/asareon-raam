package app.auf.feature.session

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "session"

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class CreatePayload(val name: String? = null, val isHidden: Boolean = false, val isAgentPrivate: Boolean = false)
    @Serializable private data class ClonePayload(val session: String)
    @Serializable private data class UpdateConfigPayload(val session: String, val name: String)
    @Serializable private data class SessionTargetPayload(val session: String)
    @Serializable private data class PostPayload(val session: String, val senderId: String, val message: String? = null, val messageId: String? = null, val metadata: JsonObject? = null, val afterMessageId: String? = null)
    @Serializable private data class UpdateMessagePayload(val session: String, val messageId: String, val newContent: String? = null, val newMetadata: JsonObject? = null)
    @Serializable private data class MessageTargetPayload(val session: String, val messageId: String)
    @Serializable private data class SetEditingSessionPayload(val sessionId: String?)
    @Serializable private data class SetEditingMessagePayload(val messageId: String?)
    @Serializable private data class ToggleMessageUiPayload(val sessionId: String, val messageId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val sessions: Map<String, Session>)
    @Serializable private data class IdentityNamesUpdatedPayload(val names: Map<String, String>)
    @Serializable private data class AgentDeletedPayload(val agentId: String)
    @Serializable private data class RequestLedgerPayload(val sessionId: String, val correlationId: String)
    @Serializable private data class GatewayMessage(val role: String, val content: String, val senderId: String, val senderName: String)
    @Serializable private data class ReorderPayload(val sessionId: String, val toIndex: Int)
    @Serializable private data class SetOrderPayload(val order: List<String>)
    @Serializable private data class ToggleMessageLockedPayload(val sessionId: String, val messageId: String)


    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        val data = envelope.payload
        when (envelope.type) {
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> {
                val fileList = data["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
                fileList.filter { it.path.endsWith(".json") }.forEach {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", platformDependencies.getFileName(it.path)) }))
                }
            }
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> {
                try {
                    val content = data["content"]?.jsonPrimitive?.content ?: ""
                    if (content.isBlank()) {
                        platformDependencies.log(LogLevel.WARN, name, "Received empty session file content for ${data["subpath"]}")
                        return
                    }
                    val session = json.decodeFromString<Session>(content)
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_INTERNAL_LOADED, Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.id to session))) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}")
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val sessionState = newState as? SessionState ?: return

        // Helper to log errors for missing sessions
        fun requireSessionId(identifier: String?, state: SessionState, context: String): String? {
            if (identifier == null) {
                platformDependencies.log(LogLevel.ERROR, name, "Action $context failed: 'session' identifier missing in payload.")
                return null
            }
            val resolved = resolveSessionId(identifier, state)
            if (resolved == null) {
                platformDependencies.log(LogLevel.ERROR, name, "Action $context failed: Could not resolve session with identifier '$identifier'.")
            }
            return resolved
        }

        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                // Register hide-hidden settings with the Settings feature for persistence.
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    put("type", "BOOLEAN")
                    put("label", "Hide hidden sessions in viewer")
                    put("description", "When enabled, sessions marked as hidden are not shown in the tab bar.")
                    put("section", "Session")
                    put("defaultValue", "true")
                }))
                store.deferredDispatch(this.name, Action(ActionNames.SETTINGS_ADD, buildJsonObject {
                    put("key", SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                    put("type", "BOOLEAN")
                    put("label", "Hide hidden sessions in manager")
                    put("description", "When enabled, sessions marked as hidden are not shown in the session manager.")
                    put("section", "Session")
                    put("defaultValue", "true")
                }))
            }

            ActionNames.SESSION_CREATE, ActionNames.SESSION_CLONE -> {
                // For CREATE/CLONE, we rely on the reducer having created the session and set it as active.
                // We persist whatever is now the active session (if it matches the action intent).
                // Ideally, we'd grab the ID generated by the reducer, but finding the 'activeSessionId' is a decent heuristic for now.
                sessionState.activeSessionId?.let { persistSession(it, sessionState, store) }
                broadcastSessionNames(sessionState, store)
            }

            ActionNames.SESSION_UPDATE_CONFIG -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "UPDATE_CONFIG") ?: return

                persistSession(sessionId, sessionState, store)
                broadcastSessionNames(sessionState, store)
            }

            ActionNames.SESSION_DELETE -> {
                // The reducer handles the state removal and sets 'lastDeletedSessionId'
                val sessionIdToDelete = sessionState.lastDeletedSessionId
                if (sessionIdToDelete != null) {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE, buildJsonObject { put("subpath", "$sessionIdToDelete.json") }))
                    broadcastSessionNames(sessionState, store)
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_DELETED, buildJsonObject { put("sessionId", sessionIdToDelete) }))
                } else {
                    // If lastDeletedSessionId is null, it means the reducer didn't find the session to delete.
                    val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                    platformDependencies.log(LogLevel.WARN, name, "SESSION_DELETE ignored: Session '$identifier' not found in state.")
                }
            }

            ActionNames.SESSION_INTERNAL_LOADED -> {
                broadcastSessionNames(sessionState, store)
                // Persist any sessions whose orderIndex was normalized during load.
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                sessionState.sessions.forEach { (id, session) ->
                    if (prevSessions[id]?.orderIndex != session.orderIndex) {
                        persistSession(id, sessionState, store)
                    }
                }
            }

            ActionNames.SESSION_POST -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "POST") ?: return

                persistSession(sessionId, sessionState, store)
                val updatedSession = sessionState.sessions[sessionId] ?: return
                // Correction: We must find the message corresponding to this action to publish it.
                // If messageId was in payload, use it. Else, rely on last.
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val postedEntry = if (messageId != null) {
                    updatedSession.ledger.find { it.id == messageId }
                } else {
                    updatedSession.ledger.lastOrNull()
                }

                if (postedEntry != null) {
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                        put("sessionId", sessionId)
                        put("entry", json.encodeToJsonElement(postedEntry))
                    }))
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
                } else {
                    platformDependencies.log(LogLevel.ERROR, name, "SESSION_POST failed: Ledger entry not found after reducer update.")
                }
            }

            ActionNames.SESSION_UPDATE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "UPDATE_MESSAGE") ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                // GUARD: Locked message check (reducer also blocks, this handles log + toast)
                val prevSessionState = previousState as? SessionState
                if (isMessageLockedGuard(sessionId, messageId, "UPDATE_MESSAGE", prevSessionState ?: sessionState, store)) return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val identifier = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull

                val sessionId = requireSessionId(identifier, sessionState, action.name) ?: return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionNames.SESSION_DELETE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "DELETE_MESSAGE") ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull

                if (messageId == null) {
                    platformDependencies.log(LogLevel.ERROR, name, "DELETE_MESSAGE failed: messageId missing.")
                    return
                }
                // GUARD: Locked message check (reducer also blocks, this handles log + toast)
                val prevSessionStateForDelete = previousState as? SessionState
                if (isMessageLockedGuard(sessionId, messageId, "DELETE_MESSAGE", prevSessionStateForDelete ?: sessionState, store)) return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
                    put("sessionId", sessionId)
                    put("messageId", messageId)
                }))
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionNames.SESSION_SET_EDITING_MESSAGE -> {
                // GUARD: Locked message check for edit initiation (reducer also blocks, this handles log + toast)
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                val prevSessionStateForEdit = previousState as? SessionState ?: sessionState
                val entry = prevSessionStateForEdit.sessions.values.flatMap { it.ledger }.find { it.id == messageId }
                if (entry?.isLocked == true) {
                    platformDependencies.log(LogLevel.WARN, name, "SET_EDITING_MESSAGE blocked: Message '$messageId' is locked.")
                    store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                        put("message", "This message is locked and cannot be modified.")
                    }))
                }
            }

            ActionNames.SESSION_REQUEST_LEDGER_CONTENT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestLedgerPayload>(it) }
                if (payload == null) {
                    platformDependencies.log(LogLevel.ERROR, name, "REQUEST_LEDGER_CONTENT failed: Payload invalid.")
                    return
                }

                val session = sessionState.sessions[payload.sessionId]
                if (session == null) {
                    platformDependencies.log(LogLevel.ERROR, name, "REQUEST_LEDGER_CONTENT failed: Session '${payload.sessionId}' not found.")
                    return
                }

                val messages = session.ledger.map { json.encodeToJsonElement(it) }

                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    putJsonArray("messages") { messages.forEach { add(it) } }
                }
                val envelope = PrivateDataEnvelope(ActionNames.Envelopes.SESSION_RESPONSE_LEDGER, responsePayload)
                store.deliverPrivateData(this.name, action.originator ?: "unknown", envelope)
            }

            ActionNames.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "TOGGLE_SESSION_HIDDEN") ?: return
                persistSession(sessionId, sessionState, store)
            }

            ActionNames.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val resolvedId = requireSessionId(sessionId, sessionState, "TOGGLE_MESSAGE_LOCKED") ?: return
                persistSession(resolvedId, sessionState, store)
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", resolvedId) }))
            }

            ActionNames.SESSION_CLEAR -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "CLEAR") ?: return
                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionNames.SESSION_SET_ORDER, ActionNames.SESSION_REORDER -> {
                // Persist every session whose orderIndex was changed by the reducer.
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                sessionState.sessions.forEach { (id, session) ->
                    if (prevSessions[id]?.orderIndex != session.orderIndex) {
                        persistSession(id, sessionState, store)
                    }
                }
            }
        }
    }

    private fun resolveSessionIdFromGenericPayload(payload: JsonObject?, state: SessionState): String? {
        val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return null
        return resolveSessionId(identifier, state)
    }

    private fun persistSession(sessionId: String, sessionState: SessionState, store: Store) {
        val sessionToSave = sessionState.sessions[sessionId] ?: return
        val persistedSession = sessionToSave.copy(
            ledger = sessionToSave.ledger.filterNot {
                it.metadata?.get("is_transient")?.jsonPrimitive?.booleanOrNull ?: false
            }
        )
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${persistedSession.id}.json"); put("content", json.encodeToString(persistedSession))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        store.deferredDispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(state.sessions.mapValues { it.value.name }))
        }))
    }

    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        if (state.sessions.containsKey(identifier)) return identifier
        return state.sessions.values.singleOrNull { it.name == identifier }?.id
    }

    private fun findUniqueName(desiredName: String, state: SessionState): String {
        val existingNames = state.sessions.values.map { it.name }.toSet()
        if (desiredName !in existingNames) return desiredName
        var n = 2; var newName: String; do { newName = "$desiredName-$n"; n++ } while (newName in existingNames)
        return newName
    }

    /**
     * Helper to check if a message is locked and, if so, log a warning and dispatch a toast.
     * Returns true if the message IS locked (i.e., the action should be blocked).
     */
    private fun isMessageLockedGuard(
        sessionId: String,
        messageId: String,
        actionContext: String,
        state: SessionState,
        store: Store
    ): Boolean {
        val session = state.sessions[sessionId] ?: return false
        val entry = session.ledger.find { it.id == messageId } ?: return false
        if (entry.isLocked) {
            platformDependencies.log(LogLevel.WARN, name, "$actionContext blocked: Message '$messageId' in session '$sessionId' is locked.")
            store.deferredDispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                put("message", "This message is locked and cannot be modified.")
            }))
            return true
        }
        return false
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? SessionState ?: SessionState()
        val payload = action.payload

        var newState: SessionState = when (action.name) {
            ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED -> {
                val identities = payload?.get("identities")?.jsonArray?.map { json.decodeFromJsonElement<Identity>(it) } ?: return currentFeatureState
                val userNames = identities.associate { it.id to it.name }
                val agentNames = currentFeatureState.identityNames.filterKeys { it !in userNames.keys }
                currentFeatureState.copy(identityNames = agentNames + userNames)
            }
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> {
                val agentNames = payload?.let { json.decodeFromJsonElement<IdentityNamesUpdatedPayload>(it) }?.names ?: emptyMap()
                val userNames = currentFeatureState.identityNames.filterKeys { it !in agentNames.keys }
                currentFeatureState.copy(identityNames = userNames + agentNames)
            }
            ActionNames.AGENT_PUBLISH_AGENT_DELETED -> {
                val agentId = payload?.let { json.decodeFromJsonElement<AgentDeletedPayload>(it) }?.agentId ?: return currentFeatureState
                currentFeatureState.copy(identityNames = currentFeatureState.identityNames - agentId)
            }
            ActionNames.SESSION_CREATE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) } ?: CreatePayload()
                val desiredName = decoded.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val newSession = Session(
                    id = platformDependencies.generateUUID(),
                    name = findUniqueName(desiredName, currentFeatureState),
                    ledger = emptyList(),
                    createdAt = platformDependencies.getSystemTimeMillis(),
                    isHidden = decoded.isHidden,
                    isAgentPrivate = decoded.isAgentPrivate,
                    orderIndex = 0 // Placed at top; tiebreaker with existing 0s is createdAt desc.
                )
                val updatedSessions = currentFeatureState.sessions + (newSession.id to newSession)
                currentFeatureState.copy(
                    sessions = updatedSessions,
                    activeSessionId = newSession.id,
                    sessionOrder = SessionState.deriveSessionOrder(updatedSessions)
                )
            }
            ActionNames.SESSION_CLONE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ClonePayload>(it) } ?: return currentFeatureState
                val sessionToClone = currentFeatureState.sessions[decoded.session] ?: return currentFeatureState
                val newName = findUniqueName("${sessionToClone.name} (Copy)", currentFeatureState)
                val newSession = sessionToClone.copy(
                    id = platformDependencies.generateUUID(),
                    name = newName,
                    createdAt = platformDependencies.getSystemTimeMillis(),
                    isHidden = false, // Clones are always non-hidden
                    isAgentPrivate = false, // Clones are always non-private
                    orderIndex = 0 // Placed at top; tiebreaker with existing 0s is createdAt desc.
                )
                val updatedSessions = currentFeatureState.sessions + (newSession.id to newSession)
                currentFeatureState.copy(
                    sessions = updatedSessions,
                    activeSessionId = newSession.id,
                    sessionOrder = SessionState.deriveSessionOrder(updatedSessions)
                )
            }
            ActionNames.SESSION_UPDATE_CONFIG -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val updatedSession = session.copy(name = findUniqueName(decoded.name, currentFeatureState))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingSessionId = null)
            }
            ActionNames.SESSION_POST -> {
                val decoded = payload?.let { json.decodeFromJsonElement<PostPayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val newEntry = LedgerEntry(
                    id = decoded.messageId ?: platformDependencies.generateUUID(),
                    timestamp = platformDependencies.getSystemTimeMillis(),
                    senderId = decoded.senderId,
                    rawContent = decoded.message,
                    content = decoded.message?.let { blockParser.parse(it) } ?: emptyList(),
                    metadata = decoded.metadata
                )
                val updatedLedger = if (decoded.afterMessageId != null) {
                    val insertionIndex = targetSession.ledger.indexOfFirst { it.id == decoded.afterMessageId }
                    if (insertionIndex != -1) {
                        targetSession.ledger.toMutableList().apply { add(insertionIndex + 1, newEntry) }
                    } else {
                        targetSession.ledger + newEntry
                    }
                } else {
                    targetSession.ledger + newEntry
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            ActionNames.SESSION_DELETE -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: ""
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = if (currentFeatureState.activeSessionId != sessionId) currentFeatureState.activeSessionId else newSessions.values.maxByOrNull { it.createdAt }?.id
                currentFeatureState.copy(
                    sessions = newSessions,
                    activeSessionId = newActiveId,
                    lastDeletedSessionId = sessionId,
                    sessionOrder = SessionState.deriveSessionOrder(newSessions)
                )
            }
            ActionNames.SESSION_UPDATE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                // GUARD: Check if the target message is locked
                val targetEntry = targetSession.ledger.find { it.id == decoded.messageId }
                if (targetEntry?.isLocked == true) return currentFeatureState // Guard blocks; toast dispatched by onAction-level guard is not applicable here. The isMessageLockedGuard in the feature handles the toast.
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) {
                        val updatedRawContent = decoded.newContent ?: it.rawContent
                        val updatedMetadata = decoded.newMetadata ?: it.metadata
                        it.copy(
                            rawContent = updatedRawContent,
                            content = updatedRawContent?.let { c -> blockParser.parse(c) } ?: emptyList(),
                            metadata = updatedMetadata
                        )
                    } else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingMessageId = null, editingMessageContent = null)
            }
            ActionNames.SESSION_DELETE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<MessageTargetPayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                // GUARD: Check if the target message is locked
                val targetEntry = targetSession.ledger.find { it.id == decoded.messageId }
                if (targetEntry?.isLocked == true) return currentFeatureState
                val updatedSession = targetSession.copy(ledger = targetSession.ledger.filterNot { it.id == decoded.messageId })
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            ActionNames.SESSION_SET_ACTIVE_TAB -> currentFeatureState.copy(activeSessionId = resolveSessionId(payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: "", currentFeatureState))
            ActionNames.SESSION_SET_EDITING_SESSION_NAME -> currentFeatureState.copy(editingSessionId = payload?.let { json.decodeFromJsonElement<SetEditingSessionPayload>(it) }?.sessionId)
            ActionNames.SESSION_SET_EDITING_MESSAGE -> {
                val messageId = payload?.let { json.decodeFromJsonElement<SetEditingMessagePayload>(it) }?.messageId
                if (messageId == null) {
                    currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                } else {
                    // GUARD: Check if the target message is locked
                    val entry = currentFeatureState.sessions.values.flatMap { it.ledger }.find { it.id == messageId }
                    if (entry?.isLocked == true) return currentFeatureState
                    currentFeatureState.copy(editingMessageId = messageId, editingMessageContent = entry?.rawContent)
                }
            }
            ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val uiState = targetSession.messageUiState[decoded.messageId] ?: MessageUiState()
                val newUiState = if (action.name == ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED) uiState.copy(isCollapsed = !uiState.isCollapsed) else uiState.copy(isRawView = !uiState.isRawView)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (decoded.messageId to newUiState))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }
            ActionNames.SESSION_INTERNAL_LOADED -> {
                val loadedSessions = payload?.let { json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) }?.sessions ?: emptyMap()
                val mergedSessions = currentFeatureState.sessions + loadedSessions.filterKeys { it !in currentFeatureState.sessions }
                val newActiveId = if (currentFeatureState.activeSessionId == null && mergedSessions.isNotEmpty()) mergedSessions.values.maxByOrNull { it.createdAt }?.id else currentFeatureState.activeSessionId
                // Normalize orderIndex values to heal any gaps/duplicates from disk, then derive display order.
                val normalizedSessions = SessionState.normalizeOrderIndices(mergedSessions)
                val derivedOrder = SessionState.deriveSessionOrder(normalizedSessions)
                currentFeatureState.copy(
                    sessions = normalizedSessions,
                    activeSessionId = newActiveId,
                    sessionOrder = derivedOrder
                )
            }

            // --- New Actions ---

            ActionNames.SESSION_REORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ReorderPayload>(it) } ?: return currentFeatureState
                val currentOrder = currentFeatureState.sessionOrder.toMutableList()
                val currentIndex = currentOrder.indexOf(decoded.sessionId)
                if (currentIndex == -1) return currentFeatureState
                currentOrder.removeAt(currentIndex)
                val clampedIndex = decoded.toIndex.coerceIn(0, currentOrder.size)
                currentOrder.add(clampedIndex, decoded.sessionId)
                // Assign contiguous orderIndex values for persistence.
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                currentOrder.forEachIndexed { index, id ->
                    updatedSessions[id]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[id] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = currentOrder)
            }

            ActionNames.SESSION_SET_ORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetOrderPayload>(it) } ?: return currentFeatureState
                // Preserve any session IDs in the existing order that aren't in the new list
                // (e.g. hidden sessions that were filtered out of the manager view).
                val suppliedSet = decoded.order.toSet()
                val remainder = currentFeatureState.sessionOrder.filter { it !in suppliedSet }
                val fullOrder = decoded.order + remainder
                // Assign contiguous orderIndex values for persistence.
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                fullOrder.forEachIndexed { index, id ->
                    updatedSessions[id]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[id] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = fullOrder)
            }

            // --- Settings Hydration ---

            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val viewerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val managerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                currentFeatureState.copy(
                    hideHiddenInViewer = viewerSetting ?: currentFeatureState.hideHiddenInViewer,
                    hideHiddenInManager = managerSetting ?: currentFeatureState.hideHiddenInManager
                )
            }

            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return currentFeatureState
                val value = payload["value"]?.jsonPrimitive?.content ?: return currentFeatureState
                when (key) {
                    SessionState.SETTING_HIDE_HIDDEN_VIEWER ->
                        currentFeatureState.copy(hideHiddenInViewer = value.toBooleanStrictOrNull() ?: currentFeatureState.hideHiddenInViewer)
                    SessionState.SETTING_HIDE_HIDDEN_MANAGER ->
                        currentFeatureState.copy(hideHiddenInManager = value.toBooleanStrictOrNull() ?: currentFeatureState.hideHiddenInManager)
                    else -> currentFeatureState
                }
            }

            ActionNames.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val updatedSession = session.copy(isHidden = !session.isHidden)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            ActionNames.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageLockedPayload>(it) } ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) it.copy(isLocked = !it.isLocked) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }

            ActionNames.SESSION_CLEAR -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val survivingLedger = targetSession.ledger.filter { it.isLocked }
                val survivingIds = survivingLedger.map { it.id }.toSet()
                val updatedSession = targetSession.copy(
                    ledger = survivingLedger,
                    messageUiState = targetSession.messageUiState.filterKeys { it in survivingIds }
                )
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            else -> currentFeatureState
        }

        if (action.name != ActionNames.SESSION_DELETE && newState.lastDeletedSessionId != null) {
            newState = newState.copy(lastDeletedSessionId = null)
        }

        return newState
    }


    override val composableProvider = object : Feature.ComposableProvider {
        private val VIEW_KEY_MAIN = "feature.session.main"
        private val VIEW_KEY_MANAGER = "feature.session.manager"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            VIEW_KEY_MAIN to { store, features -> SessionView(store, features, platformDependencies) },
            VIEW_KEY_MANAGER to { store, _ -> SessionsManagerView(store, platformDependencies) }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MANAGER) })) }) {
                Icon(Icons.AutoMirrored.Filled.ViewList, "Session Manager", tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MAIN) })) }) {
                Icon(Icons.Default.ChatBubble, "Active Session", tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}