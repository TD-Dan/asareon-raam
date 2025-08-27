package app.auf.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.ChatMessage
import app.auf.core.StateManager
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportViewModel
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSessionManager
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.model.Parameter
import app.auf.model.ToolDefinition
import app.auf.service.AufTextParser
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
    private lateinit var realParser: AufTextParser
    private val testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private val toolRegistry = listOf(
        ToolDefinition("Atomic Change Manifest", "ACTION_MANIFEST", "", emptyList(), true, ""),
        ToolDefinition("File Content View", "FILE_VIEW", "", listOf(Parameter("path", "String", true)), true, ""),
        ToolDefinition("App Request", "APP_REQUEST", "", emptyList(), true, ""),
        ToolDefinition("State Anchor", "STATE_ANCHOR", "", emptyList(), true, "")
    )

    @Before
    fun setup() {
        val initialState = AppState()
        val fakePlatform = FakePlatformDependencies()
        val fakeSessionManager = FakeSessionManager(fakePlatform)
        fakeStore = FakeStore(initialState, testCoroutineScope, fakeSessionManager)
        realParser = AufTextParser(JsonProvider.appJson, toolRegistry)
        ChatMessage.Factory.initialize(fakePlatform, realParser)
        val fakeGatewayService = FakeGatewayService(testCoroutineScope)
        val promptCompiler = PromptCompiler(JsonProvider.appJson)
        val settingsManager = SettingsManager(fakePlatform, JsonProvider.appJson)

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            graphService = FakeGraphService(),
            sourceCodeService = FakeSourceCodeService(fakePlatform),
            chatService = FakeChatService(fakeStore, fakeGatewayService, fakePlatform, realParser, toolRegistry, promptCompiler, testCoroutineScope),
            gatewayService = fakeGatewayService,
            actionExecutor = FakeActionExecutor(fakePlatform, JsonProvider.appJson),
            parser = realParser,
            settingsManager = settingsManager,
            sessionManager = fakeSessionManager,
            importExportViewModel = FakeImportExportViewModel(),
            platform = fakePlatform,
            coroutineScope = testCoroutineScope
        )
    }

    @Test
    fun `MessageCard shows compiled content toggle when compiledContent is different`() {
        val raw = "{\n  \"key\": \"value\"\n}"
        val compiled = """{"key":"value"}"""
        val message = ChatMessage.Factory.createSystem("test.json", raw).copy(compiledContent = compiled)

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithContentDescription("View Compiled Content").assertExists()
    }

    @Test
    fun `MessageCard does NOT show compiled content toggle when content is same or null`() {
        val raw = "Just text"
        val messageSame = ChatMessage.Factory.createUser(raw).copy(compiledContent = raw)
        val messageNull = ChatMessage.Factory.createUser(raw).copy(compiledContent = null)

        composeTestRule.setContent {
            MessageCard(message = messageSame, stateManager = stateManager)
        }
        composeTestRule.onNodeWithContentDescription("View Compiled Content").assertDoesNotExist()

        composeTestRule.setContent {
            MessageCard(message = messageNull, stateManager = stateManager)
        }
        composeTestRule.onNodeWithContentDescription("View Compiled Content").assertDoesNotExist()
    }

    @Test
    fun `clicking compiled toggle switches between rendered and compiled views`() {
        val raw = "{\n  \"key\": \"value\"\n}"
        val compiled = """{"key":"value"}"""
        val message = ChatMessage.Factory.createSystem("test.json", raw).copy(compiledContent = compiled)

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        // --- FIX IS HERE ---
        // The test failed because a System message card is collapsed by default.
        // We must first click the card's header to expand it before we can
        // assert on the content within it.
        composeTestRule.onNodeWithText("test.json").performClick()
        // --- END FIX ---

        // ACT 1: Initially, raw (parsed) content is shown. Assert for key substrings.
        composeTestRule.onNodeWithText(raw, substring = true).assertExists()
        composeTestRule.onNodeWithText(compiled).assertDoesNotExist()

        // ACT 2: Click the toggle
        composeTestRule.onNodeWithContentDescription("View Compiled Content").performClick()

        // ASSERT 2: Compiled content is shown, raw is hidden
        composeTestRule.onNodeWithText(raw, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(compiled).assertExists()
        composeTestRule.onNodeWithText("COMPILED CONTENT").assertExists()

        // ACT 3: Click the toggle again
        composeTestRule.onNodeWithContentDescription("View Compiled Content").performClick()

        // ASSERT 3: View reverts to raw (parsed) content
        composeTestRule.onNodeWithText(raw, substring = true).assertExists()
        composeTestRule.onNodeWithText(compiled).assertDoesNotExist()
    }

    @Test
    fun `RenderTextBlock should display the correct text`() {
        val testMessage = "This is a test message."
        val message = ChatMessage.Factory.createUser(rawContent = testMessage)
        composeTestRule.setContent { MessageCard(message = message, stateManager = stateManager) }
        composeTestRule.onNodeWithText(testMessage).assertExists()
    }

    @Test
    fun `deleteMenuItemClick should dispatch DeleteAction`() {
        val rawMessageContent = "Delete me"
        fakeStore.dispatch(AppAction.AddUserMessage(rawMessageContent))
        val messageInState = fakeStore.state.value.chatHistory.first()

        composeTestRule.setContent {
            MessageCard(message = messageInState, stateManager = stateManager)
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        val dispatchedAction = fakeStore.dispatchedActions.last()
        assertIs<AppAction.DeleteMessage>(dispatchedAction)
        assertEquals(messageInState.id, dispatchedAction.id)
    }
}