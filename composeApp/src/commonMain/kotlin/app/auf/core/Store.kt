package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A helper class to encapsulate the three-part structure of our action names.
 * E.g., "session.publish.NAMES_UPDATED"
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

    /**
     * A test-only hook to allow a derived class (like RecordingStore) to observe
     * actions that have successfully passed all security and lifecycle guards.
     * In production, this is null and has no effect.
     */
    internal var onDispatch: ((Action) -> Unit)? = null

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
    open fun deliverPrivateData(originator: String, recipient: String, envelope: PrivateDataEnvelope) {
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Delivering private data of type '${envelope.type}' from '$originator' to '$recipient' with payload '${abbreviate(envelope.payload, 100)}'"
        )
        val recipientFeature = features.find { it.name == recipient }
        if (recipientFeature == null) {
            platformDependencies.log(LogLevel.ERROR, "Store", "deliverPrivateData failed: recipient feature '$recipient' not found.")
            return
        }

        try {
            recipientFeature.onPrivateData(envelope, this)
        } catch (e: Exception) {
            handleFeatureException(e, "onPrivateData", recipientFeature.name)
        }
    }


    /**
     * The single, generic entry point for all state changes and side effects.
     */
    open fun dispatch(originator: String, action: Action) {
        // --- PHASE 1: STAMP & PARSE ---
        val stampedAction = action.copy( originator = originator)
        val parsedName = parseActionName(stampedAction.name)

        // --- PHASE 2: AUTHORIZATION & LIFECYCLE GUARDS ---

        // TODO: This guard makes no longer sense as the payload is now alway null or jsonObject. We can replace this guard with a smarter one once we have checks for action properties
        /*if (stampedAction.payload != null && stampedAction.payload !is JsonObject) {
            platformDependencies.log(
                level = LogLevel.FATAL,
                tag = "Store",
                message = "CONTRACT VIOLATION: Action '${stampedAction.name}' dispatched with a non-Object payload of type '${stampedAction.payload::class.simpleName}'. Action rejected."
            )
            return
        }*/

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
            AppLifecycle.BOOTING -> stampedAction.name == ActionNames.SYSTEM_PUBLISH_INITIALIZING
            AppLifecycle.INITIALIZING -> true
            AppLifecycle.RUNNING -> stampedAction.name != ActionNames.SYSTEM_PUBLISH_INITIALIZING && stampedAction.name != ActionNames.SYSTEM_PUBLISH_STARTING
            AppLifecycle.CLOSING -> stampedAction.name == ActionNames.SYSTEM_PUBLISH_CLOSING
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$stampedAction' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        onDispatch?.invoke(stampedAction)

        // --- PHASE 3, 4, 5: ROUTE, REDUCE, UPDATE, NOTIFY (WITH EXCEPTION HANDLING) ---
        val previousState = _state.value
        var newState = previousState

        try {
            if (parsedName.type == ParsedActionName.ActionType.INTERNAL) {
                // Targeted dispatch for internal actions
                val targetFeature = features.find { it.name == parsedName.feature }
                if (targetFeature != null) {
                    newState = targetFeature.reducer(previousState, stampedAction)
                } else {
                    platformDependencies.log(
                        level = LogLevel.ERROR,
                        tag = "Store",
                        message = "Internal action '${stampedAction.name}' dispatched, but target feature '${parsedName.feature}' not found."
                    )
                }
            } else {
                // Broadcast dispatch for all other action types
                newState = features.fold(previousState) { currentState, feature ->
                    feature.reducer(currentState, stampedAction)
                }
            }

            if (newState != previousState) {
                _state.value = newState
            }
        } catch (e: Exception) {
            handleFeatureException(e, "reducer", "broadcast")
            // Abort state mutation if reducer fails.
            return
        }

        // --- Side Effects (onAction) ---
        try {
            if (parsedName.type == ParsedActionName.ActionType.INTERNAL) {
                val targetFeature = features.find { it.name == parsedName.feature }
                targetFeature?.onAction(stampedAction, this)
            } else {
                features.forEach { feature ->
                    feature.onAction(stampedAction, this)
                }
            }
        } catch (e: Exception) {
            handleFeatureException(e, "onAction", "broadcast")
        }
    }

    private fun handleFeatureException(e: Exception, location: String, featureName: String) {
        val uniqueErrorId = platformDependencies.generateUUID().take(8)
        val logMessage = "FATAL EXCEPTION in $location for feature '$featureName' (ref: $uniqueErrorId): \n${e.stackTraceToString()}"
        val toastMessage = "An internal error occurred in '$featureName'. (Ref: $uniqueErrorId)"

        // 1. Log the full error for developers/auditors.
        platformDependencies.log(LogLevel.FATAL, "Store.ExceptionHandler", logMessage)

        // 2. Dispatch a safe, universal action to inform the user without crashing.
        val toastAction = Action(
            name = ActionNames.CORE_SHOW_TOAST,
            payload = buildJsonObject { put("message", toastMessage) }
        )
        // This dispatch is a simple state update and is considered safe.
        val coreFeature = features.find { it.name == "core" }
        if (coreFeature != null) {
            val newStateWithToast = coreFeature.reducer(_state.value, toastAction)
            if (newStateWithToast != _state.value) {
                _state.value = newStateWithToast
            }
        }
    }
}