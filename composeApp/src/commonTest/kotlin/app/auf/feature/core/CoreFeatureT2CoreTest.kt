package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Tests for CoreFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and handleSideEffects handlers
 * working together within a realistic TestEnvironment that includes the real Store.
 */
class CoreFeatureT2CoreTest {

    /**
     * Helper: seeds feature identities into AppState.identityRegistry via Store,
     * mirroring what the Store does in initFeatureLifecycles(). Since these tests
     * only include CoreFeature, we manually seed "session", "agent", etc.
     *
     * Call this AFTER building the TestEnvironment harness.
     */
    private fun seedIdentities(
        store: app.auf.core.Store,
        vararg featureHandles: String,
        extraRegistry: Map<String, Identity> = emptyMap()
    ) {
        val featureIdentities = featureHandles.associate { handle ->
            handle to Identity(
                uuid = null,
                localHandle = handle,
                handle = handle,
                name = handle.replaceFirstChar { it.uppercase() },
                parentHandle = null
            )
        }
        store.updateIdentityRegistry { it + featureIdentities + extraRegistry }
    }

    // ================================================================
    // Existing lifecycle & clipboard tests
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for SYSTEM_INITIALIZING dispatches ADD actions for its settings`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.BOOTING))
            .build(platform = platform)

        // ACT: Dispatch the lifecycle action that triggers the side-effect.
        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
        runCurrent()

        // ASSERT: Verify that the correct side-effects (dispatching ADD actions) occurred.
        val addActions = harness.processedActions.filter { it.name == ActionRegistry.Names.SETTINGS_ADD }
        assertEquals(2, addActions.size, "Should dispatch two SETTINGS_ADD actions.")
        assertNotNull(addActions.find { it.payload?.get("key").toString().contains("width") })
        assertNotNull(addActions.find { it.payload?.get("key").toString().contains("height") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for CORE_COPY_TO_CLIPBOARD copies text and shows a toast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform) // Defaults to RUNNING lifecycle

        val textToCopy = "Hello, Clipboard!"
        val action = Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
            put("text", textToCopy)
        })

        // ACT
        harness.store.dispatch("ui", action)
        runCurrent()

        // ASSERT
        assertEquals(textToCopy, platform.clipboardContent, "The text should be copied to the platform's clipboard.")

        val toastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
        assertNotNull(toastAction, "A toast message should be shown.")
        assertEquals("Copied to clipboard.", toastAction.payload?.get("message")?.jsonPrimitive?.content)
    }

    // ================================================================
    // Legacy user identity persistence & broadcast tests
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for ADD_USER_IDENTITY persists and broadcasts the new identity state`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject {
            put("name", "Test User")
        }))
        runCurrent()

        // ASSERT: Persistence
        val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
        assertEquals("identities.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        assertTrue(writeAction.payload?.get("encrypt").toString().toBoolean(), "Persistence should be encrypted.")
        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(content)
        assertTrue(content.contains("Test User"), "The new user's name should be in the persisted content.")

        // ASSERT: Broadcasting (legacy)
        val broadcastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_IDENTITIES_UPDATED }
        assertNotNull(broadcastAction, "A legacy broadcast action should be dispatched.")
        val broadcastContent = broadcastAction.payload.toString()
        assertTrue(broadcastContent.contains("Test User"), "The new user's name should be in the broadcast payload.")

        // ASSERT: Phase 2 registry broadcast
        val registryBroadcast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED }
        assertNotNull(registryBroadcast, "An IDENTITY_REGISTRY_UPDATED broadcast should be dispatched for legacy user identity changes.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for REMOVE_USER_IDENTITY persists and broadcasts changes`() = runTest {
        val platform = FakePlatformDependencies("test")
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "user-2", name = "User 2")

        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(user1, user2),
                activeUserId = "user-1",
                lifecycle = AppLifecycle.RUNNING
            ))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject {
            put("id", "user-1")
        }))
        runCurrent()

        // ASSERT: Persistence
        val writeAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(content)
        assertTrue(content.contains("User 2") && !content.contains("User 1"), "The remaining user should be in the persisted content.")
        assertTrue(content.contains("\"activeId\":\"user-2\""), "The activeId should be updated in the persisted content.")

        // ASSERT: Broadcasting
        val broadcastAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.CORE_IDENTITIES_UPDATED }
        assertNotNull(broadcastAction, "A broadcast action should be dispatched.")
        val broadcastContent = broadcastAction.payload.toString()
        assertTrue(broadcastContent.contains("User 2") && !broadcastContent.contains("User 1"), "The remaining user should be in the broadcast payload.")
        assertEquals("user-2", broadcastAction.payload?.get("activeId")?.jsonPrimitive?.content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for SET_ACTIVE_USER_IDENTITY persists and broadcasts changes`() = runTest {
        val platform = FakePlatformDependencies("test")
        val user1 = Identity("id-1", localHandle = "user-1", handle = "user-1", name = "User 1")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "user-2", name = "User 2")

        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(user1, user2),
                activeUserId = "user-1",
                lifecycle = AppLifecycle.RUNNING
            ))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject {
            put("id", "user-2")
        }))
        runCurrent()

        // ASSERT: Persistence
        val writeAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
        val content = writeAction.payload?.get("content")?.jsonPrimitive?.content
        assertNotNull(content)
        assertTrue(content.contains("User 1") && content.contains("User 2"), "Both users should be in the persisted content.")
        assertTrue(content.contains("\"activeId\":\"user-2\""), "The new activeId should be in the persisted content.")

        // ASSERT: Broadcasting
        val broadcastAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.CORE_IDENTITIES_UPDATED }
        assertNotNull(broadcastAction, "A broadcast action should be dispatched.")
        val broadcastContent = broadcastAction.payload.toString()
        assertTrue(broadcastContent.contains("User 1") && broadcastContent.contains("User 2"), "Both users should be in the broadcast payload.")
        assertEquals("user-2", broadcastAction.payload?.get("activeId")?.jsonPrimitive?.content)
    }

    // ================================================================
    // Phase 2 — REGISTER_IDENTITY side effects
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for successful REGISTER_IDENTITY dispatches response and registry update`() = runTest {
        val platform = FakePlatformDependencies("test")
        // Pre-seed "session" feature identity so the parent validation passes.
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session")

        // ACT: Session feature registers a child identity
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat-cats"); put("name", "Chat about Cats") }
        ))
        runCurrent()

        // ASSERT: A success response should be dispatched
        val responseAction = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY
        }
        assertNotNull(responseAction, "A RETURN_REGISTER_IDENTITY should be dispatched on success.")
        assertEquals("true", responseAction.payload?.get("success")?.jsonPrimitive?.content)
        assertEquals("chat-cats", responseAction.payload?.get("requestedLocalHandle")?.jsonPrimitive?.content)
        assertEquals("chat-cats", responseAction.payload?.get("approvedLocalHandle")?.jsonPrimitive?.content)
        assertEquals("session.chat-cats", responseAction.payload?.get("handle")?.jsonPrimitive?.content)
        assertEquals("session", responseAction.payload?.get("parentHandle")?.jsonPrimitive?.content)

        // ASSERT: IDENTITY_REGISTRY_UPDATED broadcast should be dispatched
        val registryBroadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertNotNull(registryBroadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast after a successful registration.")

        // ASSERT: The identity is in AppState.identityRegistry (single source of truth)
        assertNotNull(harness.store.state.value.identityRegistry["session.chat-cats"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for successful REGISTER_IDENTITY includes deduplicated handle in response`() = runTest {
        val platform = FakePlatformDependencies("test")

        // Pre-seed state with "session" feature identity AND an existing "session.chat1"
        val existingIdentity = Identity(
            uuid = "existing-uuid", localHandle = "chat1", handle = "session.chat1",
            name = "Chat 1", parentHandle = "session"
        )
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session",
            extraRegistry = mapOf("session.chat1" to existingIdentity)
        )

        // ACT: Register a duplicate localHandle
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat1"); put("name", "Chat 1 Duplicate") }
        ))
        runCurrent()

        // ASSERT: Response should show the deduplicated handle
        val responseAction = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY
        }
        assertNotNull(responseAction)
        assertEquals("true", responseAction.payload?.get("success")?.jsonPrimitive?.content)
        assertEquals("chat1", responseAction.payload?.get("requestedLocalHandle")?.jsonPrimitive?.content)
        assertEquals("chat1-2", responseAction.payload?.get("approvedLocalHandle")?.jsonPrimitive?.content,
            "Response should contain the deduplicated localHandle.")
        assertEquals("session.chat1-2", responseAction.payload?.get("handle")?.jsonPrimitive?.content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for rejected REGISTER_IDENTITY dispatches failure response and NO registry update`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session")

        // ACT: Dispatch with an invalid localHandle (uppercase)
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "InvalidHandle"); put("name", "Test") }
        ))
        runCurrent()

        // ASSERT: A failure response should be dispatched
        val responseAction = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY
        }
        assertNotNull(responseAction, "A RETURN_REGISTER_IDENTITY should be dispatched even on failure.")
        assertEquals("false", responseAction.payload?.get("success")?.jsonPrimitive?.content)
        assertNotNull(responseAction.payload?.get("error")?.jsonPrimitive?.content, "Error message should be present.")

        // ASSERT: IDENTITY_REGISTRY_UPDATED should NOT be broadcast on rejection
        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertTrue(registryBroadcasts.isEmpty(),
            "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when a registration is rejected.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for rejected REGISTER_IDENTITY with unknown originator does not broadcast`() = runTest {
        val platform = FakePlatformDependencies("test")

        // Only seed "core" — so "unknown-feature" won't be in the registry
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core")

        harness.store.dispatch("unknown-feature", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "test"); put("name", "Test") }
        ))
        runCurrent()

        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertTrue(registryBroadcasts.isEmpty(),
            "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when registration fails due to unknown parent.")
    }

    // ================================================================
    // Phase 2 — UNREGISTER_IDENTITY side effects
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for successful UNREGISTER_IDENTITY broadcasts registry update`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session",
            extraRegistry = mapOf(
                "session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session")
            )
        )

        // ACT
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") }
        ))
        runCurrent()

        // ASSERT: Registry broadcast should fire
        val registryBroadcast = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertNotNull(registryBroadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast after successful unregistration.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for rejected UNREGISTER_IDENTITY does NOT broadcast registry update`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session", "agent",
            extraRegistry = mapOf(
                "session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session")
            )
        )

        // ACT: Agent tries to unregister session identity — should be rejected by namespace enforcement
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") }
        ))
        runCurrent()

        // ASSERT: No registry broadcast
        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertTrue(registryBroadcasts.isEmpty(),
            "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when unregistration is rejected by namespace enforcement.")

        // ASSERT: Identity should still exist in AppState.identityRegistry (single source of truth)
        assertNotNull(harness.store.state.value.identityRegistry["session.chat1"], "Identity should remain after rejected unregistration.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for UNREGISTER_IDENTITY of non-existent handle does NOT broadcast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session")

        // ACT: Unregister something that doesn't exist
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.nonexistent") }
        ))
        runCurrent()

        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertTrue(registryBroadcasts.isEmpty(),
            "IDENTITY_REGISTRY_UPDATED must NOT be broadcast for a no-op unregistration (handle not in registry).")
    }

    // ================================================================
    // Phase 2 — Identity registry in AppState (single source of truth)
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity registry in AppState is updated after registration`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session")

        // ACT
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat1"); put("name", "Chat 1") }
        ))
        runCurrent()

        // ASSERT: AppState.identityRegistry (single source of truth) should contain the new identity
        val appState = harness.store.state.value
        assertNotNull(appState.identityRegistry["session.chat1"],
            "The identity should be visible in AppState.identityRegistry after registration.")
    }

    // ================================================================
    // Phase 2 — Feature identity seeding at boot
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `feature identities are seeded into registry during initFeatureLifecycles`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        // ASSERT: The core feature identity should be in AppState.identityRegistry from boot
        val appState = harness.store.state.value
        assertNotNull(appState.identityRegistry["core"],
            "Core feature identity should be seeded during initFeatureLifecycles().")
    }
}