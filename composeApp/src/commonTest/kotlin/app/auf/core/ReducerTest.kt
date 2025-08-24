package app.auf.core

import androidx.compose.foundation.layout.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReducerTest {

    private val initialState = AppState()

    @Test
    fun `UpdateActionStatus should update the status in the correct message and block`() {
        // GIVEN an initial state with a chat message containing a PENDING ActionBlock
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(
                TextBlock("Here is a plan."),
                ActionBlock(actions = emptyList(), status = ActionStatus.PENDING)
            )
        )
        val otherMessage = ChatMessage(author = Author.USER, timestamp = 67890L, contentBlocks = listOf(TextBlock("Do it.")))
        val stateWithAction = initialState.copy(chatHistory = listOf(actionMessage, otherMessage))

        // WHEN the UpdateActionStatus action is dispatched for the correct timestamp
        val action = AppAction.UpdateActionStatus(12345L, ActionStatus.EXECUTED)
        val newState = appReducer(stateWithAction, action)

        // THEN the corresponding ActionBlock should be marked as EXECUTED
        val updatedMessage = newState.chatHistory.find { it.timestamp == 12345L }
        val actionBlock = updatedMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.EXECUTED, actionBlock?.status, "ActionBlock status should be EXECUTED")

        // AND other messages should remain unchanged
        assertEquals(2, newState.chatHistory.size)
        val unchangedMessage = newState.chatHistory.find { it.timestamp == 67890L }
        assertEquals(otherMessage, unchangedMessage)
    }

    @Test
    fun `UpdateActionStatus should do nothing if timestamp does not match`() {
        // GIVEN a state with a PENDING ActionBlock
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = emptyList(), status = ActionStatus.PENDING))
        )
        val stateWithAction = initialState.copy(chatHistory = listOf(actionMessage))

        // WHEN the action is dispatched with a non-matching timestamp
        val action = AppAction.UpdateActionStatus(99999L, ActionStatus.EXECUTED)
        val newState = appReducer(stateWithAction, action)

        // THEN the ActionBlock should remain PENDING
        val originalMessage = newState.chatHistory.find { it.timestamp == 12345L }
        val actionBlock = originalMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.PENDING, actionBlock?.status, "ActionBlock status should not have changed")
        assertNotEquals(ActionStatus.EXECUTED, actionBlock?.status)
    }
}