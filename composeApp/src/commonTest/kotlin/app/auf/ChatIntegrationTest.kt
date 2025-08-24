package app.auf

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.ActionBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.FileContentBlock
import app.auf.core.GatewayResponse
import app.auf.core.HolonHeader
import app.auf.core.StateManager
import app.auf.core.TextBlock
import app.auf.core.appReducer
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphLoader
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportManager
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
import app.auf.service.ChatService
import app.auf.service.UsageMetadata
import app.auf.ui.ImportExportViewModel
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for raw content consistency across the AUF App's chat message lifecycle.
 *
 * ---
 * ## Mandate
 * This test suite verifies that the architectural principle of "raw content as ground truth"
 * is correctly implemented and maintained throughout the application's core chat functionality.
 * It ensures that raw string content for USER, AI, and SYSTEM messages is accurately captured,
 * stored, and used as the definitive source for display and export operations.
 *
 * ---
 * ## Test Strategy
 * - **Arrange: ** A `StateManager` instance is set up with all necessary fake dependencies.
 *   `ChatMessage.Factory` is initialized with a `FakePlatformDependencies` (for clipboard)
 *   and a *real* `AufTextParser` (to test content block parsing).
 * - **Act: ** `AppAction`s are dispatched or `StateManager` functions are called to simulate
 *   user input, AI responses, and system messages.
 * - **Assert: ** The `AppState` (specifically `chatHistory`) is inspected to verify `rawContent`
 *   and `contentBlocks`. `FakePlatformDependencies` is used to check clipboard content.
 *   `ChatService` functions for building prompts are also verified.
 *
 * This suite aims to confirm end-to-end data integrity for chat message content.
 *
 * @version 1.0
 * @since 2025-08-24
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIntegrationTest {

    private lateinit var store: FakeStore
    private lateinit var platform: FakePlatformDependencies
    private lateinit var parser: AufTextParser
    private lateinit var gatewayService: FakeGatewayService
    private lateinit var chatService: ChatService
    private lateinit var stateManager: StateManager
    private lateinit var testCoroutineScope: TestScope

    // Define a simple tool registry for the real parser
    private val toolRegistry = listOf(
        ToolDefinition(
            name = "Atomic Change Manifest",
            command = "ACTION_MANIFEST",
            description = "",
            parameters = emptyList(),
            expectsPayload = true,
            usage = ""
        ),
        ToolDefinition(
            name = "File Content View",
            command = "FILE_VIEW",
            description = "",
            parameters = listOf(
                Parameter(name = "path", type = "String", isRequired = true),
                Parameter(name = "language", type = "String", isRequired = false, defaultValue = "plaintext")
            ),
            expectsPayload = true,
            usage = ""
        ),
        ToolDefinition("App Request", "APP_REQUEST", "", emptyList(), true, ""),
        ToolDefinition("State Anchor", "STATE_ANCHOR", "", emptyList(), true, "")
    )

    @BeforeTest
    fun setup() {
        // Essential for coroutine tests
        Dispatchers.setMain(Dispatchers.Unconfined)
        testCoroutineScope = TestScope()

        val jsonParser = JsonProvider.appJson

        // Real parser instance for integration testing of content blocks
        parser = AufTextParser(jsonParser, toolRegistry)

        platform = FakePlatformDependencies()

        // Initialize ChatMessage.Factory with the real parser and fake platform
        ChatMessage.Factory.initialize(platform, parser)

        val initialState = AppState(
            aiPersonaId = "sage-20250726T213010Z", // Ensure a persona is selected for chatService
            availableAiPersonas = listOf(
                HolonHeader(id = "sage-20250726T213010Z", type = "AI_Persona_Root", name = "The Silicon Sage", summary = "")
            ),
            holonGraph = listOf(
                // Add a dummy holon so buildSystemContextMessages can find a persona
                app.auf.core.Holon(
                    header = HolonHeader(
                        id = "sage-22222222222222Z", // Use a different ID here to not conflict with the active one if we add it
                        type = "AI_Persona_Root",
                        name = "The Silicon Sage (v4.5 Kernel)",
                        summary = "AI Persona for testing"
                    ),
                    payload = JsonProvider.appJson.parseToJsonElement("{}").jsonObject
                )
            )
        )

        store = FakeStore(initialState, testCoroutineScope)
        gatewayService = FakeGatewayService(testCoroutineScope, toolRegistry)

        // Use the real ChatService to test its logic
        chatService = ChatService(
            store = store,
            gatewayService = gatewayService,
            platform = platform,
            parser = parser, // Pass the real parser
            toolRegistry = toolRegistry,
            coroutineScope = testCoroutineScope
        )

        stateManager = StateManager(
            store = store,
            backupManager = FakeBackupManager(platform),
            graphService = FakeGraphService(),
            sourceCodeService = FakeSourceCodeService(platform),
            chatService = chatService, // Pass the real ChatService
            gatewayService = gatewayService,
            actionExecutor = FakeActionExecutor(platform, jsonParser),
            parser = parser, // Pass the real parser to StateManager
            importExportViewModel = ImportExportViewModel(FakeImportExportManager(platform, jsonParser), testCoroutineScope),
            platform = platform,
            initialSettings = UserSettings(),
            coroutineScope = testCoroutineScope
        )
    }

    @Test
    fun `AddUserMessage action correctly stores rawContent and derives TextBlock`() = runTest {
        val userRawText = "Hello, Sage! Let's get to work."

        stateManager.sendMessage(userRawText) // This dispatches AppAction.AddUserMessage internally
        runCurrent() // Process the action in the reducer

        val lastMessage = store.state.value.chatHistory.last()
        assertEquals(Author.USER, lastMessage.author)
        assertEquals(userRawText, lastMessage.rawContent)
        assertEquals(1, lastMessage.contentBlocks.size)
        assertIs<TextBlock>(lastMessage.contentBlocks.first())
        assertEquals(userRawText, (lastMessage.contentBlocks.first() as TextBlock).text)
    }

    @Test
    fun `SendMessageSuccess action correctly stores rawContent and derives ActionBlock for AI response`() = runTest {
        val aiRawResponseWithAction = """
            Here is your action manifest:
[AUF_ACTION_MANIFEST]
[
    {"type": "CreateFile", "filePath": "report.md", "content": "Report content", "summary": "Create Report"}
]
[/AUF_ACTION_MANIFEST]
            Confirm to proceed.
        """.trimIndent()

        gatewayService.nextResponse = GatewayResponse(
            rawContent = aiRawResponseWithAction,
            usageMetadata = UsageMetadata(10, 20, 30)
        )

        // Dispatch a user message to trigger the AI response flow via chatService.sendMessage()
        store.dispatch(AppAction.AddUserMessage("Generate a report."))
        runCurrent()
        chatService.sendMessage()
        runCurrent() // Allow coroutines in chatService.sendMessage to complete

        val lastMessage = store.state.value.chatHistory.last()
        assertEquals(Author.AI, lastMessage.author)
        assertEquals(aiRawResponseWithAction, lastMessage.rawContent)
        assertEquals(3, lastMessage.contentBlocks.size) // TextBlock, ActionBlock, TextBlock
        assertIs<TextBlock>(lastMessage.contentBlocks[0])
        assertIs<ActionBlock>(lastMessage.contentBlocks[1])
        assertIs<TextBlock>(lastMessage.contentBlocks[2])

        val actionBlock = lastMessage.contentBlocks[1] as ActionBlock
        assertEquals(1, actionBlock.actions.size)
        assertIs<CreateFile>(actionBlock.actions.first())
        assertEquals("report.md", (actionBlock.actions.first() as CreateFile).filePath)
        assertEquals(30, lastMessage.usageMetadata?.totalTokenCount)
    }

    @Test
    fun `SendMessageSuccess action correctly stores rawContent and derives FileContentBlock for AI response`() = runTest {
        val aiRawResponseWithFileView = """
            ```
            Here is the content of your file:
[AUF_FILE_VIEW(path="path/to/my/file.txt")]
Line 1
Line 2
[/AUF_FILE_VIEW]
            ```
        """.trimIndent()

        gatewayService.nextResponse = GatewayResponse(rawContent = aiRawResponseWithFileView)

        store.dispatch(AppAction.AddUserMessage("Show me the file."))
        runCurrent()
        chatService.sendMessage()
        runCurrent()

        val lastMessage = store.state.value.chatHistory.last()
        assertEquals(Author.AI, lastMessage.author)
        assertEquals(aiRawResponseWithFileView, lastMessage.rawContent)

        // With the real parser, it should parse correctly into text, file view, then text
        assertEquals(3, lastMessage.contentBlocks.size)
        assertIs<TextBlock>(lastMessage.contentBlocks[0])
        assertTrue((lastMessage.contentBlocks[0] as TextBlock).text.trim().startsWith("Here is the content of your file:"))

        assertIs<FileContentBlock>(lastMessage.contentBlocks[1])
        assertEquals("path/to/my/file.txt", (lastMessage.contentBlocks[1] as FileContentBlock).fileName)
        assertEquals("Line 1\nLine 2", (lastMessage.contentBlocks[1] as FileContentBlock).content)

        assertIs<TextBlock>(lastMessage.contentBlocks[2])
        assertTrue((lastMessage.contentBlocks[2] as TextBlock).text.trim().contains("A final note."))
    }


    @Test
    fun `AddSystemMessage action correctly stores rawContent and derives TextBlock`() = runTest {
        val systemRawContent = "System initialized successfully."
        val systemTitle = "System Log"

        store.dispatch(AppAction.AddSystemMessage(systemTitle, systemRawContent))
        runCurrent()

        val lastMessage = store.state.value.chatHistory.last()
        assertEquals(Author.SYSTEM, lastMessage.author)
        assertEquals(systemTitle, lastMessage.title)
        assertEquals(systemRawContent, lastMessage.rawContent)
        assertEquals(1, lastMessage.contentBlocks.size)
        assertIs<TextBlock>(lastMessage.contentBlocks.first())
        assertEquals(systemRawContent, (lastMessage.contentBlocks.first() as TextBlock).text)
    }

    @Test
    fun `getSystemContextForDisplay builds messages with correct rawContent for framework files`() = runTest {
        val frameworkProtocolContent = "META_INSTRUCTION_CONTEXTUAL_OVERRIDE: ..."
        val hostToolManifestContent = "**Tool: Test Tool**"

        // Set up fake files for platformDependencies to read
        platform.files["framework/framework_protocol.md"] = frameworkProtocolContent
        platform.files["framework/Host Tool Manifest"] = hostToolManifestContent

        val systemContextMessages = chatService.buildSystemContextMessages()

        assertEquals(3, systemContextMessages.size) // Protocol, System Status, Host Tool Manifest

        // Verify framework_protocol.md
        val protocolMessage = systemContextMessages.first { it.title == "framework_protocol.md" }
        assertEquals(frameworkProtocolContent, protocolMessage.rawContent)
        assertEquals(1, protocolMessage.contentBlocks.size)
        assertIs<TextBlock>(protocolMessage.contentBlocks.first())
        assertEquals(frameworkProtocolContent, (protocolMessage.contentBlocks.first() as TextBlock).text)

        // Verify Host Tool Manifest
        val toolManifestMessage = systemContextMessages.first { it.title == "Host Tool Manifest" }
        assertEquals(hostToolManifestContent, toolManifestMessage.rawContent)
        assertEquals(1, toolManifestMessage.contentBlocks.size)
        assertIs<TextBlock>(toolManifestMessage.contentBlocks.first())
        assertEquals(hostToolManifestContent, (toolManifestMessage.contentBlocks.first() as TextBlock).text)

        // Verify System Status (content varies, but ensure it's there and has rawContent)
        val systemStatusMessage = systemContextMessages.first { it.title == "REAL TIME SYSTEM STATUS" }
        assertNotNull(systemStatusMessage.rawContent)
        assertTrue(systemStatusMessage.rawContent!!.contains("AUF App v1.4.0"))
    }

    @Test
    fun `getPromptForClipboard correctly uses rawContent for all message types`() = runTest {
        val userMsg1 = "Initial query."
        val aiMsg1 = "AI's first response."
        val userMsg2 = "Follow-up question."

        // Inject some system context into the fake platform
        platform.files[platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md"] = "Protocol content."
        // And ensure the persona holon is present (mocked in setup)
        platform.files["holons/sage-20250726T213010Z/sage-20250726T213010Z.json"] = """
            {"header": {"id": "sage-20250726T213010Z", "type": "AI_Persona_Root", "name": "The Silicon Sage", "summary": ""}, "payload": {}}
        """.trimIndent()

        // Add messages to chat history via StateManager (which uses ChatMessage.Factory)
        stateManager.sendMessage(userMsg1)
        runCurrent()
        gatewayService.nextResponse = GatewayResponse(rawContent = aiMsg1)
        chatService.sendMessage()
        runCurrent()
        stateManager.sendMessage(userMsg2)
        runCurrent()

        val fullPrompt = stateManager.getPromptForClipboard()
        println(fullPrompt) // For debugging if needed

        assertTrue(fullPrompt.contains("--- START OF FILE framework_protocol.md ---\nProtocol content."))
        assertTrue(fullPrompt.contains("--- START OF FILE Host Tool Manifest ---")) // Tool Manifest is dynamically generated
        assertTrue(fullPrompt.contains("--- START OF FILE REAL TIME SYSTEM STATUS ---")) // System Status is dynamically generated
        assertTrue(fullPrompt.contains("--- START OF FILE sage-20250726T213010Z.json ---")) // Persona Holon

        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMsg1"))
        assertTrue(fullPrompt.contains("--- model MESSAGE ---\n$aiMsg1"))
        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMsg2"))
    }

    @Test
    fun `copyCodebaseToClipboard uses platform copy function with correct content`() = runTest {
        val fakeCodebase = "fun main() { println(\"Fake Code\") }"
        (stateManager.sourceCodeService as FakeSourceCodeService).nextResult = fakeCodebase

        stateManager.copyCodebaseToClipboard()
        runCurrent()

        assertEquals(fakeCodebase, platform.lastCopiedToClipboard)
        assertEquals("Source code copied to clipboard!", store.state.value.toastMessage)
    }
}