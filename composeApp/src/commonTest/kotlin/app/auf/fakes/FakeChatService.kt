package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.core.Store
import app.auf.service.AufTextParser
import app.auf.service.ChatService
import app.auf.service.GatewayService
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope

class FakeChatService(
    store: Store,
    gatewayService: GatewayService,
    platform: PlatformDependencies,
    parser: AufTextParser, // <<< FIX: Added the parser parameter
    coroutineScope: CoroutineScope
) : ChatService(store, gatewayService, platform, parser, coroutineScope) { // <<< FIX: Pass parser to super()

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