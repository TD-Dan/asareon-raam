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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    @Serializable private data class PostPayload(val session: String, val agentId: String, val message: String)
    @Serializable private data class UpdateMessagePayload(val session: String, val messageId: String, val newContent: String)
    @Serializable private data class MessageTargetPayload(val session: String, val messageId: String)
    @Serializable private data class SetEditingSessionPayload(val sessionId: String?)
    @Serializable private data class SetEditingMessagePayload(val messageId: String?)
    @Serializable private data class ToggleMessageUiPayload(val sessionId: String, val messageId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val sessions: Map<String, Session>)


    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun onPrivateData(data: Any, store: Store) {
        when (data) {
            is List<*> -> { // Received file list from filesystem
                data.filterIsInstance<FileEntry>()
                    .filter { it.path.endsWith(".json") }
                    .forEach { fileEntry ->
                        store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject {
                            put("subpath", platformDependencies.getFileName(fileEntry.path))
                        }))
                    }
            }
            is JsonObject -> { // Received file content from filesystem
                val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
                try {
                    val session = json.decodeFromString<Session>(content)
                    val sessionsMap = mapOf(session.id to session)
                    val payload = Json.encodeToJsonElement(InternalSessionLoadedPayload(sessionsMap))
                    store.dispatch(this.name, Action("session.internal.LOADED", payload as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}")
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "system.STARTING" -> {
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
                store.dispatch(this.name, Action("session.REQUEST_SESSION_NAMES"))
            }
            // --- THE FIX: Merged session.DELETE into this block to ensure broadcast happens ---
            "session.CREATE", "session.UPDATE_CONFIG", "session.DELETE", "session.internal.LOADED" -> {
                // After the reducer has run, broadcast the new set of session names.
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                broadcastSessionNames(sessionState, store)

                // If this was a delete action, we also need to dispatch the file system side effect.
                if (action.name == "session.DELETE") {
                    val payload = action.payload ?: return
                    val identifier = payload["session"]?.jsonPrimitive?.contentOrNull ?: return
                    // The reducer has already removed the session, so we resolve the ID from the action payload,
                    // not from the current (already modified) state.
                    val sessionIdToDelete = if (identifier.startsWith("fake-uuid-") || identifier.length > 20) {
                        identifier
                    } else {
                        // This fallback is now less reliable since the session is gone, but we keep it for robustness.
                        // The primary path is resolving by ID directly from the action.
                        sessionState.sessions.entries.find { it.value.name == identifier }?.key ?: identifier
                    }
                    store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE", buildJsonObject {
                        put("subpath", "$sessionIdToDelete.json")
                    }))
                }
            }
            "session.REQUEST_SESSION_NAMES" -> {
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                broadcastSessionNames(sessionState, store)
            }
            "session.POST", "session.UPDATE_MESSAGE", "session.DELETE_MESSAGE",
            "session.TOGGLE_MESSAGE_COLLAPSED", "session.TOGGLE_MESSAGE_RAW_VIEW" -> {
                val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
                val sessionId = when (val payload = action.payload) {
                    is JsonObject -> payload["sessionId"]?.jsonPrimitive?.contentOrNull ?: resolveSessionIdFromGenericPayload(payload, sessionState)
                    else -> null
                }
                sessionId?.let { persistSession(it, store) }
            }
        }
    }

    private fun resolveSessionIdFromGenericPayload(payload: JsonObject, state: SessionState): String? {
        val identifier = payload["session"]?.jsonPrimitive?.contentOrNull ?: return null
        return resolveSessionId(identifier, state)
    }

    private fun persistSession(sessionId: String, store: Store) {
        val sessionState = store.state.value.featureStates[name] as? SessionState ?: return
        val sessionToSave = sessionState.sessions[sessionId] ?: return
        val fileContent = json.encodeToString(sessionToSave)
        store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
            put("subpath", "${sessionToSave.id}.json")
            put("content", fileContent)
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        val nameMap = state.sessions.mapValues { it.value.name }
        val payload = buildJsonObject { put("names", Json.encodeToJsonElement(nameMap)) }
        store.dispatch(this.name, Action("session.publish.SESSION_NAMES_UPDATED", payload))
    }


    // --- Reducer and Helpers ---

    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        if (state.sessions.containsKey(identifier)) return identifier
        val sessionsByName = state.sessions.values.filter { it.name == identifier }
        return if (sessionsByName.size == 1) sessionsByName.first().id else null
    }

    private fun findUniqueName(desiredName: String, state: SessionState): String {
        val existingNames = state.sessions.values.map { it.name }.toSet()
        if (!existingNames.contains(desiredName)) return desiredName
        var counter = 2
        var newName: String
        do {
            newName = "$desiredName-$counter"
            counter++
        } while (existingNames.contains(newName))
        platformDependencies.log(LogLevel.WARN, name, "Session name collision. Renamed '$desiredName' to '$newName'.")
        return newName
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? SessionState ?: SessionState()
        var newFeatureState: SessionState? = null

        val payload = action.payload

        when (action.name) {
            "system.INITIALIZING" -> newFeatureState = currentFeatureState.copy()
            "agent.publish.AGENT_NAMES_UPDATED" -> {
                try {
                    val names = payload?.get("names")?.let { json.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) } ?: return state
                    newFeatureState = currentFeatureState.copy(agentNames = names)
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Payload decoding failed for '${action.name}'. Error: ${e.message}")
                    return state
                }
            }
            "session.CREATE" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(CreatePayload.serializer(), it) } } catch(e: Exception) { null }
                val desiredName = decoded?.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val uniqueName = findUniqueName(desiredName, currentFeatureState)
                val newSession = Session(id = platformDependencies.generateUUID(), name = uniqueName, ledger = emptyList(), createdAt = platformDependencies.getSystemTimeMillis())
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (newSession.id to newSession), activeSessionId = newSession.id)
            }
            "session.UPDATE_CONFIG" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(UpdateConfigPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val session = currentFeatureState.sessions[sessionId] ?: return state
                val uniqueName = findUniqueName(decoded.name, currentFeatureState)
                val updatedSession = session.copy(name = uniqueName)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingSessionId = null)
            }
            "session.POST" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(PostPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val newEntry = LedgerEntry(id = platformDependencies.generateUUID(), timestamp = platformDependencies.getSystemTimeMillis(), agentId = decoded.agentId, rawContent = decoded.message, content = blockParser.parse(decoded.message))
                val updatedSession = targetSession.copy(ledger = targetSession.ledger + newEntry)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.DELETE" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(SessionTargetPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = if (currentFeatureState.activeSessionId != sessionId) currentFeatureState.activeSessionId else newSessions.values.maxByOrNull { it.createdAt }?.id
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
            "session.UPDATE_MESSAGE" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(UpdateMessagePayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val updatedLedger = targetSession.ledger.map { entry -> if (entry.id == decoded.messageId) entry.copy(rawContent = decoded.newContent, content = blockParser.parse(decoded.newContent)) else entry }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingMessageId = null, editingMessageContent = null)
            }
            "session.DELETE_MESSAGE" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(MessageTargetPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val updatedLedger = targetSession.ledger.filterNot { it.id == decoded.messageId }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.SET_ACTIVE_TAB" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(SessionTargetPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return state
                newFeatureState = currentFeatureState.copy(activeSessionId = sessionId)
            }
            "session.SET_EDITING_SESSION_NAME" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(SetEditingSessionPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                newFeatureState = currentFeatureState.copy(editingSessionId = decoded.sessionId)
            }
            "session.SET_EDITING_MESSAGE" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(SetEditingMessagePayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val messageId = decoded.messageId
                if (messageId == null) {
                    newFeatureState = currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                } else {
                    val targetEntry = currentFeatureState.sessions.values.flatMap { it.ledger }.find { it.id == messageId }
                    newFeatureState = currentFeatureState.copy(editingMessageId = messageId, editingMessageContent = targetEntry?.rawContent)
                }
            }
            "session.TOGGLE_MESSAGE_COLLAPSED" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(ToggleMessageUiPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return state
                val currentUiState = targetSession.messageUiState[decoded.messageId] ?: MessageUiState()
                val newUiState = currentUiState.copy(isCollapsed = !currentUiState.isCollapsed)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (decoded.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }
            "session.TOGGLE_MESSAGE_RAW_VIEW" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(ToggleMessageUiPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return state
                val currentUiState = targetSession.messageUiState[decoded.messageId] ?: MessageUiState()
                val newUiState = currentUiState.copy(isRawView = !currentUiState.isRawView)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (decoded.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }
            "session.internal.LOADED" -> {
                val decoded = try { payload?.let { json.decodeFromJsonElement(InternalSessionLoadedPayload.serializer(), it) } } catch(e: Exception) { null } ?: return state
                val loadedSessions = decoded.sessions
                val newSessions = currentFeatureState.sessions + loadedSessions.filterKeys { !currentFeatureState.sessions.containsKey(it) }
                val newActiveId = if (currentFeatureState.activeSessionId == null && newSessions.isNotEmpty()) newSessions.values.maxByOrNull { it.createdAt }?.id else currentFeatureState.activeSessionId
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    override val composableProvider = object : Feature.ComposableProvider {
        private val VIEW_KEY_MAIN = "feature.session.main"
        private val VIEW_KEY_MANAGER = "feature.session.manager"
        override val stageViews: Map<String, @Composable (Store) -> Unit> = mapOf(VIEW_KEY_MAIN to { SessionView(it) }, VIEW_KEY_MANAGER to { SessionsManagerView(it) })
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