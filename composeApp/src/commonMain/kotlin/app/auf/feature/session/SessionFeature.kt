package app.auf.feature.session

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "SessionFeature"

    // Private, serializable data classes for decoding action payloads safely.
    @Serializable private data class CreateSessionPayload(val name: String? = null)

    private val blockParser = BlockSeparatingParser()

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
        }

        // If the feature's state has changed, create a new AppState with the updated slice.
        // Otherwise, return the original state to prevent unnecessary recompositions.
        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }

    override fun onAction(action: Action, store: Store) {
        // To be implemented via TDD
    }

    override val composableProvider: Feature.ComposableProvider?
        get() = null // To be implemented
}