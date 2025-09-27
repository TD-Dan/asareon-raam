package app.auf.feature.core

import app.auf.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The explicit lifecycle state of the application.
 * Managed exclusively by the CoreFeature in response to Actions from the main host.
 */
enum class AppLifecycle { INITIALIZING, RUNNING, CLOSING }

@Serializable
data class CoreState(
    val toastMessage: String? = null,
    val activeViewKey: String = "feature.session.main",
    val defaultViewKey: String = "feature.session.main",
    val lifecycle: AppLifecycle = AppLifecycle.INITIALIZING
) : FeatureState

/**
 * A headless feature that acts as the recorder for core application state,
 * including the application lifecycle, UI navigation, and toast messages.
 */
class CoreFeature : Feature {
    override val name: String = "CoreFeature"

    @Serializable private data class SetActiveViewPayload(val key: String)
    @Serializable private data class ShowToastPayload(val message: String)

    override fun reducer(state: AppState, action: Action): AppState {
        val coreState = state.featureStates[name] as? CoreState ?: CoreState()
        var newCoreState = coreState

        when (action.name) {
            // Lifecycle Actions
            "app.STARTING" -> {
                println("LIFECYCLE: App Starting -> RUNNING")
                newCoreState = coreState.copy(lifecycle = AppLifecycle.RUNNING)
            }
            "app.CLOSING" -> {
                println("LIFECYCLE: App Closing -> CLOSING")
                newCoreState = coreState.copy(lifecycle = AppLifecycle.CLOSING)
            }

            // UI Actions
            "core.SET_ACTIVE_VIEW" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<SetActiveViewPayload>(it) }
                newCoreState = payload?.let { coreState.copy(activeViewKey = it.key) } ?: coreState
            }
            "core.SHOW_TOAST" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<ShowToastPayload>(it) }
                newCoreState = payload?.let { coreState.copy(toastMessage = it.message) } ?: coreState
            }
            "core.CLEAR_TOAST" -> newCoreState = coreState.copy(toastMessage = null)
        }

        return if (newCoreState != coreState) {
            state.copy(featureStates = state.featureStates + (name to newCoreState))
        } else {
            state
        }
    }
}