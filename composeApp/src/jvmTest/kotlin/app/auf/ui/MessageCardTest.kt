package app.auf.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.ActionBlock
import app.auf.core.ActionStatus
import app.auf.core.AppState
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ParseErrorBlock
import app.auf.core.StateManager
import app.auf.core.TextBlock
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportViewModel
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.model.ToolDefinition
import app.auf.model.UserSettings
import app.auf.service.AufTextParser
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for the MessageCard Composable and its sub-renderers.
 *
 * ---
 * ## Mandate
 * This test suite verifies the correctness of the UI rendering logic for different
 * types of `ContentBlock`s. It ensures that the UI accurately reflects the application's
 * state passed down to it.
 * ---
 */
class MessageCardTest {

    // Using @get:Rule for JUnit4 compatibility in Compose tests
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var stateManager: StateManager
    private lateinit var fakeStore: FakeStore
    private val testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setup() {
        val initialState = AppState()
        fakeStore = FakeStore(initialState, testCoroutineScope)
        val fakePlatform = FakePlatformDependencies()

        // --- MODIFIED: Initialize the factory before creating the StateManager ---
        ChatMessage.Factory.initialize(fakePlatform)

        val fakeGatewayService = FakeGatewayService(testCoroutineScope)
        val fakeParser = AufTextParser(JsonProvider.appJson, emptyList<ToolDefinition>())

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            graphService = FakeGraphService(),
            sourceCodeService = FakeSourceCodeService(fakePlatform),
            chatService = FakeChatService(
                fakeStore,
                fakeGatewayService,
                fakePlatform,
                fakeParser,
                emptyList(),
                testCoroutineScope
            ),
            gatewayService = fakeGatewayService,
            actionExecutor = FakeActionExecutor(fakePlatform, JsonProvider.appJson),
            parser = fakeParser,
            importExportViewModel = FakeImportExportViewModel(),
            platform = fakePlatform,
            initialSettings = UserSettings(),
            coroutineScope = testCoroutineScope
        )
    }

    @Test
    fun `RenderTextBlock should display the correct text`() {
        val testMessage = "This is a test message."
        // --- MODIFIED: Use the factory to create the test message ---
        val message = ChatMessage.Factory.createUser(
            contentBlocks = listOf(TextBlock(testMessage))
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText(testMessage).assertExists()
    }

    @Test
    fun `RenderActionBlock shows Confirm and Reject buttons when PENDING`() {
        val action = CreateFile("a.txt", "b", "c")
        // --- MODIFIED: Use the factory to create the test message ---
        val message = ChatMessage.Factory.createAi(
            contentBlocks = listOf(ActionBlock(listOf<Action>(action), status = ActionStatus.PENDING)),
            usageMetadata = null,
            rawContent = null
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText("Confirm").assertExists()
        composeTestRule.onNodeWithText("Reject").assertExists()
    }

    @Test
    fun `RenderActionBlock hides buttons when EXECUTED`() {
        val action = CreateFile("a.txt", "b", "c")
        // --- MODIFIED: Use the factory to create the test message ---
        val message = ChatMessage.Factory.createAi(
            contentBlocks = listOf(ActionBlock(listOf<Action>(action), status = ActionStatus.EXECUTED)),
            usageMetadata = null,
            rawContent = null
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reject").assertDoesNotExist()
        composeTestRule.onNodeWithText("Executed", substring = true).assertExists()
    }

    @Test
    fun `RenderActionBlock hides buttons when REJECTED`() {
        val action = CreateFile("a.txt", "b", "c")
        // --- MODIFIED: Use the factory to create the test message ---
        val message = ChatMessage.Factory.createAi(
            contentBlocks = listOf(ActionBlock(listOf<Action>(action), status = ActionStatus.REJECTED)),
            usageMetadata = null,
            rawContent = null
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reject").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rejected", substring = true).assertExists()
    }

    @Test
    fun `RenderParseErrorBlock displays error details correctly`() {
        val errorMessage = "Something went wrong."
        val rawContent = "{malformed json}"
        // --- MODIFIED: Use the factory to create the test message ---
        val message = ChatMessage.Factory.createSystem(
            title = "SYSTEM",
            contentBlocks = listOf(
                ParseErrorBlock(
                    originalTag = "TEST_TAG",
                    rawContent = rawContent,
                    errorMessage = errorMessage
                )
            )
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText("SYSTEM").performClick()

        composeTestRule.onNodeWithText("Parse Error: [AUF_TEST_TAG]", substring = true).assertExists()
        composeTestRule.onNodeWithText(errorMessage, substring = true).assertExists()
        composeTestRule.onNodeWithText(rawContent).assertExists()
    }
}