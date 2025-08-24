package app.auf.core

import app.auf.fakes.FakeAufTextParser
import app.auf.fakes.FakePlatformDependencies
import app.auf.model.CreateFile
import app.auf.model.ToolDefinition
import app.auf.util.JsonProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReducerTest {

    private lateinit var fakeParser: FakeAufTextParser // Make FakeAufTextParser accessible

    @BeforeTest
    fun initializeFactory() {
        // Initialize the fake parser here
        fakeParser = FakeAufTextParser(JsonProvider.appJson, emptyList<ToolDefinition>())
        ChatMessage.Factory.initialize(FakePlatformDependencies(), fakeParser)
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

        // Configure fakeParser to return an ActionBlock for the AI response
        fakeParser.nextParseResult = listOf(
            TextBlock("Here is a plan."),
            ActionBlock(actions = listOf(CreateFile("test.txt", "Hello", "Create")), status = ActionStatus.PENDING)
        )

        // Dispatch actions to add messages, letting the reducer handle ID and timestamp generation.
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))

        // Reset fakeParser for the next message (user message is just text)
        fakeParser.nextParseResult = listOf(TextBlock(userMessageRawContent))
        state = appReducer(state, AppAction.AddUserMessage(userMessageRawContent))

        // Get the timestamp of the message we want to modify.
        // We expect the first message in chatHistory to be the AI message containing the ActionBlock.
        val messageToUpdate = state.chatHistory.first { it.author == Author.AI }
        val otherMessage = state.chatHistory.first { it.author == Author.USER }

        // ACT: Dispatch the action to update the status.
        val action = AppAction.UpdateActionStatus(messageToUpdate.timestamp, ActionStatus.EXECUTED)
        val newState = appReducer(state, action)

        // ASSERT: Verify the state was updated correctly.
        val updatedMessage = newState.chatHistory.find { it.id == messageToUpdate.id }
        val actionBlock = updatedMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.EXECUTED, actionBlock?.status, "ActionBlock status should be EXECUTED")

        // AND other messages should remain unchanged.
        val unchangedMessage = newState.chatHistory.find { it.id == otherMessage.id }
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
        // Configure fakeParser to return an ActionBlock for the AI response
        fakeParser.nextParseResult = listOf(
            ActionBlock(actions = listOf(CreateFile("test.txt", "Hello", "Create")), status = ActionStatus.PENDING)
        )

        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))
        val messageToTest = state.chatHistory.first { it.author == Author.AI } // Get the AI message

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
        // Configure parser for the first message (User)
        fakeParser.nextParseResult = listOf(TextBlock("Message 1"))
        state = appReducer(state, AppAction.AddUserMessage("Message 1"))

        // Configure parser for the second message (AI with ActionBlock)
        fakeParser.nextParseResult = listOf(
            ActionBlock(actions = listOf(CreateFile("ai.txt", "AI content", "AI file")), status = ActionStatus.PENDING)
        )
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = "Message 2")))

        // Configure parser for the third message (User)
        fakeParser.nextParseResult = listOf(TextBlock("Message 3"))
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
        assertEquals("Message 1", newState.chatHistory[0].rawContent)
        assertEquals("Message 3", newState.chatHistory[1].rawContent)
    }
}