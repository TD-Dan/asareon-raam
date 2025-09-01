package app.auf.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.ChatMessage
import app.auf.core.StateManager
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSessionManager
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.PromptCompiler
import app.auf.service.SettingsManager
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var stateManager: StateManager
    private lateinit var fakeStore: FakeStore

    @Before
    fun setup() {
        val initialState = AppState()
        val fakePlatform = FakePlatformDependencies()
        val fakeSessionManager = FakeSessionManager(fakePlatform)
        val testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        fakeStore = FakeStore(initialState, testCoroutineScope, fakeSessionManager)
        val realParser = AufTextParser()
        ChatMessage.Factory.initialize(fakePlatform, realParser)
        val fakeGatewayService = FakeGatewayService(testCoroutineScope)
        val promptCompiler = PromptCompiler(JsonProvider.appJson)
        val settingsManager = SettingsManager(fakePlatform, JsonProvider.appJson)
        val chatService = FakeChatService(fakeStore, fakeGatewayService, fakePlatform, realParser, promptCompiler, testCoroutineScope)

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            sourceCodeService = FakeSourceCodeService(fakePlatform),
            chatService = chatService,
            gatewayService = fakeGatewayService,
            parser = realParser,
            settingsManager = settingsManager,
            sessionManager = fakeSessionManager,
            platform = fakePlatform,
            coroutineScope = testCoroutineScope
        )
    }

    @Test
    fun `MessageCard shows compiled content toggle when compiledContent is different`() {
        // ARRANGE
        val raw = "```json\n{\n  \"key\": \"value\"\n}\n```"
        val compiled = """{"key":"value"}"""
        val message = ChatMessage.Factory.createSystem("test.json", raw).copy(compiledContent = compiled)

        // ACT
        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager, onToggleCollapsed = {})
        }

        // ASSERT
        composeTestRule.onNodeWithContentDescription("View Compiled Content").assertExists()
    }

    @Test
    fun `clicking compiled toggle switches between rendered and compiled views`() {
        // ARRANGE
        val raw = "```json\n{\n  \"key\": \"value\"\n}\n```"
        val compiled = """{"key":"value"}"""
        val message = ChatMessage.Factory.createSystem("test.json", raw).copy(compiledContent = compiled)

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager, onToggleCollapsed = {})
        }

        // ACT 1: Initially, raw (parsed) content is shown.
        composeTestRule.onNodeWithText("json").assertExists() // Checks for the CodeBlock language header
        composeTestRule.onNodeWithText("COMPILED CONTENT").assertDoesNotExist()

        // ACT 2: Click the toggle
        composeTestRule.onNodeWithContentDescription("View Compiled Content").performClick()

        // ASSERT 2: Compiled content is shown, raw is hidden
        composeTestRule.onNodeWithText("json").assertDoesNotExist()
        composeTestRule.onNodeWithText(compiled).assertExists()
        composeTestRule.onNodeWithText("COMPILED CONTENT").assertExists()

        // ACT 3: Click the toggle again
        composeTestRule.onNodeWithContentDescription("View Compiled Content").performClick()

        // ASSERT 3: View reverts to raw (parsed) content
        composeTestRule.onNodeWithText("json").assertExists()
        composeTestRule.onNodeWithText(compiled).assertDoesNotExist()
    }

    @Test
    fun `delete menu item dispatches DeleteMessage action`() {
        // ARRANGE
        fakeStore.dispatch(AppAction.AddUserMessage("Delete me"))
        val messageInState = fakeStore.state.value.chatHistory.first()

        composeTestRule.setContent {
            MessageCard(message = messageInState, stateManager = stateManager, onToggleCollapsed = {})
        }

        // ACT
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        // ASSERT
        val dispatchedAction = fakeStore.dispatchedActions.last()
        assertIs<AppAction.DeleteMessage>(dispatchedAction)
        assertEquals(messageInState.id, dispatchedAction.id)
    }
}