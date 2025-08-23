package app.auf.service

import app.auf.util.JsonProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fake implementation of the Gateway for testing purposes.
 * It allows us to control the output of `listModels` to test the manager's logic.
 */
class FakeGateway : Gateway(JsonProvider.appJson) {
    var modelsToReturn: List<ModelInfo> = emptyList()
    // We only override the method we need for this test suite.
    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        return modelsToReturn
    }
}

/**
 * Unit tests for the GatewayService.
 *
 * ---
 * ## Mandate
 * This suite verifies the business logic of the GatewayService, particularly its ability
 * to correctly filter and format the list of available AI models based on their
 * declared capabilities, not on their names.
 *
 * ---
 * ## Test Strategy
 * - **Arrange:** A `FakeGateway` is instantiated and configured to return a specific, predefined list of `ModelInfo` objects for each test scenario.
 * - **Act:** The `gatewayService.listTextModels()` method is called.
 * - **Assert:** Verify that the returned list of strings is correctly filtered (only includes models supporting "generateContent"), formatted (the "models/" prefix is removed), and sorted alphabetically.
 *
 * @version 1.2
 * @since 2025-08-17
 */
class GatewayServiceTest {

    // <<< MODIFIED: Removed @BeforeTest and class-level properties for services.
    // Instantiation will happen inside each test to correctly capture the TestScope.

    @Test
    fun `listTextModels should return only models supporting generateContent and be sorted`() = runTest {
        // Arrange
        val fakeGateway = FakeGateway()
        val aufTextParser = AufTextParser(JsonProvider.appJson)
        // <<< MODIFIED: Pass 'this' (the TestScope) to the constructor.
        val gatewayService = GatewayService(fakeGateway, aufTextParser, "dummy-api-key", this)

        fakeGateway.modelsToReturn = listOf(
            ModelInfo(name = "models/gemini-1.5-pro-latest", supportedGenerationMethods = listOf("generateContent")),
            ModelInfo(name = "models/embedding-001", supportedGenerationMethods = listOf("embedContent")),
            ModelInfo(name = "models/aqa", supportedGenerationMethods = listOf("generateAnswer")),
            ModelInfo(name = "models/gemma-7b", supportedGenerationMethods = listOf("generateContent", "otherMethod")),
            ModelInfo(name = "models/gemini-1.0-pro", supportedGenerationMethods = listOf("generateContent"))
        )

        val expected = listOf(
            "gemini-1.0-pro",
            "gemini-1.5-pro-latest",
            "gemma-7b"
        )

        // Act
        val actual = gatewayService.listTextModels()

        // Assert
        assertEquals(expected, actual, "The list should be filtered by capability, have its prefix removed, and be sorted alphabetically.")
    }

    @Test
    fun `listTextModels should return an empty list when no models support generateContent`() = runTest {
        // Arrange
        val fakeGateway = FakeGateway()
        val aufTextParser = AufTextParser(JsonProvider.appJson)
        val gatewayService = GatewayService(fakeGateway, aufTextParser, "dummy-api-key", this)

        fakeGateway.modelsToReturn = listOf(
            ModelInfo(name = "models/embedding-001", supportedGenerationMethods = listOf("embedContent")),
            ModelInfo(name = "models/aqa", supportedGenerationMethods = listOf("generateAnswer"))
        )

        // Act
        val actual = gatewayService.listTextModels()

        // Assert
        assertTrue(actual.isEmpty(), "The list should be empty if no models match the capability.")
    }

    @Test
    fun `listTextModels should return an empty list when the gateway returns an empty list`() = runTest {
        // Arrange
        val fakeGateway = FakeGateway()
        val aufTextParser = AufTextParser(JsonProvider.appJson)
        val gatewayService = GatewayService(fakeGateway, aufTextParser, "dummy-api-key", this)

        fakeGateway.modelsToReturn = emptyList()

        // Act
        val actual = gatewayService.listTextModels()

        // Assert
        assertTrue(actual.isEmpty(), "The list should be empty if the gateway provides no models.")
    }



    @Test
    fun `listTextModels should handle models with empty supportedGenerationMethods lists`() = runTest {
        // Arrange
        val fakeGateway = FakeGateway()
        val aufTextParser = AufTextParser(JsonProvider.appJson)
        val gatewayService = GatewayService(fakeGateway, aufTextParser, "dummy-api-key", this)

        fakeGateway.modelsToReturn = listOf(
            ModelInfo(name = "models/gemini-1.5-pro-latest", supportedGenerationMethods = listOf("generateContent")),
            ModelInfo(name = "models/legacy-model", supportedGenerationMethods = emptyList())
        )
        val expected = listOf("gemini-1.5-pro-latest")

        // Act
        val actual = gatewayService.listTextModels()

        // Assert
        assertEquals(expected, actual, "Models with no supported methods should be filtered out.")
    }
}