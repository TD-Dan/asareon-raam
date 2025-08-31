package app.auf.core

import app.auf.fakes.*
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.service.ActionExecutorResult
import app.auf.service.PromptCompiler
import app.auf.service.SettingsManager
import app.auf.ui.ImportExportViewModel
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

    @BeforeTest
    fun initializeFactory() {
        ChatMessage.Factory.initialize(FakePlatformDependencies(), FakeAufTextParser())
    }

    private fun setupTestEnvironment(
        initialState: AppState = AppState(),
        scope: TestScope
    ): Triple<StateManager, FakeStore, FakeActionExecutor> {
        val platform = FakePlatformDependencies()
        val sessionManager = FakeSessionManager(platform)
        val store = FakeStore(initialState, scope, sessionManager)
        val backupManager = FakeBackupManager(platform)
        val graphService = FakeGraphService()
        val sourceCodeService = FakeSourceCodeService(platform)
        val jsonParser = JsonProvider.appJson

        val parser = FakeAufTextParser()
        val settingsManager = SettingsManager(platform, jsonParser)
        val promptCompiler = PromptCompiler(jsonParser)

        val gatewayService = FakeGatewayService(scope)
        val chatService = FakeChatService(store, gatewayService, platform, parser, promptCompiler, scope)

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
            parser = parser,
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
}