package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 3 Peer Tests for CoreFeature.
 *
 * Mandate (P-TEST-001, T3): To test the feature's role as a peer in larger,
 * multi-feature workflows. These tests verify the contracts (like PrivateDataEnvelope)
 * and service patterns (like the global dialog) that enable inter-feature communication.
 */
class CoreFeatureT3PeerTest {

    @Test
    fun `onPrivateData correctly loads identities from filesystem response`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform)

        // ARRANGE: Create a fake payload that mimics a successful file read from FileSystemFeature.
        val identitiesToLoad = listOf(Identity("id-1", "Loaded User"))
        val activeId = "id-1"
        val fileContent = Json.encodeToString(
            mapOf(
                "identities" to identitiesToLoad,
                "activeId" to activeId
            )
        )
        val envelope = PrivateDataEnvelope(
            type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "identities.json")
                put("content", fileContent)
            }
        )

        // ACT: Deliver the private data directly to the CoreFeature.
        harness.store.deliverPrivateData("FileSystemFeature", "core", envelope)

        // ASSERT 1: Verify that the correct internal action was dispatched.
        val loadedAction = harness.processedActions.find { it.name == ActionNames.CORE_INTERNAL_IDENTITIES_LOADED }
        assertNotNull(loadedAction, "The onPrivateData handler should dispatch an internal loaded action.")

        // ASSERT 2: Verify the final state is correct.
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertEquals(1, finalState.userIdentities.size)
        assertEquals("Loaded User", finalState.userIdentities.first().name)
        assertEquals("id-1", finalState.activeUserId)
    }

    @Test
    fun `confirmation dialog flow correctly dispatches onConfirmAction`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(toastMessage = "Initial Toast"))
            .build(platform = platform)

        // ARRANGE: Define the action that should be triggered on confirmation.
        val onConfirmAction = Action(ActionNames.CORE_CLEAR_TOAST)

        // ACT 1: Dispatch the action to show the dialog.
        val showDialogAction = Action(
            name = ActionNames.CORE_SHOW_CONFIRMATION_DIALOG,
            payload = buildJsonObject {
                put("title", "Confirm")
                put("text", "Are you sure?")
                put("confirmButtonText", "Yes")
                putJsonObject("onConfirmAction") {
                    put("name", onConfirmAction.name)
                }
            }
        )
        harness.store.dispatch("SomeOtherFeature", showDialogAction)

        // ASSERT 1: The state should now contain the dialog request.
        val stateAfterShow = harness.store.state.value.featureStates["core"] as CoreState
        assertNotNull(stateAfterShow.confirmationRequest, "Confirmation request should be in the state.")
        assertEquals("Confirm", stateAfterShow.confirmationRequest?.title)
        assertEquals(onConfirmAction.name, stateAfterShow.confirmationRequest?.onConfirmAction?.name)

        // ARRANGE 2: Get the confirmation action from the state, simulating a UI button click.
        val retrievedConfirmAction = stateAfterShow.confirmationRequest?.onConfirmAction
        assertNotNull(retrievedConfirmAction)

        // ACT 2: Dispatch the confirmation action.
        harness.store.dispatch("core.ui.dialog", retrievedConfirmAction)

        // ASSERT 2: The nested action should have been processed.
        val stateAfterConfirm = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(stateAfterConfirm.toastMessage, "The toast message should have been cleared by the onConfirmAction.")

        // ACT 3: Dispatch the dismiss action to clean up the UI state.
        harness.store.dispatch("core.ui.dialog", Action(ActionNames.CORE_DISMISS_CONFIRMATION_DIALOG))

        // ASSERT 3: The dialog request should be gone from the state.
        val finalState = harness.store.state.value.featureStates["core"] as CoreState
        assertNull(finalState.confirmationRequest, "Confirmation request should be null after dismissal.")
    }
}