package app.auf.service

import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.TextBlock
import app.auf.fakes.FakeGateway
import app.auf.model.ToolDefinition
import app.auf.util.JsonProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayServiceTest {

    private fun setupTestEnvironment(scope: TestScope): Triple<GatewayService, FakeGateway, List<ToolDefinition>> {
        val fakeGateway = FakeGateway()
        val jsonParser = JsonProvider.appJson

        val toolRegistry = listOf(
            ToolDefinition("Action Manifest", "ACTION_MANIFEST", "", emptyList(), true, "")
        )

        val parser = AufTextParser(jsonParser, toolRegistry)

        val service = GatewayService(fakeGateway, parser, "fake-api-key", scope)

        return Triple(service, fakeGateway, toolRegistry)
    }

    @Test
    fun `sendMessage success path parses response correctly`() = runTest {
        // ARRANGE
        val (service, fakeGateway, _) = setupTestEnvironment(this)
        fakeGateway.nextResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content("model", listOf(Part("Hello, this is a test."))))
            ),
            usageMetadata = UsageMetadata(10, 5, 15)
        )
        val messages = listOf(ChatMessage(Author.USER, contentBlocks = listOf(TextBlock("Hi")), timestamp = 0L))

        // ACT
        val result = service.sendMessage("test-model", messages)

        // ASSERT
        assertNotNull(result)
        assertEquals(1, result.contentBlocks.size)
        assertIs<TextBlock>(result.contentBlocks[0])
        assertEquals("Hello, this is a test.", (result.contentBlocks[0] as TextBlock).text)
        assertEquals(15, result.usageMetadata?.totalTokenCount)
        assertEquals(1, fakeGateway.generateContentCallCount)
    }

    @Test
    fun `sendMessage handles API error gracefully`() = runTest {
        // ARRANGE
        val (service, fakeGateway, _) = setupTestEnvironment(this)
        fakeGateway.nextResponse = GenerateContentResponse(
            error = ApiError(500, "Internal Server Error", "ERROR")
        )
        val messages = listOf(ChatMessage(Author.USER, contentBlocks = listOf(TextBlock("Hi")), timestamp = 0L))

        // ACT
        val result = service.sendMessage("test-model", messages)

        // ASSERT
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("API Error: Internal Server Error"))
    }

    @Test
    fun `listTextModels filters and returns correct model names`() = runTest {
        // ARRANGE
        val (service, fakeGateway, _) = setupTestEnvironment(this)
        fakeGateway.modelsResponse = listOf(
            ModelInfo(name = "models/gemini-1.5-pro", supportedGenerationMethods = listOf("generateContent")),
            ModelInfo(name = "models/text-embedding-004", supportedGenerationMethods = listOf("embedContent")),
            ModelInfo(name = "models/gemini-1.5-flash", supportedGenerationMethods = listOf("generateContent", "countTokens"))
        )

        // ACT
        val result = service.listTextModels()

        // ASSERT
        assertEquals(2, result.size)
        assertEquals("gemini-1.5-flash", result[0]) // Sorted alphabetically
        assertEquals("gemini-1.5-pro", result[1])
    }

    // --- FIX: This test is rewritten to test the public API ---
    @Test
    fun `sendMessage correctly merges consecutive messages before sending to gateway`() = runTest {
        // ARRANGE
        val (service, fakeGateway, _) = setupTestEnvironment(this)
        val messages = listOf(
            ChatMessage(Author.SYSTEM, title = "file1.txt", contentBlocks = listOf(TextBlock("System prompt")), timestamp = 1L),
            ChatMessage(Author.USER, contentBlocks = listOf(TextBlock("First user message.")), timestamp = 2L),
            ChatMessage(Author.USER, contentBlocks = listOf(TextBlock("Second user message.")), timestamp = 3L), // FIX: Added timestamp
            ChatMessage(Author.AI, contentBlocks = listOf(TextBlock("AI response.")), timestamp = 4L),           // FIX: Added timestamp
            ChatMessage(Author.USER, contentBlocks = listOf(TextBlock("Third user message.")), timestamp = 5L)  // FIX: Added timestamp
        )

        // ACT
        service.sendMessage("test-model", messages)
        val sentToGateway = fakeGateway.lastRequest?.contents

        // ASSERT
        assertNotNull(sentToGateway)
        assertEquals(4, sentToGateway.size, "Should be 4 content blocks after merging")

        // Block 1: System
        assertEquals("user", sentToGateway[0].role)
        assertTrue(sentToGateway[0].parts[0].text.contains("System prompt"))

        // Block 2: Merged User Messages
        assertEquals("user", sentToGateway[1].role)
        val expectedMergedText = "First user message.\n\nSecond user message."
        assertEquals(expectedMergedText, sentToGateway[1].parts[0].text)

        // Block 3: AI Message
        assertEquals("model", sentToGateway[2].role)
        assertEquals("AI response.", sentToGateway[2].parts[0].text)

        // Block 4: Final User Message
        assertEquals("user", sentToGateway[3].role)
        assertEquals("Third user message.", sentToGateway[3].parts[0].text)
    }
}