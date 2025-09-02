package app.auf.core

import app.auf.fakes.*
import app.auf.feature.knowledgegraph.KnowledgeGraphAction
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode
import app.auf.feature.systemclock.ClockAction
import app.auf.service.*
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ## Mandate
 * Verifies the StateManager's role as a thin orchestrator. As of v2.0, this class
 * should contain no business logic. Its sole responsibility is to receive UI events
 * and dispatch the correct AppActions to the central Store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerTest {

    private fun setupTestEnvironment(
        initialState: AppState = AppState(),
        scope: TestScope
    ): Triple<StateManager, FakeStore, FakeChatService> {
        val platform = FakePlatformDependencies()
        platform.writeFileContent(platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md", "protocol")

        val store = FakeStore(initialState, scope)
        val parser = AufTextParser()
        ChatMessage.Factory.initialize(platform, parser)

        val gatewayService = FakeGatewayService(scope)
        val chatService = FakeChatService(store, gatewayService, platform, parser, PromptCompiler(JsonProvider.appJson), scope)
        val sourceCodeService = FakeSourceCodeService(platform)
        val settingsManager = SettingsManager(platform, JsonProvider.appJson)


        val stateManager = StateManager(
            store = store,
            backupManager = FakeBackupManager(platform),
            sourceCodeService = sourceCodeService,
            chatService = chatService,
            gatewayService = gatewayService,
            parser = parser,
            settingsManager = settingsManager,
            platform = platform,
            coroutineScope = scope
        )
        return Triple(stateManager, store, chatService)
    }

    @Test
    fun `sendMessage delegates to ChatService`() = runTest {
        // ARRANGE
        val (stateManager, _, fakeChatService) = setupTestEnvironment(AppState(), this)

        // ACT
        stateManager.sendMessage("Hello")

        // ASSERT
        assertTrue(fakeChatService.sendMessageCalled, "ChatService.sendMessage() should have been called.")
    }

    @Test
    fun `deleteMessage dispatches DeleteMessage action`() = runTest {
        // ARRANGE
        val (stateManager, store, _) = setupTestEnvironment(AppState(), this)

        // ACT
        stateManager.deleteMessage(123L)

        // ASSERT
        val dispatched = store.dispatchedActions.first()
        assertIs<AppAction.DeleteMessage>(dispatched)
        assertEquals(123L, dispatched.id)
    }

    @Test
    fun `selectModel dispatches SelectModel action`() = runTest {
        // ARRANGE
        val (stateManager, store, _) = setupTestEnvironment(AppState(availableModels = listOf("model-A")), this)

        // ACT
        stateManager.selectModel("model-A")

        // ASSERT
        val dispatched = store.dispatchedActions.first()
        assertIs<AppAction.SelectModel>(dispatched)
        assertEquals("model-A", dispatched.modelName)
    }

    @Test
    fun `onHolonClicked dispatches InspectHolon and ToggleHolonActive actions`() = runTest {
        // ARRANGE
        val initialState = AppState(
            featureStates = mapOf("KnowledgeGraphFeature" to app.auf.feature.knowledgegraph.KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.INSPECTOR))
        )
        val (stateManager, store, _) = setupTestEnvironment(initialState, this)

        // ACT
        stateManager.onHolonClicked("holon-1")
        runCurrent()

        // ASSERT
        assertEquals(2, store.dispatchedActions.size)
        val action1 = store.dispatchedActions[0]
        assertIs<KnowledgeGraphAction.InspectHolon>(action1)
        assertEquals("holon-1", action1.holonId)

        val action2 = store.dispatchedActions[1]
        assertIs<KnowledgeGraphAction.ToggleHolonActive>(action2)
        assertEquals("holon-1", action2.holonId)
    }
}