package app.auf.feature.session

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.auf.core.*
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// --- 1. MODEL ---

@Serializable
data class SessionFeatureState(
    val sessions: Map<String, Session> = emptyMap(),
    val isRawContentVisible: Boolean = false,
    val rawContentViewIds: Set<Long> = emptySet()
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

sealed interface SessionAction : AppAction {
    data class CreateSession(val id: String, val name: String) : SessionAction
    data class PostEntry(val sessionId: String, val agentId: String, val content: String) : SessionAction
    data class DeleteEntry(val sessionId: String, val entryId: Long) : SessionAction
    data class ClearSession(val sessionId: String) : SessionAction
    data class LoadSessionsSuccess(val sessions: Map<String, Session>) : SessionAction
    data object ToggleRawContentView : SessionAction
    data class ToggleMessageRawView(val entryId: Long) : SessionAction
}


// --- 3. FEATURE IMPLEMENTATION ---

class SessionFeature(
    private val platform: PlatformDependencies,
    private val jsonParser: Json,
    private val coroutineScope: CoroutineScope,
    private val allFeatures: List<Feature>
) : Feature {

    override val name: String = "SessionFeature"
    override val composableProvider: Feature.ComposableProvider = SessionComposableProvider()

    private val persistenceService = SessionPersistenceService(platform, jsonParser)
    private val blockParser = BlockSeparatingParser()
    private val commandInterpreter = CommandInterpreter()
    private val nextEntryIdCounters = mutableMapOf<String, Long>()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is SessionAction) return state
        val currentState = state.featureStates[name] as? SessionFeatureState ?: SessionFeatureState()

        val newFeatureState = when (action) {
            is SessionAction.ToggleMessageRawView -> {
                val newSet = if (currentState.rawContentViewIds.contains(action.entryId)) {
                    currentState.rawContentViewIds - action.entryId
                } else {
                    currentState.rawContentViewIds + action.entryId
                }
                currentState.copy(rawContentViewIds = newSet)
            }
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
            is SessionAction.DeleteEntry -> {
                val targetSession = currentState.sessions[action.sessionId] ?: return state
                val updatedTranscript = targetSession.transcript.filter { it.id != action.entryId }
                val updatedSession = targetSession.copy(transcript = updatedTranscript)
                currentState.copy(sessions = currentState.sessions + (action.sessionId to updatedSession))
            }
            is SessionAction.ClearSession -> {
                val targetSession = currentState.sessions[action.sessionId] ?: return state
                nextEntryIdCounters[action.sessionId] = 0L // Reset the counter for this session
                val updatedSession = targetSession.copy(transcript = emptyList())
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
            is SessionAction.ToggleRawContentView -> {
                currentState.copy(isRawContentVisible = !currentState.isRawContentVisible)
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
                    store.dispatch(SessionAction.CreateSession(id = "default-session", name = "Primary Session"))
                }
            }
        }
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
        coroutineScope.launch(Dispatchers.Main) {
            var lastProcessedEntryId = -1L
            store.state
                .map { (it.featureStates[name] as? SessionFeatureState)?.sessions?.get("default-session") }
                .distinctUntilChanged()
                .collect { session ->
                    val latestEntry = session?.transcript?.lastOrNull()
                    if (session != null && latestEntry != null && latestEntry.id > lastProcessedEntryId) {
                        lastProcessedEntryId = latestEntry.id
                        if (latestEntry.agentId == "USER") {
                            val contentBlocks = blockParser.parse(latestEntry.content)
                            for (block in contentBlocks) {
                                if (block is CodeBlock) {
                                    // --- THE FIX: Use the new, more powerful interpreter ---
                                    commandInterpreter.interpret(block, session.id)?.let { action ->
                                        store.dispatch(action)
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    inner class SessionComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.session.main"

        @Composable
        override fun StageContent(stateManager: StateManager) {
            SessionView(stateManager = stateManager, features = allFeatures)
        }

        @Composable
        override fun MenuContent(stateManager: StateManager, onDismiss: () -> Unit) {
            val appState by stateManager.state.collectAsState()
            val sessionState = appState.featureStates[name] as? SessionFeatureState
            val activeSessionId = sessionState?.sessions?.keys?.firstOrNull()

            if (activeSessionId != null) {
                DropdownMenuItem(
                    text = { Text("Clear Current Session") },
                    onClick = {
                        stateManager.dispatch(SessionAction.ClearSession(activeSessionId))
                        onDismiss()
                    }
                )
            }
        }
    }

    private class SessionPersistenceService(
        private val platform: PlatformDependencies,
        private val jsonParser: Json
    ) {
        private val sessionsFilePath: String = platform.getBasePathFor(BasePath.SESSIONS) + platform.pathSeparator + "sessions.json"
        init { platform.createDirectories(platform.getBasePathFor(BasePath.SESSIONS)) }
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