package app.auf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReducerTest {

    private val initialState = AppState()

    @Test
    fun `ResolveActionInMessage should update the isResolved flag in the correct message and block`() {
        // GIVEN an initial state with a chat message containing an unresolved ActionBlock
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(
                TextBlock("Here is a plan."),
                ActionBlock(actions = emptyList(), isResolved = false)
            )
        )
        val otherMessage = ChatMessage(author = Author.USER, timestamp = 67890L, contentBlocks = listOf(TextBlock("Do it.")))
        val stateWithAction = initialState.copy(chatHistory = listOf(actionMessage, otherMessage))

        // WHEN the ResolveActionInMessage action is dispatched for the correct timestamp
        val action = AppAction.ResolveActionInMessage(12345L)
        val newState = appReducer(stateWithAction, action)

        // THEN the corresponding ActionBlock should be marked as resolved
        val updatedMessage = newState.chatHistory.find { it.timestamp == 12345L }
        val actionBlock = updatedMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertTrue(actionBlock?.isResolved ?: false, "ActionBlock should be resolved")

        // AND other messages should remain unchanged
        assertEquals(2, newState.chatHistory.size)
        val unchangedMessage = newState.chatHistory.find { it.timestamp == 67890L }
        assertEquals(otherMessage, unchangedMessage)
    }

    @Test
    fun `ResolveActionInMessage should do nothing if timestamp does not match`() {
        // GIVEN a state with an unresolved ActionBlock
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = emptyList(), isResolved = false))
        )
        val stateWithAction = initialState.copy(chatHistory = listOf(actionMessage))

        // WHEN the action is dispatched with a non-matching timestamp
        val action = AppAction.ResolveActionInMessage(99999L)
        val newState = appReducer(stateWithAction, action)

        // THEN the ActionBlock should remain unresolved
        val originalMessage = newState.chatHistory.find { it.timestamp == 12345L }
        val actionBlock = originalMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertFalse(actionBlock?.isResolved ?: true, "ActionBlock should not be resolved")
    }
}