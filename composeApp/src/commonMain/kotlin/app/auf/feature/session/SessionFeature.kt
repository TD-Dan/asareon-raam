package app.auf.feature.session

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
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
            // Case 1: Received a list of files from filesystem.SYSTEM_LIST
            is List<*> -> {
                val fileEntries = data.filterIsInstance<FileEntry>()
                fileEntries.filter { it.path.endsWith(".json") }.forEach { fileEntry ->
                    store.dispatch(this.name, Action("filesystem.SYSTEM_READ", buildJsonObject {
                        put("subpath", platformDependencies.getFileName(fileEntry.path))
                    }))
                }
            }
            // Case 2: Received file content from filesystem.SYSTEM_READ. Collect and dispatch once.
            is JsonObject -> {
                val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
                try {
                    val session = json.decodeFromString<Session>(content)
                    val sessionsMap = mapOf(session.id to session)
                    val payload = Json.encodeToJsonElement(InternalSessionLoadedPayload(sessionsMap))
                    // Dispatch an internal action to load this session into the state via the reducer.
                    store.dispatch(this.name, Action("session.internal.LOADED", payload as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(
                        level = LogLevel.ERROR,
                        tag = name,
                        message = "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}"
                    )
                }
            }
        }
    }

    override fun onAction(action: Action, store: Store) {
        val currentState = store.state.value.featureStates[name] as? SessionState ?: return

        // Helper to find the session ID from the action payload for persistence tasks.
        val targetSessionId = when (action.name) {
            "session.POST", "session.UPDATE_CONFIG", "session.DELETE",
            "session.UPDATE_MESSAGE", "session.DELETE_MESSAGE" -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return
                resolveSessionId(identifier, currentState)
            }
            else -> null
        }

        when (action.name) {
            "system.STARTING" -> {
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
            }
            "session.POST", "session.UPDATE_CONFIG", "session.UPDATE_MESSAGE", "session.DELETE_MESSAGE" -> {
                targetSessionId?.let { persistSession(it, store) }
            }
            "session.DELETE" -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return
                // For DELETE, the session is already gone from the state, so we use the raw identifier if it looks like a UUID.
                val sessionIdToDelete = if (identifier.startsWith("fake-uuid-") || identifier.length > 20) {
                    identifier
                } else {
                    // This is a best-effort for name-based deletion; not guaranteed if name was ambiguous.
                    currentState.sessions.entries.find { it.value.name == identifier }?.key ?: identifier
                }
                store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE", buildJsonObject {
                    put("subpath", "$sessionIdToDelete.json")
                }))
            }
        }
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

    // --- Reducer and Helpers ---

    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        // Priority 1: Direct match by ID
        if (state.sessions.containsKey(identifier)) {
            return identifier
        }
        // Priority 2: Unique match by name
        val sessionsByName = state.sessions.values.filter { it.name == identifier }
        return if (sessionsByName.size == 1) {
            sessionsByName.first().id
        } else {
            // Log ambiguity or failure and return null
            platformDependencies.log(
                LogLevel.WARN, name,
                "Could not resolve session identifier '$identifier'. Found ${sessionsByName.size} matches by name."
            )
            null
        }
    }

    private fun findUniqueName(desiredName: String, state: SessionState): String {
        val existingNames = state.sessions.values.map { it.name }.toSet()
        if (!existingNames.contains(desiredName)) {
            return desiredName
        }
        var counter = 2
        var newName: String
        do {
            newName = "$desiredName-$counter"
            counter++
        } while (existingNames.contains(newName))

        platformDependencies.log(
            LogLevel.WARN, name,
            "Session name collision. Renamed '$desiredName' to '$newName'."
        )
        return newName
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? SessionState ?: SessionState()
        var newFeatureState: SessionState? = null

        val getTargetSessionId: (JsonObject) -> String? = { payload ->
            payload["session"]?.jsonPrimitive?.contentOrNull?.let {
                resolveSessionId(it, currentFeatureState)
            }
        }

        when (action.name) {
            "system.INITIALIZING" -> newFeatureState = currentFeatureState.copy()
            "session.CREATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CreatePayload>(it) }
                val desiredName = payload?.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val uniqueName = findUniqueName(desiredName, currentFeatureState)

                val newSession = Session(
                    id = platformDependencies.generateUUID(),
                    name = uniqueName,
                    ledger = emptyList(),
                    createdAt = platformDependencies.getSystemTimeMillis()
                )
                newFeatureState = currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (newSession.id to newSession),
                    activeSessionId = newSession.id // Automatically make the new session active
                )
            }
            "session.UPDATE_CONFIG" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                val session = currentFeatureState.sessions[sessionId] ?: return state

                val uniqueName = findUniqueName(payload.name, currentFeatureState)
                val updatedSession = session.copy(name = uniqueName)
                newFeatureState = currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (sessionId to updatedSession),
                    editingSessionId = null
                )
            }
            "session.POST" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PostPayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state

                val newEntry = LedgerEntry(
                    id = platformDependencies.generateUUID(),
                    timestamp = platformDependencies.getSystemTimeMillis(),
                    agentId = payload.agentId,
                    rawContent = payload.message,
                    content = blockParser.parse(payload.message)
                )
                val updatedSession = targetSession.copy(ledger = targetSession.ledger + newEntry)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.DELETE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SessionTargetPayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = when {
                    currentFeatureState.activeSessionId != sessionId -> currentFeatureState.activeSessionId
                    newSessions.isNotEmpty() -> newSessions.values.maxByOrNull { it.createdAt }?.id
                    else -> null
                }
                newFeatureState = currentFeatureState.copy(sessions = newSessions, activeSessionId = newActiveId)
            }
            "session.UPDATE_MESSAGE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state

                val updatedLedger = targetSession.ledger.map { entry ->
                    if (entry.id == payload.messageId) {
                        entry.copy(
                            rawContent = payload.newContent,
                            content = blockParser.parse(payload.newContent)
                        )
                    } else {
                        entry
                    }
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (sessionId to updatedSession),
                    editingMessageId = null,
                    editingMessageContent = null
                )
            }
            "session.DELETE_MESSAGE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<MessageTargetPayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                val targetSession = currentFeatureState.sessions[sessionId] ?: return state
                val updatedLedger = targetSession.ledger.filterNot { it.id == payload.messageId }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            "session.SET_ACTIVE_TAB" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SessionTargetPayload>(it) } ?: return state
                val sessionId = resolveSessionId(payload.session, currentFeatureState) ?: return state
                newFeatureState = currentFeatureState.copy(activeSessionId = sessionId)
            }
            "session.SET_EDITING_SESSION_NAME" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetEditingSessionPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(editingSessionId = payload.sessionId)
            }
            "session.SET_EDITING_MESSAGE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetEditingMessagePayload>(it) } ?: return state
                val messageId = payload.messageId
                if (messageId == null) {
                    newFeatureState = currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                } else {
                    // Find the message across all sessions to preload its content
                    val targetEntry = currentFeatureState.sessions.values
                        .flatMap { it.ledger }
                        .find { it.id == messageId }

                    newFeatureState = currentFeatureState.copy(
                        editingMessageId = messageId,
                        editingMessageContent = targetEntry?.rawContent
                    )
                }
            }
            "session.TOGGLE_MESSAGE_COLLAPSED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return state
                val targetSession = currentFeatureState.sessions[payload.sessionId] ?: return state
                val currentUiState = targetSession.messageUiState[payload.messageId] ?: MessageUiState()
                val newUiState = currentUiState.copy(isCollapsed = !currentUiState.isCollapsed)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (payload.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (payload.sessionId to updatedSession))
            }
            "session.TOGGLE_MESSAGE_RAW_VIEW" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return state
                val targetSession = currentFeatureState.sessions[payload.sessionId] ?: return state
                val currentUiState = targetSession.messageUiState[payload.messageId] ?: MessageUiState()
                val newUiState = currentUiState.copy(isRawView = !currentUiState.isRawView)
                val updatedSession = targetSession.copy(messageUiState = targetSession.messageUiState + (payload.messageId to newUiState))
                newFeatureState = currentFeatureState.copy(sessions = currentFeatureState.sessions + (payload.sessionId to updatedSession))

            }
            "session.internal.LOADED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) } ?: return state
                val loadedSessions = payload.sessions
                val newSessions = currentFeatureState.sessions + loadedSessions.filterKeys { !currentFeatureState.sessions.containsKey(it) }
                val newActiveId = if (currentFeatureState.activeSessionId == null && newSessions.isNotEmpty()) {
                    newSessions.values.maxByOrNull { it.createdAt }?.id
                } else {
                    currentFeatureState.activeSessionId
                }
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

        override val stageViews: Map<String, @Composable (Store) -> Unit> = mapOf(
            VIEW_KEY_MAIN to { store -> SessionView(store) },
            VIEW_KEY_MANAGER to { store -> SessionsManagerView(store) }
        )

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            IconButton(onClick = {
                store.dispatch("session.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", VIEW_KEY_MANAGER) }))
            }) {
                Icon(
                    imageVector = Icons.Default.ViewList,
                    contentDescription = "Session Manager",
                    tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                store.dispatch("session.ui", Action("core.SET_ACTIVE_VIEW", buildJsonObject { put("key", VIEW_KEY_MAIN) }))
            }) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = "Active Session",
                    tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}