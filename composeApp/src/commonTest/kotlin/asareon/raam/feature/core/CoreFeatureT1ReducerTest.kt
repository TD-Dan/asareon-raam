package asareon.raam.feature.core

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tier 1 Unit Tests for CoreFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 * No TestEnvironment or real Store is used.
 *
 */
class CoreFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = CoreFeature(platformDependencies = platform)

    // ================================================================
    // Lifecycle Reducer Tests
    // ================================================================

    @Test
    fun `reducer transitions from BOOTING to INITIALIZING on SYSTEM_INITIALIZING`() {
        val initialState = CoreState(lifecycle = AppLifecycle.BOOTING)
        val action = Action(ActionRegistry.Names.SYSTEM_INITIALIZING)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(AppLifecycle.INITIALIZING, newState?.lifecycle)
    }

    @Test
    fun `reducer transitions from INITIALIZING to RUNNING on SYSTEM_RUNNING`() {
        val initialState = CoreState(lifecycle = AppLifecycle.INITIALIZING)
        val action = Action(ActionRegistry.Names.SYSTEM_RUNNING)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(AppLifecycle.RUNNING, newState?.lifecycle)
    }

    @Test
    fun `reducer transitions to CLOSING on SYSTEM_CLOSING`() {
        val initialState = CoreState(lifecycle = AppLifecycle.RUNNING)
        val action = Action(ActionRegistry.Names.SYSTEM_CLOSING)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(AppLifecycle.CLOSING, newState?.lifecycle)
    }

    @Test
    fun `reducer transitions to SHUTDOWN on SYSTEM_SHUTDOWN`() {
        val initialState = CoreState(lifecycle = AppLifecycle.CLOSING)
        val action = Action(ActionRegistry.Names.SYSTEM_SHUTDOWN)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(AppLifecycle.SHUTDOWN, newState?.lifecycle)
    }

    // ================================================================
    // State Mutation Reducer Tests
    // ================================================================

    @Test
    fun `reducer correctly handles SET_ACTIVE_VIEW`() {
        val initialState = CoreState(activeViewKey = "old.key")
        val payload = buildJsonObject { put("key", "new.key") }
        val action = Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, payload)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("new.key", newState.activeViewKey)
    }

    @Test
    fun `reducer correctly handles SHOW_TOAST`() {
        val initialState = CoreState(toastMessage = null)
        val payload = buildJsonObject { put("message", "Hello") }
        val action = Action(ActionRegistry.Names.CORE_SHOW_TOAST, payload)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("Hello", newState.toastMessage)
    }

    @Test
    fun `reducer correctly handles CLEAR_TOAST`() {
        val initialState = CoreState(toastMessage = "Something")
        val action = Action(ActionRegistry.Names.CORE_CLEAR_TOAST)
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNull(newState.toastMessage)
    }

    @Test
    fun `reducer ignores unknown actions`() {
        val initialState = CoreState()
        val action = Action("some.other.ACTION")
        val newState = feature.reducer(initialState, action)
        assertEquals(initialState, newState, "State should not change for an unknown action.")
    }

    // ================================================================
    // User Identity Management (reducer-level)
    //
    // Since the deprecation cleanup, identity mutations (add, remove) are
    // handled entirely in handleSideEffects via the registry. The reducer
    // only manages activeUserId. These tests verify that contract.
    // ================================================================

    @Test
    fun `reducer on CORE_ADD_USER_IDENTITY is a no-op`() {
        val initialState = CoreState(activeUserId = "core.alice")
        val action = Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", "New User") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(initialState, newState, "ADD_USER_IDENTITY should be a reducer no-op (handled in side effects).")
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY is a no-op`() {
        val initialState = CoreState(activeUserId = "core.alice")
        val action = Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "core.alice") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(initialState, newState, "REMOVE_USER_IDENTITY should be a reducer no-op (handled in side effects).")
    }

    @Test
    fun `reducer on CORE_SET_ACTIVE_USER_IDENTITY sets the active user`() {
        val initialState = CoreState(activeUserId = "core.user-1")
        val action = Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", "core.user-2") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("core.user-2", newState.activeUserId)
    }

    @Test
    fun `reducer on CORE_IDENTITIES_LOADED sets activeUserId from payload`() {
        val initialState = CoreState()
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                add(buildJsonObject { put("uuid", "uuid-1"); put("localHandle", "alice"); put("handle", "core.alice"); put("name", "Alice"); put("parentHandle", "core") })
                add(buildJsonObject { put("uuid", "uuid-2"); put("localHandle", "bob"); put("handle", "core.bob"); put("name", "Bob"); put("parentHandle", "core") })
            })
            put("activeId", "core.bob")
        })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("core.bob", newState.activeUserId)
    }

    @Test
    fun `reducer on CORE_IDENTITIES_LOADED defaults activeUserId to first core child when saved activeId is invalid`() {
        val initialState = CoreState()
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                add(buildJsonObject { put("uuid", "uuid-1"); put("localHandle", "alice"); put("handle", "core.alice"); put("name", "Alice"); put("parentHandle", "core") })
            })
            put("activeId", "invalid-id")
        })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("core.alice", newState.activeUserId, "Should default to the first core child when activeId is invalid.")
    }

    @Test
    fun `reducer on CORE_IDENTITIES_LOADED defaults activeUserId when no core children exist`() {
        val initialState = CoreState()
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                // Only a feature identity (uuid=null, no parentHandle) — no core children
                add(buildJsonObject { put("uuid", null as String?); put("localHandle", "agent"); put("handle", "agent"); put("name", "Agent Runtime") })
            })
        })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("core.default-user", newState.activeUserId, "Should default to core.default-user when no core children are in the payload.")
    }

    // ================================================================
    // Phase 2 — Identity Registry: REGISTER / UNREGISTER_IDENTITY
    // ================================================================
    // These operations moved from the reducer to handleSideEffects in the
    // "Eliminate Identity Registry Dual-Ownership" change. Business logic
    // (validation, dedup, namespace enforcement, cascade) is now exercised
    // through the Store via T2 and T3 tests.

    // ================================================================
    // Static Validation Helper
    // ================================================================

    @Test
    fun `isValidLocalHandle rejects various invalid formats`() {
        assertFalse(CoreFeature.isValidLocalHandle(""), "Empty string")
        assertFalse(CoreFeature.isValidLocalHandle("A"), "Uppercase")
        assertFalse(CoreFeature.isValidLocalHandle("1abc"), "Starts with digit")
        assertFalse(CoreFeature.isValidLocalHandle("-abc"), "Starts with hyphen")
        assertFalse(CoreFeature.isValidLocalHandle("a.b"), "Contains dot")
        assertFalse(CoreFeature.isValidLocalHandle("a b"), "Contains space")
        assertFalse(CoreFeature.isValidLocalHandle("a_b"), "Contains underscore")
    }

    @Test
    fun `isValidLocalHandle accepts valid formats`() {
        assertTrue(CoreFeature.isValidLocalHandle("a"))
        assertTrue(CoreFeature.isValidLocalHandle("session"))
        assertTrue(CoreFeature.isValidLocalHandle("chat-cats"))
        assertTrue(CoreFeature.isValidLocalHandle("gemini-flash-2"))
        assertTrue(CoreFeature.isValidLocalHandle("a1"))
    }
}