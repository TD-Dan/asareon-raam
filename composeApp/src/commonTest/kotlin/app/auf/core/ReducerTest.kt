package app.auf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReducerTest {

    private val initialState = AppState()

    @Test
    fun `UpdateActionStatus should update the status in the correct message and block`() {
        // ARRANGE: Use the reducer to create messages with proper, unique IDs.
        var state = initialState
        val actionMessageContent = listOf(
            TextBlock("Here is a plan."),
            ActionBlock(actions = emptyList(), status = ActionStatus.PENDING)
        )
        // Dispatch actions to add messages, letting the reducer handle ID generation.
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(contentBlocks = actionMessageContent), 12345L))
        state = appReducer(state, AppAction.AddUserMessage(listOf(TextBlock("Do it.")), 67890L))

        // Get the timestamp of the message we want to modify.
        val messageToUpdate = state.chatHistory.first()
        val otherMessage = state.chatHistory.last()

        // ACT: Dispatch the action to update the status.
        val action = AppAction.UpdateActionStatus(messageToUpdate.timestamp, ActionStatus.EXECUTED)
        val newState = appReducer(state, action)

        // ASSERT: Verify the state was updated correctly.
        val updatedMessage = newState.chatHistory.find { it.id == messageToUpdate.id }
        val actionBlock = updatedMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.EXECUTED, actionBlock?.status, "ActionBlock status should be EXECUTED")

        // AND other messages should remain unchanged.
        val unchangedMessage = newState.chatHistory.find { it.id == otherMessage.id }
        assertEquals(otherMessage.contentBlocks, unchangedMessage?.contentBlocks)
    }

    @Test
    fun `UpdateActionStatus should do nothing if timestamp does not match`() {
        // ARRANGE: Create the initial message using the reducer.
        var state = initialState
        val actionMessageContent = listOf(ActionBlock(actions = emptyList(), status = ActionStatus.PENDING))
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(contentBlocks = actionMessageContent), 12345L))
        val messageToTest = state.chatHistory.first()

        // ACT: Dispatch an action with a non-matching timestamp.
        val action = AppAction.UpdateActionStatus(99999L, ActionStatus.EXECUTED)
        val newState = appReducer(state, action)

        // ASSERT: The state should be unchanged.
        val originalMessage = newState.chatHistory.find { it.id == messageToTest.id }
        val actionBlock = originalMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.PENDING, actionBlock?.status, "ActionBlock status should not have changed")
        assertNotEquals(ActionStatus.EXECUTED, actionBlock?.status)
    }
}