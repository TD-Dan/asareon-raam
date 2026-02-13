package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionNames
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
    fun `reducer on CORE_ADD_USER_IDENTITY also populates identityRegistry`() {
        val initialState = CoreState(userIdentities = emptyList())
        val action = Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject { put("name", "Alice") })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        val registryEntry = newState.identityRegistry.values.find { it.name == "Alice" }
        assertNotNull(registryEntry, "New user should also appear in identityRegistry.")
        assertEquals("core", registryEntry.parentHandle, "User identity should have parentHandle 'core'.")
        assertTrue(registryEntry.handle.startsWith("core."), "User handle should start with 'core.'.")
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
    fun `reducer on CORE_IDENTITIES_LOADED creates a default user if loaded list is empty`() {
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
    fun `reducer on CORE_IDENTITIES_LOADED migrates users into identityRegistry`() {
        platform.uuidCounter = 0
        val initialState = CoreState()
        val action = Action(ActionRegistry.Names.CORE_IDENTITIES_LOADED, buildJsonObject {
            put("identities", buildJsonArray {
                add(buildJsonObject { put("uuid", "uuid-a"); put("localHandle", "alice"); put("handle", "alice"); put("name", "Alice") })
            })
            put("activeId", "alice")
        })
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        val registryEntry = newState.identityRegistry["core.alice"]
        assertNotNull(registryEntry, "Loaded identity should be migrated to identityRegistry under core.* namespace.")
        assertEquals("Alice", registryEntry.name)
        assertEquals("core", registryEntry.parentHandle)
    }

    @Test
    fun `reducer on CORE_IDENTITIES_LOADED sets active user to first if saved active ID is invalid`() {
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
    // Phase 2 — Identity Registry: REGISTER_IDENTITY
    // ================================================================

    /**
     * Helper to create a state with a feature identity pre-seeded in the registry,
     * simulating what the Store does at boot via initFeatureLifecycles().
     */
    private fun stateWithFeatureSeeded(featureHandle: String = "session"): CoreState {
        val featureIdentity = Identity(
            uuid = null,
            localHandle = featureHandle,
            handle = featureHandle,
            name = featureHandle.replaceFirstChar { it.uppercase() },
            parentHandle = null
        )
        return CoreState(
            identityRegistry = mapOf(featureHandle to featureIdentity),
            lifecycle = AppLifecycle.RUNNING
        )
    }

    @Test
    fun `REGISTER_IDENTITY adds identity to registry with correct handle construction`() {
        platform.uuidCounter = 0
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject {
                put("localHandle", "chat-cats")
                put("name", "Chat about Cats")
            },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)

        val registered = newState.identityRegistry["session.chat-cats"]
        assertNotNull(registered, "The new identity should exist at 'session.chat-cats' in the registry.")
        assertEquals("chat-cats", registered.localHandle)
        assertEquals("session.chat-cats", registered.handle)
        assertEquals("Chat about Cats", registered.name)
        assertEquals("session", registered.parentHandle)
        assertEquals("fake-uuid-1", registered.uuid)
    }

    @Test
    fun `REGISTER_IDENTITY preserves existing registry entries`() {
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat1"); put("name", "Chat 1") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNotNull(newState.identityRegistry["session"], "Feature identity should still be present.")
        assertNotNull(newState.identityRegistry["session.chat1"], "New identity should be added.")
        assertEquals(2, newState.identityRegistry.size)
    }

    @Test
    fun `REGISTER_IDENTITY rejects invalid localHandle with uppercase`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "BadHandle"); put("name", "Test") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(initialState.identityRegistry, newState.identityRegistry, "Registry should be unchanged for invalid localHandle.")
    }

    @Test
    fun `REGISTER_IDENTITY rejects localHandle starting with digit`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "1agent"); put("name", "Test") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry)
    }

    @Test
    fun `REGISTER_IDENTITY rejects localHandle containing dots`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "my.agent"); put("name", "Test") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry,
            "Dots are hierarchy separators and must not appear in localHandle.")
    }

    @Test
    fun `REGISTER_IDENTITY rejects empty localHandle`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", ""); put("name", "Test") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry)
    }

    @Test
    fun `REGISTER_IDENTITY rejects localHandle with spaces`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "my agent"); put("name", "Test") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry)
    }

    @Test
    fun `REGISTER_IDENTITY accepts valid localHandle with hyphens and digits`() {
        val initialState = stateWithFeatureSeeded("agent")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "gemini-flash-2"); put("name", "Gemini Flash 2") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState?.identityRegistry?.get("agent.gemini-flash-2"))
    }

    @Test
    fun `REGISTER_IDENTITY deduplicates among siblings by appending -2`() {
        platform.uuidCounter = 0
        val existingIdentity = Identity(
            uuid = "existing-uuid", localHandle = "chat1", handle = "session.chat1",
            name = "Chat 1", parentHandle = "session"
        )
        val initialState = stateWithFeatureSeeded("session").let { state ->
            state.copy(identityRegistry = state.identityRegistry + ("session.chat1" to existingIdentity))
        }

        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat1"); put("name", "Chat 1 Clone") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)

        // Original should still exist
        assertNotNull(newState.identityRegistry["session.chat1"])
        // Deduplicated version should be at session.chat1-2
        val deduped = newState.identityRegistry["session.chat1-2"]
        assertNotNull(deduped, "Duplicate localHandle should be deduplicated to 'chat1-2'.")
        assertEquals("chat1-2", deduped.localHandle)
        assertEquals("Chat 1 Clone", deduped.name)
    }

    @Test
    fun `REGISTER_IDENTITY deduplicates to -3 when -2 is also taken`() {
        val initialState = stateWithFeatureSeeded("session").let { state ->
            state.copy(identityRegistry = state.identityRegistry +
                    ("session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "C1", parentHandle = "session")) +
                    ("session.chat1-2" to Identity(uuid = "u2", localHandle = "chat1-2", handle = "session.chat1-2", name = "C2", parentHandle = "session"))
            )
        }

        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat1"); put("name", "Chat 1 Third") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState?.identityRegistry?.get("session.chat1-3"),
            "Should deduplicate to -3 when both original and -2 are taken.")
    }

    @Test
    fun `REGISTER_IDENTITY rejects when originator is not in the registry`() {
        // State has NO feature identities seeded — simulates a bad originator
        val initialState = CoreState(identityRegistry = emptyMap(), lifecycle = AppLifecycle.RUNNING)
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "test"); put("name", "Test") },
            originator = "unknown-feature"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertEquals(emptyMap(), newState.identityRegistry,
            "Registration should fail when the parent (originator) is not in the registry.")
    }

    @Test
    fun `REGISTER_IDENTITY supports hierarchical registration (child of child)`() {
        platform.uuidCounter = 0
        val agentIdentity = Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent", parentHandle = null)
        val geminiIdentity = Identity(uuid = "u1", localHandle = "gemini-coder", handle = "agent.gemini-coder", name = "Gemini Coder", parentHandle = "agent")
        val initialState = CoreState(
            identityRegistry = mapOf(
                "agent" to agentIdentity,
                "agent.gemini-coder" to geminiIdentity
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "sub-task"); put("name", "Sub-Task Worker") },
            originator = "agent.gemini-coder"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        val subTask = newState.identityRegistry["agent.gemini-coder.sub-task"]
        assertNotNull(subTask, "Three-level hierarchy should be supported.")
        assertEquals("agent.gemini-coder", subTask.parentHandle)
        assertEquals("sub-task", subTask.localHandle)
    }

    @Test
    fun `REGISTER_IDENTITY with missing payload returns unchanged state`() {
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, payload = null, originator = "session")
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry)
    }

    @Test
    fun `REGISTER_IDENTITY generates a UUID and timestamp for the new identity`() {
        platform.uuidCounter = 10
        platform.currentTime = 9_876_543_210L
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "test"); put("name", "Test") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        val identity = newState?.identityRegistry?.get("session.test")
        assertNotNull(identity)
        assertEquals("fake-uuid-11", identity.uuid, "Should use platform UUID generator.")
        assertEquals(9_876_543_210L, identity.registeredAt, "Should use platform timestamp.")
    }

    // ================================================================
    // Phase 2 — Identity Registry: UNREGISTER_IDENTITY
    // ================================================================

    @Test
    fun `UNREGISTER_IDENTITY removes the specified identity`() {
        val initialState = stateWithFeatureSeeded("session").let { state ->
            state.copy(identityRegistry = state.identityRegistry +
                    ("session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session"))
            )
        }

        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNull(newState.identityRegistry["session.chat1"], "The identity should be removed.")
        assertNotNull(newState.identityRegistry["session"], "The parent feature identity should remain.")
    }

    @Test
    fun `UNREGISTER_IDENTITY cascades to remove all descendants`() {
        val initialState = CoreState(
            identityRegistry = mapOf(
                "agent" to Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent"),
                "agent.gemini" to Identity(uuid = "u1", localHandle = "gemini", handle = "agent.gemini", name = "Gemini", parentHandle = "agent"),
                "agent.gemini.sub-a" to Identity(uuid = "u2", localHandle = "sub-a", handle = "agent.gemini.sub-a", name = "Sub A", parentHandle = "agent.gemini"),
                "agent.gemini.sub-b" to Identity(uuid = "u3", localHandle = "sub-b", handle = "agent.gemini.sub-b", name = "Sub B", parentHandle = "agent.gemini"),
                "agent.other" to Identity(uuid = "u4", localHandle = "other", handle = "agent.other", name = "Other", parentHandle = "agent")
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "agent.gemini") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNull(newState.identityRegistry["agent.gemini"], "Target should be removed.")
        assertNull(newState.identityRegistry["agent.gemini.sub-a"], "Descendant sub-a should be cascade-removed.")
        assertNull(newState.identityRegistry["agent.gemini.sub-b"], "Descendant sub-b should be cascade-removed.")
        assertNotNull(newState.identityRegistry["agent"], "Parent feature should remain.")
        assertNotNull(newState.identityRegistry["agent.other"], "Sibling should remain.")
    }

    @Test
    fun `UNREGISTER_IDENTITY enforces namespace - rejects cross-namespace deletion`() {
        val initialState = CoreState(
            identityRegistry = mapOf(
                "session" to Identity(uuid = null, localHandle = "session", handle = "session", name = "Session"),
                "session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session"),
                "agent" to Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent")
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        // Agent tries to unregister a session identity — should be rejected
        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNotNull(newState.identityRegistry["session.chat1"],
            "Cross-namespace deletion should be rejected; identity should remain.")
    }

    @Test
    fun `UNREGISTER_IDENTITY allows unregistering within own namespace`() {
        val initialState = CoreState(
            identityRegistry = mapOf(
                "session" to Identity(uuid = null, localHandle = "session", handle = "session", name = "Session"),
                "session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session")
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNull(newState?.identityRegistry?.get("session.chat1"),
            "Same-namespace deletion should succeed.")
    }

    @Test
    fun `UNREGISTER_IDENTITY allows self-unregistration`() {
        val initialState = CoreState(
            identityRegistry = mapOf(
                "agent" to Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent"),
                "agent.gemini" to Identity(uuid = "u1", localHandle = "gemini", handle = "agent.gemini", name = "Gemini", parentHandle = "agent")
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        // agent.gemini unregisters itself
        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "agent.gemini") },
            originator = "agent.gemini"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNull(newState?.identityRegistry?.get("agent.gemini"),
            "An identity should be able to unregister itself.")
    }

    @Test
    fun `UNREGISTER_IDENTITY returns unchanged state for handle not in registry`() {
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.nonexistent") },
            originator = "session"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry,
            "State should be unchanged when trying to unregister a non-existent handle.")
    }

    @Test
    fun `UNREGISTER_IDENTITY with missing payload returns unchanged state`() {
        val initialState = stateWithFeatureSeeded("session")
        val action = Action(ActionRegistry.Names.CORE_UNREGISTER_IDENTITY, payload = null, originator = "session")
        val newState = feature.reducer(initialState, action) as? CoreState
        assertEquals(initialState.identityRegistry, newState?.identityRegistry)
    }

    @Test
    fun `UNREGISTER_IDENTITY cascade does not remove false prefix matches`() {
        // Ensure "agent.gem" doesn't accidentally cascade-remove "agent.gemini"
        val initialState = CoreState(
            identityRegistry = mapOf(
                "agent" to Identity(uuid = null, localHandle = "agent", handle = "agent", name = "Agent"),
                "agent.gem" to Identity(uuid = "u1", localHandle = "gem", handle = "agent.gem", name = "Gem", parentHandle = "agent"),
                "agent.gemini" to Identity(uuid = "u2", localHandle = "gemini", handle = "agent.gemini", name = "Gemini", parentHandle = "agent")
            ),
            lifecycle = AppLifecycle.RUNNING
        )

        val action = Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "agent.gem") },
            originator = "agent"
        )
        val newState = feature.reducer(initialState, action) as? CoreState
        assertNotNull(newState)
        assertNull(newState.identityRegistry["agent.gem"], "Target should be removed.")
        assertNotNull(newState.identityRegistry["agent.gemini"],
            "Sibling 'agent.gemini' must NOT be removed — cascade uses 'handle.' prefix, not substring match.")
    }

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