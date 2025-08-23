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
        val actionExecutor = FakeActionExecutor(platform, chatService.jsonParser)
        val importExportManager = FakeImportExportManager(platform, chatService.jsonParser)
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
        // GIVEN a message with an unresolved ActionBlock and a successful ActionExecutor
        val manifest = listOf(CreateFile("test.txt", "content", "Create test file"))
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = manifest, isResolved = false))
        )
        val initialState = AppState(chatHistory = listOf(actionMessage))
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Success("Manifest executed.")
        val graphService = (stateManager.graphService as FakeGraphService) // Cast to access spy property

        // WHEN executeActionFromMessage is called
        stateManager.executeActionFromMessage(12345L)

        // THEN the correct sequence of actions should be dispatched
        val dispatchedActions = store.dispatchedActions
        assertEquals(4, dispatchedActions.size)
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ResolveActionInMessage>(dispatchedActions[1])
        assertIs<AppAction.ExecuteActionManifestSuccess>(dispatchedActions[2])
        assertIs<AppAction.LoadGraph>(dispatchedActions[3]) // Verifies graph reload is triggered

        // AND the action executor should have been called with the correct manifest
        assertEquals(manifest, fakeActionExecutor.lastExecutedManifest)

        // AND the graph service load function should have been called
        assertTrue(graphService.loadGraphCalled > 0, "loadGraph() should have been called after execution")
    }

    @Test
    fun `executeActionFromMessage failure path dispatches failure action`() = runTest {
        // GIVEN a message with an unresolved ActionBlock and a failing ActionExecutor
        val manifest = listOf<Action>(CreateFile("test.txt", "content", "Create test file"))
        val actionMessage = ChatMessage(
            author = Author.AI,
            timestamp = 12345L,
            contentBlocks = listOf(ActionBlock(actions = manifest, isResolved = false))
        )
        val initialState = AppState(chatHistory = listOf(actionMessage))
        val (stateManager, store, fakeActionExecutor) = setupTestEnvironment(initialState, this)
        fakeActionExecutor.nextResult = ActionExecutorResult.Failure("File not found.")

        // WHEN executeActionFromMessage is called
        stateManager.executeActionFromMessage(12345L)

        // THEN the correct failure action should be dispatched
        val dispatchedActions = store.dispatchedActions
        assertEquals(2, dispatchedActions.size)
        assertIs<AppAction.ExecuteActionManifest>(dispatchedActions[0])
        assertIs<AppAction.ExecuteActionManifestFailure>(dispatchedActions[1])
        assertEquals("File not found.", (dispatchedActions[1] as AppAction.ExecuteActionManifestFailure).error)
    }

    @Test
    fun `rejectActionFromMessage should resolve the block and show a toast`() = runTest {
        // GIVEN an initial state
        val (stateManager, store, _) = setupTestEnvironment(scope = this)

        // WHEN rejectActionFromMessage is called
        stateManager.rejectActionFromMessage(12345L)

        // THEN a Resolve action and a ShowToast action are dispatched
        val dispatchedActions = store.dispatchedActions
        assertEquals(2, dispatchedActions.size)
        assertIs<AppAction.ResolveActionInMessage>(dispatchedActions[0])
        assertEquals(12345L, (dispatchedActions[0] as AppAction.ResolveActionInMessage).messageTimestamp)
        assertIs<AppAction.ShowToast>(dispatchedActions[1])
        assertEquals("Action Manifest Rejected.", (dispatchedActions[1] as AppAction.ShowToast).message)
    }
}