package asareon.raam.feature.gateway

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.gateway.minimax.MiniMaxProvider
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for MiniMaxProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * Key contract differences from other providers:
 *   - API is OpenAI-compatible (/v1/chat/completions, Bearer auth)
 *   - Does NOT include the `name` field per message (like Inception)
 *   - System prompt is injected as a `{"role": "system"}` message
 *   - Error response field is polymorphic: can be a bare string OR an object with a `message` field
 *   - Response may include a separate `reasoning_content` field (MiniMax M2.7).
 *     When present and `<think>` tags are NOT already embedded in `content`, the
 *     parser prepends a `<think>` block so the downstream BlockSeparatingParser
 *     can handle reasoning uniformly across all providers.
 *   - Does NOT expose a /v1/models endpoint — models come from a static KNOWN_MODELS catalogue
 *   - Does NOT override countTokens — returns null (no token-counting endpoint)
 *
 * ARCHITECTURE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 */
class GatewayFeatureT1MiniMaxProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = MiniMaxProvider(platform)

    // -------------------------------------------------------------------------
    // buildRequestPayload
    // -------------------------------------------------------------------------

    @Test
    fun `buildRequestPayload correctly transforms universal request to MiniMax format`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "MiniMax-M2.7")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Hi there")
                })
            })
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload correctly maps model role to assistant`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("model", "Answer", "agent-1", "Agent", 1000L)),
            correlationId = "corr-role"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals("assistant", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload correctly maps user role to user`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Question", "user-1", "User", 1000L)),
            correlationId = "corr-role"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload does NOT include name field - unlike OpenAI`() {
        // ARRANGE: MiniMax does not use OpenAI's name field convention.
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-noname"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertFalse(
            messages[0].jsonObject.containsKey("name"),
            "MiniMax messages should NOT include a 'name' field — that is OpenAI-specific."
        )
    }

    @Test
    fun `buildRequestPayload includes system prompt as a system-role message`() {
        // ARRANGE: MiniMax uses OpenAI-style system messages.
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-sys",
            systemPrompt = "You are a helpful assistant."
        )
        val expected = buildJsonObject {
            put("model", "MiniMax-M2.7")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "You are a helpful assistant.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                })
            })
        }

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(expected, actual)
    }

    @Test
    fun `buildRequestPayload omits system message when no system prompt provided`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-nosys"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Only the user message should be present — no system message.")
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload handles empty contents list`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = emptyList(),
            correlationId = "corr-empty"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(1, messages.size, "Messages array should contain 1 trigger message when contents list is empty.")
        assertEquals("user", messages[0].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload injects SYSTEM_PROMPT_TRIGGER when contents is empty`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = emptyList(),
            correlationId = "corr-trigger"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray
        val triggerContent = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertEquals(MiniMaxProvider.SYSTEM_PROMPT_TRIGGER, triggerContent)
    }

    @Test
    fun `buildRequestPayload handles empty contents with system prompt`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = emptyList(),
            correlationId = "corr-empty-sys",
            systemPrompt = "You are helpful."
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(2, messages.size, "System message and trigger user message should both be present.")
        assertEquals("system", messages[0].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("user", messages[1].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload includes max_tokens when specified`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-max",
            maxOutputTokens = 8192
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(
            "8192",
            actual.jsonObject["max_tokens"]?.toString(),
            "max_tokens should be present with the configured value"
        )
    }

    @Test
    fun `buildRequestPayload omits max_tokens when not specified`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-no-max"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertFalse(
            actual.jsonObject.containsKey("max_tokens"),
            "max_tokens should be omitted when not specified."
        )
    }

    @Test
    fun `buildRequestPayload preserves message ordering`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(
                GatewayMessage("user", "Q1", "u1", "User", 1000L),
                GatewayMessage("model", "A1", "a1", "Agent", 2000L),
                GatewayMessage("user", "Q2", "u1", "User", 3000L),
                GatewayMessage("model", "A2", "a1", "Agent", 4000L)
            ),
            correlationId = "corr-multi"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray

        // ASSERT
        assertNotNull(messages)
        assertEquals(4, messages.size)
        assertEquals("user",      messages[0].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[1].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("user",      messages[2].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("assistant", messages[3].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `buildRequestPayload passes through pre-enriched content unchanged`() {
        // ARRANGE: The pipeline pre-enriches content before sending to providers.
        val enrichedContent = "John Doe (user-123) @ 2024-01-01T00:00:05Z: Test message"
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", enrichedContent, "user-123", "John Doe", 5000L)),
            correlationId = "corr-123"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val content = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(content)
        assertEquals(enrichedContent, content, "Provider should pass through pre-enriched content unchanged")
    }

    // -------------------------------------------------------------------------
    // parseResponse — happy path
    // -------------------------------------------------------------------------

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "MiniMax Response" } }] }"""
        val correlationId = "corr-ok"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("MiniMax Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE
        val responseBody = """{
            "choices": [{ "message": { "role": "assistant", "content": "Response" } }],
            "usage": { "prompt_tokens": 150, "completion_tokens": 40, "total_tokens": 190 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(150, response.inputTokens)
        assertEquals(40, response.outputTokens)
        assertNull(response.errorMessage)
    }

    @Test
    fun `parseResponse logs warning when successful response has no token usage`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Response" } }] }"""
        val correlationId = "corr-no-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "minimax" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
    }

    // -------------------------------------------------------------------------
    // parseResponse — error path (polymorphic error field)
    // -------------------------------------------------------------------------

    @Test
    fun `parseResponse handles API error returned as a bare string`() {
        // ARRANGE: MiniMax may return {"error": "message"} as a bare string.
        val responseBody = """{"error": "Authentication failed"}"""
        val correlationId = "corr-auth-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: Authentication failed", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse handles API error returned as an object with message field`() {
        // ARRANGE: MiniMax may also return {"error": {"message": "..."}} in structured form.
        val responseBody = """{"error": {"message": "Rate limit exceeded", "code": "429"}}"""
        val correlationId = "corr-struct-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: Rate limit exceeded", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse handles API error object without message field gracefully`() {
        // ARRANGE: Defensive — if the error object lacks a `message` key, we fall back to the JSON repr.
        val responseBody = """{"error": {"code": "unknown", "detail": "something"}}"""
        val correlationId = "corr-weird-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertNotNull(response.errorMessage)
        assertTrue(response.errorMessage.startsWith("API Error: "), "Error message should have the standard prefix")
        assertTrue(
            response.errorMessage.contains("code") || response.errorMessage.contains("detail"),
            "Fallback should preserve the raw object contents so the error is diagnosable: ${response.errorMessage}"
        )
    }

    @Test
    fun `parseResponse correctly handles an unrecognised response format`() {
        // ARRANGE
        val responseBody = """{ "some_new_field": "some_value" }"""
        val correlationId = "corr-unknown"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from MiniMax API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "minimax" }
        assertNotNull(log, "An error should be logged for an unrecognised response.")
        assertTrue(log.message.contains(responseBody))
    }

    @Test
    fun `parseResponse handles choice with null content field`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": null } }] }"""
        val correlationId = "corr-null-content"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from MiniMax API.", response.errorMessage)
    }

    // -------------------------------------------------------------------------
    // parseResponse — reasoning_content handling (MiniMax M2.7 specific)
    // -------------------------------------------------------------------------

    @Test
    fun `parseResponse wraps reasoning_content in think tags when content has no think block`() {
        // ARRANGE: MiniMax M2.7 may split reasoning into a separate `reasoning_content` field.
        // The provider should prepend it as a <think> block so BlockSeparatingParser can
        // handle reasoning uniformly across all providers.
        val responseBody = """{
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "The answer is 42.",
                    "reasoning_content": "Let me think about this carefully. The user asked a meaningful question."
                }
            }]
        }"""
        val correlationId = "corr-reasoning"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNotNull(response.rawContent)
        assertTrue(response.rawContent.startsWith("<think>"), "Reasoning should be wrapped in a <think> block at the start")
        assertTrue(response.rawContent.contains("Let me think about this carefully"), "Reasoning text should be preserved inside the block")
        assertTrue(response.rawContent.contains("</think>"), "Reasoning block should be properly closed")
        assertTrue(response.rawContent.contains("The answer is 42."), "Original content should still be present after the reasoning block")
        assertNull(response.errorMessage)
    }

    @Test
    fun `parseResponse does not duplicate think tags when content already contains them`() {
        // ARRANGE: If the model has already embedded <think> tags inline in `content`,
        // the provider should NOT prepend a second block from `reasoning_content` —
        // this would produce duplicate reasoning in the agent's output.
        val responseBody = """{
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "<think>Inline reasoning.</think>\nThe answer is 42.",
                    "reasoning_content": "Structured reasoning that should be ignored."
                }
            }]
        }"""
        val correlationId = "corr-dedup"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNotNull(response.rawContent)
        assertEquals(
            "<think>Inline reasoning.</think>\nThe answer is 42.",
            response.rawContent,
            "When inline <think> is present, reasoning_content should be ignored to avoid duplication."
        )
        assertFalse(
            response.rawContent.contains("Structured reasoning"),
            "The structured reasoning_content should NOT appear when inline <think> is already present."
        )
    }

    @Test
    fun `parseResponse handles missing reasoning_content field gracefully`() {
        // ARRANGE: Most MiniMax responses will lack reasoning_content entirely.
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Plain answer." } }] }"""
        val correlationId = "corr-no-reasoning"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Plain answer.", response.rawContent, "Content should pass through unchanged when reasoning_content is absent.")
        assertFalse(response.rawContent!!.contains("<think>"), "No <think> block should be injected when reasoning_content is absent.")
    }

    @Test
    fun `parseResponse handles blank reasoning_content field gracefully`() {
        // ARRANGE: An empty or blank reasoning_content should be treated as absent.
        val responseBody = """{
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Plain answer.",
                    "reasoning_content": ""
                }
            }]
        }"""
        val correlationId = "corr-blank-reasoning"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Plain answer.", response.rawContent, "Blank reasoning_content should not produce an empty <think> block.")
        assertFalse(response.rawContent!!.contains("<think>"), "No <think> block should be injected for blank reasoning_content.")
    }

    // -------------------------------------------------------------------------
    // generatePreview
    // -------------------------------------------------------------------------

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"model\": \"MiniMax-M2.7\""), "Should contain model name")
        assertTrue(preview.contains("\"messages\": ["), "Should contain messages array")
        assertTrue(preview.contains("\"role\": \"user\""), "Should contain user role")
        assertTrue(preview.contains("Hello"), "Should contain message content")
    }

    @Test
    fun `generatePreview includes system prompt as a system-role message`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123",
            systemPrompt = "You are a pirate."
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"role\": \"system\""), "Should contain system role message")
        assertTrue(preview.contains("You are a pirate."), "Should contain system prompt text")
    }

    // -------------------------------------------------------------------------
    // listAvailableModels — static catalogue behavior
    // -------------------------------------------------------------------------

    @Test
    fun `listAvailableModels returns empty list when API key is not configured`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: Without an API key, the catalogue is still withheld — listing models
        // without auth would suggest they're usable when they aren't.
        val settings = emptyMap<String, String>()

        // ACT
        val models = provider.listAvailableModels(settings)

        // ASSERT
        assertTrue(models.isEmpty(), "Model list should be empty when API key is absent.")
        val warnLog = platform.capturedLogs.find {
            it.level == LogLevel.WARN && it.tag == "minimax" && it.message.contains("API Key is not configured")
        }
        assertNotNull(warnLog, "A warning should be logged when attempting to list models without an API key.")
    }

    @Test
    fun `listAvailableModels returns the static catalogue when API key is configured`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: MiniMax does not expose /v1/models, so the provider returns a static list.
        // We don't assert the exact contents (that's a catalogue-maintenance concern,
        // not a code-correctness concern), only the contract.
        val settings = mapOf("gateway.minimax.apiKey" to "test-key")

        // ACT
        val models = provider.listAvailableModels(settings)

        // ASSERT
        assertTrue(models.isNotEmpty(), "The static catalogue should not be empty.")
        assertTrue(
            models.all { it.id.startsWith("MiniMax") },
            "All catalogued models should use the MiniMax-* naming convention: ${models.map { it.id }}"
        )
        // At least some models should carry a maxOutputTokens value (M2.7 family).
        assertTrue(
            models.any { it.maxOutputTokens != null },
            "At least some catalogued models should declare a maxOutputTokens ceiling."
        )
    }

    @Test
    fun `listAvailableModels returns distinct model IDs`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: Catalogue hygiene — if someone accidentally adds a duplicate, this will catch it.
        val settings = mapOf("gateway.minimax.apiKey" to "test-key")

        // ACT
        val models = provider.listAvailableModels(settings)
        val ids = models.map { it.id }

        // ASSERT
        assertEquals(ids.size, ids.toSet().size, "Catalogued model IDs must be unique: $ids")
    }

    // -------------------------------------------------------------------------
    // countTokens
    // -------------------------------------------------------------------------

    @Test
    fun `countTokens returns null - MiniMax has no token counting endpoint`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: MiniMaxProvider intentionally does not override countTokens.
        val request = GatewayRequest(
            modelName = "MiniMax-M2.7",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "corr-count"
        )
        val settings = mapOf("gateway.minimax.apiKey" to "test-key")

        // ACT & ASSERT
        val result = (provider as UniversalGatewayProvider).countTokens(request, settings)
        assertNull(result, "MiniMaxProvider should return null for countTokens — no endpoint exists.")
    }
}