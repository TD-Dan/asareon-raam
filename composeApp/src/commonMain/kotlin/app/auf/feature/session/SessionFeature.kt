package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "SessionFeature"

    // Private, serializable data classes for decoding action payloads safely.
    @Serializable private data class CreateSessionPayload(val name: String? = null)
    @Serializable private data class PostPayload(val sessionId: String, val agentId: String, val message: String)
    @Serializable private data class SessionIdPayload(val sessionId: String)

    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true }

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

                // Create the new state by adding the new session to the map.
                // This does not touch any other properties like activeSessionId.
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
        }

        // If the feature's state has changed, create a new AppState with the updated slice.
        // Otherwise, return the original state to prevent unnecessary recompositions.
        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "session.POST" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<PostPayload>(it) } ?: return
                persistSession(store, payload.sessionId)
            }
        }
    }

    private fun persistSession(store: Store, sessionId: String) {
        val currentState = store.state.value.featureStates[name] as? SessionState ?: return
        val sessionToSave = currentState.sessions[sessionId] ?: return

        val sessionsBasePath = platformDependencies.getBasePathFor(BasePath.SESSIONS)
        platformDependencies.createDirectories(sessionsBasePath) // Ensure the directory exists
        val filePath = "$sessionsBasePath${platformDependencies.pathSeparator}$sessionId.json"

        val fileContent = json.encodeToString(sessionToSave)

        val fileSystemPayload = buildJsonObject {
            put("path", filePath)
            put("newContent", fileContent)
        }

        // We use STAGE_UPDATE because it handles both creation and update safely.
        store.dispatch(this.name, Action("filesystem.STAGE_UPDATE", fileSystemPayload))
    }

    override val composableProvider: Feature.ComposableProvider?
        get() = null // To be implemented
}