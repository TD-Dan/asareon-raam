package app.auf.feature.session

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
    override val name: String = "SessionFeature"

    // Private, serializable data classes for decoding action payloads safely.
    @Serializable private data class CreateSessionPayload(val name: String? = null)
    @Serializable private data class PostPayload(val sessionId: String, val agentId: String, val message: String)
    @Serializable private data class SessionIdPayload(val sessionId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val session: Session)

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
            // Case 2: Received file content from filesystem.SYSTEM_READ
            is JsonObject -> {
                val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return
                try {
                    val session = json.decodeFromString<Session>(content)
                    val payload = Json.encodeToJsonElement(InternalSessionLoadedPayload(session))
                    // Dispatch an internal action to load this session into the state via the reducer.
                    store.dispatch(this.name, Action("session.internal.SESSION_LOADED", payload as JsonObject))
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
        when (action.name) {
            "system.STARTING" -> {
                // Request a listing of our sandboxed directory to load persisted sessions.
                store.dispatch(this.name, Action("filesystem.SYSTEM_LIST"))
            }
            "session.POST" -> {
                // The reducer has already updated the state. Now we get the updated session and persist it.
                val currentState = store.state.value.featureStates[name] as? SessionState ?: return
                val payload = action.payload?.let { Json.decodeFromJsonElement<PostPayload>(it) } ?: return
                val sessionToSave = currentState.sessions[payload.sessionId] ?: return
                val fileContent = json.encodeToString(sessionToSave)

                store.dispatch(this.name, Action("filesystem.SYSTEM_WRITE", buildJsonObject {
                    put("subpath", "${payload.sessionId}.json")
                    put("content", fileContent)
                }))
            }
            "session.DELETE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SessionIdPayload>(it) } ?: return
                store.dispatch(this.name, Action("filesystem.SYSTEM_DELETE", buildJsonObject {
                    put("subpath", "${payload.sessionId}.json")
                }))
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        // Get the current state for this feature, or create a default one if it doesn't exist.
        val currentFeatureState = state.featureStates[name] as? SessionState ?: SessionState()
        var newFeatureState: SessionState? = null

        when (action.name) {
            "session.CREATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<CreateSessionPayload>(it) }
                val newId = platformDependencies.generateUUID()
                val timestamp = platformDependencies.getSystemTimeMillis()

                val newSession = Session(
                    id = newId,
                    name = payload?.name ?: "New Session", // Provide a sensible default if name is missing
                    ledger = emptyList(),
                    createdAt = timestamp
                )

                newFeatureState = currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (newId to newSession)
                )
            }
            "session.POST" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PostPayload>(it) } ?: return state
                val targetSession = currentFeatureState.sessions[payload.sessionId] ?: return state

                val newEntry = LedgerEntry(
                    id = platformDependencies.generateUUID(),
                    timestamp = platformDependencies.getSystemTimeMillis(),
                    agentId = payload.agentId,
                    rawContent = payload.message,
                    content = blockParser.parse(payload.message)
                )

                val updatedSession = targetSession.copy(ledger = targetSession.ledger + newEntry)
                newFeatureState = currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (payload.sessionId to updatedSession)
                )
            }
            "session.DELETE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SessionIdPayload>(it) } ?: return state
                if (!currentFeatureState.sessions.containsKey(payload.sessionId)) return state // No-op if not found

                val newSessions = currentFeatureState.sessions - payload.sessionId
                // If we deleted the active session, clear the active ID.
                val newActiveId = if (currentFeatureState.activeSessionId == payload.sessionId) {
                    null
                } else {
                    currentFeatureState.activeSessionId
                }

                newFeatureState = currentFeatureState.copy(
                    sessions = newSessions,
                    activeSessionId = newActiveId
                )
            }
            "session.SET_ACTIVE_TAB" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SessionIdPayload>(it) } ?: return state
                // Only update if the session ID is valid, otherwise no-op.
                if (currentFeatureState.sessions.containsKey(payload.sessionId)) {
                    newFeatureState = currentFeatureState.copy(activeSessionId = payload.sessionId)
                }
            }
            "session.internal.SESSION_LOADED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) } ?: return state
                val session = payload.session
                // Avoid overwriting a session that's already in memory from active use
                if (!currentFeatureState.sessions.containsKey(session.id)) {
                    newFeatureState = currentFeatureState.copy(
                        sessions = currentFeatureState.sessions + (session.id to session)
                    )
                }
            }
        }

        // If the feature's state has changed, create a new AppState with the updated slice.
        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    override val composableProvider: Feature.ComposableProvider?
        get() = null // To be implemented
}