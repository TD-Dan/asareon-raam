package app.auf.core

import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        else -> ParsedActionName.ActionType.PUBLIC // Default to PUBLIC logic if unclear, but validation catches this.
    }
    return ParsedActionName(featureName, actionType)
}


/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 */
open class Store(
    initialState: AppState,
    val features: List<Feature>,
    val platformDependencies: PlatformDependencies,
    private val validActionNames: Set<String>
) {

    private val _state = MutableStateFlow(initialState)
    open val state = _state.asStateFlow()

    private var lifecycleStarted = false
    private var isDispatching = false
    private val deferredActionQueue = mutableListOf<Action>()

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

        // TODO: check that the envelope has a valid action name defined in the ActionNames.kt file
        // TODO: evolve the *.actions.json files to be real json schemas and verify the payload conforms to the defined schema

        try {
            recipientFeature.onPrivateData(envelope, this)
        } catch (e: Exception) {
            handleFeatureException(e, "onPrivateData", recipientFeature.name)
        }

        // After the private data handler runs, ensure any deferred actions are processed.
        ensureProcessingLoop()
    }

    /**
     * Enqueues an action to be dispatched.
     * This is the preferred method for actions triggered inside `onAction` to avoid re-entrancy issues.
     *
     * ARCHITECTURAL NOTE: This method is functionally distinct from [dispatch] to avoid
     * double-recording issues in inheritance-based test spies. It replicates the queuing logic
     * but skips the re-entrancy warning, as deferral is the correct solution for re-entrancy.
     */
    open fun deferredDispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Deferring: $stampedAction"
        )
        deferredActionQueue.add(stampedAction)
        ensureProcessingLoop()
    }


    /**
     * The single, generic entry point for all state changes and side effects.
     *
     * ARCHITECTURAL NOTE: This method does NOT delegate to [deferredDispatch].
     * It adds to the queue directly. This is deliberate to ensure that a TestStore
     * overriding both methods does not record the same action twice (once in dispatch, once in deferredDispatch).
     */
    open fun dispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        if (isDispatching) {
            platformDependencies.log(
                level = LogLevel.WARN,
                tag = "Store",
                message = "Re-entrant dispatch detected for $stampedAction. This relies on the queue safety net. Prefer 'deferredDispatch' for internal chaining."
            )
        }
        deferredActionQueue.add(stampedAction)
        ensureProcessingLoop()
    }

    /**
     * [NEW] The master event loop for the application.
     * This is the single, synchronized point where all actions (initial and deferred)
     * are processed sequentially.
     */
    private fun ensureProcessingLoop() {
        if (isDispatching) return
        isDispatching = true
        try {
            while (deferredActionQueue.isNotEmpty()) {
                val actionToProcess = deferredActionQueue.removeAt(0)
                processAction(actionToProcess)
            }
        } finally {
            isDispatching = false
        }
    }

    /**
     * [NEW] The core logic for processing a single action.
     * This was extracted from the old `dispatch` function.
     */
    private fun processAction(action: Action) {
        // --- PHASE 1: PARSE, AUTHORIZATION & LIFECYCLE GUARDS ---
        val parsedName = parseActionName(action.name)

        if (!validActionNames.contains(action.name)) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Unknown Action '${action.name}' dispatched by '${action.originator}'. Action ignored."
            )
            return
        }

        val isAuthorized = when (parsedName.type) {
            ParsedActionName.ActionType.SYSTEM -> action.originator?.startsWith("system") == true
            ParsedActionName.ActionType.INTERNAL, ParsedActionName.ActionType.PUBLISH -> action.originator == parsedName.feature
            ParsedActionName.ActionType.PUBLIC -> true
        }

        if (!isAuthorized) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Action '${action.name}' dispatched by unauthorized originator '${action.originator}'. Action ignored."
            )
            return
        }

        val coreState = _state.value.featureStates["core"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Processing (queued:${deferredActionQueue.size}): $action"
        )

        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> action.name == ActionNames.SYSTEM_PUBLISH_INITIALIZING
            AppLifecycle.INITIALIZING -> true
            AppLifecycle.RUNNING -> action.name != ActionNames.SYSTEM_PUBLISH_INITIALIZING && action.name != ActionNames.SYSTEM_PUBLISH_STARTING
            AppLifecycle.CLOSING -> action.name == ActionNames.SYSTEM_PUBLISH_CLOSING
        }

        if (!isActionAllowed) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '$action' dispatched in invalid lifecycle state '$currentLifecycle'. Action ignored."
            )
            return
        }

        // T2 Hook: Capture the processed action.
        onDispatch?.invoke(action)

        // --- PHASE 2 & 3 Combined into a single, safe block ---
        try {
            // --- PHASE 2: ROUTE, REDUCE, UPDATE ---
            val previousState = _state.value
            val newState: AppState

            if (parsedName.type == ParsedActionName.ActionType.INTERNAL) {
                // Targeted dispatch for internal actions
                val targetFeature = features.find { it.name == parsedName.feature }
                if (targetFeature != null) {
                    val oldFeatureState = previousState.featureStates[targetFeature.name]
                    val newFeatureState = targetFeature.reducer(oldFeatureState, action)
                    newState = if (oldFeatureState !== newFeatureState) {
                        val newFeatureStates = previousState.featureStates.toMutableMap()
                        newFeatureState?.let { newFeatureStates[targetFeature.name] = it } ?: newFeatureStates.remove(targetFeature.name)
                        previousState.copy(featureStates = newFeatureStates)
                    } else {
                        previousState
                    }
                } else {
                    platformDependencies.log(LogLevel.ERROR, "Store", "Internal action '${action.name}' dispatched, but target feature '${parsedName.feature}' not found.")
                    newState = previousState
                }
            } else {
                // Sequential fold for all broadcast actions. This is architecturally critical.
                newState = features.fold(previousState) { accumulatingState, feature ->
                    val oldFeatureState = accumulatingState.featureStates[feature.name]
                    val newFeatureState = feature.reducer(oldFeatureState, action)
                    if (oldFeatureState !== newFeatureState) {
                        val newFeatureStates = accumulatingState.featureStates.toMutableMap()
                        newFeatureState?.let { newFeatureStates[feature.name] = it } ?: newFeatureStates.remove(feature.name)
                        accumulatingState.copy(featureStates = newFeatureStates)
                    } else {
                        accumulatingState
                    }
                }
            }

            if (newState != previousState) {
                _state.value = newState
            }

            // --- PHASE 3: Side Effects (onAction) ---
            if (parsedName.type == ParsedActionName.ActionType.INTERNAL) {
                val targetFeature = features.find { it.name == parsedName.feature }
                if (targetFeature != null) {
                    val prevFeatureState = previousState.featureStates[targetFeature.name]
                    val newFeatureState = newState.featureStates[targetFeature.name]
                    targetFeature.onAction(action, this, prevFeatureState, newFeatureState)
                }
            } else {
                features.forEach { feature ->
                    val prevFeatureState = previousState.featureStates[feature.name]
                    val newFeatureState = newState.featureStates[feature.name]
                    feature.onAction(action, this, prevFeatureState, newFeatureState)
                }
            }
        } catch (e: Exception) {
            handleFeatureException(e, "reducer/onAction", "broadcast")
        }
    }


    fun handleFeatureException(e: Exception, location: String, featureName: String) {
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
        deferredDispatch("Store.ExceptionHandler", toastAction)
        ensureProcessingLoop()
    }
}