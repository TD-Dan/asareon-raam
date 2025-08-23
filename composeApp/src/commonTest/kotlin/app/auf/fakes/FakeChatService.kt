// --- FILE: composeApp/src/commonTest/kotlin/app/auf/fakes/FakeChatService.kt ---
package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.core.Store
import app.auf.service.ChatService
import app.auf.service.GatewayService
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope

class FakeChatService(
    store: Store,
    gatewayService: GatewayService,
    platform: PlatformDependencies,
    coroutineScope: CoroutineScope
) : ChatService(store, gatewayService, platform, coroutineScope) {

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