package app.auf.fakes

import app.auf.service.Candidate
import app.auf.service.Content
import app.auf.service.GenerateContentRequest
import app.auf.service.GenerateContentResponse
import app.auf.service.Gateway
import app.auf.service.ModelInfo
import app.auf.service.Part
import app.auf.util.JsonProvider
import kotlinx.serialization.json.Json

/**
 * A fake implementation of the Gateway for use in unit tests.
 * This allows us to control the raw API responses without making real network calls.
 */
class FakeGateway(
    jsonParser: Json = JsonProvider.appJson
) : Gateway(jsonParser) {

    // --- Test Configuration ---
    var nextResponse: GenerateContentResponse = GenerateContentResponse(
        candidates = listOf(Candidate(content = Content("model", listOf(Part("Fake response")))))
    )
    var modelsResponse: List<ModelInfo> = emptyList()

    // --- Test Inspection ---
    var generateContentCallCount = 0
    var lastRequest: GenerateContentRequest? = null


    override suspend fun generateContent(apiKey: String, model: String, contents: List<Content>): GenerateContentResponse {
        generateContentCallCount++
        lastRequest = GenerateContentRequest(contents)
        return nextResponse
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        return modelsResponse
    }
}