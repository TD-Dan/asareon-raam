package app.auf.core

import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.test.testDescriptorsFor
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for the Store's schema-driven authorization and routing.
 *
 * Tests the orthogonal concern model:
 *   - `open` flag controls AUTHORIZATION (who can dispatch)
 *   - `broadcast` / `targeted` flags control DELIVERY (who receives)
 *
 * These are independent — every combination is valid and tested.
 *
 * The five action types derived from these flags:
 *   | Type                | open  | broadcast | targeted |
 *   |---------------------|-------|-----------|----------|
 *   | Command             | true  | true      | false    |
 *   | Open Non-Broadcast  | true  | false     | false    |
 *   | Event               | false | true      | false    |
 *   | Internal            | false | false     | false    |
 *   | Response (Phase 3)  | false | false     | true     |
 */
class StoreT1RoutingTest {

    // ========================================================================
    // Test infrastructure: two features that track which actions they received
    // ========================================================================

    private data class TrackingState(
        val reducerLog: List<String> = emptyList(),
        val sideEffectLog: List<String> = emptyList()
    ) : FeatureState

    /**
     * A feature that records every action it receives in both reducer and handleSideEffects.
     * This lets us assert exactly which features were delivered an action.
     */
    private class TrackingFeature(handle: String, displayName: String) : Feature {
        override val identity = Identity(
            uuid = null, localHandle = handle, handle = handle, name = displayName
        )
        override val composableProvider: Feature.ComposableProvider? = null

        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            val current = state as? TrackingState ?: TrackingState()
            // Only track test actions (not system lifecycle actions)
            if (!action.name.startsWith("test.") && !action.name.startsWith("alpha.") && !action.name.startsWith("beta.")) {
                return current
            }
            return current.copy(reducerLog = current.reducerLog + action.name)
        }

        override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            if (!action.name.startsWith("test.") && !action.name.startsWith("alpha.") && !action.name.startsWith("beta.")) return
            val current = newState as? TrackingState ?: return
            // Mutate via a deferred self-action to record side-effect delivery.
            // We use a simpler approach: just track in the state directly via the reducer log
            // which already captured the action name in the reducer phase.
            // The fact that handleSideEffects was called at all is what we're testing.
        }
    }

    /**
     * An enhanced tracking feature that also records handleSideEffects calls
     * by dispatching a marker action. This enables us to verify side-effect
     * delivery scope separately from reducer delivery scope.
     */
    private class SideEffectTrackingFeature(handle: String, displayName: String) : Feature {
        override val identity = Identity(
            uuid = null, localHandle = handle, handle = handle, name = displayName
        )
        override val composableProvider: Feature.ComposableProvider? = null

        override fun reducer(state: FeatureState?, action: Action): FeatureState? {
            val current = state as? TrackingState ?: TrackingState()
            if (action.name.startsWith("marker.${identity.handle}.")) {
                // Record that handleSideEffects was called for the original action
                val originalAction = action.name.removePrefix("marker.${identity.handle}.")
                return current.copy(sideEffectLog = current.sideEffectLog + originalAction)
            }
            if (!action.name.startsWith("test.") && !action.name.startsWith("alpha.") && !action.name.startsWith("beta.")) {
                return current
            }
            return current.copy(reducerLog = current.reducerLog + action.name)
        }

        override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
            if (!action.name.startsWith("test.") && !action.name.startsWith("alpha.") && !action.name.startsWith("beta.")) return
            // Dispatch a marker action to record that side effects were delivered
            store.deferredDispatch(identity.handle, Action("marker.${identity.handle}.${action.name}"))
        }
    }

    private val platform = FakePlatformDependencies("v2-test")

    /**
     * Creates a descriptor with specific routing flags.
     */
    private fun descriptor(
        name: String,
        featureName: String,
        open: Boolean,
        broadcast: Boolean,
        targeted: Boolean = false
    ): Pair<String, ActionRegistry.ActionDescriptor> {
        return name to ActionRegistry.ActionDescriptor(
            fullName = name,
            featureName = featureName,
            suffix = name.substringAfter("."),
            summary = "Test descriptor",
            open = open,
            broadcast = broadcast,
            targeted = targeted,
            payloadFields = emptyList(),
            requiredFields = emptyList(),
            agentExposure = null
        )
    }

    /**
     * Creates a Store with two tracking features (alpha, beta) and custom descriptors.
     */
    private fun createRoutingStore(
        vararg descriptors: Pair<String, ActionRegistry.ActionDescriptor>,
        useSideEffectTracking: Boolean = false
    ): Store {
        val alphaFeature = if (useSideEffectTracking) SideEffectTrackingFeature("alpha", "Alpha") else TrackingFeature("alpha", "Alpha")
        val betaFeature = if (useSideEffectTracking) SideEffectTrackingFeature("beta", "Beta") else TrackingFeature("beta", "Beta")

        // Include marker descriptors for side-effect tracking
        val markerDescriptors = if (useSideEffectTracking) {
            val markerNames = descriptors.flatMap { (name, _) ->
                listOf("marker.alpha.$name", "marker.beta.$name")
            }.toSet()
            testDescriptorsFor(markerNames).map { (k, v) ->
                // Marker actions are internal to the feature that dispatches them
                k to v.copy(featureName = k.removePrefix("marker.").substringBefore("."), open = false, broadcast = false)
            }.toMap()
        } else emptyMap()

        val allDescriptors = ActionRegistry.byActionName + descriptors.toMap() + markerDescriptors

        val features = listOf(CoreFeature(platform), alphaFeature, betaFeature)
        val initialState = AppState(
            featureStates = mapOf(
                "core" to CoreState(lifecycle = AppLifecycle.RUNNING),
                "alpha" to TrackingState(),
                "beta" to TrackingState()
            ),
            actionDescriptors = allDescriptors
        )
        return Store(initialState, features, platform)
    }

    private fun Store.alphaState() = state.value.featureStates["alpha"] as TrackingState
    private fun Store.betaState() = state.value.featureStates["beta"] as TrackingState

    // ========================================================================
    // AUTHORIZATION TESTS (open flag — who can dispatch)
    // ========================================================================

    @Test
    fun `open action allows any originator`() {
        val store = createRoutingStore(
            descriptor("test.OPEN_CMD", featureName = "alpha", open = true, broadcast = true)
        )

        // Dispatch from a completely unrelated originator
        store.dispatch("unrelated.caller", Action("test.OPEN_CMD"))

        // Should be delivered (at least to alpha, the owner)
        assertTrue(store.alphaState().reducerLog.contains("test.OPEN_CMD"),
            "Open action should be accepted from any originator.")
    }

    @Test
    fun `restricted action allows owning feature as originator`() {
        val store = createRoutingStore(
            descriptor("alpha.INTERNAL", featureName = "alpha", open = false, broadcast = false)
        )

        store.dispatch("alpha", Action("alpha.INTERNAL"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.INTERNAL"),
            "Restricted action should be accepted from owning feature.")
    }

    @Test
    fun `restricted action allows hierarchical child of owning feature`() {
        val store = createRoutingStore(
            descriptor("alpha.INTERNAL", featureName = "alpha", open = false, broadcast = false)
        )

        // "alpha.sub-entity-123" should resolve to feature "alpha" via extractFeatureHandle
        store.dispatch("alpha.sub-entity-123", Action("alpha.INTERNAL"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.INTERNAL"),
            "Restricted action should be accepted from a hierarchical child of the owning feature.")
    }

    @Test
    fun `restricted action rejects foreign originator`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.INTERNAL", featureName = "alpha", open = false, broadcast = false)
        )

        store.dispatch("beta", Action("alpha.INTERNAL"))

        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Restricted action should NOT be delivered when dispatched by a foreign feature.")

        val securityLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("SECURITY VIOLATION")
        }
        assertNotNull(securityLog, "A security violation should be logged for unauthorized dispatch.")
    }

    @Test
    fun `restricted action rejects hierarchical child of foreign feature`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.INTERNAL", featureName = "alpha", open = false, broadcast = false)
        )

        // "beta.some-agent" should resolve to feature "beta", not "alpha"
        store.dispatch("beta.some-agent", Action("alpha.INTERNAL"))

        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Restricted action should NOT be accepted from a child of a foreign feature.")
    }

    // ========================================================================
    // DELIVERY / ROUTING TESTS (broadcast/targeted flags — who receives)
    // ========================================================================

    @Test
    fun `broadcast action is delivered to all features`() {
        val store = createRoutingStore(
            descriptor("test.BROADCAST", featureName = "alpha", open = true, broadcast = true)
        )

        store.dispatch("anyone", Action("test.BROADCAST"))

        assertTrue(store.alphaState().reducerLog.contains("test.BROADCAST"),
            "Broadcast action should be delivered to alpha (owner).")
        assertTrue(store.betaState().reducerLog.contains("test.BROADCAST"),
            "Broadcast action should be delivered to beta (non-owner).")
    }

    @Test
    fun `non-broadcast action is delivered to owning feature only`() {
        val store = createRoutingStore(
            descriptor("alpha.PRIVATE", featureName = "alpha", open = false, broadcast = false)
        )

        store.dispatch("alpha", Action("alpha.PRIVATE"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.PRIVATE"),
            "Non-broadcast action should be delivered to the owning feature.")
        assertTrue(store.betaState().reducerLog.isEmpty(),
            "Non-broadcast action should NOT be delivered to other features.")
    }

    @Test
    fun `open non-broadcast action is delivered to owner only despite any-originator auth`() {
        // This is the REGISTER_IDENTITY pattern: anyone can dispatch, but only CoreFeature receives.
        val store = createRoutingStore(
            descriptor("alpha.OPEN_PRIVATE", featureName = "alpha", open = true, broadcast = false)
        )

        // Beta dispatches, but only alpha should receive
        store.dispatch("beta", Action("alpha.OPEN_PRIVATE"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.OPEN_PRIVATE"),
            "Open non-broadcast action should be delivered to the owning feature.")
        assertTrue(store.betaState().reducerLog.isEmpty(),
            "Open non-broadcast action should NOT be delivered to non-owning features.")
    }

    @Test
    fun `restricted broadcast (event) is delivered to all features`() {
        // Events: only the owner can dispatch, but all features receive
        val store = createRoutingStore(
            descriptor("alpha.EVENT", featureName = "alpha", open = false, broadcast = true)
        )

        store.dispatch("alpha", Action("alpha.EVENT"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.EVENT"),
            "Event should be delivered to owner.")
        assertTrue(store.betaState().reducerLog.contains("alpha.EVENT"),
            "Event should be delivered to all other features.")
    }

    @Test
    fun `restricted broadcast (event) rejects foreign originator`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.EVENT", featureName = "alpha", open = false, broadcast = true)
        )

        store.dispatch("beta", Action("alpha.EVENT"))

        assertTrue(store.alphaState().reducerLog.isEmpty(), "Event should not be delivered if originator is unauthorized.")
        assertTrue(store.betaState().reducerLog.isEmpty(), "Event should not be delivered if originator is unauthorized.")
    }

    @Test
    fun `targeted action is delivered to recipient feature only`() {
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true)
        )

        // Alpha dispatches a targeted response to beta
        store.dispatch("alpha", Action("alpha.RESPONSE", targetRecipient = "beta"))

        assertTrue(store.betaState().reducerLog.contains("alpha.RESPONSE"),
            "Targeted action should be delivered to the recipient feature.")
        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Targeted action should NOT be delivered to the dispatching/owning feature (unless it's also the recipient).")
    }

    @Test
    fun `targeted action delivered to self when recipient is owning feature`() {
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true)
        )

        // Alpha dispatches a targeted response to itself
        store.dispatch("alpha", Action("alpha.RESPONSE", targetRecipient = "alpha"))

        assertTrue(store.alphaState().reducerLog.contains("alpha.RESPONSE"),
            "Targeted action should be delivered when recipient is the owning feature itself.")
        assertTrue(store.betaState().reducerLog.isEmpty(),
            "Targeted action should NOT be delivered to non-recipient features.")
    }

    @Test
    fun `targeted action rejects foreign originator`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true)
        )

        // Beta tries to dispatch alpha's targeted action — should be rejected by authorization
        store.dispatch("beta", Action("alpha.RESPONSE", targetRecipient = "alpha"))

        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Targeted action dispatched by unauthorized originator should be rejected.")
        assertTrue(store.betaState().reducerLog.isEmpty(),
            "Targeted action dispatched by unauthorized originator should not reach any feature.")
    }

    @Test
    fun `targeted action without targetRecipient is rejected`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true)
        )

        // Dispatch without targetRecipient — should be rejected at Step 1b
        store.dispatch("alpha", Action("alpha.RESPONSE"))

        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Targeted action without targetRecipient should be rejected.")

        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("without targetRecipient")
        }
        assertNotNull(errorLog, "An error should be logged for targeted action missing targetRecipient.")
    }

    @Test
    fun `non-targeted action with targetRecipient is rejected`() {
        platform.capturedLogs.clear()
        val store = createRoutingStore(
            descriptor("alpha.INTERNAL", featureName = "alpha", open = false, broadcast = false, targeted = false)
        )

        // Dispatch a non-targeted action with targetRecipient — should be rejected at Step 1b
        store.dispatch("alpha", Action("alpha.INTERNAL", targetRecipient = "beta"))

        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Non-targeted action with targetRecipient should be rejected.")

        val errorLog = platform.capturedLogs.find {
            it.level == LogLevel.ERROR && it.message.contains("not declared as targeted")
        }
        assertNotNull(errorLog, "An error should be logged for non-targeted action carrying targetRecipient.")
    }

    @Test
    fun `targeted action resolves hierarchical recipient to feature level`() {
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true)
        )

        // Target "beta.sub-entity" — Store should resolve to feature "beta"
        store.dispatch("alpha", Action("alpha.RESPONSE", targetRecipient = "beta.sub-entity"))

        assertTrue(store.betaState().reducerLog.contains("alpha.RESPONSE"),
            "Targeted action with hierarchical recipient should be delivered to the resolved feature.")
        assertTrue(store.alphaState().reducerLog.isEmpty(),
            "Targeted action should not be delivered to the owning feature.")
    }

    // ========================================================================
    // SIDE-EFFECT DELIVERY SCOPE TESTS
    // handleSideEffects delivery should mirror reducer delivery
    // ========================================================================

    @Test
    fun `handleSideEffects called on all features for broadcast action`() {
        val store = createRoutingStore(
            descriptor("test.BROADCAST", featureName = "alpha", open = true, broadcast = true),
            useSideEffectTracking = true
        )

        store.dispatch("anyone", Action("test.BROADCAST"))

        // The marker actions prove handleSideEffects was called
        assertTrue(store.alphaState().sideEffectLog.contains("test.BROADCAST"),
            "handleSideEffects should be called on alpha for broadcast action.")
        assertTrue(store.betaState().sideEffectLog.contains("test.BROADCAST"),
            "handleSideEffects should be called on beta for broadcast action.")
    }

    @Test
    fun `handleSideEffects called only on owner for non-broadcast action`() {
        val store = createRoutingStore(
            descriptor("alpha.PRIVATE", featureName = "alpha", open = false, broadcast = false),
            useSideEffectTracking = true
        )

        store.dispatch("alpha", Action("alpha.PRIVATE"))

        assertTrue(store.alphaState().sideEffectLog.contains("alpha.PRIVATE"),
            "handleSideEffects should be called on alpha (owner) for non-broadcast action.")
        assertTrue(store.betaState().sideEffectLog.isEmpty(),
            "handleSideEffects should NOT be called on beta for non-broadcast action.")
    }

    @Test
    fun `handleSideEffects called only on owner for open non-broadcast action`() {
        val store = createRoutingStore(
            descriptor("alpha.OPEN_PRIVATE", featureName = "alpha", open = true, broadcast = false),
            useSideEffectTracking = true
        )

        store.dispatch("beta", Action("alpha.OPEN_PRIVATE"))

        assertTrue(store.alphaState().sideEffectLog.contains("alpha.OPEN_PRIVATE"),
            "handleSideEffects should be called on alpha (owner) for open non-broadcast.")
        assertTrue(store.betaState().sideEffectLog.isEmpty(),
            "handleSideEffects should NOT be called on beta for open non-broadcast.")
    }

    @Test
    fun `handleSideEffects called only on recipient for targeted action`() {
        val store = createRoutingStore(
            descriptor("alpha.RESPONSE", featureName = "alpha", open = false, broadcast = false, targeted = true),
            useSideEffectTracking = true
        )

        store.dispatch("alpha", Action("alpha.RESPONSE", targetRecipient = "beta"))

        assertTrue(store.betaState().sideEffectLog.contains("alpha.RESPONSE"),
            "handleSideEffects should be called on beta (recipient) for targeted action.")
        assertTrue(store.alphaState().sideEffectLog.isEmpty(),
            "handleSideEffects should NOT be called on alpha (owner/sender) for targeted action.")
    }

    // ========================================================================
    // IDENTITY REGISTRY LIFT TEST
    // Verifies CoreState.identityRegistry → AppState.identityRegistry mechanical lift
    // ========================================================================

    @Test
    fun `identity registry is lifted from CoreState to AppState after reduce`() {
        // Use a fresh Store with initFeatureLifecycles() so feature identities are seeded.
        // REGISTER_IDENTITY validates that the parent (originator) exists in the registry.
        val alpha = TrackingFeature("alpha", "Alpha")
        val beta = TrackingFeature("beta", "Beta")
        val coreFeature = CoreFeature(platform)

        val store = Store(
            AppState(
                featureStates = mapOf(
                    "core" to CoreState(lifecycle = AppLifecycle.RUNNING),
                    "alpha" to TrackingState(),
                    "beta" to TrackingState()
                ),
                actionDescriptors = ActionRegistry.byActionName
            ),
            listOf(coreFeature, alpha, beta),
            platform
        )
        store.initFeatureLifecycles()

        // Register an identity via the real REGISTER_IDENTITY action
        store.dispatch("alpha", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("localHandle", "test-entity")
                put("name", "Test Entity")
            }
        ))

        val appState = store.state.value
        val coreState = appState.featureStates["core"] as CoreState

        // The identity should exist in both CoreState and AppState registries
        assertTrue(coreState.identityRegistry.containsKey("alpha.test-entity"),
            "CoreState.identityRegistry should contain the registered identity.")
        assertTrue(appState.identityRegistry.containsKey("alpha.test-entity"),
            "AppState.identityRegistry should be lifted from CoreState after reduce.")
        assertEquals(coreState.identityRegistry, appState.identityRegistry,
            "AppState.identityRegistry should exactly mirror CoreState.identityRegistry.")
    }

    // ========================================================================
    // FEATURE IDENTITY SEEDING TEST
    // ========================================================================

    @Test
    fun `feature identities are seeded in identityRegistry during initFeatureLifecycles`() {
        val alpha = TrackingFeature("alpha", "Alpha")
        val beta = TrackingFeature("beta", "Beta")
        val coreFeature = CoreFeature(platform)

        val store = Store(
            AppState(
                featureStates = mapOf(
                    "core" to CoreState(lifecycle = AppLifecycle.RUNNING),
                    "alpha" to TrackingState(),
                    "beta" to TrackingState()
                ),
                actionDescriptors = ActionRegistry.byActionName
            ),
            listOf(coreFeature, alpha, beta),
            platform
        )

        // Before init, only the default AppState identityRegistry (empty)
        // After init, feature identities should be seeded
        store.initFeatureLifecycles()

        val registry = store.state.value.identityRegistry
        assertTrue(registry.containsKey("core"), "Core feature identity should be seeded.")
        assertTrue(registry.containsKey("alpha"), "Alpha feature identity should be seeded.")
        assertTrue(registry.containsKey("beta"), "Beta feature identity should be seeded.")
        assertNull(registry["alpha"]?.parentHandle, "Feature identities should have null parentHandle (root).")
        assertNull(registry["alpha"]?.uuid, "Feature identities should have null uuid (stable handles).")
    }
}