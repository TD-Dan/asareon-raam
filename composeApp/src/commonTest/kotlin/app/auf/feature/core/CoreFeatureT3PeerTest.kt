package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
 * multi-feature workflows. These tests verify the contracts (like PrivateDataEnvelope)
 * and service patterns (like the global dialog) that enable inter-feature communication.
 */
class CoreFeatureT3PeerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onPrivateData correctly loads identities from filesystem response`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        // ARRANGE: Manually construct the JSON string that FileSystemFeature would provide.
        val fileContent = buildJsonObject {
            putJsonArray("identities") {
                add(buildJsonObject {
                    put("id", "id-1")
                    put("name", "Loaded User")
                })
            }
            put("activeId", "id-1")
        }.toString()

        val envelope = PrivateDataEnvelope(
            type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "identities.json")
                put("content", fileContent)
            }
        )

        // ACT: Deliver the private data directly to the CoreFeature.
        harness.store.deliverPrivateData("FileSystemFeature", "core", envelope)
        runCurrent() // Process deferred actions

        // ASSERT 1: Verify that the correct internal action was dispatched.
        val loadedAction = harness.processedActions.find { it.name == ActionNames.CORE_INTERNAL_IDENTITIES_LOADED }
        assertNotNull(loadedAction, "The onPrivateData handler should dispatch an internal loaded action.")

        // ASSERT 2: Verify the final state is correct.
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertEquals(1, finalState.userIdentities.size)
        assertEquals("Loaded User", finalState.userIdentities.first().name)
        assertEquals("id-1", finalState.activeUserId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `confirmation dialog flow delivers private response on CONFIRM`() = runTest {
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

        // --- ASSERT 2: The correct private response is delivered and state is cleared ---
        assertEquals(1, harness.deliveredPrivateData.size, "A private data envelope should have been delivered.")
        val delivery = harness.deliveredPrivateData.first()
        assertEquals("core", delivery.originator)
        assertEquals(originatorFeatureName, delivery.recipient)
        assertEquals(ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION, delivery.envelope.type)

        val responsePayload = Json.decodeFromString<ConfirmationResponsePayload>(delivery.envelope.payload.toString())
        assertEquals(testRequestId, responsePayload.requestId)
        assertTrue(responsePayload.confirmed, "The response should indicate confirmation.")

        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(finalState.confirmationRequest, "Confirmation request should be cleared from state.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `confirmation dialog flow delivers private response on DISMISS`() = runTest {
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

        // --- ASSERT: The correct private response is delivered ---
        assertEquals(1, harness.deliveredPrivateData.size)
        val delivery = harness.deliveredPrivateData.first()
        assertEquals(ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION, delivery.envelope.type)

        val responsePayload = Json.decodeFromString<ConfirmationResponsePayload>(delivery.envelope.payload.toString())
        assertEquals(testRequestId, responsePayload.requestId)
        assertTrue(!responsePayload.confirmed, "The response should indicate dismissal (not confirmed).")

        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(finalState.confirmationRequest, "Confirmation request should be cleared from state.")
    }

    // Helper class for decoding the private response payload in tests
    @kotlinx.serialization.Serializable
    private data class ConfirmationResponsePayload(val requestId: String, val confirmed: Boolean)
}