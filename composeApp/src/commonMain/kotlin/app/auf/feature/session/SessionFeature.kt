package app.auf.feature.session

import app.auf.core.*
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// --- 1. MODEL ---

/**
 * ---
 * ## Mandate
 * Defines the complete data model for the Session feature. It is a pure data container
 * for a map of all sessions, with no concept of which one is "active".
 */
@Serializable
data class SessionFeatureState(
    val sessions: Map<String, Session> = emptyMap()
) : FeatureState

@Serializable
data class Session(
    val id: String,
    val name: String,
    val transcript: List<LedgerEntry> = emptyList()
)

@Serializable
data class LedgerEntry(
    val id: Long,
    val agentId: String,
    val content: String,
    val timestamp: Long
)


// --- 2. ACTIONS ---

/**
 * ---
 * ## Mandate
 * Defines actions to manipulate the collection of sessions. Actions are explicit and
 * target sessions by their unique ID.
 */
sealed interface SessionAction : AppAction {
    data class CreateSession(val id: String, val name: String) : SessionAction
    data class PostEntry(val sessionId: String, val agentId: String, val content: String) : SessionAction
    data class LoadSessionsSuccess(val sessions: Map<String, Session>) : SessionAction
}


// --- 3. FEATURE IMPLEMENTATION ---

/**
 * ---
 * ## Mandate
 * Implements the "Public Ledger" architectural pattern. Its sole responsibility is to manage
 * the state of all conversation transcripts. It is a self-contained, autonomous plugin that
 * also handles its own data persistence as a side effect.
 */
class SessionFeature(
    private val platform: PlatformDependencies,
    private val jsonParser: Json,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "SessionFeature"

    private val persistenceService = SessionPersistenceService(platform, jsonParser)
    private val nextEntryIdCounters = mutableMapOf<String, Long>()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is SessionAction) return state
        val currentState = state.featureStates[name] as? SessionFeatureState ?: SessionFeatureState()

        val newFeatureState = when (action) {
            is SessionAction.CreateSession -> {
                val newSession = Session(id = action.id, name = action.name)
                currentState.copy(
                    sessions = currentState.sessions + (action.id to newSession)
                )
            }
            is SessionAction.PostEntry -> {
                val targetSession = currentState.sessions[action.sessionId] ?: return state

                val nextId = nextEntryIdCounters.getOrPut(action.sessionId) { 0L } + 1
                nextEntryIdCounters[action.sessionId] = nextId

                val newEntry = LedgerEntry(
                    id = nextId,
                    agentId = action.agentId,
                    content = action.content,
                    timestamp = platform.getSystemTimeMillis()
                )

                val updatedTranscript = targetSession.transcript + newEntry
                val updatedSession = targetSession.copy(transcript = updatedTranscript)

                currentState.copy(sessions = currentState.sessions + (action.sessionId to updatedSession))
            }
            is SessionAction.LoadSessionsSuccess -> {
                action.sessions.values.forEach { session ->
                    val maxId = session.transcript.maxOfOrNull { it.id } ?: 0L
                    nextEntryIdCounters[session.id] = maxId
                }
                currentState.copy(
                    sessions = action.sessions
                )
            }
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        coroutineScope.launch(Dispatchers.Default) {
            val loadedSessions = persistenceService.loadSessions()
            withContext(Dispatchers.Main) {
                if (!loadedSessions.isNullOrEmpty()) {
                    store.dispatch(SessionAction.LoadSessionsSuccess(loadedSessions))
                } else {
                    // Create a default session on first launch if none exist.
                    store.dispatch(SessionAction.CreateSession(id = "default-session", name = "Primary Session"))
                }
            }
        }

        // Autonomous persistence side-effect
        coroutineScope.launch(Dispatchers.Default) {
            store.state
                .map { (it.featureStates[name] as? SessionFeatureState)?.sessions }
                .distinctUntilChanged()
                .drop(1)
                .collect { sessionsToSave ->
                    if (sessionsToSave != null) {
                        persistenceService.saveSessions(sessionsToSave)
                    }
                }
        }
    }

    private class SessionPersistenceService(
        private val platform: PlatformDependencies,
        private val jsonParser: Json
    ) {
        private val sessionsFilePath: String = platform.getBasePathFor(BasePath.SESSIONS) + platform.pathSeparator + "sessions.json"

        init {
            platform.createDirectories(platform.getBasePathFor(BasePath.SESSIONS))
        }

        fun saveSessions(sessions: Map<String, Session>) {
            try {
                val serializer = MapSerializer(serializer<String>(), serializer<Session>())
                val jsonString = jsonParser.encodeToString(serializer, sessions)
                platform.writeFileContent(sessionsFilePath, jsonString)
            } catch (e: Exception) {
                println("ERROR: Could not save sessions file: ${e.message}")
            }
        }

        fun loadSessions(): Map<String, Session>? {
            if (!platform.fileExists(sessionsFilePath)) return null
            return try {
                val jsonString = platform.readFileContent(sessionsFilePath)
                if (jsonString.isBlank()) null else {
                    val serializer = MapSerializer(serializer<String>(), serializer<Session>())
                    jsonParser.decodeFromString(serializer, jsonString)
                }
            } catch (e: Exception) {
                println("WARNING: Could not parse sessions file. Starting fresh. Error: ${e.message}")
                null
            }
        }
    }
}