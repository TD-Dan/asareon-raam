package app.auf.core

import app.auf.fakes.FakeAufTextParser // Import the new Fake
import app.auf.fakes.FakePlatformDependencies
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReducerTest {

    @BeforeTest
    fun initializeFactory() {
        // MODIFICATION: Initialize factory with parser
        ChatMessage.Factory.initialize(FakePlatformDependencies(), FakeAufTextParser())
    }

    private val initialState = AppState()

    @Test
    fun `UpdateActionStatus should update the status in the correct message and block`() {
        // ARRANGE: Use the reducer to create messages with proper, unique IDs.
        var state = initialState
        val actionMessageRawContent = """
            Here is a plan.
            [AUF_ACTION_MANIFEST]
            []
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        val userMessageRawContent = "Do it."

        // Dispatch actions to add messages, letting the reducer handle ID and timestamp generation.
        // MODIFICATION: Pass rawContent to createAi
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))
        // MODIFICATION: Pass rawContent to AddUserMessage
        state = appReducer(state, AppAction.AddUserMessage(userMessageRawContent))

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
        // MODIFICATION: Assert on rawContent for consistency
        assertEquals(otherMessage.rawContent, unchangedMessage?.rawContent)
    }

    @Test
    fun `UpdateActionStatus should do nothing if timestamp does not match`() {
        // ARRANGE: Create the initial message using the reducer.
        var state = initialState
        val actionMessageRawContent = """
            [AUF_ACTION_MANIFEST]
            []
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        // MODIFICATION: Pass rawContent to createAi
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))
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
        // MODIFICATION: Pass rawContent to AddUserMessage
        state = appReducer(state, AppAction.AddUserMessage("Message 1"))
        // MODIFICATION: Pass rawContent to SendMessageSuccess
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = "Message 2")))
        state = appReducer(state, AppAction.AddUserMessage("Message 3"))

        // Get the unique ID of the message to delete.
        val messageToDelete = state.chatHistory[1] // The AI's message
        val idToDelete = messageToDelete.id
        assertEquals(3, state.chatHistory.size)

        // ACT: Dispatch the delete action using the unique ID.
        val action = AppAction.DeleteMessage(idToDelete)
        val newState = appReducer(state, action)

        // ASSERT: The state should be updated correctly.
        assertEquals(2, newState.chatHistory.size, "The chat history should have one less message.")
        val messageStillExists = newState.chatHistory.any { it.id == idToDelete }
        assertEquals(false, messageStillExists, "The message with the specified ID should be gone.")
        // MODIFICATION: Assert on rawContent
        assertEquals("Message 1", newState.chatHistory[0].rawContent)
        assertEquals("Message 3", newState.chatHistory[1].rawContent)
    }
}