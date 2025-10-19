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
    @Serializable private data class CreatePayload(val name: String? = null)
    @Serializable private data class UpdateConfigPayload(val session: String, val name: String)
    @Serializable private data class SessionTargetPayload(val session: String)
    @Serializable private data class PostPayload(val session: String, val senderId: String, val message: String? = null, val messageId: String? = null, val metadata: JsonObject? = null)
    @Serializable private data class UpdateMessagePayload(val session: String, val messageId: String, val newContent: String, val newMetadata: JsonObject? = null)
    @Serializable private data class MessageTargetPayload(val session: String, val messageId: String)
    @Serializable private data class SetEditingSessionPayload(val sessionId: String?)
    @Serializable private data class SetEditingMessagePayload(val messageId: String?)
    @Serializable private data class ToggleMessageUiPayload(val sessionId: String, val messageId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val sessions: Map<String, Session>)
    @Serializable private data class AgentNamesUpdatedPayload(val names: Map<String, String>)
    @Serializable private data class AgentDeletedPayload(val agentId: String)
    @Serializable private data class RequestLedgerPayload(val sessionId: String, val correlationId: String)
    @Serializable private data class GatewayMessage(val role: String, val content: String)


    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        // THIS LOGIC IS NOW INCORRECT AND WILL BE FIXED IN PHASE 3.
        // It is temporarily updated to satisfy the compiler.
        val data = envelope.payload
        when (envelope.type) {
            "filesystem.response.list" -> {
                val fileList = data["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
                fileList.filter { it.path.endsWith(".json") }.forEach {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", platformDependencies.getFileName(it.path)) }))
                }
            }
            "filesystem.response.read" -> {
                try {
                    val session = json.decodeFromString<Session>(data["content"]?.jsonPrimitive?.content ?: "")
                    store.dispatch(this.name, Action(ActionNames.SESSION_INTERNAL_LOADED, Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.id to session))) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}")
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store) {
        val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            ActionNames.SESSION_CREATE -> {
                val latestState = store.state.value.featureStates[name] as? SessionState ?: return
                latestState.activeSessionId?.let { persistSession(it, store) }
                broadcastSessionNames(latestState, store)
            }
            ActionNames.SESSION_UPDATE_CONFIG -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return
                val sessionId = resolveSessionId(identifier, sessionState) ?: return
                persistSession(sessionId, store)
                val updatedSessionState = store.state.value.featureStates[name] as? SessionState ?: return
                broadcastSessionNames(updatedSessionState, store)
            }
            ActionNames.SESSION_DELETE -> {
                val sessionIdToDelete = sessionState.lastDeletedSessionId
                if (sessionIdToDelete != null) {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE, buildJsonObject { put("subpath", "$sessionIdToDelete.json") }))
                    val updatedSessionState = store.state.value.featureStates[name] as? SessionState ?: return
                    broadcastSessionNames(updatedSessionState, store)
                    store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_DELETED, buildJsonObject { put("sessionId", sessionIdToDelete) }))
                }
            }
            ActionNames.SESSION_INTERNAL_LOADED -> {
                val latestState = store.state.value.featureStates[name] as? SessionState ?: return
                broadcastSessionNames(latestState, store)
            }
            ActionNames.SESSION_POST -> {
                val sessionId = resolveSessionIdFromGenericPayload(action.payload, sessionState) ?: return
                persistSession(sessionId, store)
                val updatedSession = (store.state.value.featureStates[name] as? SessionState)?.sessions?.get(sessionId) ?: return
                val postedEntry = updatedSession.ledger.last()
                store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                    put("sessionId", sessionId)
                    put("entry", json.encodeToJsonElement(postedEntry))
                }))
                store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }
            ActionNames.SESSION_UPDATE_MESSAGE, ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: resolveSessionIdFromGenericPayload(action.payload, sessionState) ?: return
                persistSession(sessionId, store)
                store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }
            ActionNames.SESSION_DELETE_MESSAGE -> {
                val sessionId = resolveSessionIdFromGenericPayload(action.payload, sessionState) ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                persistSession(sessionId, store)
                store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
                    put("sessionId", sessionId)
                    put("messageId", messageId)
                }))
                store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }
            ActionNames.SESSION_REQUEST_LEDGER_CONTENT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestLedgerPayload>(it) } ?: return
                val session = sessionState.sessions[payload.sessionId] ?: return
                val messages = session.ledger.mapNotNull {
                    it.rawContent?.let { content ->
                        val role = if (it.senderId == "user") "user" else "model"
                        GatewayMessage(role, content)
                    }
                }
                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    putJsonArray("messages") { messages.forEach { add(Json.encodeToJsonElement(it)) } }
                }
                val envelope = PrivateDataEnvelope("session.response.ledger", responsePayload)
                store.deliverPrivateData(this.name, action.originator ?: "unknown", envelope)
            }
        }
    }

    private fun resolveSessionIdFromGenericPayload(payload: JsonObject?, state: SessionState): String? {
        val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return null
        return resolveSessionId(identifier, state)
    }

    private fun persistSession(sessionId: String, store: Store) {
        val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
        val sessionToSave = sessionState.sessions[sessionId] ?: return
        val persistedSession = sessionToSave.copy(
            ledger = sessionToSave.ledger.filterNot {
                it.metadata?.get("is_transient")?.jsonPrimitive?.booleanOrNull ?: false
            }
        )
        store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${persistedSession.id}.json"); put("content", json.encodeToString(persistedSession))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        store.dispatch(this.name, Action(ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED, buildJsonObject {
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

    override fun reducer(state: AppState, action: Action): AppState {
        val (stateWithFeature, currentFeatureState) = state.featureStates[name]
            ?.let { state to (it as SessionState) }
            ?: (state.copy(featureStates = state.featureStates + (name to SessionState())) to SessionState())

        var newFeatureState: SessionState? = null
        val payload = action.payload
        when (action.name) {
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> newFeatureState = currentFeatureState.copy(agentNames = payload?.let { json.decodeFromJsonElement<AgentNamesUpdatedPayload>(it) }?.names ?: emptyMap())
            ActionNames.AGENT_PUBLISH_AGENT_DELETED -> {
                val agentId = payload?.let { json.decodeFromJsonElement<AgentDeletedPayload>(it) }?.agentId ?: return stateWithFeature
                newFeatureState = currentFeatureState.copy(agentNames = currentFeatureState.agentNames - agentId)
            }
            ActionNames.SESSION_CREATE -> {
                val desiredName = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) }?.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val newSession = Session(platformDependencies.generateUUID(), findUniqueName(desiredName, currentFeatureState), emptyList(), platformDependencies.getSystemTimeMillis())
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (newSession.id to newSession), activeSessionId = newSession.id)
            }
            ActionNames.SESSION_UPDATE_CONFIG -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return stateWithFeature
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return stateWithFeature
                val session = currentFeatureState.sessions[sessionId] ?: return stateWithFeature
                val updatedSession = session.copy(name = findUniqueName(decoded.name, currentFeatureState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingSessionId = null)
            }
            ActionNames.SESSION_POST -> {
                val decoded = payload?.let { json.decodeFromJsonElement<PostPayload>(it) } ?: return stateWithFeature
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return stateWithFeature
                val targetSession = currentFeatureState.sessions[sessionId] ?: return stateWithFeature
                val newEntry = LedgerEntry(
                    id = decoded.messageId ?: platformDependencies.generateUUID(),
                    timestamp = platformDependencies.getSystemTimeMillis(),
                    senderId = decoded.senderId,
                    rawContent = decoded.message,
                    content = decoded.message?.let { blockParser.parse(it) } ?: emptyList(),
                    metadata = decoded.metadata
                )
                val updatedSession = targetSession.copy(ledger = targetSession.ledger + newEntry)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            ActionNames.SESSION_DELETE -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: ""
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return stateWithFeature
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = if (currentFeatureState.activeSessionId != sessionId) currentFeatureState.activeSessionId else newSessions.values.maxByOrNull { it.createdAt }?.id
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId, lastDeletedSessionId = sessionId)
            }
            ActionNames.SESSION_UPDATE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return stateWithFeature
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return stateWithFeature
                val targetSession = currentFeatureState.sessions[sessionId] ?: return stateWithFeature
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) it.copy(
                        rawContent = decoded.newContent,
                        content = blockParser.parse(decoded.newContent),
                        metadata = decoded.newMetadata ?: it.metadata
                    ) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingMessageId = null, editingMessageContent = null)
            }
            ActionNames.SESSION_DELETE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<MessageTargetPayload>(it) } ?: return stateWithFeature
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return stateWithFeature
                val targetSession = currentFeatureState.sessions[sessionId] ?: return stateWithFeature
                val updatedSession = targetSession.copy(ledger = targetSession.ledger.filterNot { it.id == decoded.messageId })
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            ActionNames.SESSION_SET_ACTIVE_TAB -> newFeatureState = currentFeatureState.copy(activeSessionId = resolveSessionId(payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: "", currentFeatureState))
            ActionNames.SESSION_SET_EDITING_SESSION_NAME -> newFeatureState = currentFeatureState.copy(editingSessionId = payload?.let { json.decodeFromJsonElement<SetEditingSessionPayload>(it) }?.sessionId)
            ActionNames.SESSION_SET_EDITING_MESSAGE -> {
                val messageId = payload?.let { json.decodeFromJsonElement<SetEditingMessagePayload>(it) }?.messageId
                newFeatureState = if (messageId == null) currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                else currentFeatureState.copy(editingMessageId = messageId, editingMessageContent = currentFeatureState.sessions.values.flatMap { it.ledger }.find { it.id == messageId }?.rawContent)
            }
            ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return stateWithFeature
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return stateWithFeature
                val uiState = targetSession.messageUiState[decoded.messageId] ?: MessageUiState()
                val newUiState = if (action.name == ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED) uiState.copy(isCollapsed = !uiState.isCollapsed) else uiState.copy(isRawView = !uiState.isRawView)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (decoded.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }
            ActionNames.SESSION_INTERNAL_LOADED -> {
                val loadedSessions = payload?.let { json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) }?.sessions ?: emptyMap()
                val newSessions = currentFeatureState.sessions + loadedSessions.filterKeys { it !in currentFeatureState.sessions }
                val newActiveId = if (currentFeatureState.activeSessionId == null && newSessions.isNotEmpty()) newSessions.values.maxByOrNull { it.createdAt }?.id else currentFeatureState.activeSessionId
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
        }
        return newFeatureState?.let {
            val finalState = if (action.name != ActionNames.SESSION_DELETE) it.copy(lastDeletedSessionId = null) else it
            if (finalState != currentFeatureState) stateWithFeature.copy(featureStates = stateWithFeature.featureStates + (name to finalState)) else stateWithFeature
        } ?: stateWithFeature
    }

    override val composableProvider = object : Feature.ComposableProvider {
        private val VIEW_KEY_MAIN = "feature.session.main"
        private val VIEW_KEY_MANAGER = "feature.session.manager"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            VIEW_KEY_MAIN to { store, features -> SessionView(store, features) },
            VIEW_KEY_MANAGER to { store, _ -> SessionsManagerView(store) }
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