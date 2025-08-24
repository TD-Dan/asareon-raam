package app.auf.core

import app.auf.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReducerTest {

    @BeforeTest
    fun initializeFactory() {
        ChatMessage.Factory.initialize(FakePlatformDependencies())
    }

    private val initialState = AppState()

    @Test
    fun `UpdateActionStatus should update the status in the correct message and block`() {
        // ARRANGE: Use the reducer to create messages with proper, unique IDs.
        var state = initialState
        val actionMessageContent = listOf(
            TextBlock("Here is a plan."),
            ActionBlock(actions = emptyList(), status = ActionStatus.PENDING)
        )
        // Dispatch actions to add messages, letting the reducer handle ID and timestamp generation.
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(contentBlocks = actionMessageContent)))
        state = appReducer(state, AppAction.AddUserMessage(listOf(TextBlock("Do it."))))

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
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(contentBlocks = actionMessageContent)))
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

    @Test
    fun `DeleteMessage should remove the correct message by its unique ID`() {
        // ARRANGE: Create a series of messages using the reducer.
        var state = initialState
        state = appReducer(state, AppAction.AddUserMessage(listOf(TextBlock("Message 1"))))
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(contentBlocks = listOf(TextBlock("Message 2")))))
        state = appReducer(state, AppAction.AddUserMessage(listOf(TextBlock("Message 3"))))

        // Get the unique ID of the message to delete.
        val messageToDelete = state.chatHistory[1] // The AI's message
        val idToDelete = messageToDelete.id
        assertEquals(3, state.chatHistory.size)

        // ACT: Dispatch the delete action using the unique ID.
        val action = AppAction.DeleteMessage(idToDelete)
        val newState = appReducer(state, action)

        // ASSERT: The state should be updated correctly.
        assertEquals(2, newState.chatHistory.size, "The chat history should have one less message.")
        // --- MODIFIED: Assert that no message with the deleted ID exists. ---
        val messageStillExists = newState.chatHistory.any { it.id == idToDelete }
        assertEquals(false, messageStillExists, "The message with the specified ID should be gone.")
        assertEquals("Message 1", (newState.chatHistory[0].contentBlocks.first() as TextBlock).text)
        assertEquals("Message 3", (newState.chatHistory[1].contentBlocks.first() as TextBlock).text)
    }
}