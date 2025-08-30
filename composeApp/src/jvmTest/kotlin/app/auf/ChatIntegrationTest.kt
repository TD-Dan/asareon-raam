package app.auf


import app.auf.core.*
import app.auf.fakes.*
import app.auf.model.Parameter
import app.auf.model.SettingValue
import app.auf.model.ToolDefinition
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.PromptCompiler
import app.auf.service.SettingsManager
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ## Mandate
 * This test suite focuses on integration-level scenarios within the chat functionality,
 * particularly on message creation, the prompt compilation pipeline, and state flow.
 */
class ChatIntegrationTest {

    @get:Rule
    val composeTestRule = androidx.compose.ui.test.junit4.createComposeRule()

    private lateinit var stateManager: StateManager
    private lateinit var fakeStore: FakeStore
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var realParser: AufTextParser
    private lateinit var fakeGatewayService: FakeGatewayService
    private lateinit var fakeSourceCodeService: FakeSourceCodeService
    private lateinit var realPromptCompiler: PromptCompiler
    private lateinit var settingsManager: SettingsManager
    private lateinit var testCoroutineScope: CoroutineScope
    private lateinit var fakeSessionManager: FakeSessionManager

    private val toolRegistry = listOf(
        ToolDefinition("Atomic Change Manifest", "ACTION_MANIFEST", "", emptyList(), true, ""),
        ToolDefinition("Application Request", "APP_REQUEST", "", emptyList(), true, ""),
        ToolDefinition("File Content View", "FILE_VIEW", "", listOf(Parameter("path", "String", true)), true, ""),
        ToolDefinition("State Anchor", "STATE_ANCHOR", "", emptyList(), true, "")
    )

    @Before
    fun setup() {
        testCoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        val initialPersonaHeader = HolonHeader("sage-1", "AI_Persona_Root", "Sage", "")
        val initialState = AppState(
            aiPersonaId = initialPersonaHeader.id,
            availableAiPersonas = listOf(initialPersonaHeader),
            activeHolons = emptyMap()
        )
        fakePlatform = FakePlatformDependencies()
        fakeSessionManager = FakeSessionManager(fakePlatform)
        fakeStore = FakeStore(initialState, testCoroutineScope, fakeSessionManager)
        realParser = AufTextParser(JsonProvider.appJson, toolRegistry)
        realPromptCompiler = PromptCompiler(JsonProvider.appJson)
        settingsManager = SettingsManager(fakePlatform, JsonProvider.appJson)

        ChatMessage.Factory.initialize(fakePlatform, realParser)

        fakeGatewayService = FakeGatewayService(testCoroutineScope, toolRegistry)
        fakeSourceCodeService = FakeSourceCodeService(fakePlatform)

        val chatService = ChatService(
            fakeStore,
            fakeGatewayService,
            fakePlatform,
            realParser,
            toolRegistry,
            realPromptCompiler,
            testCoroutineScope
        )

        stateManager = StateManager(
            store = fakeStore,
            backupManager = FakeBackupManager(fakePlatform),
            graphService = FakeGraphService(),
            sourceCodeService = fakeSourceCodeService,
            chatService = chatService,
            gatewayService = fakeGatewayService,
            actionExecutor = FakeActionExecutor(fakePlatform, JsonProvider.appJson),
            parser = realParser,
            settingsManager = settingsManager,
            sessionManager = fakeSessionManager,
            // --- MODIFICATION: Pass the required coroutine scope to the fake view model ---
            importExportViewModel = FakeImportExportViewModel(testCoroutineScope),
            platform = fakePlatform,
            coroutineScope = testCoroutineScope
        )

        fakePlatform.writeFileContent(
            fakePlatform.getBasePathFor(BasePath.FRAMEWORK) + fakePlatform.pathSeparator + "framework_protocol.md",
            "**Protocol**"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `system messages are compiled based on settings before being sent`() = runTest {
        val holonRawContent = """
        {
            "header": {
                "id": "test-holon-1",
                "type": "Test",
                "name": "Test Holon",
                "summary": "A test holon.",
                "version": "1.0",
                "created_at": "2025-01-01T00:00:00Z"
            },
            "payload": { "data": "some value" }
        }
        """.trimIndent()
        val holon = JsonProvider.appJson.decodeFromString(Holon.serializer(), holonRawContent)
        fakeStore.dispatch(AppAction.LoadGraphSuccess(GraphLoadResult(holonGraph = listOf(holon))))
        fakeStore.dispatch(AppAction.ToggleHolonActive("test-holon-1"))

        stateManager.updateSetting(SettingValue("compiler.cleanHeaders", true))
        stateManager.updateSetting(SettingValue("compiler.minifyJson", true))
        stateManager.updateSetting(SettingValue("compiler.removeWhitespace", true))
        advanceUntilIdle()

        stateManager.sendMessage("Go")
        advanceUntilIdle()

        val systemMessagesSentToChatService = stateManager.getSystemContextForDisplay()
        assertNotNull(systemMessagesSentToChatService)

        val systemBlock = systemMessagesSentToChatService.first { it.title == "test-holon-1.json" }
        val sentContent = systemBlock.compiledContent
        assertNotNull(sentContent, "The compiled content of the system message should not be null.")

        assertFalse(sentContent.contains("\n"), "Compiled content should be minified.")
        assertFalse(sentContent.contains("version"), "Compiled content should not contain 'version'.")
        assertFalse(sentContent.contains("created_at"), "Compiled content should not contain 'created_at'.")
        assertTrue(sentContent.contains("test-holon-1"), "Compiled content must contain the ID.")
        assertTrue(sentContent.contains("some value"), "Compiled content must contain the payload data.")
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getPromptForClipboard uses compiled content for system messages and raw for others`() = runTest {
        stateManager.updateSetting(SettingValue("compiler.minifyJson", true))
        val userMessage = "User query."
        val aiResponse = "AI response."
        val systemMessageRaw = """
        {
            "header": { "id": "sys-holon" },
            "payload": {}
        }
        """.trimIndent()
        val expectedSystemCompiled = """{"header":{"id":"sys-holon"},"payload":{}}"""

        fakeStore.dispatch(AppAction.AddUserMessage(userMessage))
        fakeStore.dispatch(AppAction.SendMessageSuccess(GatewayResponse(rawContent = aiResponse)))
        fakeStore.dispatch(AppAction.AddSystemMessage("sys-holon.json", systemMessageRaw))
        advanceUntilIdle()

        val fullPrompt = stateManager.getPromptForClipboard()

        assertTrue(fullPrompt.contains(expectedSystemCompiled), "Prompt should contain the compiled system message.")
        assertFalse(fullPrompt.contains(""""header": {"""), "Prompt should NOT contain the pretty-printed system message.")
        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMessage"))
        assertTrue(fullPrompt.contains("--- model MESSAGE ---\n$aiResponse"))
    }
}