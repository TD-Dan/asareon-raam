package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionNames
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 3 Peer Tests for CoreFeature.
 *
 * Mandate (P-TEST-001, T3): To test the feature's role as a peer in larger,
 * multi-feature workflows. These tests verify the targeted action contracts
 * and service patterns (like the global dialog and identity registry) that enable
 * inter-feature communication.
 *
 * Phase 3: Tests updated to use targeted dispatch (Action.targetRecipient) instead of
 * the deprecated PrivateDataEnvelope / deliverPrivateData pattern.
 */
class CoreFeatureT3PeerTest {

    /**
     * Helper: creates a CoreState with feature identities pre-seeded in the identity
     * registry. This mirrors what the Store does in initFeatureLifecycles() and allows
     * REGISTER_IDENTITY dispatches from these originators to pass parent validation.
     */
    private fun coreStateWithFeatureIdentities(
        vararg featureHandles: String,
        lifecycle: AppLifecycle = AppLifecycle.RUNNING,
        extraRegistry: Map<String, Identity> = emptyMap()
    ): CoreState {
        val featureIdentities = featureHandles.associate { handle ->
            handle to Identity(
                uuid = null,
                localHandle = handle,
                handle = handle,
                name = handle.replaceFirstChar { it.uppercase() },
                parentHandle = null
            )
        }
        return CoreState(
            identityRegistry = featureIdentities + extraRegistry,
            lifecycle = lifecycle
        )
    }

    // ================================================================
    // Targeted delivery: identities loaded from filesystem
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `targeted FILESYSTEM_RESPONSE_READ correctly loads identities`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        // ARRANGE: Manually construct the JSON string that FileSystemFeature would provide.
        val fileContent = buildJsonObject {
            putJsonArray("identities") {
                add(buildJsonObject {
                    put("uuid", "uuid-1")
                    put("localHandle", "loaded-user")
                    put("handle", "loaded-user")
                    put("name", "Loaded User")
                })
            }
            put("activeId", "loaded-user")
        }.toString()

        // ACT: Dispatch a targeted action (as FileSystemFeature would after Phase 3 migration).
        harness.store.dispatch("filesystem", Action(
            name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "identities.json")
                put("content", fileContent)
            },
            targetRecipient = "core"
        ))
        runCurrent()

        // ASSERT 1: Verify that the correct internal action was dispatched.
        val loadedAction = harness.processedActions.find { it.name == ActionNames.CORE_INTERNAL_IDENTITIES_LOADED }
        assertNotNull(loadedAction, "The handleSideEffects handler should dispatch an internal loaded action.")

        // ASSERT 2: Verify the final state is correct.
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertEquals(1, finalState.userIdentities.size)
        assertEquals("Loaded User", finalState.userIdentities.first().name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `targeted FILESYSTEM_RESPONSE_READ migrates loaded identities into identity registry`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        val fileContent = buildJsonObject {
            putJsonArray("identities") {
                add(buildJsonObject {
                    put("uuid", "uuid-alice")
                    put("localHandle", "alice")
                    put("handle", "alice")
                    put("name", "Alice")
                })
                add(buildJsonObject {
                    put("uuid", "uuid-bob")
                    put("localHandle", "bob")
                    put("handle", "bob")
                    put("name", "Bob")
                })
            }
            put("activeId", "alice")
        }.toString()

        // ACT: Dispatch targeted action (replacing old deliverPrivateData call).
        harness.store.dispatch("filesystem", Action(
            name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "identities.json")
                put("content", fileContent)
            },
            targetRecipient = "core"
        ))
        runCurrent()

        // ASSERT: Loaded identities should be in the identity registry under core.* namespace
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        val aliceEntry = finalState.identityRegistry["core.alice"]
        assertNotNull(aliceEntry, "Alice should be migrated to identityRegistry as 'core.alice'.")
        assertEquals("Alice", aliceEntry.name)
        assertEquals("core", aliceEntry.parentHandle)

        val bobEntry = finalState.identityRegistry["core.bob"]
        assertNotNull(bobEntry, "Bob should be migrated to identityRegistry as 'core.bob'.")
    }

    // ================================================================
    // Confirmation dialog flow
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `confirmation dialog flow delivers targeted response on CONFIRM`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        val originatorFeatureName = "FileSystemFeature"
        val testRequestId = "test-request-123"

        // --- ACT 1: Another feature requests a confirmation ---
        val showDialogAction = Action(
            name = ActionNames.CORE_SHOW_CONFIRMATION_DIALOG,
            payload = buildJsonObject {
                put("title", "Confirm Deletion")
                put("text", "Are you sure?")
                put("confirmButtonText", "Delete")
                put("requestId", testRequestId)
            }
        )
        harness.store.dispatch(originatorFeatureName, showDialogAction)
        runCurrent()

        // --- ASSERT 1: The request is correctly stored in the state ---
        val stateAfterShow = harness.store.state.value.featureStates["core"] as CoreState
        assertNotNull(stateAfterShow.confirmationRequest, "Confirmation request should be in the state.")
        assertEquals(testRequestId, stateAfterShow.confirmationRequest?.requestId)
        assertEquals(originatorFeatureName, stateAfterShow.confirmationRequest?.originator, "Originator should be captured.")

        // --- ACT 2: The UI dispatches the confirmation action ---
        harness.store.dispatch("core.ui", Action(ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
            put("confirmed", true)
        }))
        runCurrent()

        // --- ASSERT 2: A targeted CORE_RESPONSE_CONFIRMATION action was dispatched ---
        val responseAction = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_RESPONSE_CONFIRMATION
        }
        assertNotNull(responseAction, "A targeted CORE_RESPONSE_CONFIRMATION action should have been dispatched.")
        assertEquals("core", responseAction.originator, "The response should originate from core.")
        assertEquals(originatorFeatureName, responseAction.targetRecipient, "The response should be targeted to the requesting feature.")

        val responsePayload = Json.decodeFromString<ConfirmationResponsePayload>(responseAction.payload.toString())
        assertEquals(testRequestId, responsePayload.requestId)
        assertTrue(responsePayload.confirmed, "The response should indicate confirmation.")

        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(finalState.confirmationRequest, "Confirmation request should be cleared from state.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `confirmation dialog flow delivers targeted response on DISMISS`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        val originatorFeatureName = "FileSystemFeature"
        val testRequestId = "test-request-456"

        // --- ACT 1: Show the dialog ---
        harness.store.dispatch(originatorFeatureName, Action(
            name = ActionNames.CORE_SHOW_CONFIRMATION_DIALOG,
            payload = buildJsonObject {
                put("title", "Confirm")
                put("text", "Are you sure?")
                put("confirmButtonText", "Yes")
                put("requestId", testRequestId)
            }
        ))
        runCurrent()

        // --- ACT 2: The UI dispatches the dismiss action ---
        harness.store.dispatch("core.ui", Action(ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
            put("confirmed", false)
        }))
        runCurrent()

        // --- ASSERT: A targeted CORE_RESPONSE_CONFIRMATION action was dispatched ---
        val responseAction = harness.processedActions.find {
            it.name == ActionRegistry.Names.CORE_RESPONSE_CONFIRMATION
        }
        assertNotNull(responseAction, "A targeted CORE_RESPONSE_CONFIRMATION action should have been dispatched.")
        assertEquals(originatorFeatureName, responseAction.targetRecipient, "The response should be targeted to the requesting feature.")

        val responsePayload = Json.decodeFromString<ConfirmationResponsePayload>(responseAction.payload.toString())
        assertEquals(testRequestId, responsePayload.requestId)
        assertTrue(!responsePayload.confirmed, "The response should indicate dismissal (not confirmed).")

        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(finalState.confirmationRequest, "Confirmation request should be cleared from state.")
    }

    // ================================================================
    // Phase 2 — Identity Registry as a service: inter-feature workflows
    // ================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity registry acts as a shared service - multiple features register children`() = runTest {
        val platform = FakePlatformDependencies("test")
        // Pre-seed both "session" and "agent" feature identities so parent validation passes.
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", coreStateWithFeatureIdentities("core", "session", "agent"))
            .build(platform = platform)

        // ACT: Two different features register child identities
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "chat-cats"); put("name", "Chat about Cats") }
        ))
        runCurrent()

        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "gemini-coder"); put("name", "Gemini Coder") }
        ))
        runCurrent()

        // ASSERT: Both identities exist in the unified registry, each under its parent's namespace
        val appState = harness.store.state.value
        val sessionChat = appState.identityRegistry["session.chat-cats"]
        assertNotNull(sessionChat, "Session's child identity should be in the registry.")
        assertEquals("session", sessionChat.parentHandle)

        val agentGemini = appState.identityRegistry["agent.gemini-coder"]
        assertNotNull(agentGemini, "Agent's child identity should be in the registry.")
        assertEquals("agent", agentGemini.parentHandle)

        // ASSERT: Two IDENTITY_REGISTRY_UPDATED broadcasts were sent (one per registration)
        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertEquals(2, registryBroadcasts.size, "Each successful registration should produce a registry broadcast.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity registry preserves namespace isolation between features`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", coreStateWithFeatureIdentities(
                "core", "session", "agent",
                extraRegistry = mapOf(
                    "session.chat1" to Identity(uuid = "u1", localHandle = "chat1", handle = "session.chat1", name = "Chat 1", parentHandle = "session"),
                    "agent.gemini" to Identity(uuid = "u2", localHandle = "gemini", handle = "agent.gemini", name = "Gemini", parentHandle = "agent")
                )
            ))
            .build(platform = platform)

        // ACT: Agent tries to unregister a session child — should fail
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.chat1") }
        ))
        runCurrent()

        // ASSERT: Session's identity should remain intact
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNotNull(finalState.identityRegistry["session.chat1"],
            "Namespace isolation: agent cannot unregister session's child identity.")

        // ASSERT: No broadcast was sent (operation was rejected)
        val registryBroadcasts = harness.processedActions.filter {
            it.name == ActionRegistry.Names.CORE_IDENTITY_REGISTRY_UPDATED
        }
        assertTrue(registryBroadcasts.isEmpty(),
            "No IDENTITY_REGISTRY_UPDATED should be broadcast when an operation is rejected.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `register then unregister roundtrip works correctly`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", coreStateWithFeatureIdentities("core", "session"))
            .build(platform = platform)

        // ACT 1: Register
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "temp-chat"); put("name", "Temporary Chat") }
        ))
        runCurrent()

        val midState = harness.store.state.value
        assertNotNull(midState.identityRegistry["session.temp-chat"], "Identity should exist after registration.")

        // ACT 2: Unregister
        harness.store.dispatch("session", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "session.temp-chat") }
        ))
        runCurrent()

        val finalState = harness.store.state.value
        assertNull(finalState.identityRegistry["session.temp-chat"], "Identity should be removed after unregistration.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hierarchical registration and cascade unregistration through three levels`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", coreStateWithFeatureIdentities("core", "agent"))
            .build(platform = platform)

        // ACT: Build a three-level tree:
        //   agent (feature, pre-seeded)
        //   └── agent.gemini-coder (registered by agent)
        //       └── agent.gemini-coder.sub-task (registered by agent.gemini-coder)

        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "gemini-coder"); put("name", "Gemini Coder") }
        ))
        runCurrent()

        harness.store.dispatch("agent.gemini-coder", Action(
            ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            buildJsonObject { put("localHandle", "sub-task"); put("name", "Sub-Task Worker") }
        ))
        runCurrent()

        // Verify all three levels exist
        val midState = harness.store.state.value
        assertNotNull(midState.identityRegistry["agent"])
        assertNotNull(midState.identityRegistry["agent.gemini-coder"])
        assertNotNull(midState.identityRegistry["agent.gemini-coder.sub-task"])

        // ACT: Cascade unregister from mid-level
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            buildJsonObject { put("handle", "agent.gemini-coder") }
        ))
        runCurrent()

        // ASSERT: Mid-level and all descendants removed; root preserved
        val finalState = harness.store.state.value
        assertNotNull(finalState.identityRegistry["agent"], "Root feature identity should remain.")
        assertNull(finalState.identityRegistry["agent.gemini-coder"], "Mid-level identity should be cascade-removed.")
        assertNull(finalState.identityRegistry["agent.gemini-coder.sub-task"], "Leaf-level identity should be cascade-removed.")
    }

    // Helper class for decoding the private response payload in tests
    @kotlinx.serialization.Serializable
    private data class ConfirmationResponsePayload(val requestId: String, val confirmed: Boolean)
}