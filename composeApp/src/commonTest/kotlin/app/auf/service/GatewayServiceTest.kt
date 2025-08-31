package app.auf.service

import app.auf.core.ChatMessage
import app.auf.core.TextBlock
import app.auf.fakes.FakeAufTextParser
import app.auf.fakes.FakeGateway
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayServiceTest {

    @BeforeTest
    fun initializeFactory() {
        ChatMessage.Factory.initialize(FakePlatformDependencies(), FakeAufTextParser())
    }

    private fun setupTestEnvironment(scope: TestScope): Triple<GatewayService, FakeGateway, FakeAufTextParser> {
        val fakeGateway = FakeGateway()
        val parser = FakeAufTextParser()
        val service = GatewayService(fakeGateway, parser, "fake-api-key", scope)
        return Triple(service, fakeGateway, parser)
    }

    @Test
    fun `sendMessage success path parses response correctly`() = runTest {
        // ARRANGE
        val (service, fakeGateway, fakeParser) = setupTestEnvironment(this)
        val aiRawResponse = "Hello, this is a test."
        fakeGateway.nextResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content("model", listOf(Part(aiRawResponse))))
            ),
            usageMetadata = UsageMetadata(10, 5, 15)
        )
        fakeParser.nextParseResult = listOf(TextBlock("Hello, this is a test."))
        val messages = listOf(ChatMessage.Factory.createUser(rawContent = "Hi"))

        // ACT
        val result = service.sendMessage("test-model", messages)

        // ASSERT
        assertNotNull(result)
        assertEquals(1, result.contentBlocks.size)
        assertIs<TextBlock>(result.contentBlocks[0])
        assertEquals("Hello, this is a test.", (result.contentBlocks[0] as TextBlock).text)
        assertEquals(aiRawResponse, result.rawContent)
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
        val messages = listOf(ChatMessage.Factory.createUser(rawContent = "Hi"))

        // ACT
        val result = service.sendMessage("test-model", messages)

        // ASSERT
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage.contains("API Error: Internal Server Error"))
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

    @Test
    fun `sendMessage correctly merges consecutive messages before sending to gateway`() = runTest {
        // ARRANGE
        val (service, fakeGateway, _) = setupTestEnvironment(this)
        val messages = listOf(
            ChatMessage.Factory.createSystem(title = "file1.txt", rawContent = "System prompt."),
            ChatMessage.Factory.createUser(rawContent = "First user message."),
            ChatMessage.Factory.createUser(rawContent = "Second user message."),
            ChatMessage.Factory.createAi(rawContent = "AI response.", usageMetadata = null),
            ChatMessage.Factory.createUser(rawContent = "Third user message.")
        )

        // ACT
        service.sendMessage("test-model", messages)
        val sentToGateway = fakeGateway.lastRequest?.contents

        // ASSERT
        assertNotNull(sentToGateway)
        assertEquals(3, sentToGateway.size, "Should be 3 content blocks after merging SYSTEM and USER roles.")

        assertEquals("user", sentToGateway[0].role)
        val expectedMergedText = "--- START OF FILE file1.txt ---\nSystem prompt.\n\nFirst user message.\n\nSecond user message."
        assertEquals(expectedMergedText, sentToGateway[0].parts[0].text)

        assertEquals("model", sentToGateway[1].role)
        assertEquals("AI response.", sentToGateway[1].parts[0].text)

        assertEquals("user", sentToGateway[2].role)
        assertEquals("Third user message.", sentToGateway[2].parts[0].text)
    }
}