package app.auf.feature.session

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
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

    @Serializable private data class CreatePayload(val name: String? = null)
    @Serializable private data class UpdateConfigPayload(val session: String, val name: String)
    @Serializable private data class SessionTargetPayload(val session: String)
    @Serializable private data class PostPayload(val session: String, val agentId: String, val message: String)
    @Serializable private data class UpdateMessagePayload(val session: String, val messageId: String, val newContent: String)
    @Serializable private data class MessageTargetPayload(val session: String, val messageId: String)
    @Serializable private data class SetEditingSessionPayload(val sessionId: String?)
    @Serializable private data class SetEditingMessagePayload(val messageId: String?)
    @Serializable private data class ToggleMessageUiPayload(val sessionId: String, val messageId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val sessions: Map<String, Session>)
    @Serializable private data class AgentNamesUpdatedPayload(val names: Map<String, String>)

    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> data.filterIsInstance<FileEntry>().filter { it.path.endsWith(".json") }.forEach {
                store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject { put("subpath", platformDependencies.getFileName(it.path)) }))
            }
            is JsonObject -> try {
                val session = json.decodeFromString<Session>(data["content"]?.jsonPrimitive?.content ?: "")
                store.dispatch(this.name, Action("session.internal.LOADED", Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.id to session))) as JsonObject))
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}")
            }
        }
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.STARTING" -> store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
            "session.CREATE" -> {
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                sessionState.activeSessionId?.let { persistSession(it, store) }
                broadcastSessionNames(sessionState, store)
            }
            "session.UPDATE_CONFIG", "session.DELETE", "session.internal.LOADED" -> {
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                broadcastSessionNames(sessionState, store)
                if (action.name == "session.DELETE") {
                    val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return
                    val sessionIdToDelete = if (identifier.length > 20) identifier else sessionState.sessions.entries.find { it.value.name == identifier }?.key ?: identifier
                    store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE", buildJsonObject { put("subpath", "$sessionIdToDelete.json") }))
                }
            }
            "session.POST", "session.UPDATE_MESSAGE", "session.DELETE_MESSAGE",
            "session.TOGGLE_MESSAGE_COLLAPSED", "session.TOGGLE_MESSAGE_RAW_VIEW" -> {
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: resolveSessionIdFromGenericPayload(action.payload, sessionState)
                sessionId?.let { persistSession(it, store) }
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
        store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
            put("subpath", "${sessionToSave.id}.json"); put("content", json.encodeToString(sessionToSave))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        store.dispatch(this.name, Action("session.publish.SESSION_NAMES_UPDATED", buildJsonObject {
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
        val currentFeatureState = state.featureStates[name] as? SessionState ?: SessionState()
        var newFeatureState: SessionState? = null
        val payload = action.payload
        when (action.name) {
            "agent.publish.AGENT_NAMES_UPDATED" -> newFeatureState = currentFeatureState.copy(agentNames = payload?.let { json.decodeFromJsonElement<AgentNamesUpdatedPayload>(it) }?.names ?: emptyMap())
            "session.CREATE" -> {
                val desiredName = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) }?.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val newSession = Session(platformDependencies.generateUUID(), findUniqueName(desiredName, currentFeatureState), emptyList(), platformDependencies.getSystemTimeMillis())
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (newSession.id to newSession), activeSessionId = newSession.id)
            }
            "session.UPDATE_CONFIG" -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val session = currentFeatureState.sessions[sessionId] ?: return state
                val updatedSession = session.copy(name = findUniqueName(decoded.name, currentFeatureState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingSessionId = null)
            }
            "session.POST" -> {
                val decoded = payload?.let { json.decodeFromJsonElement<PostPayload>(it) } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val newEntry = LedgerEntry(platformDependencies.generateUUID(), platformDependencies.getSystemTimeMillis(), decoded.agentId, decoded.message, blockParser.parse(decoded.message))
                val updatedSession = targetSession.copy(ledger = targetSession.ledger + newEntry)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.DELETE" -> {
                val sessionId = resolveSessionId(payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: "", currentFeatureState) ?: return state
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = if (currentFeatureState.activeSessionId != sessionId) currentFeatureState.activeSessionId else newSessions.values.maxByOrNull { it.createdAt }?.id
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
            "session.UPDATE_MESSAGE" -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val updatedLedger = targetSession.ledger.map { if (it.id == decoded.messageId) it.copy(rawContent = decoded.newContent, content = blockParser.parse(decoded.newContent)) else it }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingMessageId = null, editingMessageContent = null)
            }
            "session.DELETE_MESSAGE" -> {
                val decoded = payload?.let { json.decodeFromJsonElement<MessageTargetPayload>(it) } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val updatedSession = targetSession.copy(ledger = targetSession.ledger.filterNot { it.id == decoded.messageId })
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.SET_ACTIVE_TAB" -> newFeatureState = currentFeatureState.copy(activeSessionId = resolveSessionId(payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: "", currentFeatureState))
            "session.SET_EDITING_SESSION_NAME" -> newFeatureState = currentFeatureState.copy(editingSessionId = payload?.let { json.decodeFromJsonElement<SetEditingSessionPayload>(it) }?.sessionId)
            "session.SET_EDITING_MESSAGE" -> {
                val messageId = payload?.let { json.decodeFromJsonElement<SetEditingMessagePayload>(it) }?.messageId
                newFeatureState = if (messageId == null) currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                else currentFeatureState.copy(editingMessageId = messageId, editingMessageContent = currentFeatureState.sessions.values.flatMap { it.ledger }.find { it.id == messageId }?.rawContent)
            }
            "session.TOGGLE_MESSAGE_COLLAPSED", "session.TOGGLE_MESSAGE_RAW_VIEW" -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return state
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return state
                val uiState = targetSession.messageUiState[decoded.messageId] ?: MessageUiState()
                val newUiState = if (action.name.contains("COLLAPSED")) uiState.copy(isCollapsed = !uiState.isCollapsed) else uiState.copy(isRawView = !uiState.isRawView)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (decoded.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }
            "session.internal.LOADED" -> {
                val loadedSessions = payload?.let { json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) }?.sessions ?: emptyMap()
                val newSessions = currentFeatureState.sessions + loadedSessions.filterKeys { it !in currentFeatureState.sessions }
                val newActiveId = if (currentFeatureState.activeSessionId == null && newSessions.isNotEmpty()) newSessions.values.maxByOrNull { it.createdAt }?.id else currentFeatureState.activeSessionId
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
        }
        return newFeatureState?.let { if (it != currentFeatureState) state.copy(featureStates = state.featureStates + (name to it)) else state } ?: state
    }

    override val composableProvider = object : Feature.ComposableProvider {
        private val VIEW_KEY_MAIN = "feature.session.main"
        private val VIEW_KEY_MANAGER = "feature.session.manager"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            VIEW_KEY_MAIN to { store, features -> SessionView(store, features) },
            VIEW_KEY_MANAGER to { store, _ -> SessionsManagerView(store) }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            IconButton(onClick = { store.dispatch("session.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", VIEW_KEY_MANAGER) })) }) {
                Icon(Icons.Default.ViewList, "Session Manager", tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { store.dispatch("session.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", VIEW_KEY_MAIN) })) }) {
                Icon(Icons.Default.ChatBubble, "Active Session", tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
