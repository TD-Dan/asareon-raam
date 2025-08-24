package app.auf.core

import app.auf.fakes.FakeActionExecutor
import app.auf.fakes.FakeAufTextParser // Import the new Fake
import app.auf.fakes.FakeBackupManager
import app.auf.fakes.FakeChatService
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakeGraphService
import app.auf.fakes.FakeImportExportManager
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSourceCodeService
import app.auf.fakes.FakeStore
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.model.ToolDefinition
import app.auf.model.UserSettings
import app.auf.service.ActionExecutorResult
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
        // MODIFICATION: Initialize factory with parser
        ChatMessage.Factory.initialize(FakePlatformDependencies(), FakeAufTextParser())
    }

    private fun setupTestEnvironment(
        initialState: AppState = AppState(),
        scope: TestScope
    ): Triple<StateManager, FakeStore, FakeActionExecutor> {
        val store = FakeStore(initialState, scope)
        val platform = FakePlatformDependencies()
        val backupManager = FakeBackupManager(platform)
        val graphService = FakeGraphService()
        val sourceCodeService = FakeSourceCodeService(platform)
        val jsonParser = JsonProvider.appJson

        val toolRegistry = listOf<ToolDefinition>()
        // MODIFICATION: Use FakeAufTextParser here
        val parser = FakeAufTextParser(jsonParser, toolRegistry)

        val gatewayService = FakeGatewayService(scope, toolRegistry)
        val chatService = FakeChatService(store, gatewayService, platform, parser, toolRegistry, scope)

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
            importExportViewModel = importExportViewModel,
            platform = platform,
            initialSettings = UserSettings(),
            coroutineScope = scope
        )
        return Triple(stateManager, store, actionExecutor)
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeActionFromMessage success path dispatches correct actions and reloads graph`() = runTest {
        val manifest = listOf(CreateFile("test.txt", "content", "Create test file"))
        val rawManifestContent = """
            [AUF_ACTION_MANIFEST]
            [
                {
                    "type": "CreateFile",
                    "filePath": "test.txt",
                    "content": "Hello",
                    "summary": "Create test file"
                }
            ]
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        // MODIFICATION: Use rawContent to create an AI message
        val actionMessage = ChatMessage.Factory.createAi(
            rawContent = rawManifestContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(ActionBlock(actions = manifest, status = ActionStatus.PENDING))) // Manual override for test block

        val messageTimestamp = actionMessage.timestamp

        val initialState = AppState(chatHistory = listOf(actionMessage), aiPersonaId = "sage-1")
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Success("Manifest executed.")

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
        assertEquals(manifest, fakeActionExecutor.lastExecutedManifest)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `executeActionFromMessage failure path dispatches failure action`() = runTest {
        val manifest = listOf<Action>(CreateFile("test.txt", "content", "Create test file"))
        val rawManifestContent = """
            [AUF_ACTION_MANIFEST]
            [
                {
                    "type": "CreateFile",
                    "filePath": "test.txt",
                    "content": "Hello",
                    "summary": "Create test file"
                }
            ]
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        // MODIFICATION: Use rawContent to create an AI message
        val actionMessage = ChatMessage.Factory.createAi(
            rawContent = rawManifestContent,
            usageMetadata = null
        ).copy(contentBlocks = listOf(ActionBlock(actions = manifest, status = ActionStatus.PENDING))) // Manual override for test block
        val messageTimestamp = actionMessage.timestamp

        val initialState = AppState(chatHistory = listOf(actionMessage))
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Failure("File not found.")

        stateManager.executeActionFromMessage(messageTimestamp)

        runCurrent()

        val dispatchedActions = store.dispatchedActions
        assertEquals(2, dispatchedActions.size)
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ExecuteActionManifestFailure>(dispatchedActions[1])
        assertEquals("File not found.", (dispatchedActions[1] as AppAction.ExecuteActionManifestFailure).error)
    }
}