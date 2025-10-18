package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A helper class to encapsulate the three-part structure of our action names.
 * e.g., "session.publish.NAMES_UPDATED"
 */
private data class ParsedActionName(
    val feature: String,
    val type: ActionType,
) {
    enum class ActionType { PUBLIC, INTERNAL, PUBLISH, SYSTEM }
}

/**
 * Parses a string action name into its constituent parts for routing and security checks.
 */
private fun parseActionName(name: String): ParsedActionName {
    val parts = name.split('.')
    val featureName = parts.getOrNull(0) ?: "unknown"
    val actionType = when {
        featureName == "system" -> ParsedActionName.ActionType.SYSTEM
        parts.getOrNull(1) == "internal" -> ParsedActionName.ActionType.INTERNAL
        parts.getOrNull(1) == "publish" -> ParsedActionName.ActionType.PUBLISH
        else -> ParsedActionName.ActionType.PUBLIC
    }
    return ParsedActionName(featureName, actionType)
}


/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 */
open class Store(
    initialState: AppState,
    private val features: List<Feature>,
    private val platformDependencies: PlatformDependencies,
    private val validActionNames: Set<String>
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
    open fun deliverPrivateData(originator: String, recipient: String, data: Any) {
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
     * This method has been hardened to support three classes of actions:
     * - **Commands (Public):** e.g., `session.POST`. Broadcast to all features.
     * - **Internal Events:** e.g., `session.internal.LOADED`. Routed ONLY to the owning feature.
     * - **Published Events:** e.g., `session.publish.UPDATED`. Broadcast to all features, but can only be dispatched by the owning feature.
     *
     * The process is a strictly ordered, synchronous call:
     * 1.  **Stamp & Parse:** The action is stamped and its name is parsed.
     * 2.  **Authorize & Guard:** The action is validated against the Action Registry, originator rules, and the current `AppLifecycle`.
     * 3.  **Route & Reduce:** Based on the action type, the action is either broadcast to all reducers or routed to a single reducer to calculate the new state.
     * 4.  **Update:** The central state is atomically updated.
     * 5.  **Route & `onAction`:** The action is routed to the appropriate side-effect handlers.
     */
    open fun dispatch(originator: String, action: Action) {
        // --- PHASE 1: STAMP & PARSE ---
        val stampedAction = action.copy( originator = originator)
        val parsedName = parseActionName(stampedAction.name)

        // --- PHASE 2: AUTHORIZATION & LIFECYCLE GUARDS ---

        // NEW: Action Registry Guard
        if (!validActionNames.contains(stampedAction.name)) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Unknown Action '${stampedAction.name}' dispatched by '$originator'. Action ignored."
            )
            return
        }

        val isAuthorized = when (parsedName.type) {
            ParsedActionName.ActionType.SYSTEM -> originator.startsWith("system")
            ParsedActionName.ActionType.INTERNAL, ParsedActionName.ActionType.PUBLISH -> originator == parsedName.feature
            ParsedActionName.ActionType.PUBLIC -> true
        }

        if (!isAuthorized) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Action '${stampedAction.name}' dispatched by unauthorized originator '$originator'. Action ignored."
            )
            return
        }

        val coreState = _state.value.featureStates["core"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Dispatching: $stampedAction"
        )

        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> stampedAction.name == ActionNames.SYSTEM_INITIALIZING
            AppLifecycle.INITIALIZING -> true
            AppLifecycle.RUNNING -> stampedAction.name != ActionNames.SYSTEM_INITIALIZING && stampedAction.name != ActionNames.SYSTEM_STARTING
            AppLifecycle.CLOSING -> stampedAction.name == ActionNames.SYSTEM_CLOSING
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$stampedAction' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        // --- PHASE 3, 4, 5: ROUTE, REDUCE, UPDATE, NOTIFY ---
        if (parsedName.type == ParsedActionName.ActionType.INTERNAL) {
            // Targeted dispatch for internal actions
            val targetFeature = features.find { it.name == parsedName.feature }
            if (targetFeature != null) {
                val previousState = _state.value
                val newState = targetFeature.reducer(previousState, stampedAction)
                if (newState != previousState) {
                    _state.value = newState
                }
                targetFeature.onAction(stampedAction, this)
            } else {
                platformDependencies.log(
                    level = LogLevel.ERROR,
                    tag = "Store",
                    message = "Internal action '${stampedAction.name}' dispatched, but target feature '${parsedName.feature}' not found."
                )
            }
        } else {
            // Broadcast dispatch for all other action types
            val previousState = _state.value
            val newState = features.fold(previousState) { currentState, feature ->
                feature.reducer(currentState, stampedAction)
            }
            if (newState != previousState) {
                _state.value = newState
            }
            features.forEach { feature ->
                feature.onAction(stampedAction, this)
            }
        }
    }
}
