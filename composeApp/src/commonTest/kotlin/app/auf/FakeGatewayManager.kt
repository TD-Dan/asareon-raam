package app.auf

import kotlinx.serialization.json.Json

/**
 * A fake, in-memory implementation of GatewayManager for use in tests.
 *
 * ---
 * ## Mandate
 * This class inherits from the real `GatewayManager` to satisfy type constraints,
 * but overrides its core methods to prevent real network calls. It provides
 * controllable responses (`fakeResponse`) and spies (`sendMessageCalledWith`)
 * to allow tests to verify interactions with the gateway layer.
 *
 * ---
 * ## Dependencies
 * - `app.auf.GatewayManager` (the class it inherits from)
 *
 * @version 1.1
 * @since 2025-08-14
 */
class FakeGatewayManager : GatewayManager(
    // Provide dummy values for the real constructor.
    apiKey = "fake-api-key",
    jsonParser = JsonProvider.appJson // Use the canonical parser
) {
    // Spy property to inspect what the test called the method with.
    var sendMessageCalledWith: Pair<String, List<ChatMessage>>? = null

    // Control property to dictate the fake's behavior.
    var fakeResponse: AIResponse = AIResponse(
        contentBlocks = listOf(TextBlock("Default fake response")),
        rawContent = "Default fake response"
    )

    /**
     * Overrides the real network call. It records the input and returns the
     * pre-configured `fakeResponse`.
     */
    override suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): AIResponse {
        sendMessageCalledWith = selectedModel to messages
        return fakeResponse
    }

    /**
     * Overrides the real network call to return a static list of fake models,
     * now adhering to the hardened, nullable-version data contract.
     */
    override suspend fun listModels(): List<ModelInfo> {
        return listOf(
            ModelInfo(
                name = "models/fake-model-1",
                displayName = "Fake Model 1",
                version = "1.0.0" // A model *with* a version
            ),
            ModelInfo(
                name = "models/fake-model-2",
                displayName = "Fake Model 2",
                version = null // A model *without* a version
            )
        )
    }
}