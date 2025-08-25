package app.auf.core

import app.auf.fakes.FakeAufTextParser
import app.auf.fakes.FakePlatformDependencies
import app.auf.model.CompilerSettings
import app.auf.model.CreateFile
import app.auf.model.SettingValue
import app.auf.model.ToolDefinition
import app.auf.util.JsonProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReducerTest {

    private lateinit var fakeParser: FakeAufTextParser

    @BeforeTest
    fun initializeFactory() {
        fakeParser = FakeAufTextParser(JsonProvider.appJson, emptyList<ToolDefinition>())
        ChatMessage.Factory.initialize(FakePlatformDependencies(), fakeParser)
    }

    private val initialState = AppState()

    @Test
    fun `UpdateSetting should correctly update a boolean compiler setting`() {
        // ARRANGE
        val initialState = AppState(compilerSettings = CompilerSettings(removeWhitespace = true, cleanHeaders = true, minifyJson = false))
        val settingToUpdate = SettingValue(key = "compiler.minifyJson", value = true)
        val action = AppAction.UpdateSetting(settingToUpdate)

        // ACT
        val newState = appReducer(initialState, action)

        // ASSERT
        assertTrue(newState.compilerSettings.removeWhitespace, "removeWhitespace should remain true")
        assertTrue(newState.compilerSettings.cleanHeaders, "cleanHeaders should remain true")
        assertTrue(newState.compilerSettings.minifyJson, "minifyJson should be updated to true")
    }

    @Test
    fun `UpdateSetting should not change state for an unknown key`() {
        // ARRANGE
        val initialState = AppState(compilerSettings = CompilerSettings(removeWhitespace = true, cleanHeaders = true, minifyJson = false))
        val settingToUpdate = SettingValue(key = "unknown.setting", value = true)
        val action = AppAction.UpdateSetting(settingToUpdate)

        // ACT
        val newState = appReducer(initialState, action)

        // ASSERT
        assertEquals(initialState.compilerSettings, newState.compilerSettings, "Compiler settings should not change for an unknown key")
    }

    @Test
    fun `UpdateActionStatus should update the status in the correct message and block`() {
        // ARRANGE
        var state = initialState
        val actionMessageRawContent = """
            Here is a plan.
[AUF_ACTION_MANIFEST]
[]
[/AUF_ACTION_MANIFEST]
""".trimIndent()
        val userMessageRawContent = "Do it."

        fakeParser.nextParseResult = listOf(
            TextBlock("Here is a plan."),
            ActionBlock(actions = listOf(CreateFile("test.txt", "Hello", "Create")), status = ActionStatus.PENDING)
        )
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))

        fakeParser.nextParseResult = listOf(TextBlock(userMessageRawContent))
        state = appReducer(state, AppAction.AddUserMessage(userMessageRawContent))

        val messageToUpdate = state.chatHistory.first { it.author == Author.AI }
        val otherMessage = state.chatHistory.first { it.author == Author.USER }

        // ACT
        val action = AppAction.UpdateActionStatus(messageToUpdate.timestamp, ActionStatus.EXECUTED)
        val newState = appReducer(state, action)

        // ASSERT
        val updatedMessage = newState.chatHistory.find { it.id == messageToUpdate.id }
        val actionBlock = updatedMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.EXECUTED, actionBlock?.status, "ActionBlock status should be EXECUTED")

        val unchangedMessage = newState.chatHistory.find { it.id == otherMessage.id }
        assertEquals(otherMessage.rawContent, unchangedMessage?.rawContent)
    }

    @Test
    fun `UpdateActionStatus should do nothing if timestamp does not match`() {
        // ARRANGE
        var state = initialState
        val actionMessageRawContent = """
[AUF_ACTION_MANIFEST]
[]
[/AUF_ACTION_MANIFEST]
""".trimIndent()
        fakeParser.nextParseResult = listOf(
            ActionBlock(actions = listOf(CreateFile("test.txt", "Hello", "Create")), status = ActionStatus.PENDING)
        )

        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = actionMessageRawContent)))
        val messageToTest = state.chatHistory.first { it.author == Author.AI }

        // ACT
        val action = AppAction.UpdateActionStatus(99999L, ActionStatus.EXECUTED)
        val newState = appReducer(state, action)

        // ASSERT
        val originalMessage = newState.chatHistory.find { it.id == messageToTest.id }
        val actionBlock = originalMessage?.contentBlocks?.filterIsInstance<ActionBlock>()?.first()
        assertEquals(ActionStatus.PENDING, actionBlock?.status, "ActionBlock status should not have changed")
        assertNotEquals(ActionStatus.EXECUTED, actionBlock?.status)
    }

    @Test
    fun `DeleteMessage should remove the correct message by its unique ID`() {
        // ARRANGE
        var state = initialState
        fakeParser.nextParseResult = listOf(TextBlock("Message 1"))
        state = appReducer(state, AppAction.AddUserMessage("Message 1"))

        fakeParser.nextParseResult = listOf(
            ActionBlock(actions = listOf(CreateFile("ai.txt", "AI content", "AI file")), status = ActionStatus.PENDING)
        )
        state = appReducer(state, AppAction.SendMessageSuccess(GatewayResponse(rawContent = "Message 2")))

        fakeParser.nextParseResult = listOf(TextBlock("Message 3"))
        state = appReducer(state, AppAction.AddUserMessage("Message 3"))


        val messageToDelete = state.chatHistory[1]
        val idToDelete = messageToDelete.id
        assertEquals(3, state.chatHistory.size)

        // ACT
        val action = AppAction.DeleteMessage(idToDelete)
        val newState = appReducer(state, action)

        // ASSERT
        assertEquals(2, newState.chatHistory.size, "The chat history should have one less message.")
        val messageStillExists = newState.chatHistory.any { it.id == idToDelete }
        assertEquals(false, messageStillExists, "The message with the specified ID should be gone.")
        assertEquals("Message 1", newState.chatHistory[0].rawContent)
        assertEquals("Message 3", newState.chatHistory[1].rawContent)
    }
}