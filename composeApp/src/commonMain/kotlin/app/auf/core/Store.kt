package app.auf.core

import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 */
open class Store(
    initialState: AppState,
    private val features: List<Feature>,
    private val platformDependencies: PlatformDependencies
) {

    private val _state = MutableStateFlow(initialState)
    open val state = _state.asStateFlow()

    private var lifecycleStarted = false

    fun initFeatureLifecycles() {
        if (!lifecycleStarted) {
            features.forEach { it.init(this) }
            lifecycleStarted = true
        }
    }

    /**
     * Securely delivers a private data payload to a single target feature, bypassing the
     * public action bus. This is a privileged operation. The act of delivery is logged
     * for audibility, but the payload content is NOT.
     */
    fun deliverPrivateData(originator: String, recipient: String, data: Any) {
        // Log the event for audit purposes, WITHOUT logging the sensitive data.
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Delivering private data from '$originator' to '$recipient'."
        )
        // Find the specific feature instance and call the private method.
        features.find { it.name == recipient }?.onPrivateData(data, this)
    }


    /**
     * The single, generic entry point for all state changes and side effects.
     * The process is a strictly ordered, synchronous, blocking call:
     *
     * 1.  **Stamp:** The action is stamped with the verified `originator`.
     * 2.  **Authorize & Guard:** The stamped action is validated against originator rules and the current `AppLifecycle` state.
     * 3.  **Reduce: ** The `reducer` from every feature is called sequentially to calculate the new state.
     * 4.  **Update: ** The central state is atomically updated.
     * 5.  **`onAction`:** The `onAction` side effect handler from every feature is called sequentially.
     *
     * This synchronous and sequential execution is the foundation of the application's
     * deterministic startup process. The function will not return until all phases are complete.
     *
     * @param originator A non-repudiable string identifying the caller (e.g., a feature's name).
     * @param action The action to be dispatched.
     */
    open fun dispatch(originator: String, action: Action) {
        // --- PHASE 0: STAMP ---
        val stampedAction = action.copy( originator = originator)

        // --- PHASE 1: AUTHORIZATION GUARD (P-SEC-003 and System Privilege) ---
        val actionNameParts = stampedAction.name.split('.')
        val isAuthorized = when {
            // Rule 1: System-Privileged Actions (e.g., "system.INITIALIZING")
            actionNameParts.getOrNull(0) == "system" -> {
                originator.startsWith("system")
            }
            // Rule 2: Feature-Internal Actions (e.g., "filesystem.internal.LOADED")
            actionNameParts.getOrNull(1) == "internal" -> {
                originator == actionNameParts.getOrNull(0)
            }
            // Rule 3: Public Actions
            else -> true
        }

        if (!isAuthorized) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Action '${stampedAction.name}' dispatched by unauthorized originator '$originator'. Action ignored."
            )
            return
        }

        val coreState = _state.value.featureStates["CoreFeature"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Dispatching: $stampedAction"
        )

        // --- PHASE 2: LIFECYCLE GUARD ---
        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> stampedAction.name == "system.INITIALIZING"
            AppLifecycle.INITIALIZING -> true // Allow all actions during the init/load phase
            AppLifecycle.RUNNING -> stampedAction.name != "system.INITIALIZING" && stampedAction.name != "system.STARTING"
            AppLifecycle.CLOSING -> stampedAction.name == "system.CLOSING" // Only allow closing action
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$stampedAction' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        // --- PHASE 3: REDUCE ---
        val previousState = _state.value
        val newState = features.fold(previousState) { currentState, feature ->
            feature.reducer(currentState, stampedAction)
        }

        // --- PHASE 4: UPDATE STATE ---
        if (newState != previousState) {
            _state.value = newState
        }

        // --- PHASE 5: SIDE-EFFECTS ---
        features.forEach { feature ->
            feature.onAction(stampedAction, this)
        }
    }
}