package app.auf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A fake, in-memory implementation of GatewayManager for use in tests.
 * It avoids real network calls and provides controllable responses.
 */
class FakeGatewayManager : GatewayManager {
    override val availableModels: StateFlow<List<String>> =
        MutableStateFlow(listOf("test-model-1", "test-model-2"))

    var sendChatRequestCalledWith: String? = null
    var shouldReturnError = false

    override suspend fun sendChatRequest(
        prompt: String,
        history: List<ChatMessage>,
        config: GeminiConfig
    ): Result<String> {
        sendChatRequestCalledWith = prompt
        return if (shouldReturnError) {
            Result.failure(Exception("Fake network error"))
        } else {
            Result.success("Fake AI response")
        }
    }
}