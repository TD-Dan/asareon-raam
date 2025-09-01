package app.auf

import app.auf.core.*
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeSessionManager
import app.auf.fakes.FakeStore
import app.auf.feature.knowledgegraph.GraphLoadResult
import app.auf.feature.knowledgegraph.Holon
import app.auf.feature.knowledgegraph.KnowledgeGraphAction
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.PromptCompiler
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * ## Mandate
 * This integration test verifies the complete, end-to-end flow of a chat interaction.
 * It ensures that the ChatService correctly orchestrates actions and state changes by
 * interacting with the Store, KnowledgeGraph, and GatewayService. It is not concerned
 * with the specifics of prompt compilation, but rather with the integrity of the data flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIntegrationTest {

    private data class TestEnvironment(
        val store: FakeStore,
        val chatService: ChatService,
        val gatewayService: FakeGatewayService
    )

    private fun TestScope.setupTestEnvironment(initialState: AppState = AppState()): TestEnvironment {
        val platform = FakePlatformDependencies()
        val sessionManager = FakeSessionManager(platform)
        val kgFeature = KnowledgeGraphFeature(platform, this)
        val store = FakeStore(initialState, this, sessionManager, listOf(kgFeature))
        val parser = AufTextParser()
        val promptCompiler = PromptCompiler(JsonProvider.appJson)
        val gatewayService = FakeGatewayService(this)
        val chatService = ChatService(store, gatewayService, platform, parser, promptCompiler, this)

        ChatMessage.Factory.initialize(platform, parser)
        platform.writeFileContent(
            platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md",
            "**Protocol**"
        )
        store.startFeatureLifecycles()
        runCurrent() // Allow initial graph load

        // COMMON SETUP: Ensure a persona is loaded and selected for most tests
        val persona = Holon(holonHeader, kotlinx.serialization.json.JsonNull)
        store.dispatch(KnowledgeGraphAction.LoadGraphSuccess(GraphLoadResult(holonGraph = listOf(persona), availableAiPersonas = listOf(holonHeader), determinedPersonaId = holonHeader.id)))
        runCurrent()

        return TestEnvironment(store, chatService, gatewayService)
    }

    private val holonHeader = app.auf.feature.knowledgegraph.HolonHeader("sage-1", "AI_Persona_Root", "Sage", "")

    @Test
    fun `sendMessage happy path dispatches correct actions and updates state`() = runTest {
        // ARRANGE
        val (store, chatService, gatewayService) = setupTestEnvironment()
        gatewayService.nextResponse = GatewayResponse(rawContent = "AI reply")

        // ACT
        chatService.sendMessage()
        advanceUntilIdle()

        // ASSERT
        assertTrue(gatewayService.sendMessageCalledWith != null, "GatewayService.sendMessage should have been called.")
        assertEquals(2, store.dispatchedActions.size, "Expected 2 actions: Loading and Success.")
        assertIs<AppAction.SendMessageLoading>(store.dispatchedActions[0])
        assertIs<AppAction.SendMessageSuccess>(store.dispatchedActions[1])

        val finalState = store.state.value
        assertFalse(finalState.isProcessing, "isProcessing flag should be false after completion.")
        val lastMessage = finalState.chatHistory.last()
        assertEquals(Author.AI, lastMessage.author)
        assertEquals("AI reply", lastMessage.rawContent)
    }

    @Test
    fun `sendMessage error path dispatches failure and updates state with error message`() = runTest {
        // ARRANGE
        val (store, chatService, gatewayService) = setupTestEnvironment()
        gatewayService.nextResponse = GatewayResponse(errorMessage = "API Failure")

        // ACT
        chatService.sendMessage()
        advanceUntilIdle()

        // ASSERT
        assertEquals(2, store.dispatchedActions.size)
        assertIs<AppAction.SendMessageLoading>(store.dispatchedActions[0])
        assertIs<AppAction.SendMessageFailure>(store.dispatchedActions[1])

        val finalState = store.state.value
        assertFalse(finalState.isProcessing)
        val lastMessage = finalState.chatHistory.last()
        assertEquals(Author.SYSTEM, lastMessage.author)
        assertEquals("Gateway Error", lastMessage.title)
        assertEquals("API Failure", lastMessage.rawContent)
    }

    @Test
    fun `sendMessage is blocked if isProcessing is true`() = runTest {
        // ARRANGE
        val initialState = AppState(isProcessing = true)
        val (store, chatService, gatewayService) = setupTestEnvironment(initialState)

        // ACT
        chatService.sendMessage()
        advanceUntilIdle()

        // ASSERT
        assertTrue(gatewayService.sendMessageCalledWith == null, "GatewayService.sendMessage should NOT be called.")
        assertTrue(store.dispatchedActions.isEmpty(), "No actions should be dispatched when processing.")
    }

    @Test
    fun `sendMessage is blocked if no AI persona is selected`() = runTest {
        // ARRANGE
        val (store, chatService, gatewayService) = setupTestEnvironment()
        // Override the common setup by deselecting the persona
        store.dispatch(KnowledgeGraphAction.SelectAiPersona(null))
        runCurrent()

        // ACT
        chatService.sendMessage()
        advanceUntilIdle()

        // ASSERT
        assertTrue(gatewayService.sendMessageCalledWith == null, "GatewayService.sendMessage should NOT be called.")
        // The SelectAiPersona action will be in the history, so we check that no *new* actions were dispatched.
        assertEquals(1, store.dispatchedActions.size)
        assertIs<KnowledgeGraphAction.SelectAiPersona>(store.dispatchedActions[0])
    }
}