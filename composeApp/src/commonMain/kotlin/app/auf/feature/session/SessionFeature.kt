// --- FILE: SessionFeature.kt ---
package app.auf.feature.session

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val rawContentViewIds: Set<String> = emptySet()
) : FeatureState

@Serializable
data class Session(
    val id: String,
    val name: String,
    val transcript: List<LedgerEntry> = emptyList()
)

@Serializable
sealed interface LedgerEntry {
    val entryId: String
    val timestamp: Long

    @Serializable
    data class Message(
        override val entryId: String,
        override val timestamp: Long,
        val agentId: String,
        val content: List<ContentBlock>,
    ) : LedgerEntry

    @Serializable
    data class AgentTurn(
        override val entryId: String, // This is the turnId
        override val timestamp: Long,
        val agentId: String, // This is the FEATURE'S name, used for rendering
        val parentEntryId: String?
    ) : LedgerEntry, TransientEntry
}


// --- 2. ACTIONS ---

data class PostUserMessage(val sessionId: String, val content: String) : Command
data class DeleteEntry(val sessionId: String, val entryId: String) : Command
data class ClearSession(val sessionId: String) : Command
data object ToggleRawContentView : Command
data class ToggleMessageRawView(val entryId: String) : Command
data class CreateSession(val id: String, val name: String) : Event
data class LoadSessionsSuccess(val sessions: Map<String, Session>) : Event


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

    override fun reducer(state: AppState, action: AppAction): AppState {
        val featureState = state.featureStates[name] as? SessionFeatureState ?: return state
        val targetSessionId = "default-session"
        val targetSession = featureState.sessions[targetSessionId]

        val newFeatureState: SessionFeatureState = when (action) {
            // --- AGENT EVENTS & COMMANDS ---
            is AgentEvent.TurnBegan -> {
                if (targetSession == null) return state
                val newEntry = LedgerEntry.AgentTurn(
                    entryId = action.turnId,
                    timestamp = platform.getSystemTimeMillis(),
                    agentId = action.agentId, // The feature name for the renderer
                    parentEntryId = action.parentEntryId
                )
                val newTranscript = targetSession.transcript + newEntry
                val updatedSession = targetSession.copy(transcript = newTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            is AgentEvent.TurnCompleted -> {
                if (targetSession == null) return state
                val newTranscript = targetSession.transcript.map {
                    if (it is LedgerEntry.AgentTurn && it.entryId == action.turnId) {
                        LedgerEntry.Message(
                            entryId = platform.generateUUID(),
                            timestamp = platform.getSystemTimeMillis(),
                            agentId = it.agentId, // Preserve the original agent/feature ID
                            content = action.content
                        )
                    } else { it }
                }
                val updatedSession = targetSession.copy(transcript = newTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            is AgentEvent.TurnFailed -> {
                if (targetSession == null) return state
                val newTranscript = targetSession.transcript.map {
                    if (it is LedgerEntry.AgentTurn && it.entryId == action.turnId) {
                        LedgerEntry.Message(
                            entryId = platform.generateUUID(),
                            timestamp = platform.getSystemTimeMillis(),
                            agentId = "CORE", // System-level failure
                            content = listOf(TextBlock("ERROR: Agent turn failed. ${action.error}"))
                        )
                    } else { it }
                }
                val updatedSession = targetSession.copy(transcript = newTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            is AgentCommand.TurnCancelled -> {
                if (targetSession == null) return state
                val newTranscript = targetSession.transcript.filterNot { it is LedgerEntry.AgentTurn && it.entryId == action.turnId }
                val updatedSession = targetSession.copy(transcript = newTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }

            // --- SESSION-SPECIFIC ACTIONS ---
            is ToggleMessageRawView -> {
                val newSet = if (featureState.rawContentViewIds.contains(action.entryId)) {
                    featureState.rawContentViewIds - action.entryId
                } else {
                    featureState.rawContentViewIds + action.entryId
                }
                featureState.copy(rawContentViewIds = newSet)
            }
            is CreateSession -> {
                val newSession = Session(id = action.id, name = action.name)
                featureState.copy(sessions = featureState.sessions + (action.id to newSession))
            }
            is PostUserMessage -> {
                if (targetSession == null) return state
                val newEntry = LedgerEntry.Message(
                    entryId = platform.generateUUID(),
                    agentId = "USER",
                    content = blockParser.parse(action.content),
                    timestamp = platform.getSystemTimeMillis()
                )
                val updatedTranscript = targetSession.transcript + newEntry
                val updatedSession = targetSession.copy(transcript = updatedTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            // ... other session actions remain the same
            is DeleteEntry -> {
                if (targetSession == null) return state
                val updatedTranscript = targetSession.transcript.filter { it.entryId != action.entryId }
                val updatedSession = targetSession.copy(transcript = updatedTranscript)
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            is ClearSession -> {
                if (targetSession == null) return state
                val updatedSession = targetSession.copy(transcript = emptyList())
                featureState.copy(sessions = featureState.sessions + (targetSessionId to updatedSession))
            }
            is LoadSessionsSuccess -> featureState.copy(sessions = action.sessions)
            is ToggleRawContentView -> featureState.copy(isRawContentVisible = !featureState.isRawContentVisible)

            else -> return state
        }

        return if (newFeatureState != featureState) {
            state.copy(featureStates = state.featureStates + (name to newFeatureState))
        } else {
            state
        }
    }

    // --- start() and other methods remain unchanged ---
    override fun start(store: Store) {
        coroutineScope.launch(Dispatchers.Default) {
            val loadedSessions = persistenceService.loadSessions()
            withContext(Dispatchers.Main) {
                if (!loadedSessions.isNullOrEmpty()) {
                    store.dispatch(LoadSessionsSuccess(loadedSessions))
                } else {
                    store.dispatch(CreateSession(id = "default-session", name = "Primary Session"))
                }
            }
        }
        coroutineScope.launch(Dispatchers.Default) {
            store.state
                .map<AppState, Map<String, Session>?> { (it.featureStates[name] as? SessionFeatureState)?.sessions }
                .distinctUntilChanged()
                .drop(1)
                .collect { sessionsToSave ->
                    if (sessionsToSave != null) {
                        persistenceService.saveSessions(sessionsToSave)
                    }
                }
        }
        coroutineScope.launch(Dispatchers.Main) {
            var lastProcessedEntryId: String? = null
            store.state
                .map<AppState, LedgerEntry?> { (it.featureStates[name] as? SessionFeatureState)?.sessions?.get("default-session")?.transcript?.lastOrNull() }
                .distinctUntilChanged()
                .collect { latestEntry ->
                    if (latestEntry != null && latestEntry is LedgerEntry.Message && latestEntry.entryId != lastProcessedEntryId) {
                        lastProcessedEntryId = latestEntry.entryId
                        if (latestEntry.agentId == "USER") {
                            latestEntry.content.forEach { block ->
                                if (block is CodeBlock) {
                                    commandInterpreter.interpret(block, "default-session")?.let { action ->
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
        @Composable override fun StageContent(stateManager: StateManager) { SessionView(stateManager = stateManager, features = allFeatures) }
        @Composable override fun MenuContent(stateManager: StateManager, onDismiss: () -> Unit) {
            DropdownMenuItem( text = { Text("Clear Current Session") }, onClick = { stateManager.dispatch(ClearSession("default-session")); onDismiss() })
        }
    }
    private class SessionPersistenceService(private val platform: PlatformDependencies, private val jsonParser: Json) {
        private val sessionsFilePath: String = platform.getBasePathFor(BasePath.SESSIONS) + platform.pathSeparator + "sessions.json"
        init { platform.createDirectories(platform.getBasePathFor(BasePath.SESSIONS)) }
        fun saveSessions(sessions: Map<String, Session>) {
            try {
                val persistentSessions = sessions.mapValues { (_, session) -> session.copy(transcript = session.transcript.filter { it !is TransientEntry }) }
                val serializer = MapSerializer(serializer<String>(), serializer<Session>())
                val jsonString = jsonParser.encodeToString(serializer, persistentSessions)
                platform.writeFileContent(sessionsFilePath, jsonString)
            } catch (e: Exception) { println("ERROR: Could not save sessions file: ${e.message}") }
        }
        fun loadSessions(): Map<String, Session>? {
            if (!platform.fileExists(sessionsFilePath)) return null
            return try {
                val jsonString = platform.readFileContent(sessionsFilePath)
                if (jsonString.isBlank()) null else {
                    val serializer = MapSerializer(serializer<String>(), serializer<Session>())
                    jsonParser.decodeFromString(serializer, jsonString)
                }
            } catch (e: Exception) { println("WARNING: Could not parse sessions file. Starting fresh. Error: ${e.message}"); null }
        }
    }
}