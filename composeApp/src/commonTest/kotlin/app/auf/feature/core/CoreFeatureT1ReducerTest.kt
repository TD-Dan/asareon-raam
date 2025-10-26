package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for CoreFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 * No TestEnvironment or real Store is used.
 */
class CoreFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("v2-test")
    private val feature = CoreFeature(platformDependencies = platform)
    private val featureName = feature.name

    private fun createAppState(coreState: CoreState = CoreState()) = AppState(
        featureStates = mapOf(featureName to coreState)
    )

    // --- Lifecycle Reducer Tests ---

    @Test
    fun `reducer transitions from BOOTING to INITIALIZING on SYSTEM_PUBLISH_INITIALIZING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.BOOTING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.INITIALIZING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions from INITIALIZING to RUNNING on SYSTEM_PUBLISH_STARTING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_STARTING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.RUNNING, newCoreState.lifecycle)
    }

    @Test
    fun `reducer transitions to CLOSING on SYSTEM_PUBLISH_CLOSING`() {
        val initialState = createAppState(CoreState(lifecycle = AppLifecycle.RUNNING))
        val action = Action(ActionNames.SYSTEM_PUBLISH_CLOSING)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(AppLifecycle.CLOSING, newCoreState.lifecycle)
    }

    // --- State Mutation Reducer Tests ---

    @Test
    fun `reducer correctly handles SET_ACTIVE_VIEW`() {
        val initialState = createAppState(CoreState(activeViewKey = "old.key"))
        val payload = buildJsonObject { put("key", "new.key") }
        val action = Action(ActionNames.CORE_SET_ACTIVE_VIEW, payload)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertEquals("new.key", newCoreState.activeViewKey)
    }

    @Test
    fun `reducer correctly handles SHOW_TOAST`() {
        val initialState = createAppState(CoreState(toastMessage = null))
        val payload = buildJsonObject { put("message", "Hello") }
        val action = Action(ActionNames.CORE_SHOW_TOAST, payload)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertEquals("Hello", newCoreState.toastMessage)
    }

    @Test
    fun `reducer correctly handles CLEAR_TOAST`() {
        val initialState = createAppState(CoreState(toastMessage = "Something"))
        val action = Action(ActionNames.CORE_CLEAR_TOAST)

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as? CoreState

        assertNotNull(newCoreState)
        assertNull(newCoreState.toastMessage)
    }

    @Test
    fun `reducer ignores unknown actions`() {
        val initialState = createAppState()
        val action = Action("some.other.ACTION")

        val newState = feature.reducer(initialState, action)

        assertEquals(initialState, newState, "State should not change for an unknown action.")
    }

    // --- NEW TESTS for Identity Management ---

    @Test
    fun `reducer on CORE_ADD_USER_IDENTITY adds a new identity`() {
        val initialState = createAppState(CoreState(userIdentities = emptyList()))
        val action = Action(ActionNames.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", "New User") })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(1, newCoreState.userIdentities.size)
        assertEquals("New User", newCoreState.userIdentities.first().name)
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY removes the specified identity`() {
        val user1 = Identity("id-1", "User 1")
        val user2 = Identity("id-2", "User 2")
        val initialState = createAppState(CoreState(userIdentities = listOf(user1, user2), activeUserId = "id-1"))
        val action = Action(ActionNames.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "id-1") })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(1, newCoreState.userIdentities.size)
        assertEquals("id-2", newCoreState.userIdentities.first().id)
    }

    @Test
    fun `reducer on CORE_REMOVE_USER_IDENTITY correctly reassigns active user if active user was deleted`() {
        val user1 = Identity("id-1", "User 1")
        val user2 = Identity("id-2", "User 2")
        val initialState = createAppState(CoreState(userIdentities = listOf(user1, user2), activeUserId = "id-1"))
        val action = Action(ActionNames.CORE_REMOVE_USER_IDENTITY, buildJsonObject { put("id", "id-1") })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals("id-2", newCoreState.activeUserId, "Active user should be reassigned to the next available user.")
    }

    @Test
    fun `reducer on CORE_SET_ACTIVE_USER_IDENTITY sets the active user`() {
        val user1 = Identity("id-1", "User 1")
        val user2 = Identity("id-2", "User 2")
        val initialState = createAppState(CoreState(userIdentities = listOf(user1, user2), activeUserId = "id-1"))
        val action = Action(ActionNames.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject { put("id", "id-2") })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals("id-2", newCoreState.activeUserId)
    }

    @Test
    fun `reducer on CORE_INTERNAL_IDENTITIES_LOADED creates a default user if loaded list is empty`() {
        platform.uuidCounter = 0 // Reset for predictable ID
        val initialState = createAppState(CoreState(userIdentities = emptyList()))
        val action = Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, buildJsonObject { put("identities", buildJsonArray {}) })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(1, newCoreState.userIdentities.size)
        assertEquals("User", newCoreState.userIdentities.first().name)
        assertEquals("fake-uuid-1", newCoreState.userIdentities.first().id)
        assertEquals("fake-uuid-1", newCoreState.activeUserId, "The new default user should be active.")
    }

    @Test
    fun `reducer on CORE_INTERNAL_IDENTITIES_LOADED sets active user to first if saved active ID is invalid`() {
        val user1 = Identity("id-1", "User 1")
        val user2 = Identity("id-2", "User 2")
        val initialState = createAppState()
        val action = Action(ActionNames.CORE_INTERNAL_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                add(buildJsonObject { put("id", "id-1"); put("name", "User 1")})
                add(buildJsonObject { put("id", "id-2"); put("name", "User 2")})
            })
            put("activeId", "invalid-id")
        })

        val newState = feature.reducer(initialState, action)
        val newCoreState = newState.featureStates[featureName] as CoreState

        assertEquals(2, newCoreState.userIdentities.size)
        assertEquals("id-1", newCoreState.activeUserId, "Should default to the first user if active ID is not found.")
    }
}