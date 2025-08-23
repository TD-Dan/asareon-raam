package app.auf.core

import app.auf.fakes.FakeActionExecutor
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
import app.auf.model.UserSettings
import app.auf.service.ActionExecutorResult
import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateManagerTest {

    private fun setupTestEnvironment(
        initialState: AppState = AppState(),
        scope: TestScope
    ): Triple<StateManager, FakeStore, FakeActionExecutor> {
        val store = FakeStore(initialState, scope)
        val platform = FakePlatformDependencies()
        val backupManager = FakeBackupManager(platform)
        val graphService = FakeGraphService()
        val sourceCodeService = FakeSourceCodeService(platform)
        val chatService = FakeChatService(store)
        val gatewayService = FakeGatewayService()
        val jsonParser = JsonProvider.appJson
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
            importExportViewModel = importExportViewModel,
            platform = platform,
            initialSettings = UserSettings(),
            coroutineScope = scope
        )
        return Triple(stateManager, store, actionExecutor)
    }


    @Test
    fun `executeActionFromMessage success path dispatches correct actions and reloads graph`() = runTest {
        val manifest = listOf(CreateFile("test.txt", "content", "Create test file"))
        val actionMessage = ChatMessage(
            author = Author.AI, timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = manifest, isResolved = false))
        )
        val initialState = AppState(chatHistory = listOf(actionMessage), aiPersonaId = "sage-1")
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Success("Manifest executed.")

        stateManager.executeActionFromMessage(12345L)

        val dispatchedActions = store.dispatchedActions
        assertEquals(5, dispatchedActions.size, "Expected 5 actions: Execute, Resolve, Success, Load, LoadSuccess")
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ResolveActionInMessage>(dispatchedActions[1])
        assertIs<AppAction.ExecuteActionManifestSuccess>(dispatchedActions[2])
        assertIs<AppAction.LoadGraph>(dispatchedActions[3])
        assertIs<AppAction.LoadGraphSuccess>(dispatchedActions[4])
        assertEquals(manifest, fakeActionExecutor.lastExecutedManifest)
    }

    @Test
    fun `executeActionFromMessage failure path dispatches failure action`() = runTest {
        val manifest = listOf<Action>(CreateFile("test.txt", "content", "Create test file"))
        val actionMessage = ChatMessage(
            author = Author.AI, timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = manifest, isResolved = false))
        )
        val initialState = AppState(chatHistory = listOf(actionMessage))
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Failure("File not found.")

        stateManager.executeActionFromMessage(12345L)

        val dispatchedActions = store.dispatchedActions
        assertEquals(2, dispatchedActions.size)
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ExecuteActionManifestFailure>(dispatchedActions[1])
        assertEquals("File not found.", (dispatchedActions[1] as AppAction.ExecuteActionManifestFailure).error)
    }
}