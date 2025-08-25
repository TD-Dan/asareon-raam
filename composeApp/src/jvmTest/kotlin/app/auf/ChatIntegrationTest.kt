package app.auf


import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.core.GraphLoadResult
import app.auf.core.Holon
import app.auf.core.HolonHeader
import app.auf.core.StateManager
import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportViewModel
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
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
    private lateinit var fakeGatewayService: FakeGatewayService
    private lateinit var fakeSourceCodeService: FakeSourceCodeService
    private lateinit var realPromptCompiler: PromptCompiler
    private lateinit var settingsManager: SettingsManager
    private lateinit var testCoroutineScope: CoroutineScope

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
        fakeStore = FakeStore(initialState, testCoroutineScope)
        fakePlatform = FakePlatformDependencies()
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
            importExportViewModel = FakeImportExportViewModel(),
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
        // ARRANGE: A holon that can be cleaned and minified
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

        // Set compiler settings to be aggressive
        stateManager.updateSetting(SettingValue("compiler.cleanHeaders", true)) // <<< FIX: Corrected method call
        stateManager.updateSetting(SettingValue("compiler.minifyJson", true)) // <<< FIX: Corrected method call
        stateManager.updateSetting(SettingValue("compiler.removeWhitespace", true)) // <<< FIX: Corrected method call
        advanceUntilIdle()

        // ACT
        stateManager.sendMessage("Go")
        advanceUntilIdle()

        // ASSERT
        val apiContents = fakeGatewayService.sendMessageCalledWith
        assertNotNull(apiContents)

        val systemBlock = apiContents.first { it.author == Author.SYSTEM && it.title == "test-holon-1.json" }
        val sentContent = systemBlock.rawContent
        assertNotNull(sentContent)

        assertFalse(sentContent.contains("\n"), "Compiled content should be minified.")
        assertFalse(sentContent.contains("version"), "Compiled content should not contain 'version'.")
        assertFalse(sentContent.contains("created_at"), "Compiled content should not contain 'created_at'.")
        assertTrue(sentContent.contains("test-holon-1"), "Compiled content must contain the ID.")
        assertTrue(sentContent.contains("some value"), "Compiled content must contain the payload data.")
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getPromptForClipboard uses compiled content for system messages and raw for others`() = runTest {
        // ARRANGE
        stateManager.updateSetting(SettingValue("compiler.minifyJson", true)) // <<< FIX: Corrected method call
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

        // ACT
        val fullPrompt = stateManager.getPromptForClipboard()

        // ASSERT
        assertTrue(fullPrompt.contains(expectedSystemCompiled), "Prompt should contain the compiled system message.")
        assertFalse(fullPrompt.contains(""""header": {"""), "Prompt should NOT contain the pretty-printed system message.")
        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMessage"))
        assertTrue(fullPrompt.contains("--- model MESSAGE ---\n$aiResponse"))
    }
}