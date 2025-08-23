// --- FILE: composeApp/src/commonTest/kotlin/app/auf/fakes/FakeGatewayService.kt ---
package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.core.GatewayResponse
import app.auf.service.AufTextParser
import app.auf.service.Gateway
import app.auf.service.GatewayService
import app.auf.util.JsonProvider

class FakeGatewayService : GatewayService(
    gateway = Gateway(JsonProvider.appJson),
    // --- MODIFICATION: Provide the AufTextParser the service now requires ---
    parser = AufTextParser(JsonProvider.appJson),
    apiKey = "fake-api-key"
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