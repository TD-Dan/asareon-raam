package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.core.Store
import app.auf.model.ToolDefinition
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.GatewayService
import app.auf.service.PromptCompiler
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope

class FakeChatService(
    store: Store,
    gatewayService: GatewayService,
    platform: PlatformDependencies,
    parser: AufTextParser,
    toolRegistry: List<ToolDefinition>,
    promptCompiler: PromptCompiler, // <<< ADDED
    coroutineScope: CoroutineScope
) : ChatService(store, gatewayService, platform, parser, toolRegistry, promptCompiler, coroutineScope) { // <<< ADDED

    var sendMessageCalled = false
    var cancelMessageCalled = false

    override fun sendMessage() {
        sendMessageCalled = true
    }

    override fun cancelMessage() {
        cancelMessageCalled = true
    }

    override fun buildSystemContextMessages(): List<ChatMessage> {
        return emptyList()
    }

    override fun buildFullPromptAsString(): String {
        return "Fake Prompt"
    }
}