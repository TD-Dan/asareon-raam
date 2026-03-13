package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.DefaultPermissions
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Tests for CoreFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and handleSideEffects handlers
 * working together within a realistic TestEnvironment that includes the real Store.
 */
class CoreFeatureT2CoreTest {

    /**
     * Parses the persisted identities.json content from a FILESYSTEM_WRITE action payload.
     * Returns (identities as list of JsonObject, activeId as String?).
     */
    private fun parsePersistedContent(action: Action): Pair<List<JsonObject>, String?> {
        val content = action.payload?.get("content")?.jsonPrimitive?.content
            ?: error("No content in FILESYSTEM_WRITE payload")
        val root = Json.parseToJsonElement(content).jsonObject
        val identities = root["identities"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val activeId = root["activeId"]?.jsonPrimitive?.contentOrNull
        return identities to activeId
    }

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
            // Apply DefaultPermissions, matching what Store.initFeatureLifecycles() does.
            val defaults = DefaultPermissions.grantsFor(handle)
            handle to Identity(
                uuid = null,
                localHandle = handle,
                handle = handle,
                name = handle.replaceFirstChar { it.uppercase() },
                parentHandle = null,
                permissions = defaults
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
        harness.runAndLogOnFailure {
            val addActions = harness.processedActions.filter { it.name == ActionRegistry.Names.SETTINGS_ADD }
            assertEquals(3, addActions.size, "Should dispatch three SETTINGS_ADD actions (width, height, use_identity_color).")
            assertNotNull(addActions.find { it.payload?.get("key").toString().contains("width") })
            assertNotNull(addActions.find { it.payload?.get("key").toString().contains("height") })
            assertNotNull(addActions.find { it.payload?.get("key").toString().contains("use_identity_color") })
        }
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
        harness.store.dispatch("core", action)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            assertEquals(textToCopy, platform.clipboardContent, "The text should be copied to the platform's clipboard.")

            val toastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toastAction, "A toast message should be shown.")
            assertEquals("Copied to clipboard.", toastAction.payload?.get("message")?.jsonPrimitive?.content)
        }
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
        harness.store.dispatch("core", Action(ActionRegistry.Names.CORE_ADD_USER_IDENTITY, buildJsonObject {
            put("name", "Test User")
        }))
        runCurrent()

        // ASSERT: Persistence
        harness.runAndLogOnFailure {
            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
            assertEquals("identities.json", writeAction.payload?.get("path")?.jsonPrimitive?.content)
            assertFalse(writeAction.payload?.get("encrypt").toString().toBoolean(), "Persistence encryption is temporarily disabled for debugging.")
            val (identities, _) = parsePersistedContent(writeAction)
            assertTrue(identities.any { it["name"]?.jsonPrimitive?.content == "Test User" },
                "The new user should be in the persisted identities.")

            // ASSERT: Broadcasting
            val broadcastAction = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_IDENTITIES_UPDATED }
            assertNotNull(broadcastAction, "A broadcast action should be dispatched.")
            val broadcastIdentities = broadcastAction.payload?.get("identities")?.jsonArray
            assertNotNull(broadcastIdentities)
            assertTrue(broadcastIdentities.any { it.jsonObject["name"]?.jsonPrimitive?.content == "Test User" },
                "The new user should be in the broadcast payload.")

            // ASSERT: Phase 2 registry broadcast
            val registryBroadcast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED }
            assertNotNull(registryBroadcast, "An IDENTITY_REGISTRY_UPDATED broadcast should be dispatched for legacy user identity changes.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for REMOVE_USER_IDENTITY persists and broadcasts changes`() = runTest {
        val platform = FakePlatformDependencies("test")
        val user1 = Identity("id-1", localHandle = "user-1", handle = "core.user-1", name = "User 1", parentHandle = "core")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "core.user-2", name = "User 2", parentHandle = "core")

        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                activeUserId = "core.user-1",
                lifecycle = AppLifecycle.RUNNING
            ))
            .build(platform = platform)
        harness.store.updateIdentityRegistry { it + mapOf(
            "core.user-1" to user1,
            "core.user-2" to user2
        )}

        // ACT
        harness.store.dispatch("core", Action(ActionRegistry.Names.CORE_REMOVE_USER_IDENTITY, buildJsonObject {
            put("id", "core.user-1")
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // Identity removed from registry
            assertNull(harness.store.state.value.identityRegistry["core.user-1"],
                "User 1 should be removed from the registry.")
            assertNotNull(harness.store.state.value.identityRegistry["core.user-2"],
                "User 2 should remain in the registry.")

            // Persistence
            val writeAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
            val (identities, _) = parsePersistedContent(writeAction)
            val persistedNames = identities.map { it["name"]?.jsonPrimitive?.content }
            assertTrue("User 2" in persistedNames, "User 2 should be in the persisted identities.")
            assertFalse("User 1" in persistedNames, "User 1 should NOT be in the persisted identities.")

            // Registry broadcast
            val registryBroadcast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED }
            assertNotNull(registryBroadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for SET_ACTIVE_USER_IDENTITY persists and broadcasts changes`() = runTest {
        val platform = FakePlatformDependencies("test")
        val user1 = Identity("id-1", localHandle = "user-1", handle = "core.user-1", name = "User 1", parentHandle = "core")
        val user2 = Identity("id-2", localHandle = "user-2", handle = "core.user-2", name = "User 2", parentHandle = "core")

        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(
                activeUserId = "core.user-1",
                lifecycle = AppLifecycle.RUNNING
            ))
            .build(platform = platform)
        harness.store.updateIdentityRegistry { it + mapOf(
            "core.user-1" to user1,
            "core.user-2" to user2
        )}

        // ACT
        harness.store.dispatch("core", Action(ActionRegistry.Names.CORE_SET_ACTIVE_USER_IDENTITY, buildJsonObject {
            put("id", "core.user-2")
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val coreState = harness.store.state.value.featureStates["core"] as CoreState
            assertEquals("core.user-2", coreState.activeUserId)

            val writeAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
            val (identities, activeId) = parsePersistedContent(writeAction)
            val persistedNames = identities.map { it["name"]?.jsonPrimitive?.content }
            assertTrue("User 1" in persistedNames && "User 2" in persistedNames,
                "Both users should be in the persisted identities.")
            assertEquals("core.user-2", activeId, "The new activeId should be persisted.")
        }
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

        // ASSERT
        harness.runAndLogOnFailure {
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY
            }
            assertNotNull(responseAction, "A RETURN_REGISTER_IDENTITY should be dispatched on success.")
            assertEquals("true", responseAction.payload?.get("success")?.jsonPrimitive?.content)
            assertEquals("chat-cats", responseAction.payload?.get("requestedLocalHandle")?.jsonPrimitive?.content)
            assertEquals("chat-cats", responseAction.payload?.get("approvedLocalHandle")?.jsonPrimitive?.content)
            assertEquals("session.chat-cats", responseAction.payload?.get("handle")?.jsonPrimitive?.content)
            assertEquals("session", responseAction.payload?.get("parentHandle")?.jsonPrimitive?.content)

            val registryBroadcast = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertNotNull(registryBroadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast after a successful registration.")

            assertNotNull(harness.store.state.value.identityRegistry["session.chat-cats"])
        }
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
        harness.runAndLogOnFailure {
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

        // ASSERT
        harness.runAndLogOnFailure {
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY
            }
            assertNotNull(responseAction, "A RETURN_REGISTER_IDENTITY should be dispatched even on failure.")
            assertEquals("false", responseAction.payload?.get("success")?.jsonPrimitive?.content)
            assertNotNull(responseAction.payload?.get("error")?.jsonPrimitive?.content, "Error message should be present.")

            val registryBroadcasts = harness.processedActions.filter {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertTrue(registryBroadcasts.isEmpty(),
                "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when a registration is rejected.")
        }
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

        harness.runAndLogOnFailure {
            val registryBroadcasts = harness.processedActions.filter {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertTrue(registryBroadcasts.isEmpty(),
                "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when registration fails due to unknown parent.")
        }
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

        // ASSERT
        harness.runAndLogOnFailure {
            val registryBroadcast = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertNotNull(registryBroadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast after successful unregistration.")
        }
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

        // ASSERT
        harness.runAndLogOnFailure {
            val registryBroadcasts = harness.processedActions.filter {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertTrue(registryBroadcasts.isEmpty(),
                "IDENTITY_REGISTRY_UPDATED must NOT be broadcast when unregistration is rejected by namespace enforcement.")

            assertNotNull(harness.store.state.value.identityRegistry["session.chat1"], "Identity should remain after rejected unregistration.")
        }
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

        harness.runAndLogOnFailure {
            val registryBroadcasts = harness.processedActions.filter {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertTrue(registryBroadcasts.isEmpty(),
                "IDENTITY_REGISTRY_UPDATED must NOT be broadcast for a no-op unregistration (handle not in registry).")
        }
    }

    // ================================================================
    // Phase 2 — UPDATE_IDENTITY side effects
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for successful UPDATE_IDENTITY persists identities to disk`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session",
            extraRegistry = mapOf(
                "session.old-name" to Identity(
                    uuid = "u1", localHandle = "old-name", handle = "session.old-name",
                    name = "Old Name", parentHandle = "session"
                )
            )
        )

        // ACT: Session feature renames the identity
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
            buildJsonObject { put("handle", "session.old-name"); put("newName", "New Name") }
        ))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val writeActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.content == "identities.json"
            }
            assertTrue(writeActions.isNotEmpty(),
                "UPDATE_IDENTITY must persist identities.json to disk — this is the bug that caused " +
                        "stale handles to survive restart.")

            val (identities, _) = parsePersistedContent(writeActions.last())
            val persistedHandles = identities.map { it["handle"]?.jsonPrimitive?.content }
            assertTrue("session.new-name" in persistedHandles,
                "The renamed handle should be in the persisted identities.")
            assertFalse("session.old-name" in persistedHandles,
                "The old handle should NOT be in the persisted identities.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for successful UPDATE_IDENTITY updates registry and broadcasts`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session",
            extraRegistry = mapOf(
                "session.chat1" to Identity(
                    uuid = "u1", localHandle = "chat1", handle = "session.chat1",
                    name = "Chat 1", parentHandle = "session"
                )
            )
        )

        // ACT
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
            buildJsonObject { put("handle", "session.chat1"); put("newName", "Renamed Chat") }
        ))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val response = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY
            }
            assertNotNull(response, "RETURN_UPDATE_IDENTITY should be dispatched.")
            assertEquals("true", response.payload?.get("success")?.jsonPrimitive?.content)
            assertEquals("session.chat1", response.payload?.get("oldHandle")?.jsonPrimitive?.content)
            assertEquals("session.renamed-chat", response.payload?.get("newHandle")?.jsonPrimitive?.content)

            val registry = harness.store.state.value.identityRegistry
            assertNull(registry["session.chat1"], "Old handle should be removed from registry.")
            assertNotNull(registry["session.renamed-chat"], "New handle should be in registry.")
            assertEquals("Renamed Chat", registry["session.renamed-chat"]?.name)

            val broadcast = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertNotNull(broadcast, "IDENTITY_REGISTRY_UPDATED should be broadcast after UPDATE_IDENTITY.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handleSideEffects for rejected UPDATE_IDENTITY does not persist or broadcast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .build(platform = platform)
        seedIdentities(harness.store, "core", "session", "agent",
            extraRegistry = mapOf(
                "session.chat1" to Identity(
                    uuid = "u1", localHandle = "chat1", handle = "session.chat1",
                    name = "Chat 1", parentHandle = "session"
                )
            )
        )

        // ACT: Agent tries to rename a session identity — namespace violation
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_UPDATE_IDENTITY,
            buildJsonObject { put("handle", "session.chat1"); put("newName", "Hacked Name") }
        ))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val writeActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE &&
                        it.payload?.get("path")?.jsonPrimitive?.content == "identities.json"
            }
            assertTrue(writeActions.isEmpty(),
                "Rejected UPDATE_IDENTITY must NOT trigger identities.json persistence.")

            val broadcasts = harness.processedActions.filter {
                it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
            }
            assertTrue(broadcasts.isEmpty(),
                "IDENTITY_REGISTRY_UPDATED must NOT be broadcast on rejected UPDATE_IDENTITY.")

            assertEquals("Chat 1", harness.store.state.value.identityRegistry["session.chat1"]?.name)
        }
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

        // ASSERT
        harness.runAndLogOnFailure {
            val appState = harness.store.state.value
            assertNotNull(appState.identityRegistry["session.chat1"],
                "The identity should be visible in AppState.identityRegistry after registration.")
        }
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

        // ASSERT
        harness.runAndLogOnFailure {
            val appState = harness.store.state.value
            assertNotNull(appState.identityRegistry["core"],
                "Core feature identity should be seeded during initFeatureLifecycles().")
        }
    }
}