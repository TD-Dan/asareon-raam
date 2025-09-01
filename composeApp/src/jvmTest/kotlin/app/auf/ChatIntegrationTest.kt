package app.auf

import app.auf.core.*
import app.auf.fakes.FakeGatewayService
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.feature.knowledgegraph.Holon
import app.auf.feature.knowledgegraph.HolonHeader
import app.auf.feature.knowledgegraph.KnowledgeGraphAction
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.model.SettingValue
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.PromptCompiler
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ## Mandate
 * This test suite focuses on integration-level scenarios within the chat functionality,
 * particularly on the prompt compilation pipeline and the interaction between the
 * ChatService, the KnowledgeGraphFeature, and the GatewayService.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIntegrationTest {

    private fun setupTestEnvironment(initialState: AppState = AppState()) = runTest {
        val platform = FakePlatformDependencies()
        val sessionManager = fakes.FakeSessionManager(platform)
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
        // Start feature lifecycles to trigger initial graph load etc.
        store.startFeatureLifecycles()
        runCurrent()

        // Return all the components needed for the tests
        object {
            val store = store
            val chatService = chatService
            val gatewayService = gatewayService
        }
    }


    @Test
    fun `system messages are compiled based on settings before being sent`() = runTest {
        // ARRANGE
        val testEnv = setupTestEnvironment()
        val store = testEnv.store
        val chatService = testEnv.chatService

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

        // Set up the knowledge graph state
        store.dispatch(KnowledgeGraphAction.LoadGraphSuccess(GraphLoadResult(holonGraph = listOf(holon))))
        store.dispatch(KnowledgeGraphAction.ToggleHolonActive("test-holon-1"))
        store.dispatch(KnowledgeGraphAction.SelectAiPersona(holon.header.id))

        // Update compiler settings via core AppActions
        store.dispatch(AppAction.UpdateSetting(SettingValue("compiler.cleanHeaders", true)))
        store.dispatch(AppAction.UpdateSetting(SettingValue("compiler.minifyJson", true)))
        store.dispatch(AppAction.UpdateSetting(SettingValue("compiler.removeWhitespace", true)))
        advanceUntilIdle()

        // ACT
        chatService.sendMessage()
        advanceUntilIdle()

        // ASSERT
        val systemMessagesSentToChatService = chatService.buildSystemContextMessages()
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

    @Test
    fun `buildFullPromptAsString uses compiled content for system messages and raw for others`() = runTest {
        // ARRANGE
        val testEnv = setupTestEnvironment()
        val store = testEnv.store
        val chatService = testEnv.chatService

        // Set up state
        store.dispatch(AppAction.UpdateSetting(SettingValue("compiler.minifyJson", true)))
        val userMessage = "User query."
        val aiResponse = "AI response."
        val systemMessageRaw = """
        {
            "header": { "id": "sys-holon" },
            "payload": {}
        }
        """.trimIndent()
        val expectedSystemCompiled = """{"header":{"id":"sys-holon"},"payload":{}}"""

        store.dispatch(AppAction.AddUserMessage(userMessage))
        store.dispatch(AppAction.SendMessageSuccess(GatewayResponse(rawContent = aiResponse)))
        store.dispatch(AppAction.AddSystemMessage("sys-holon.json", systemMessageRaw))
        advanceUntilIdle()

        // ACT
        val fullPrompt = chatService.buildFullPromptAsString()

        // ASSERT
        assertTrue(fullPrompt.contains(expectedSystemCompiled), "Prompt should contain the compiled system message.")
        assertFalse(fullPrompt.contains(""""header": {"""), "Prompt should NOT contain the pretty-printed system message.")
        assertTrue(fullPrompt.contains("--- USER MESSAGE ---\n$userMessage"), "Prompt should contain raw user message.")
        assertTrue(fullPrompt.contains("--- model MESSAGE ---\n$aiResponse"), "Prompt should contain raw AI message.")
    }
}