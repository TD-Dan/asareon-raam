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
    val rawContentViewIds: Set<String> = emptySet() // Now uses stable String ID
) : FeatureState

@Serializable
data class Session(
    val id: String,
    val name: String,
    val transcript: List<LedgerEntry> = emptyList()
)

// The LedgerEntry is now a sealed interface to support different types of content.
@Serializable
sealed interface LedgerEntry {
    val entryId: String // Use a stable, unique String ID for keys and lookups.
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
        val agentId: String,
        val parentEntryId: String? // For inserting turns mid-transcript
    ) : LedgerEntry, TransientEntry // Implements TransientEntry to prevent persistence
}


// --- 2. ACTIONS ---

sealed interface SessionAction : AppAction {
    // User-facing actions
    data class PostUserMessage(val sessionId: String, val content: String) : SessionAction
    data class DeleteEntry(val sessionId: String, val entryId: String) : SessionAction
    data class ClearSession(val sessionId: String) : SessionAction
    data object ToggleRawContentView : SessionAction
    data class ToggleMessageRawView(val entryId: String) : SessionAction

    // System actions for persistence
    data class _CreateSession(val id: String, val name: String) : SessionAction
    data class _LoadSessionsSuccess(val sessions: Map<String, Session>) : SessionAction
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

    override fun reducer(state: AppState, action: AppAction): AppState {
        val currentState = state.featureStates[name] as? SessionFeatureState ?: SessionFeatureState()
        val targetSessionId = "default-session" // Assuming single session for now
        val targetSession = currentState.sessions[targetSessionId]

        // Handle Agent Actions first, as they are central to the new architecture
        if (action is AgentAction && targetSession != null) {
            val newTranscript = when (action) {
                is AgentAction.TurnBegan -> {
                    val newEntry = LedgerEntry.AgentTurn(
                        entryId = action.turnId,
                        timestamp = platform.getSystemTimeMillis(),
                        agentId = action.agentId,
                        parentEntryId = action.parentEntryId
                    )
                    // If parentEntryId is specified, insert after it. Otherwise, append.
                    val insertIndex = action.parentEntryId?.let { pid ->
                        targetSession.transcript.indexOfFirst { it.entryId == pid }
                    }
                    if (insertIndex != null && insertIndex != -1) {
                        targetSession.transcript.toMutableList().apply { add(insertIndex + 1, newEntry) }
                    } else {
                        targetSession.transcript + newEntry
                    }
                }
                is AgentAction.TurnCompleted -> {
                    targetSession.transcript.flatMap {
                        if (it is LedgerEntry.AgentTurn && it.entryId == action.turnId) {
                            listOf(LedgerEntry.Message(
                                entryId = platform.generateUUID(),
                                timestamp = platform.getSystemTimeMillis(),
                                agentId = it.agentId,
                                content = action.content
                            ))
                        } else {
                            listOf(it)
                        }
                    }
                }
                is AgentAction.TurnCancelled, is AgentAction.TurnFailed -> {
                    targetSession.transcript.filterNot { it is LedgerEntry.AgentTurn && it.entryId == action.turnId }
                }
            }
            val updatedSession = targetSession.copy(transcript = newTranscript)
            val newFeatureState = currentState.copy(sessions = currentState.sessions + (targetSessionId to updatedSession))
            return state.copy(featureStates = state.featureStates + (name to newFeatureState))
        }


        // Handle Session-specific actions
        if (action !is SessionAction) return state
        val newFeatureState = when (action) {
            is SessionAction.ToggleMessageRawView -> {
                val newSet = if (currentState.rawContentViewIds.contains(action.entryId)) {
                    currentState.rawContentViewIds - action.entryId
                } else {
                    currentState.rawContentViewIds + action.entryId
                }
                currentState.copy(rawContentViewIds = newSet)
            }
            is SessionAction._CreateSession -> {
                val newSession = Session(id = action.id, name = action.name)
                currentState.copy(sessions = currentState.sessions + (action.id to newSession))
            }
            is SessionAction.PostUserMessage -> {
                if (targetSession == null) return state
                val newEntry = LedgerEntry.Message(
                    entryId = platform.generateUUID(),
                    agentId = "USER",
                    content = blockParser.parse(action.content),
                    timestamp = platform.getSystemTimeMillis()
                )
                val updatedTranscript = targetSession.transcript + newEntry
                val updatedSession = targetSession.copy(transcript = updatedTranscript)
                currentState.copy(sessions = currentState.sessions + (targetSessionId to updatedSession))
            }
            is SessionAction.DeleteEntry -> {
                if (targetSession == null) return state
                val updatedTranscript = targetSession.transcript.filter { it.entryId != action.entryId }
                val updatedSession = targetSession.copy(transcript = updatedTranscript)
                currentState.copy(sessions = currentState.sessions + (targetSessionId to updatedSession))
            }
            is SessionAction.ClearSession -> {
                if (targetSession == null) return state
                val updatedSession = targetSession.copy(transcript = emptyList())
                currentState.copy(sessions = currentState.sessions + (targetSessionId to updatedSession))
            }
            is SessionAction._LoadSessionsSuccess -> currentState.copy(sessions = action.sessions)
            is SessionAction.ToggleRawContentView -> currentState.copy(isRawContentVisible = !currentState.isRawContentVisible)
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        // Persistence logic remains largely the same, but uses internal actions
        coroutineScope.launch(Dispatchers.Default) {
            val loadedSessions = persistenceService.loadSessions()
            withContext(Dispatchers.Main) {
                if (!loadedSessions.isNullOrEmpty()) {
                    store.dispatch(SessionAction._LoadSessionsSuccess(loadedSessions))
                } else {
                    store.dispatch(SessionAction._CreateSession(id = "default-session", name = "Primary Session"))
                }
            }
        }
        coroutineScope.launch(Dispatchers.Default) {
            store.stateFlow
                .map { (it.featureStates[name] as? SessionFeatureState)?.sessions }
                .distinctUntilChanged()
                .drop(1) // Don't save on initial load
                .collect { sessionsToSave ->
                    if (sessionsToSave != null) {
                        persistenceService.saveSessions(sessionsToSave)
                    }
                }
        }

        // Command Interpreter Logic
        coroutineScope.launch(Dispatchers.Main) {
            var lastProcessedEntryId: String? = null
            store.stateFlow
                .map { (it.featureStates[name] as? SessionFeatureState)?.sessions?.get("default-session")?.transcript?.lastOrNull() }
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

        @Composable
        override fun StageContent(stateManager: StateManager) {
            SessionView(stateManager = stateManager, features = allFeatures)
        }

        @Composable
        override fun MenuContent(stateManager: StateManager, onDismiss: () -> Unit) {
            // This logic remains the same
            DropdownMenuItem(
                text = { Text("Clear Current Session") },
                onClick = {
                    stateManager.dispatch(SessionAction.ClearSession("default-session"))
                    onDismiss()
                }
            )
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
                // IMPORTANT: Filter out transient entries before saving.
                val persistentSessions = sessions.mapValues { (_, session) ->
                    session.copy(transcript = session.transcript.filter { it !is TransientEntry })
                }
                val serializer = MapSerializer(serializer<String>(), serializer<Session>())
                val jsonString = jsonParser.encodeToString(serializer, persistentSessions)
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