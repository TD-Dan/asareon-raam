package app.auf.core

import app.auf.fakes.*
import app.auf.feature.systemclock.ClockAction
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.service.ActionExecutorResult
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.PromptCompiler
import app.auf.service.SettingsManager
import app.auf.ui.ImportExportViewModel
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StateManagerTest {

    private lateinit var fakeParser: FakeAufTextParser

    @BeforeTest
    fun initializeFactory() {
        fakeParser = FakeAufTextParser()
        ChatMessage.Factory.initialize(FakePlatformDependencies(), fakeParser)
    }

    private fun setupTestEnvironment(
        initialState: AppState = AppState(),
        scope: TestScope
    ): Triple<StateManager, FakeStore, FakeActionExecutor> {
        val platform = FakePlatformDependencies()
        val frameworkPath = platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md"
        platform.writeFileContent(frameworkPath, "Fake protocol content")

        val sessionManager = FakeSessionManager(platform)
        val store = FakeStore(initialState, scope, sessionManager)
        val backupManager = FakeBackupManager(platform)
        val graphService = FakeGraphService()
        val sourceCodeService = FakeSourceCodeService(platform)
        val jsonParser = JsonProvider.appJson

        val realParser = AufTextParser()
        val settingsManager = SettingsManager(platform, jsonParser)
        val promptCompiler = PromptCompiler(jsonParser)

        val gatewayService = FakeGatewayService(scope)
        val chatService = ChatService(store, gatewayService, platform, realParser, promptCompiler, scope)


        val actionExecutor = FakeActionExecutor(platform, jsonParser)
        val importExportManager = FakeImportExportManager(platform, jsonParser)
        val importExportViewModel = ImportExportViewModel(importExportManager, scope)

        val stateManager = StateManager(
            store = store,
            backupManager = backupManager,
            graphService = graphService,
            sourceCodeService = sourceCodeService,
            chatService = chatService,
            gatewayService = gatewayService,
            actionExecutor = actionExecutor,
            parser = realParser,
            settingsManager = settingsManager,
            sessionManager = sessionManager,
            importExportViewModel = importExportViewModel,
            platform = platform,
            coroutineScope = scope
        )
        return Triple(stateManager, store, actionExecutor)
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeActionFromMessage success path dispatches correct actions and reloads graph`() = runTest {
        val manifest = listOf(CreateFile("test.txt", "content", "Create test file"))
        val manifestJson = """[{"type":"CreateFile","filePath":"test.txt","content":"content","summary":"Create test file"}]"""
        val rawManifestContent = "```json\n$manifestJson\n```"
        val actionMessage = ChatMessage.Factory.createAi(
            rawContent = rawManifestContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(CodeBlock(language = "json", content = manifestJson, status = ActionStatus.PENDING)))

        val messageTimestamp = actionMessage.timestamp

        val initialState = AppState(chatHistory = listOf(actionMessage), aiPersonaId = "sage-1")
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Success("Manifest executed.")

        store.startFeatureLifecycles()
        stateManager.executeActionFromMessage(messageTimestamp)

        runCurrent()

        val dispatchedActions = store.dispatchedActions
        assertEquals(5, dispatchedActions.size, "Expected 5 actions: Execute, UpdateStatus, Success, Load, LoadSuccess")
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.UpdateActionStatus>(dispatchedActions[1])
        assertEquals(ActionStatus.EXECUTED, (dispatchedActions[1] as AppAction.UpdateActionStatus).status)
        assertIs<AppAction.ExecuteActionManifestSuccess>(dispatchedActions[2])
        assertIs<AppAction.LoadGraph>(dispatchedActions[3])
        assertIs<AppAction.LoadGraphSuccess>(dispatchedActions[4])
        assertEquals(manifest[0].summary, fakeActionExecutor.lastExecutedManifest?.get(0)?.summary)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeActionFromMessage failure path dispatches failure action`() = runTest {
        val manifest = listOf<Action>(CreateFile("test.txt", "content", "Create test file"))
        val manifestJson = """[{"type":"CreateFile","filePath":"test.txt","content":"content","summary":"Create test file"}]"""
        val rawManifestContent = "```json\n$manifestJson\n```"
        val actionMessage = ChatMessage.Factory.createAi(
            rawContent = rawManifestContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(CodeBlock(language = "json", content = manifestJson, status = ActionStatus.PENDING)))
        val messageTimestamp = actionMessage.timestamp

        val initialState = AppState(chatHistory = listOf(actionMessage))
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Failure("File not found.")

        store.startFeatureLifecycles()
        stateManager.executeActionFromMessage(messageTimestamp)

        runCurrent()

        val dispatchedActions = store.dispatchedActions
        assertEquals(2, dispatchedActions.size)
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ExecuteActionManifestFailure>(dispatchedActions[1])
        assertEquals("File not found.", (dispatchedActions[1] as AppAction.ExecuteActionManifestFailure).error)
    }

    // --- NEW TESTS FOR CONVERSATIONAL COMMAND-LINE ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendMessage with valid CCL action dispatches correct AppAction`() = runTest {
        // ARRANGE
        val (stateManager, store, _) = setupTestEnvironment(AppState(aiPersonaId = "test"), this)
        val cclMessage = "Please start the clock.\n```auf_action\nClockAction.Start\n```"

        // ACT
        stateManager.sendMessage(cclMessage)
        runCurrent()

        // ASSERT
        // --- FIX: The test expectation was wrong. 5 actions are dispatched in total. ---
        val dispatched = store.dispatchedActions
        assertEquals(5, dispatched.size)
        assertIs<AppAction.AddUserMessage>(dispatched[0])
        assertIs<ClockAction.Start>(dispatched[1])
        assertIs<AppAction.ShowToast>(dispatched[2])
        assertIs<AppAction.SendMessageLoading>(dispatched[3])
        assertIs<AppAction.SendMessageSuccess>(dispatched[4]) // This was the missing action
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendMessage with invalid CCL action dispatches a ShowToast action`() = runTest {
        // ARRANGE
        val (stateManager, store, _) = setupTestEnvironment(AppState(aiPersonaId = "test"), this)
        val cclMessage = "```auf_action\nClockAction.Invalid\n```"

        // ACT
        stateManager.sendMessage(cclMessage)
        runCurrent()

        // ASSERT
        val dispatched = store.dispatchedActions
        assertEquals(4, dispatched.size)
        assertIs<AppAction.AddUserMessage>(dispatched[0])
        assertIs<AppAction.ShowToast>(dispatched[1])
        assertEquals("Unknown CCL action: ClockAction.Invalid", (dispatched[1] as AppAction.ShowToast).message)
        assertIs<AppAction.SendMessageLoading>(dispatched[2])
        assertIs<AppAction.SendMessageSuccess>(dispatched[3])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendMessage with non-action code block does not dispatch CCL action`() = runTest {
        // ARRANGE
        val (stateManager, store, _) = setupTestEnvironment(AppState(aiPersonaId = "test"), this)
        val message = "Here is some code\n```kotlin\nval x = 1\n```"

        // ACT
        stateManager.sendMessage(message)
        runCurrent()

        // ASSERT
        val dispatched = store.dispatchedActions
        assertEquals(3, dispatched.size)
        assertIs<AppAction.AddUserMessage>(dispatched[0])
        assertIs<AppAction.SendMessageLoading>(dispatched[1])
        assertIs<AppAction.SendMessageSuccess>(dispatched[2])
    }
}