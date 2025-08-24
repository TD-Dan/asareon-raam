package app.auf

import app.auf.core.ActionBlock
import app.auf.core.ActionStatus
import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.AppState
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.core.HolonHeader
import app.auf.core.TextBlock
import app.auf.core.StateManager
import app.auf.core.Version
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportViewModel
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.model.CreateFile
import app.auf.model.Parameter
import app.auf.model.ToolDefinition
import app.auf.model.UserSettings
import app.auf.service.AufTextParser
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ## Mandate
 * This test suite focuses on integration-level scenarios within the chat functionality,
 * particularly on the flow of `ChatMessage` creation, storage, and retrieval,
 * with an emphasis on the 'raw content as ground truth' principle.
 * It simulates user and AI interactions to ensure the core data flow is robust and consistent.
 *
 * ## Dependencies
 * - All core AUF services and components, though often faked for isolation.
 */
class ChatIntegrationTest {

    @get:Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createComposeRule()

    private lateinit var stateManager: StateManager
    private lateinit var fakeStore: FakeStore
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var realParser: AufTextParser
    private lateinit var fakeChatService: FakeChatService // Use FakeChatService
    private lateinit var fakeGatewayService: FakeGatewayService
    private lateinit var fakeSourceCodeService: FakeSourceCodeService
    private lateinit var testCoroutineScope: CoroutineScope

    // Define the same tool registry as in main.kt for consistent parsing
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
            name = "Application Request",
            command = "APP_REQUEST",
            description = "Request the host application to perform a pre-defined, non-file-system action.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = """
[AUF_APP_REQUEST]START_DREAM_CYCLE[/AUF_APP_REQUEST]"""
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
        ToolDefinition(
            name = "State Anchor",
            command = "STATE_ANCHOR",
            description = "Create a persistent, context-immune memory waypoint within the chat history.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = """
[AUF_STATE_ANCHOR]
{"anchorId": "...", ...}
[/AUF_STATE_ANCHOR]"""
        )
    )

    @Before
    fun setup() {
        testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        // MODIFICATION: Correctly initialize availableAiPersonas as List<HolonHeader>
        val initialPersonaHeader = HolonHeader(
            id = "sage-20250726T213010Z",
            type = "AI_Persona_Root",
            name = "The Silicon Sage (v4.5 Kernel)",
            summary = "Fake persona for testing"
        )
        val initialState = AppState(
            aiPersonaId = initialPersonaHeader.id,
            availableAiPersonas = listOf(initialPersonaHeader), // MODIFIED: Correct type and content
            // MODIFICATION: Ensure activeHolons is empty for predictable system context tests
            activeHolons = emptyMap()
        )
        fakeStore = FakeStore(initialState, testCoroutineScope)
        fakePlatform = FakePlatformDependencies()
        realParser = AufTextParser(JsonProvider.appJson, toolRegistry)

        // Initialize with a real AufTextParser for correct ContentBlock generation
        ChatMessage.Factory.initialize(fakePlatform, realParser)

        fakeGatewayService = FakeGatewayService(testCoroutineScope, toolRegistry)
        fakeSourceCodeService = FakeSourceCodeService(fakePlatform) // Initialize fake source code service

        fakeChatService = FakeChatService(
            fakeStore,
            fakeGatewayService,
            fakePlatform,
            realParser,
            toolRegistry,
            testCoroutineScope
        )

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            graphService = FakeGraphService(),
            sourceCodeService = fakeSourceCodeService, // Use fake source code service
            chatService = fakeChatService,
            gatewayService = fakeGatewayService,
            actionExecutor = FakeActionExecutor(fakePlatform, JsonProvider.appJson),
            parser = realParser,
            importExportViewModel = FakeImportExportViewModel(),
            platform = fakePlatform,
            initialSettings = UserSettings(),
            coroutineScope = testCoroutineScope
        )

        // Initialize necessary platform files for system context messages
        fakePlatform.writeFileContent(
            fakePlatform.getBasePathFor(BasePath.FRAMEWORK) + fakePlatform.pathSeparator + "framework_protocol.md",
            "**META_INSTRUCTION_CONTEXTUAL_OVERRIDE: This is the framework protocol.**"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `user message rawContent is correctly stored and parsed to TextBlock`() = runTest {
        val rawUserMessage = "Hello, Sage!"
        stateManager.sendMessage(rawUserMessage) // MODIFIED: Use sendMessage
        advanceUntilIdle() // Process coroutines

        val lastMessage = fakeStore.state.value.chatHistory.last()
        assertEquals(Author.USER, lastMessage.author)
        assertEquals(rawUserMessage, lastMessage.rawContent)
        assertIs<TextBlock>(lastMessage.contentBlocks.first())
        assertEquals(rawUserMessage, (lastMessage.contentBlocks.first() as TextBlock).text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SendMessageSuccess action correctly stores rawContent and derives ActionBlock for AI response`() = runTest {
        val rawAiResponse = """
[AUF_ACTION_MANIFEST]
[
    {
        "type": "CreateFile",
        "filePath": "test.txt",
        "content": "Hello World",
        "summary": "Create a new test file"
    }
]
[/AUF_ACTION_MANIFEST]
""".trimIndent()

        // Configure FakeGatewayService to return an AI message with the action manifest
        fakeGatewayService.nextResponse = GatewayResponse(
            rawContent = rawAiResponse,
            usageMetadata = null
        )

        stateManager.sendMessage("Please create a file.") // MODIFIED: Use sendMessage
        advanceUntilIdle() // Process coroutines

        val lastMessage = fakeStore.state.value.chatHistory.last()

        // MODIFICATION: Assert that the author is AI (inferred from reducer/factory)
        assertEquals(Author.AI, lastMessage.author)
        assertEquals(rawAiResponse, lastMessage.rawContent)
        assertIs<ActionBlock>(lastMessage.contentBlocks.first())
        val actionBlock = lastMessage.contentBlocks.first() as ActionBlock
        assertEquals(1, actionBlock.actions.size)
        assertIs<CreateFile>(actionBlock.actions.first())
        assertEquals("Create a new test file", actionBlock.summary)
        assertEquals(ActionStatus.PENDING, actionBlock.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SendMessageFailure action correctly stores error message and sets processing to false`() = runTest {
        val errorMessage = "API call failed."

        fakeGatewayService.nextResponse = GatewayResponse(errorMessage = errorMessage)

        stateManager.sendMessage("Tell me something.") // MODIFIED: Use sendMessage
        advanceUntilIdle() // Process coroutines

        val state = fakeStore.state.value
        assertFalse(state.isProcessing)
        val lastMessage = state.chatHistory.last()
        assertEquals(Author.SYSTEM, lastMessage.author) // Error messages are system messages
        assertTrue(lastMessage.title?.contains("Gateway Error") == true)
        assertIs<TextBlock>(lastMessage.contentBlocks.first())
        assertTrue((lastMessage.contentBlocks.first() as TextBlock).text.contains(errorMessage))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `AI message with AppRequestBlock triggers follow-up sendMessage`() = runTest {
        val rawAiResponseWithAppRequest = """
Hello, Daniel.
[AUF_APP_REQUEST]START_DREAM_CYCLE[/AUF_APP_REQUEST]
""".trimIndent()

        fakeGatewayService.nextResponse = GatewayResponse(
            rawContent = rawAiResponseWithAppRequest,
            // MODIFIED: Removed author parameter
            usageMetadata = null
        )

        val initialChatHistorySize = fakeStore.state.value.chatHistory.size

        stateManager.sendMessage("Start a dream cycle.") // MODIFIED: Use sendMessage
        advanceUntilIdle() // Process initial message and AI response
        advanceUntilIdle() // Process the follow-up sendMessage call triggered by AppRequestBlock

        // Expect: user message, AI response, and then a new system message + AI response from dream cycle
        val chatHistory = fakeStore.state.value.chatHistory
        assertEquals(initialChatHistorySize + 4, chatHistory.size) // User, AI, System (dream), AI (dream response)

        val aiResponseMessage = chatHistory[initialChatHistorySize + 1]
        assertEquals(Author.AI, aiResponseMessage.author)
        assertIs<AppRequestBlock>(aiResponseMessage.contentBlocks.last())

        val dreamSystemMessage = chatHistory[initialChatHistorySize + 2]
        assertEquals(Author.SYSTEM, dreamSystemMessage.author)
        assertEquals("App Request", dreamSystemMessage.title)
        assertTrue(dreamSystemMessage.rawContent?.contains("Dream Cycle Simulation") == true)

        val dreamAiResponse = chatHistory[initialChatHistorySize + 3]
        assertEquals(Author.AI, dreamAiResponse.author)
        assertNotNull(dreamAiResponse.rawContent)
        // Check that a new sendMessage was effectively triggered by checking the fake gateway
        assertNotNull(fakeGatewayService.sendMessageCalledWith)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `rerunFromMessage correctly prunes history and triggers sendMessage`() = runTest {
        // MODIFICATION: Dispatch proper AppActions
        fakeStore.dispatch(AppAction.AddUserMessage("Msg 1"))
        fakeStore.dispatch(AppAction.SendMessageSuccess(GatewayResponse(rawContent = "AI Msg 1")))
        fakeStore.dispatch(AppAction.AddUserMessage("Msg 2"))
        advanceUntilIdle()

        val messages = fakeStore.state.value.chatHistory
        assertEquals(3, messages.size) // User 1, AI 1, User 2
        val messageToRerunId = messages[1].id // AI Msg 1's ID

        fakeGatewayService.nextResponse = GatewayResponse(rawContent = "Rerun AI Response")

        stateManager.rerunFromMessage(messageToRerunId)
        advanceUntilIdle()

        val chatHistory = fakeStore.state.value.chatHistory
        // Expected: User 1, AI 1, User 2 (rerun message), new AI response
        assertEquals(4, chatHistory.size)
        assertEquals(messages[0].id, chatHistory[0].id)
        assertEquals(messages[1].id, chatHistory[1].id) // message2 is the starting point
        assertEquals(messages[2].id, chatHistory[2].id) // message3 is the last user input before rerun
        assertEquals("Rerun AI Response", chatHistory.last().rawContent)
        assertEquals(Author.AI, chatHistory.last().author)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deleteMessage correctly removes message from history`() = runTest {
        // MODIFICATION: Dispatch proper AppActions
        fakeStore.dispatch(AppAction.AddUserMessage("Msg 1"))
        fakeStore.dispatch(AppAction.SendMessageSuccess(GatewayResponse(rawContent = "AI Msg 1")))
        advanceUntilIdle()

        val messages = fakeStore.state.value.chatHistory
        assertEquals(2, messages.size)
        val messageToDeleteId = messages[0].id // User message 1

        stateManager.deleteMessage(messageToDeleteId)
        advanceUntilIdle()

        assertEquals(1, fakeStore.state.value.chatHistory.size)
        assertEquals(messages[1].id, fakeStore.state.value.chatHistory.first().id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getSystemContextForDisplay builds messages with correct rawContent for framework files`() = runTest {
        // The setup already writes framework_protocol.md to fakePlatform
        // The AppState is initialized with empty activeHolons map

        val systemMessages = stateManager.getSystemContextForDisplay()
        advanceUntilIdle() // Ensure all coroutines are processed

        // Expected messages: framework_protocol.md, REAL TIME SYSTEM STATUS, Host Tool Manifest
        assertEquals(3, systemMessages.size)

        // Verify framework_protocol.md
        val protocolMessage = systemMessages.firstOrNull { it.title == "framework_protocol.md" }
        assertNotNull(protocolMessage)
        assertEquals("**META_INSTRUCTION_CONTEXTUAL_OVERRIDE: This is the framework protocol.**", protocolMessage.rawContent)

        // Verify REAL TIME SYSTEM STATUS (content is dynamic, check structure)
        val systemStatusMessage = systemMessages.firstOrNull { it.title == "REAL TIME SYSTEM STATUS" }
        assertNotNull(systemStatusMessage)
        assertTrue(systemStatusMessage.rawContent?.contains("AUF App v${Version.APP_VERSION}") == true)

        // Verify Host Tool Manifest
        val toolManifestMessage = systemMessages.firstOrNull { it.title == "Host Tool Manifest" }
        assertNotNull(toolManifestMessage)
        assertTrue(toolManifestMessage.rawContent?.contains("Tool: Atomic Change Manifest") == true)
        assertTrue(toolManifestMessage.rawContent?.contains("Tool: Application Request") == true)
        assertTrue(toolManifestMessage.rawContent?.contains("Tool: File Content View") == true)
        assertTrue(toolManifestMessage.rawContent?.contains("Tool: State Anchor") == true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getPromptForClipboard correctly uses rawContent for all message types`() = runTest {
        val userMessage = "User query."
        val aiResponse = """
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
        val systemError = "Gateway Error: Failed to connect."

        // MODIFICATION: Dispatch proper AppActions
        fakeStore.dispatch(AppAction.AddUserMessage(userMessage))
        fakeStore.dispatch(AppAction.SendMessageSuccess(GatewayResponse(rawContent = aiResponse)))
        fakeStore.dispatch(AppAction.SendMessageFailure(systemError))
        advanceUntilIdle()

        val fullPrompt = stateManager.getPromptForClipboard()
        assertNotNull(fullPrompt)

        // Assert that the full prompt contains the raw content of all message types
        assertTrue(fullPrompt.contains(userMessage))
        assertTrue(fullPrompt.contains(aiResponse))
        assertTrue(fullPrompt.contains("--- Gateway Error ---\nGateway Error: Failed to connect.")) // Verify system error format
        assertTrue(fullPrompt.contains("**META_INSTRUCTION_CONTEXTUAL_OVERRIDE: This is the framework protocol.**")) // From system context
        assertTrue(fullPrompt.contains("--- REAL TIME SYSTEM STATUS ---")) // From system context
        assertTrue(fullPrompt.contains("--- Host Tool Manifest ---")) // From system context

        // Specifically check the formatting of messages with rawContent for consistency
        val expectedUserSection = "--- USER MESSAGE ---\n$userMessage"
        val expectedAiSection = "--- model MESSAGE ---\n$aiResponse"
        assertTrue(fullPrompt.contains(expectedUserSection))
        assertTrue(fullPrompt.contains(expectedAiSection))
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `copyCodebaseToClipboard uses platform copy function with correct content`() = runTest {
        val fakeCode = "fun main() { println(\"Fake Code\") }"
        fakeSourceCodeService.nextResult = fakeCode // Set the expected code content
        stateManager.copyCodebaseToClipboard()
        advanceUntilIdle()

        assertEquals(fakeCode, fakePlatform.lastCopiedToClipboard)
    }
}