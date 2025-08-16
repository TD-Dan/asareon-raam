// FILE: composeApp/src/commonTest/kotlin/app/auf/FakeGateway.kt
package app.auf

import app.auf.service.Content
import app.auf.service.Gateway
import app.auf.service.GenerateContentRequest
import app.auf.service.GenerateContentResponse
import app.auf.service.ModelInfo
import app.auf.util.JsonProvider
import kotlinx.serialization.json.Json

/**
 * A fake, in-memory implementation of the Gateway for use in tests.
 *
 * ---
 * ## Mandate
 * This class inherits from the real `Gateway` to satisfy type constraints,
 * but overrides its core methods to prevent real network calls. It provides
 * a controllable response (`responseToReturn`) and spies to allow tests
 * to verify interactions with the network layer.
 *
 * ---
 * ## Dependencies
 * - `app.auf.Gateway` (the class it inherits from)
 *
 * @version 1.0
 * @since 2025-08-16
 */
class FakeGateway(
    jsonParser: Json = JsonProvider.appJson
) : Gateway(jsonParser) {

    // Control property to dictate the fake's behavior.
    var responseToReturn: GenerateContentResponse = GenerateContentResponse()

    // Spy property to inspect what the test called the method with.
    var generateContentCalledWith: GenerateContentRequest? = null

    /**
     * Overrides the real network call. It records the input and returns the
     * pre-configured `responseToReturn`.
     */
    override suspend fun generateContent(
        apiKey: String,
        model: String,
        contents: List<Content>
    ): GenerateContentResponse {
        generateContentCalledWith = GenerateContentRequest(contents)
        return responseToReturn
    }

    /**
     * Overrides the real network call to return a static list of fake models.
     */
    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        return listOf(
            ModelInfo(name = "models/fake-model-pro", displayName = "Fake Pro"),
            ModelInfo(name = "models/fake-model-flash", displayName = "Fake Flash")
        )
    }
}