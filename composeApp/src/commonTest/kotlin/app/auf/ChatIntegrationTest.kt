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
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
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
 * - **Arrange:** A `StateManager` instance is set up with all necessary fake dependencies.
 *   `ChatMessage.Factory` is initialized with a `FakePlatformDependencies` (for clipboard)
 *   and a *real* `AufTextParser` (to test content block parsing).
 * - **Act:** `AppAction`s are dispatched or `StateManager` functions are called to simulate
 *   user input, AI responses, and system messages.
 * - **Assert:** The `AppState` (specifically `chatHistory`) is inspected to verify `rawContent`
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

    // Define a simple tool registry for the real parser - COPIED FROM main.kt
    private val toolRegistry = listOf(
        ToolDefinition(
            name = "Atomic Change Manifest",
            command = "ACTION_MANIFEST",
            description = "Propose a transactional set of changes to the Holon Knowledge Graph file system.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = "[AUF_ACTION_MANIFEST]\n[...json array of Action objects...]\n[/AUF_ACTION_MANIFEST]"
        ),
        ToolDefinition(
            name = "Application Request",
            command = "APP_REQUEST",
            description = "Request the host application to perform a pre-defined, non-file-system action.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = "[AUF_APP_REQUEST]START_DREAM_CYCLE[/AUF_APP_REQUEST]"
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
            usage = "[AUF_FILE_VIEW(path=\"path/to/your/file.kt\")]\n...file content...\n[/AUF_FILE_VIEW]"
        ),
        ToolDefinition(
            name = "State Anchor",
            command = "STATE_ANCHOR",
            description = "Create a persistent, context-immune memory waypoint within the chat history.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = "[AUF_STATE_ANCHOR]\n{\"anchorId\": \"...\", ...}\n[/AUF_STATE_ANCHOR]"
        )
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

        // PRE-LOAD essential files into the fake file system for ChatService.buildSystemContextMessages
        platform.files[platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md"] = "META_INSTRUCTION_CONTEXTUAL_OVERRIDE: This is the protocol content."
        // Host Tool Manifest is dynamically generated by ChatService.generateDynamicToolManifest(), not read from a file.

        // Ensure the persona holon file content is present for graph loading and context building
        val personaHolonId = "sage-20250726T213010Z"
        val personaFilePath = "holons/$personaHolonId/$personaHolonId.json"
        val personaContent = """
            {"header": {"id": "$personaHolonId", "type": "AI_Persona_Root", "name": "The Silicon Sage", "summary": ""}, "payload": {}}
        """.trimIndent()
        platform.files[personaFilePath] = personaContent
        platform.directories.add("holons/$personaHolonId") // Ensure directory structure exists


        // Initialize ChatMessage.Factory with the real parser and fake platform
        ChatMessage.Factory.initialize(platform, parser)

        val initialState = AppState(
            aiPersonaId = personaHolonId, // Ensure a persona is selected for chatService
            availableAiPersonas = listOf(
                HolonHeader(id = personaHolonId, type = "AI_Persona_Root", name = "The Silicon Sage", summary = "")
            ),
            holonGraph = listOf(
                // Add the actual persona holon for context loading
                app.auf.core.Holon(
                    header = HolonHeader(
                        id = personaHolonId,
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

        // Run initial load graph to populate context from fake files
        stateManager.loadHolonGraph()
    }

    @Test
    fun `AddUserMessage action correctly stores rawContent and derives TextBlock`() = runTest {
        val userRawText = "Hello, Sage! Let's get to work."

        stateManager.sendMessage(userRawText) // This dispatches AppAction.AddUserMessage internally
        advanceUntilIdle() // Process the action in the reducer and any subsequent ChatService side-effects

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
        advanceUntilIdle() // Allow all coroutines including ChatService.sendMessage to complete

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
            Here is the content of your file:
[AUF_FILE_VIEW(path="path/to/my/file.txt")]
Line 1
Line 2
[/AUF_FILE_VIEW]
            A final note.
        """.trimIndent()

        gatewayService.nextResponse = GatewayResponse(rawContent = aiRawResponseWithFileView)

        store.dispatch(AppAction.AddUserMessage("Show me the file."))
        advanceUntilIdle() // Ensure all coroutines complete
        // REMOVED: Redundant runCurrent() call

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
        advanceUntilIdle()

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
        val frameworkProtocolContent = "META_INSTRUCTION_CONTEXTUAL_OVERRIDE: Protocol content."
        val personaHolonId = "sage-20250726T213010Z"
        val personaContent = """{"header": {"id": "$personaHolonId", "type": "AI_Persona_Root", "name": "The Silicon Sage", "summary": ""}, "payload": {}}"""

        // The setup() method already pre-loads framework_protocol.md and the persona holon.
        // It also calls stateManager.loadHolonGraph() and runCurrent() to ensure the graph is loaded.

        val systemContextMessages = chatService.buildSystemContextMessages()

        // Expected messages: framework_protocol, REAL TIME SYSTEM STATUS, Host Tool Manifest, Persona Holon
        // The holonGraph will also contain the persona holon, as loaded by stateManager.initialize() in setup.
        val expectedSystemMessageCount = 3 + store.state.value.activeHolons.size // Fixed count (protocol, status, manifest) + active holons
        assertEquals(expectedSystemMessageCount, systemContextMessages.size)

        // Verify framework_protocol.md
        val protocolMessage = systemContextMessages.first { it.title == "framework_protocol.md" }
        assertEquals(frameworkProtocolContent, protocolMessage.rawContent)
        assertEquals(1, protocolMessage.contentBlocks.size)
        assertIs<TextBlock>(protocolMessage.contentBlocks.first())
        assertEquals(frameworkProtocolContent, (protocolMessage.contentBlocks.first() as TextBlock).text)

        // Verify Host Tool Manifest
        val toolManifestMessage = systemContextMessages.first { it.title == "Host Tool Manifest" }
        val expectedDynamicToolManifest = toolRegistry.joinToString("\n\n") { tool ->
            """
            **Tool: ${tool.name}**
            *   **Description:** ${tool.description}
            *   **Usage:** `${tool.usage}`
            """.trimIndent()
        }
        assertEquals(expectedDynamicToolManifest.trim(), toolManifestMessage.rawContent?.trim())
        assertEquals(1, toolManifestMessage.contentBlocks.size)
        assertIs<TextBlock>(toolManifestMessage.contentBlocks.first())
        assertEquals(expectedDynamicToolManifest.trim(), (toolManifestMessage.contentBlocks.first() as TextBlock).text.trim())


        // Verify System Status (content varies, but ensure it's there and has rawContent)
        val systemStatusMessage = systemContextMessages.first { it.title == "REAL TIME SYSTEM STATUS" }
        assertNotNull(systemStatusMessage.rawContent)
        assertTrue(systemStatusMessage.rawContent!!.contains("AUF App v1.4.0"))

        // Verify Persona Holon
        val personaMessage = systemContextMessages.first { it.title == "$personaHolonId.json" }
        assertEquals(personaContent.trim(), personaMessage.rawContent?.trim())
    }

    @Test
    fun `getPromptForClipboard correctly uses rawContent for all message types`() = runTest {
        val userMsg1 = "Initial query."
        val aiMsg1 = "AI's first response."
        val userMsg2 = "Follow-up question."

        // setup() already pre-loads framework_protocol.md and the persona holon content.
        // It also runs loadHolonGraph()

        // Add messages to chat history via StateManager (which uses ChatMessage.Factory)
        stateManager.sendMessage(userMsg1)
        advanceUntilIdle() // Ensure user message is dispatched and processed

        gatewayService.nextResponse = GatewayResponse(rawContent = aiMsg1)
        chatService.sendMessage()
        advanceUntilIdle() // Ensure AI message is dispatched and processed

        stateManager.sendMessage(userMsg2)
        advanceUntilIdle() // Ensure second user message is dispatched and processed

        val fullPrompt = stateManager.getPromptForClipboard()
        // println(fullPrompt) // For debugging if needed

        assertTrue(fullPrompt.contains("--- START OF FILE framework_protocol.md ---\nMETA_INSTRUCTION_CONTEXTUAL_OVERRIDE: This is the protocol content."))
        assertTrue(fullPrompt.contains("--- START OF FILE Host Tool Manifest ---")) // Tool Manifest is dynamically generated
        assertTrue(fullPrompt.contains("--- START OF FILE REAL TIME SYSTEM STATUS ---")) // System Status is dynamically generated

        val personaHolonId = "sage-20250726T213010Z"
        val personaContent = """{"header": {"id": "$personaHolonId", "type": "AI_Persona_Root", "name": "The Silicon Sage", "summary": ""}, "payload": {}}"""
        assertTrue(fullPrompt.contains("--- START OF FILE $personaHolonId.json ---\n${personaContent.trim()}")) // Persona Holon

        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMsg1"))
        assertTrue(fullPrompt.contains("--- model MESSAGE ---\n$aiMsg1"))
        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMsg2"))
    }

    @Test
    fun `copyCodebaseToClipboard uses platform copy function with correct content`() = runTest {
        val fakeCodebase = "fun main() { println(\"Fake Code\") }"
        (stateManager.sourceCodeService as FakeSourceCodeService).nextResult = fakeCodebase

        stateManager.copyCodebaseToClipboard()
        advanceUntilIdle() // Ensure the coroutine in copyCodebaseToClipboard completes

        assertEquals(fakeCodebase, platform.lastCopiedToClipboard)
        assertEquals("Source code copied to clipboard!", store.state.value.toastMessage)
    }
}