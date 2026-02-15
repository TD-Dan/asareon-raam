package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
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
    fun `reducer transitions from INITIALIZING to RUNNING on SYSTEM_STARTING`() {
        val initialState = CoreState(lifecycle = AppLifecycle.INITIALIZING)
        val action = Action(ActionRegistry.Names.SYSTEM_STARTING)
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
    // Legacy User Identity Management (deprecated, kept for migration)
    // ================================================================

    @Test
    fun `reducer on CORE_ADD_USER_IDENTITY adds a new identity`() {
        val initialState = CoreState(userIdentities = emptyList())
        val action = Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", "New User") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(1, newState.userIdentities.size)
        assertEquals("New User", newState.userIdentities.first().name)
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY removes the specified identity`() {
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "user-2", name = "User 2")
        val initialState = CoreState(userIdentities = listOf(user1, user2), activeUserId = "user-1")
        val action = Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "user-1") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(1, newState.userIdentities.size)
        assertEquals("User 2", newState.userIdentities.first().name)
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY correctly reassigns active user if active user was deleted`() {
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "user-2", name = "User 2")
        val initialState = CoreState(userIdentities = listOf(user1, user2), activeUserId = "user-1")
        val action = Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "user-1") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("user-2", newState.activeUserId, "Active user should be reassigned to the next available user.")
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY correctly handles removing the last user`() {
        val user1 = Identity("id-1", localHandle = "the-last-user", handle = "the-last-user", name = "The Last User")
        val initialState = CoreState(userIdentities = listOf(user1), activeUserId = "the-last-user")
        val action = Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "the-last-user") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertTrue(newState.userIdentities.isEmpty(), "The user identities list should be empty.")
        assertNull(newState.activeUserId, "The active user ID should be null when no users are left.")
    }

    @Test
    fun `reducer on CORE_SET_ACTIVE_USER_IDENTITY sets the active user`() {
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "user-2", name = "User 2")
        val initialState = CoreState(userIdentities = listOf(user1, user2), activeUserId = "user-1")
        val action = Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", "user-2") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("user-2", newState.activeUserId)
    }

    @Test
    fun `reducer on CORE_SET_ACTIVE_USER_IDENTITY ignores non-existent identity`() {
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val initialState = CoreState(userIdentities = listOf(user1), activeUserId = "user-1")
        val action = Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", "non-existent") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals("user-1", newState.activeUserId, "Active user should remain unchanged for a non-existent ID.")
    }

    @Test
    fun `reducer on CORE_INTERNAL_IDENTITIES_LOADED creates a default user if loaded list is empty`() {
        platform.uuidCounter = 0 // Reset for predictable ID
        val initialState = CoreState(userIdentities = emptyList())
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject { put("identities", buildJsonArray {}) })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(1, newState.userIdentities.size)
        assertEquals("DefaultUser", newState.userIdentities.first().name)
        assertNotNull(newState.activeUserId, "The new default user should be active.")
    }

    @Test
    fun `reducer on CORE_INTERNAL_IDENTITIES_LOADED sets active user to first if saved active ID is invalid`() {
        val initialState = CoreState()
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                add(buildJsonObject { put("uuid", "uuid-1"); put("localHandle", "user-1"); put("handle", "user-1"); put("name", "User 1") })
                add(buildJsonObject { put("uuid", "uuid-2"); put("localHandle", "user-2"); put("handle", "user-2"); put("name", "User 2") })
            })
            put("activeId", "invalid-id")
        })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(2, newState.userIdentities.size)
        assertEquals("user-1", newState.activeUserId, "Should default to the first user if active ID is not found.")
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