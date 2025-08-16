// FILE: composeApp/src/commonTest/kotlin/app/auf/GatewayManagerTest.kt
package app.auf

import app.auf.core.ActionBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.FileContentBlock
import app.auf.core.TextBlock
import app.auf.model.CreateFile
import app.auf.service.ApiError
import app.auf.service.Candidate
import app.auf.service.Content
import app.auf.service.GatewayManager
import app.auf.service.GenerateContentResponse
import app.auf.service.Part
import app.auf.util.JsonProvider
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the GatewayManager.
 *
 * ---
 * ## Mandate
 * This suite verifies the business logic of the GatewayManager, focusing on its
 * two primary responsibilities:
 * 1. Correctly parsing raw, unpredictable AI string responses into a structured
 *    list of `ContentBlock`s.
 * 2. Correctly formatting our internal `ChatMessage` list into the data contract
 *    required by the external API.
 *
 * ---
 * ## Test Strategy
 * - A `FakeGateway` is injected to provide controlled, raw API responses without
 *   making any real network calls.
 * - The `sendMessage` method is called on the real `GatewayManager`.
 * - The returned `AIResponse` object is inspected to verify that the parsing logic
 *   behaved as expected.
 *
 * @version 1.2
 * @since 2025-08-16
 */
class GatewayManagerTest {

    private lateinit var fakeGateway: FakeGateway
    private lateinit var gatewayManager: GatewayManager
    private val jsonParser = JsonProvider.appJson

    @BeforeTest
    fun setup() {
        fakeGateway = FakeGateway()
        // The real GatewayManager is instantiated with the fake dependency
        gatewayManager = GatewayManager(
            gateway = fakeGateway,
            jsonParser = jsonParser,
            apiKey = "fake-api-key"
        )
    }

    @Test
    fun `sendMessage with plain text response should parse into a single TextBlock`() = runTest {
        // Arrange
        val rawText = "This is a simple text response from the AI."
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(1, response.contentBlocks.size)
        val block = response.contentBlocks.first()
        assertIs<TextBlock>(block, "The block should be a TextBlock.")
        assertEquals(rawText, block.text)
    }

    @Test
    fun `sendMessage with a valid action manifest should parse correctly`() = runTest {
        // Arrange
        val rawText = """
            [AUF_ACTION_MANIFEST]
            ```json
            [
              {
                "type": "CreateFile",
                "filePath": "test.txt",
                "content": "hello",
                "summary": "Create test file"
              }
            ]
            ```
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(1, response.contentBlocks.size)
        val block = response.contentBlocks.first()
        assertIs<ActionBlock>(block, "The block should be an ActionBlock.")
        assertEquals(1, block.actions.size)
        val action = block.actions.first()
        assertIs<CreateFile>(action)
        assertEquals("test.txt", action.filePath)
    }

    @Test
    fun `sendMessage with mixed content should parse into multiple blocks in correct order`() = runTest {
        // Arrange
        val rawText = """
            Here is the file you requested:
            [AUF_FILE_VIEW: code.kt]
            val x = 1
            [/AUF_FILE_VIEW]
            Please review it.
        """.trimIndent()
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(3, response.contentBlocks.size)
        assertIs<TextBlock>(response.contentBlocks[0])
        assertEquals("Here is the file you requested:", (response.contentBlocks[0] as TextBlock).text)

        assertIs<FileContentBlock>(response.contentBlocks[1])
        assertEquals("code.kt", (response.contentBlocks[1] as FileContentBlock).fileName)

        assertIs<TextBlock>(response.contentBlocks[2])
        assertEquals("Please review it.", (response.contentBlocks[2] as TextBlock).text)
    }

    @Test
    fun `sendMessage with a malformed JSON block should produce a graceful error block`() = runTest {
        // Arrange
        val rawText = """
            [AUF_ACTION_MANIFEST]
            [
              { "type": "CreateFile", "filePath": "test.txt" } // Missing comma
              { "type": "UpdateHolonContent", "holonId": "123" }
            ]
            [/AUF_ACTION_MANIFEST]
        """.trimIndent()
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(1, response.contentBlocks.size)
        val block = response.contentBlocks.first()
        assertIs<TextBlock>(block, "A parsing failure should result in a TextBlock.")
        assertTrue(block.text.contains("--- ERROR PARSING BLOCK ---"), "The error block should contain the error signature.")
        assertTrue(block.text.contains("AUF_ACTION_MANIFEST"), "The error block should identify the failing tag.")
    }

    @Test
    fun `convertChatToApiContents should merge consecutive messages from the same author`() = runTest {
        // Arrange
        val chatMessages = listOf(
            ChatMessage(Author.USER, "Prompt 1", contentBlocks = listOf(TextBlock("Hello."))),
            ChatMessage(Author.USER, "Prompt 2", contentBlocks = listOf(TextBlock("How are you?"))),
            ChatMessage(Author.AI, "Response 1", contentBlocks = listOf(TextBlock("I am well."))),
            ChatMessage(Author.USER, "Prompt 3", contentBlocks = listOf(TextBlock("Good.")))
        )

        // Act
        gatewayManager.sendMessage("test-model", chatMessages)
        val apiRequest = fakeGateway.generateContentCalledWith

        // Assert
        assertNotNull(apiRequest, "The fake gateway should have been called.")
        assertEquals(3, apiRequest.contents.size, "Should be 3 API content blocks (user, model, user).")
        assertEquals("user", apiRequest.contents[0].role)
        assertTrue(apiRequest.contents[0].parts.first().text.contains("Hello.\n\nHow are you?"))
        assertEquals("model", apiRequest.contents[1].role)
        assertEquals("user", apiRequest.contents[2].role)
    }

    @Test
    fun `convertChatToApiContents should handle system messages correctly`() = runTest {
        // Arrange
        val chatMessages = listOf(
            ChatMessage(Author.SYSTEM, "protocol.md", contentBlocks = listOf(TextBlock("System rule 1."))),
            ChatMessage(Author.USER, "User Prompt", contentBlocks = listOf(TextBlock("Do the thing.")))
        )

        // Act
        gatewayManager.sendMessage("test-model", chatMessages)
        val apiRequest = fakeGateway.generateContentCalledWith

        // Assert
        assertNotNull(apiRequest, "The fake gateway should have been called.")
        // System messages are converted to the "user" role for the API, and consecutive user roles are merged.
        assertEquals(1, apiRequest.contents.size)
        assertEquals("user", apiRequest.contents[0].role)
        val mergedText = apiRequest.contents[0].parts.first().text
        assertTrue(mergedText.startsWith("--- START OF FILE protocol.md ---"))
        assertTrue(mergedText.contains("System rule 1."))
        assertTrue(mergedText.endsWith("Do the thing."))
    }

    @Test
    fun `sendMessage with excessive whitespace should parse correctly`() = runTest {
        // Arrange
        val rawText = """


            Here is some text.

            [AUF_ACTION_MANIFEST]


            [
                {
                    "type": "CreateFile",
                    "filePath": "a.txt",
                    "content": "b",
                    "summary": "c"
                }
            ]


            [/AUF_ACTION_MANIFEST]


            And some trailing text.

        """.trimIndent()
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(3, response.contentBlocks.size, "Should parse three distinct blocks.")
        assertIs<TextBlock>(response.contentBlocks[0])
        assertIs<ActionBlock>(response.contentBlocks[1])
        assertIs<TextBlock>(response.contentBlocks[2])
        assertEquals(1, (response.contentBlocks[1] as ActionBlock).actions.size)
    }

    @Test
    fun `sendMessage with unclosed tag should treat entire content as plain text`() = runTest {
        // This is a critical robustness test. A malformed tag should not crash the parser.
        // Arrange
        val rawText = """
            Here is a manifest that is missing its closing tag.
            [AUF_ACTION_MANIFEST]
            [ { "type": "CreateFile", "filePath": "test.txt" } ]
            And some more text.
        """.trimIndent()
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(1, response.contentBlocks.size, "Should parse as a single text block.")
        assertIs<TextBlock>(response.contentBlocks[0])
        assertEquals(rawText, (response.contentBlocks[0] as TextBlock).text, "The content should be the entire raw string.")
    }

    @Test
    fun `sendMessage with case-sensitive tags should ignore lowercase tags`() = runTest {
        // Arrange
        val rawText = "Text with [auf_action_manifest]...[/auf_action_manifest] should be ignored."
        fakeGateway.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(Content("model", listOf(Part(rawText)))))
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertEquals(1, response.contentBlocks.size)
        assertIs<TextBlock>(response.contentBlocks[0])
        assertEquals(rawText, (response.contentBlocks[0] as TextBlock).text)
    }

    @Test
    fun `sendMessage with API error should return a response with an error message`() = runTest {
        // Arrange
        fakeGateway.responseToReturn = GenerateContentResponse(
            error = ApiError(400, "Invalid API Key", "INVALID_ARGUMENT")
        )

        // Act
        val response = gatewayManager.sendMessage("test-model", emptyList())

        // Assert
        assertNotNull(response.errorMessage, "Error message should not be null.")
        assertTrue(response.errorMessage!!.contains("API Error: Invalid API Key"), "Error message should contain the API error.")
        assertTrue(response.contentBlocks.isEmpty(), "Content blocks should be empty on API error.")
    }
}