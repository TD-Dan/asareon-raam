package app.auf.core

import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.AppLifecycle // Allowed: system and core are interconnected
import app.auf.feature.core.CoreState // Allowed: system and core are interconnected
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


/**
 * The central state container for the Unidirectional Data Flow (UDF) architecture.
 *
 * - Authorization is schema-driven via the `public` flag (who can dispatch).
 * - Routing is schema-driven via `broadcast`/`targeted` flags (who receives).
 *   These two concerns are orthogonal.
 * - Feature identities are seeded at boot in initFeatureLifecycles().
 * - AppState.identityRegistry is the single source of truth, mutated via updateIdentityRegistry().
 * - The validActionNames constructor param is removed; validation uses AppState.actionDescriptors.
 */
open class Store(
    initialState: AppState,
    val features: List<Feature>,
    val platformDependencies: PlatformDependencies
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

    /**
     * Extracts the feature-level handle from a hierarchical originator string.
     * "agent.gemini-flash-abc123" → "agent"
     * "agent.gemini-flash.sub-task" → "agent"
     * "session.chat1" → "session"
     * "core" → "core"
     * null → null
     *
     * This enables hierarchical originator resolution: an action dispatched by
     * "agent.gemini-flash-abc123" is authorized as if it came from the "agent" feature.
     */
    private fun extractFeatureHandle(originator: String?): String? =
        originator?.substringBefore('.')

    /**
     * Mutates AppState.identityRegistry directly.
     * Called by CoreFeature from handleSideEffects to register/unregister identities.
     * Business logic (validation, parent checks) remains in CoreFeature;
     * the Store owns only the state.
     */
    fun updateIdentityRegistry(transform: (Map<String, Identity>) -> Map<String, Identity>) {
        _state.value = _state.value.copy(
            identityRegistry = transform(_state.value.identityRegistry)
        )
    }

    fun initFeatureLifecycles() {
        if (!lifecycleStarted) {
            // Seed feature identities directly — no action bus needed.
            // Feature identities are structural facts known at compile time.
            // They cannot go through the action bus because during BOOTING,
            // only system.INITIALIZING is permitted (lifecycle guard).
            val featureIdentities = features.associate { feature ->
                feature.identity.handle to feature.identity
            }

            // Seed AppState directly — single source of truth from the start.
            _state.value = _state.value.copy(
                identityRegistry = _state.value.identityRegistry + featureIdentities
            )

            features.forEach { it.init(this) }
            lifecycleStarted = true
        }
    }

    /**
     * Enqueues an action to be dispatched.
     * This is the preferred method for actions triggered inside `handleSideEffects` to avoid re-entrancy issues.
     */
    open fun deferredDispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        platformDependencies.log(
            level = LogLevel.DEBUG,
            tag = "Store",
            message = "Deferring: $stampedAction"
        )
        deferredActionQueue.add(stampedAction)
        ensureProcessingLoop()
    }

    /**
     * Schedules an action to be dispatched after a delay.
     */
    open fun scheduleDelayed(delayMs: Long, originator: String, action: Action): Any? {
        return platformDependencies.scheduleDelayed(delayMs) {
            deferredDispatch(originator, action)
        }
    }

    /**
     * The single, generic entry point for all state changes and side effects.
     */
    open fun dispatch(originator: String, action: Action) {
        val stampedAction = action.copy(originator = originator)
        if (isDispatching) {
            platformDependencies.log(
                level = LogLevel.WARN,
                tag = "Store",
                message = "Re-entrant dispatch detected for $stampedAction. Auto deferring to the queue. Use 'deferredDispatch' instead."
            )
        }
        deferredActionQueue.add(stampedAction)
        ensureProcessingLoop()
    }

    /**
     * The master event loop for the application.
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
     * The core logic for processing a single action.
     *
     * Authorization and routing are schema-driven via ActionDescriptor flags:
     *   - `public` controls AUTHORIZATION: who can dispatch the action
     *   - `broadcast`/`targeted` control DELIVERY: who receives the action
     * These two concerns are orthogonal.
     *
     * Authorization (step 2):
     *   public: true  → any originator
     *   public: false → originator's feature handle must match action's owning feature
     *
     * Routing (step 4):
     *   targeted: true     → deliver to targetRecipient only (Phase 3)
     *   broadcast: true    → deliver to all features
     *   else (!broadcast)  → deliver to owning feature only
     *
     * This means `public: true, broadcast: false` is valid: "anyone can dispatch, only
     * the owning feature receives." Used for commands like REGISTER_IDENTITY where
     * any feature can request, but only CoreFeature processes.
     */
    private fun processAction(action: Action) {
        // --- STEP 1: SCHEMA LOOKUP ---

        val descriptor = _state.value.actionDescriptors[action.name]
        if (descriptor == null) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Unknown Action '${action.name}' dispatched by '${action.originator}'. Action ignored."
            )
            return
        }

        // --- STEP 1b: TARGETED VALIDATION ---
        // Reject targetRecipient on non-targeted actions, and targeted actions without one.
        if (action.targetRecipient != null && !descriptor.targeted) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Action '${action.name}' has targetRecipient '${action.targetRecipient}' but is not declared as targeted. Rejected."
            )
            return
        }
        if (descriptor.targeted && action.targetRecipient == null) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "Targeted action '${action.name}' dispatched without targetRecipient. Rejected."
            )
            return
        }

        // --- STEP 2: AUTHORIZATION (public flag) ---
        // `public` answers: "who is allowed to dispatch this?"
        // public: true  → any originator
        // public: false → only the owning feature (via hierarchical prefix match)
        val isAuthorized = when {
            descriptor.public -> true
            else -> extractFeatureHandle(action.originator) == descriptor.featureName
        }

        if (!isAuthorized) {
            platformDependencies.log(
                level = LogLevel.ERROR,
                tag = "Store",
                message = "SECURITY VIOLATION: Action '${action.name}' dispatched by unauthorized originator '${action.originator}'. Action ignored."
            )
            return
        }

        // FUTURE: After authorization, check required permissions:
        // val requiredPerms = descriptor.requiredPermissions
        // if (requiredPerms != null) {
        //     val identity = _state.value.identityRegistry[action.originator]
        //     val effectivePerms = resolvePermissions(identity)  // walk parentHandle chain
        //     if (!requiredPerms.all { effectivePerms[it] == true }) {
        //         log(ERROR, "Permission denied for '${action.originator}' on '${action.name}'")
        //         return
        //     }
        // }

        // --- STEP 3: LIFECYCLE GUARD ---

        val coreState = _state.value.featureStates["core"] as? CoreState
        val currentLifecycle = coreState?.lifecycle ?: AppLifecycle.BOOTING
        platformDependencies.log(
            level = LogLevel.INFO,
            tag = "Store",
            message = "Processing (queued:${deferredActionQueue.size}): $action"
        )

        val isActionAllowed = when (currentLifecycle) {
            AppLifecycle.BOOTING -> action.name == ActionRegistry.Names.SYSTEM_INITIALIZING
            AppLifecycle.INITIALIZING -> true
            AppLifecycle.RUNNING -> action.name != ActionRegistry.Names.SYSTEM_INITIALIZING && action.name != ActionRegistry.Names.SYSTEM_STARTING
            AppLifecycle.CLOSING -> action.name == ActionRegistry.Names.SYSTEM_CLOSING
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

        // --- STEP 4: ROUTE, REDUCE, SIDE-EFFECTS ---
        try {
            val previousState = _state.value
            val newState: AppState

            // Routing: `broadcast` and `targeted` control delivery (orthogonal to `public`).
            //   targeted: true     → deliver to targetRecipient only (Phase 3)
            //   broadcast: true    → deliver to all features
            //   !broadcast         → deliver to owning feature only
            //
            // This means public+!broadcast (e.g., REGISTER_IDENTITY) is correctly routed
            // to the owning feature only, even though anyone can dispatch it.
            if (descriptor.targeted) {
                // Targeted delivery: deliver to the feature identified by targetRecipient.
                // The Store resolves at the feature level only — if targetRecipient is
                // "session.chat1", we deliver to the "session" feature. Sub-entity
                // targeting is the feature's responsibility.
                val recipientHandle = extractFeatureHandle(action.targetRecipient)
                val targetFeature = features.find { it.identity.handle == recipientHandle }
                if (targetFeature != null) {
                    val oldFeatureState = previousState.featureStates[targetFeature.identity.handle]
                    val newFeatureState = targetFeature.reducer(oldFeatureState, action)
                    newState = if (oldFeatureState !== newFeatureState) {
                        val newFeatureStates = previousState.featureStates.toMutableMap()
                        newFeatureState?.let { newFeatureStates[targetFeature.identity.handle] = it } ?: newFeatureStates.remove(targetFeature.identity.handle)
                        previousState.copy(featureStates = newFeatureStates)
                    } else {
                        previousState
                    }
                } else {
                    platformDependencies.log(LogLevel.ERROR, "Store", "Targeted action '${action.name}' → unknown recipient '${action.targetRecipient}' (resolved to feature '$recipientHandle')")
                    newState = previousState
                }
            } else if (!descriptor.broadcast) {
                // Non-broadcast: deliver to owning feature only.
                // This covers both internal actions (!public, !broadcast) and
                // public non-broadcast commands (public, !broadcast) like REGISTER_IDENTITY.
                val targetFeature = features.find { it.identity.handle == descriptor.featureName }
                if (targetFeature != null) {
                    val oldFeatureState = previousState.featureStates[targetFeature.identity.handle]
                    val newFeatureState = targetFeature.reducer(oldFeatureState, action)
                    newState = if (oldFeatureState !== newFeatureState) {
                        val newFeatureStates = previousState.featureStates.toMutableMap()
                        newFeatureState?.let { newFeatureStates[targetFeature.identity.handle] = it } ?: newFeatureStates.remove(targetFeature.identity.handle)
                        previousState.copy(featureStates = newFeatureStates)
                    } else {
                        previousState
                    }
                } else {
                    platformDependencies.log(LogLevel.ERROR, "Store", "Non-broadcast action '${action.name}' dispatched, but target feature '${descriptor.featureName}' not found.")
                    newState = previousState
                }
            } else {
                // Broadcast: deliver to all features.
                newState = features.fold(previousState) { accumulatingState, feature ->
                    val oldFeatureState = accumulatingState.featureStates[feature.identity.handle]
                    val newFeatureState = feature.reducer(oldFeatureState, action)
                    if (oldFeatureState !== newFeatureState) {
                        val newFeatureStates = accumulatingState.featureStates.toMutableMap()
                        newFeatureState?.let { newFeatureStates[feature.identity.handle] = it } ?: newFeatureStates.remove(feature.identity.handle)
                        accumulatingState.copy(featureStates = newFeatureStates)
                    } else {
                        accumulatingState
                    }
                }
            }

            if (newState != previousState) {
                _state.value = newState
            }

            // --- STEP 5: SIDE EFFECTS (handleSideEffects) ---
            // Delivery scope for side effects mirrors the routing decision above.
            if (descriptor.targeted) {
                // Targeted: side effects for recipient feature only
                val recipientHandle = extractFeatureHandle(action.targetRecipient)
                val targetFeature = features.find { it.identity.handle == recipientHandle }
                if (targetFeature != null) {
                    val prevFeatureState = previousState.featureStates[targetFeature.identity.handle]
                    val newFeatureState = newState.featureStates[targetFeature.identity.handle]
                    targetFeature.handleSideEffects(action, this, prevFeatureState, newFeatureState)
                }
            } else if (!descriptor.broadcast) {
                // Non-broadcast: side effects for owning feature only
                val targetFeature = features.find { it.identity.handle == descriptor.featureName }
                if (targetFeature != null) {
                    val prevFeatureState = previousState.featureStates[targetFeature.identity.handle]
                    val newFeatureState = newState.featureStates[targetFeature.identity.handle]
                    targetFeature.handleSideEffects(action, this, prevFeatureState, newFeatureState)
                }
            } else {
                // broadcast: side effects for all features
                features.forEach { feature ->
                    val prevFeatureState = previousState.featureStates[feature.identity.handle]
                    val newFeatureState = newState.featureStates[feature.identity.handle]
                    feature.handleSideEffects(action, this, prevFeatureState, newFeatureState)
                }
            }
        } catch (e: Exception) {
            handleFeatureException(e, "reducer/handleSideEffects", "broadcast")
        }
    }


    fun handleFeatureException(e: Exception, location: String, featureName: String) {
        val uniqueErrorId = platformDependencies.generateUUID().take(8)
        val logMessage = "FATAL EXCEPTION in $location for feature '$featureName' (ref: $uniqueErrorId): \n${e.stackTraceToString()}"
        val toastMessage = "An internal error occurred in '$featureName'. (Ref: $uniqueErrorId)"

        platformDependencies.log(LogLevel.FATAL, "Store.ExceptionHandler", logMessage)

        val toastAction = Action(
            name = ActionRegistry.Names.CORE_SHOW_TOAST,
            payload = buildJsonObject { put("message", toastMessage) }
        )
        deferredDispatch("Store.ExceptionHandler", toastAction)
        ensureProcessingLoop()
    }
}