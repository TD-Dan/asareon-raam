// --- FILE: composeApp/src/commonTest/kotlin/app/auf/fakes/FakeGatewayService.kt ---
package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.service.AufTextParser
import app.auf.service.Gateway
import app.auf.service.GatewayService
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope

// <<< MODIFIED: Updated constructor to accept CoroutineScope
class FakeGatewayService(coroutineScope: CoroutineScope) : GatewayService(
    gateway = Gateway(JsonProvider.appJson),
    parser = AufTextParser(JsonProvider.appJson),
    apiKey = "fake-api-key",
    coroutineScope = coroutineScope // <<< MODIFIED: Pass the scope to the super constructor
) {
    var sendMessageCalledWith: List<ChatMessage>? = null
    var nextResponse = GatewayResponse()

    override suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): GatewayResponse {
        sendMessageCalledWith = messages
        return nextResponse
    }

    override suspend fun listTextModels(): List<String> {
        return listOf("fake-gemini-pro", "fake-gemma-2")
    }
}