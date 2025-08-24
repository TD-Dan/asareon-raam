package app.auf.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.ActionBlock
import app.auf.core.ActionStatus
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
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.model.Parameter
import app.auf.model.ToolDefinition
import app.auf.model.UserSettings
import app.auf.service.AufTextParser
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var realParser: AufTextParser // Use a real parser here for factory
    private val testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    // Define the same tool registry as in main.kt/ChatIntegrationTest.kt for consistent parsing
    private val toolRegistry = listOf(
        ToolDefinition(
            name = "Atomic Change Manifest",
            command = "ACTION_MANIFEST",
            description = "Propose a transactional set of changes to the Holon Knowledge Graph file system.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = """
[AUF_ACTION_MANIFEST]
[...json array of Action objects...]
[/AUF_ACTION_MANIFEST]"""
        ),
        ToolDefinition(
            name = "File Content View",
            command = "FILE_VIEW",
            description = "Display the content of a non-Holon file within the chat.",
            parameters = listOf(
                Parameter(name = "path", type = "String", isRequired = true, defaultValue = null),
                Parameter(name = "language", type = "String", isRequired = false, defaultValue = null)
            ),
            expectsPayload = true,
            usage = """
[AUF_FILE_VIEW(path="path/to/your/file.kt")]
...file content...
[/AUF_FILE_VIEW]"""
        ),
        ToolDefinition("App Request", "APP_REQUEST", "", emptyList(), true, ""),
        ToolDefinition("State Anchor", "STATE_ANCHOR", "", emptyList(), true, "")
    )

    @Before
    fun setup() {
        val initialState = AppState()
        fakeStore = FakeStore(initialState, testCoroutineScope)
        fakePlatform = FakePlatformDependencies()

        // Initialize with a real AufTextParser for correct ContentBlock generation
        realParser = AufTextParser(JsonProvider.appJson, toolRegistry)

        // Pass the real parser to the factory initializer.
        ChatMessage.Factory.initialize(fakePlatform, realParser)

        val fakeGatewayService = FakeGatewayService(testCoroutineScope)

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            graphService = FakeGraphService(),
            sourceCodeService = FakeSourceCodeService(fakePlatform),
            chatService = FakeChatService(
                fakeStore,
                fakeGatewayService,
                fakePlatform,
                realParser, // Use real parser here
                toolRegistry,
                testCoroutineScope
            ),
            gatewayService = fakeGatewayService,
            actionExecutor = FakeActionExecutor(fakePlatform, JsonProvider.appJson),
            parser = realParser, // Use real parser here
            importExportViewModel = FakeImportExportViewModel(),
            platform = fakePlatform,
            initialSettings = UserSettings(),
            coroutineScope = testCoroutineScope
        )
    }

    @Test
    fun `RenderTextBlock should display the correct text`() {
        val testMessage = "This is a test message."
        // Use the factory to create the test message from rawContent
        val message = ChatMessage.Factory.createUser(
            rawContent = testMessage
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText(testMessage).assertExists()
    }

    @Test
    fun `RenderActionBlock shows Confirm and Reject buttons when PENDING`() {
        // Raw content that the real parser will correctly convert into an ActionBlock
        val rawActionContent = """
[AUF_ACTION_MANIFEST]
[
    {
        "type": "CreateFile",
        "filePath": "a.txt",
        "content": "b",
        "summary": "c"
    }
]
[/AUF_ACTION_MANIFEST]
""".trimIndent()
        // Use the factory to create the test message from rawContent
        val message = ChatMessage.Factory.createAi(
            rawContent = rawActionContent,
            usageMetadata = null
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
        val rawActionContent = """
[AUF_ACTION_MANIFEST]
[
    {
        "type": "CreateFile",
        "filePath": "a.txt",
        "content": "b",
        "summary": "c"
    }
]
[/AUF_ACTION_MANIFEST]
""".trimIndent()
        // Create the message as PENDING, then manually set status to EXECUTED for the test
        val message = ChatMessage.Factory.createAi(
            rawContent = rawActionContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(ActionBlock(listOf<Action>(action), status = ActionStatus.EXECUTED)))


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
        val rawActionContent = """
[AUF_ACTION_MANIFEST]
[
    {
        "type": "CreateFile",
        "filePath": "a.txt",
        "content": "b",
        "summary": "c"
    }
]
[/AUF_ACTION_MANIFEST]
""".trimIndent()
        // Create the message as PENDING, then manually set status to REJECTED for the test
        val message = ChatMessage.Factory.createAi(
            rawContent = rawActionContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(ActionBlock(listOf<Action>(action), status = ActionStatus.REJECTED)))

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reject").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rejected", substring = true).assertExists()
    }

    @Test
    fun `RenderParseErrorBlock displays error details correctly`() {
        // Raw content that will cause the real parser to produce a ParseErrorBlock
        val rawContentCausingParseError = """
<!-- PARSE ERROR: A deserialization error occurred: Unexpected JSON token at offset 0: Expected start of the array '[', but had 'T' instead at path: $
JSON input: This is not valid JSON | RAW: This is not valid JSON -->
""".trimIndent()
        // MODIFIED: Assert on the exact error message that the parser will produce
        val expectedErrorMessage = "A deserialization error occurred: Unexpected JSON token at offset 0: Expected start of the array '[', but had 'T' instead at path: $"

        // Use the factory to create the message, which will now internally generate a ParseErrorBlock
        val message = ChatMessage.Factory.createSystem(
            title = "SYSTEM PARSE ERROR", // MODIFIED: Give a distinct title to avoid default collapsing
            rawContent = rawContentCausingParseError
        )

        composeTestRule.setContent {
            MessageCard(message = message, stateManager = stateManager)
        }

        // The MessageCard's internal logic for isCollapsed should now ensure it's not collapsed.
        // We can directly assert for the text existence.
        composeTestRule.onNodeWithText("PARSE ERROR: UNKNOWN", substring = true).assertExists() // MODIFIED: Check for the title as rendered
        composeTestRule.onNodeWithText(expectedErrorMessage, substring = true).assertExists()
        composeTestRule.onNodeWithText("--- Raw Content ---", substring = true).assertExists()
        composeTestRule.onNodeWithText(rawContentCausingParseError, substring = true).assertExists()
    }

    @Test
    fun `deleteMenuItemClick should dispatch DeleteAction`() {
        // ARRANGE:
        val rawMessageContent = "Delete me"
        // Dispatch the raw content, simulating the new flow.
        fakeStore.dispatch(
            AppAction.AddUserMessage(rawMessageContent)
        )
        // The message in the store will have a new ID, so we need to get it.
        val messageInState = fakeStore.state.value.chatHistory.first()


        composeTestRule.setContent {
            MessageCard(message = messageInState, stateManager = stateManager)
        }

        // ACT: Simulate the user clicks.
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        // ASSERT: Check the actions dispatched to the store.
        assertEquals(2, fakeStore.dispatchedActions.size) // Initial Add + Delete
        val dispatchedAction = fakeStore.dispatchedActions.last()
        assertIs<AppAction.DeleteMessage>(dispatchedAction)
        assertEquals(messageInState.id, dispatchedAction.id)
    }
}