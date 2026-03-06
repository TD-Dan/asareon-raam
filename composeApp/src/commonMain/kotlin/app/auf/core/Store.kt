package app.auf.core

import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.AppLifecycle // Allowed: system and core are interconnected
import app.auf.feature.core.CoreState // Allowed: system and core are interconnected
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * - Permission guard (Phase 1): Step 2b checks required_permissions against originator's effective grants.
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
     *
     * NOTE: For deeper hierarchies ("agent.coder-1.sub-task"), this returns "agent"
     * (first segment only). This is intentional — only exact feature handles skip
     * permission checks (feature trust exemption).
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
            //
            // Default permissions from DefaultPermissions are applied here so
            // that child identities can inherit from their parent feature
            // naturally via resolveEffectivePermissions. For example, "agent"
            // gets filesystem:workspace=YES, and "agent.mercury" inherits it.
            val featureIdentities = features.associate { feature ->
                val defaults = DefaultPermissions.grantsFor(feature.identity.handle)
                val identityWithDefaults = if (defaults.isNotEmpty()) {
                    feature.identity.copy(permissions = defaults + feature.identity.permissions)
                } else {
                    feature.identity
                }
                feature.identity.handle to identityWithDefaults
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
     * Resolves the effective permissions for an identity by walking the
     * parent chain. Applies controlled escalation policy (Pillar 7):
     * child grants may override parent grants freely, but escalations
     * (child level > parent level) are logged at WARN for audit.
     *
     * Resolution: accumulate from root to leaf. Each layer overrides
     * the inherited value.
     */
    fun resolveEffectivePermissions(
        identity: Identity
    ): Map<String, PermissionGrant> {
        val registry = _state.value.identityRegistry

        // Collect the chain: [self, parent, grandparent, ...]
        val chain = mutableListOf(identity)
        var current = identity
        while (current.parentHandle != null) {
            val parent = registry[current.parentHandle] ?: break
            chain.add(parent)
            current = parent
        }

        // Merge root-first: each child layer overrides
        val effective = mutableMapOf<String, PermissionGrant>()

        for (ancestor in chain.reversed()) {
            for ((key, grant) in ancestor.permissions) {
                val parentGrant = effective[key]
                if (parentGrant == null) {
                    // First in chain to declare this key
                    effective[key] = grant
                } else {
                    // Controlled escalation: allow but log
                    if (grant.level > parentGrant.level) {
                        platformDependencies.log(
                            LogLevel.WARN, "Store",
                            "PERMISSION ESCALATION: '${ancestor.handle}' has " +
                                    "'${grant.level}' for '$key' but parent effective is " +
                                    "'${parentGrant.level}'. Allowed under controlled escalation."
                        )
                    }
                    effective[key] = grant
                }
            }
        }

        return effective
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
     * Permission guard (step 2b — Phase 1):
     *   If the action has required_permissions, the originator's effective grants
     *   must include YES for all listed keys. Features (uuid == null) are exempt.
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

        // --- STEP 1c: ORIGINATOR VALIDATION (Pre-Phase 1) ---
        // Reject originators not in the identity registry and not resolvable to a known feature.
        // "Known feature" means either a registered Feature instance OR a feature name declared
        // in the action descriptors (covers infrastructure like "system" which has a manifest
        // but no Feature instance — it's dispatched by the app container).
        if (action.originator != null) {
            val originatorInRegistry = _state.value.identityRegistry.containsKey(action.originator)
            val originatorIsFeature = features.any { it.identity.handle == action.originator }
            if (!originatorInRegistry && !originatorIsFeature) {
                val featureHandle = extractFeatureHandle(action.originator)
                val parentIsFeature = features.any { it.identity.handle == featureHandle }
                val parentIsKnownDescriptorFeature = _state.value.actionDescriptors.values
                    .any { it.featureName == featureHandle }
                if (!parentIsFeature && !parentIsKnownDescriptorFeature) {
                    platformDependencies.log(
                        LogLevel.ERROR, "Store",
                        "INVALID ORIGINATOR: '${action.originator}' is not a registered identity " +
                                "or feature. Action '${action.name}' rejected."
                    )
                    return
                }
            }
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

        // --- STEP 2b: PERMISSION GUARD (Phase 1: YES/NO, Phase 2: PERMISSION_DENIED notifications) ---
        val requiredPerms = descriptor.requiredPermissions
        if (requiredPerms != null && requiredPerms.isNotEmpty()) {
            val originatorIdentity = action.originator?.let {
                _state.value.identityRegistry[it]
            }

            // Feature identities (uuid == null) are trusted — skip permission check.
            if (originatorIdentity != null && originatorIdentity.uuid != null) {
                val effective = resolveEffectivePermissions(originatorIdentity)
                val missingPermissions = mutableListOf<String>()

                for (permKey in requiredPerms) {
                    val grant = effective[permKey]
                    val level = grant?.level ?: PermissionLevel.NO  // deny by default (Pillar 5)

                    when (level) {
                        PermissionLevel.NO -> {
                            missingPermissions.add(permKey)
                        }
                        PermissionLevel.ASK -> {
                            // ASK system not yet implemented. Treat as NO with warning.
                            platformDependencies.log(
                                LogLevel.WARN, "Store",
                                "PERMISSION ASK not yet implemented (treating as NO): " +
                                        "'${action.originator}' has ASK for '$permKey' " +
                                        "on action '${action.name}'."
                            )
                            missingPermissions.add(permKey)
                        }
                        PermissionLevel.APP_LIFETIME -> {
                            // APP_LIFETIME system not yet implemented. Treat as NO with warning.
                            platformDependencies.log(
                                LogLevel.WARN, "Store",
                                "PERMISSION APP_LIFETIME not yet implemented (treating as NO): " +
                                        "'${action.originator}' has APP_LIFETIME for '$permKey' " +
                                        "on action '${action.name}'."
                            )
                            missingPermissions.add(permKey)
                        }
                        PermissionLevel.YES -> {
                            // Permitted — continue to next required permission.
                        }
                    }
                }

                if (missingPermissions.isNotEmpty()) {
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION DENIED: '${action.originator}' lacks " +
                                "${missingPermissions.joinToString(", ") { "'$it'" }} " +
                                "for action '${action.name}'. Action blocked."
                    )

                    // Phase 2: Broadcast PERMISSION_DENIED notification so observing
                    // features (e.g., CommandBot) can provide user-facing feedback.
                    deferredActionQueue.add(Action(
                        name = ActionRegistry.Names.CORE_PERMISSION_DENIED,
                        payload = buildJsonObject {
                            put("blockedAction", action.name)
                            put("originatorHandle", action.originator ?: "")
                            put("missingPermissions", buildJsonArray {
                                missingPermissions.forEach { add(JsonPrimitive(it)) }
                            })
                        },
                        originator = "core"
                    ))
                    return
                }
            }
            // Unknown originator with required permissions: check if resolvable to feature.
            else if (originatorIdentity == null && action.originator != null) {
                val featureHandle = extractFeatureHandle(action.originator)
                val isFeature = features.any { it.identity.handle == featureHandle }
                val isKnownDescriptorFeature = _state.value.actionDescriptors.values
                    .any { it.featureName == featureHandle }
                if (!isFeature && !isKnownDescriptorFeature) {
                    platformDependencies.log(
                        LogLevel.ERROR, "Store",
                        "PERMISSION DENIED: originator '${action.originator}' not found in " +
                                "identity registry and action '${action.name}' requires permissions. Blocked."
                    )
                    return
                }
                // Resolvable to a trusted feature — allowed (feature trust exemption).
            }
        }

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
        // Pre-Phase 1 fix: dispatch as "core" (registered feature handle) instead of
        // "Store.ExceptionHandler" (unregistered), so originator validation passes.
        deferredDispatch("core", toastAction)
        ensureProcessingLoop()
    }
}