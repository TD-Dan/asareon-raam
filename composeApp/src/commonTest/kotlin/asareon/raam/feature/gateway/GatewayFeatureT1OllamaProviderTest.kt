package asareon.raam.feature.gateway

import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.gateway.ollama.OllamaProvider
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Test for OllamaProvider.
 *
 * Mandate (P-TEST-001, T1): To test the provider's pure data transformation logic
 * without any network dependencies.
 *
 * Key contract differences from other providers:
 *   - API is OpenAI-compatible (/v1/chat/completions) — Ollama's compat layer
 *   - INCLUDES the `name` field per message — Ollama's compat layer accepts
 *     OpenAI's convention; we pass through the same sanitized name as OpenAIProvider
 *   - Accepts NO API key — setting is `gateway.ollama.baseUrl` instead
 *   - `baseUrl` defaults to http://localhost:11434 and is configurable
 *   - System prompt is injected as a `{"role": "system"}` message
 *   - Uses `max_tokens` uniformly (no reasoning-model parameter split)
 *   - Does NOT override countTokens — returns null (no token-counting endpoint)
 *
 * ARCHITECTURE: Content enrichment (adding sender info, timestamps) is the
 * responsibility of AgentCognitivePipeline.executeTurn, NOT the providers.
 * Providers receive pre-enriched content and pass it through unchanged.
 */
class GatewayFeatureT1OllamaProviderTest {
    private val platform = FakePlatformDependencies("test")
    private val provider = OllamaProvider(platform)

    // -------------------------------------------------------------------------
    // resolveBaseUrl
    // -------------------------------------------------------------------------

    @Test
    fun `resolveBaseUrl returns default when setting is absent`() {
        val baseUrl = provider.resolveBaseUrl(emptyMap())
        assertEquals("http://localhost:11434", baseUrl)
    }

    @Test
    fun `resolveBaseUrl returns default when setting is blank`() {
        val baseUrl = provider.resolveBaseUrl(mapOf("gateway.ollama.baseUrl" to ""))
        assertEquals("http://localhost:11434", baseUrl)
    }

    @Test
    fun `resolveBaseUrl returns configured value when set`() {
        val baseUrl = provider.resolveBaseUrl(mapOf("gateway.ollama.baseUrl" to "http://remote-box:11434"))
        assertEquals("http://remote-box:11434", baseUrl)
    }

    @Test
    fun `resolveBaseUrl strips trailing slash`() {
        // Trailing slashes in user input produce malformed URLs like http://host//v1/models.
        val baseUrl = provider.resolveBaseUrl(mapOf("gateway.ollama.baseUrl" to "http://localhost:11434/"))
        assertEquals("http://localhost:11434", baseUrl)
    }

    @Test
    fun `resolveBaseUrl strips multiple trailing slashes`() {
        val baseUrl = provider.resolveBaseUrl(mapOf("gateway.ollama.baseUrl" to "http://localhost:11434///"))
        assertEquals("http://localhost:11434", baseUrl)
    }

    @Test
    fun `resolveBaseUrl accepts https scheme`() {
        val baseUrl = provider.resolveBaseUrl(mapOf("gateway.ollama.baseUrl" to "https://ollama.example.com"))
        assertEquals("https://ollama.example.com", baseUrl)
    }

    // -------------------------------------------------------------------------
    // buildRequestPayload
    // -------------------------------------------------------------------------

    @Test
    fun `buildRequestPayload correctly transforms universal request to Ollama format`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(
                GatewayMessage("user", "Hello", "user-1", "User", 1000L),
                GatewayMessage("model", "Hi there", "agent-1", "Assistant", 2000L)
            ),
            correlationId = "corr-123"
        )
        val expected = buildJsonObject {
            put("model", "gemma4:e4b")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                    put("name", "User_user-1")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "Hi there")
                    put("name", "Assistant_agent-1")
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
            modelName = "gemma4:e4b",
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
            modelName = "gemma4:e4b",
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
    fun `buildRequestPayload includes sanitized name field`() {
        // ARRANGE: Ollama's OpenAI-compat layer inherits OpenAI's `name` field convention
        // and its character restrictions ([a-zA-Z0-9_-]{1,64}).
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "user-123", "John Doe", 1000L)),
            correlationId = "corr-name"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField, "Ollama messages should include a sanitized 'name' field")
        assertTrue(nameField.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")), "Name should be sanitized: $nameField")
        assertTrue(nameField.contains("John"), "Name should contain sender name")
        assertTrue(nameField.contains("user-123"), "Name should contain sender ID")
    }

    @Test
    fun `sanitized name handles special characters`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "José García!", 1000L)),
            correlationId = "corr-special"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)
        val messages = actual.jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField)
        assertTrue(nameField.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")), "Name should be sanitized: $nameField")
    }

    @Test
    fun `sanitized name truncates to 64 characters`() {
        // ARRANGE
        val longName = "A".repeat(50)
        val longId = "B".repeat(50)
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", longId, longName, 1000L)),
            correlationId = "corr-long"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray
        val nameField = messages?.get(0)?.jsonObject?.get("name")?.toString()?.trim('"')

        // ASSERT
        assertNotNull(nameField)
        assertTrue(nameField.length <= 64, "Name should be truncated to 64 chars, was ${nameField.length}")
    }

    @Test
    fun `buildRequestPayload includes system prompt as a system-role message`() {
        // ARRANGE: Ollama's OpenAI-compat layer uses OpenAI-style system messages.
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-sys",
            systemPrompt = "You are a helpful assistant."
        )
        val expected = buildJsonObject {
            put("model", "gemma4:e4b")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "You are a helpful assistant.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello")
                    put("name", "User_user-1")
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
            modelName = "gemma4:e4b",
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
            modelName = "gemma4:e4b",
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
            modelName = "gemma4:e4b",
            contents = emptyList(),
            correlationId = "corr-trigger"
        )

        // ACT
        val messages = provider.buildRequestPayload(request).jsonObject["messages"]?.jsonArray
        val triggerContent = messages?.get(0)?.jsonObject?.get("content")?.toString()?.trim('"')

        // ASSERT
        assertEquals(OllamaProvider.SYSTEM_PROMPT_TRIGGER, triggerContent)
    }

    @Test
    fun `buildRequestPayload handles empty contents with system prompt`() {
        // ARRANGE: System prompt + empty contents produces system message + trigger user message.
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
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
        // ARRANGE: Ollama accepts `max_tokens` via its OpenAI-compat layer.
        // Note: internally Ollama maps this to `num_predict` in its options block —
        // we don't need to care; the compat layer handles it.
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-max",
            maxOutputTokens = 4096
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertEquals(
            "4096",
            actual.jsonObject["max_tokens"]?.toString(),
            "max_tokens should be present with the configured value"
        )
    }

    @Test
    fun `buildRequestPayload omits max_tokens when not specified`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "user-1", "User", 1000L)),
            correlationId = "corr-no-max"
        )

        // ACT
        val actual = provider.buildRequestPayload(request)

        // ASSERT
        assertFalse(
            actual.jsonObject.containsKey("max_tokens"),
            "max_tokens should be omitted when not specified — let the model's default apply."
        )
    }

    @Test
    fun `buildRequestPayload preserves message ordering`() {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
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
            modelName = "gemma4:e4b",
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
    // parseResponse
    // -------------------------------------------------------------------------

    @Test
    fun `parseResponse correctly handles a successful API response`() {
        // ARRANGE
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Ollama Response" } }] }"""
        val correlationId = "corr-ok"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Ollama Response", response.rawContent)
        assertNull(response.errorMessage)
        assertEquals(correlationId, response.correlationId)
    }

    @Test
    fun `parseResponse correctly extracts token usage from successful response`() {
        // ARRANGE: Ollama reports usage in the OpenAI-compatible shape.
        val responseBody = """{
            "choices": [{ "message": { "role": "assistant", "content": "Response" } }],
            "usage": { "prompt_tokens": 42, "completion_tokens": 17, "total_tokens": 59 }
        }"""
        val correlationId = "corr-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertEquals(42, response.inputTokens)
        assertEquals(17, response.outputTokens)
        assertNull(response.errorMessage)
    }

    @Test
    fun `parseResponse logs warning when successful response has no token usage`() {
        // ARRANGE: Modern Ollama should always report usage. Missing usage indicates
        // an unusually old Ollama version — worth surfacing as a WARN.
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": "Response" } }] }"""
        val correlationId = "corr-no-tokens"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertEquals("Response", response.rawContent)
        assertNull(response.inputTokens)
        assertNull(response.outputTokens)
        val warnLog = platform.capturedLogs.find { it.level == LogLevel.WARN && it.tag == "ollama" }
        assertNotNull(warnLog, "A warning should be logged when token usage is missing from a successful response.")
        assertTrue(warnLog.message.contains("no token usage data"))
        assertTrue(
            warnLog.message.contains("older Ollama version"),
            "The warning should specifically flag old Ollama versions as a likely cause."
        )
    }

    @Test
    fun `parseResponse correctly handles an API error response`() {
        // ARRANGE: Ollama may surface errors via the OpenAI-compatible error envelope
        // when the underlying model fails (e.g., model not pulled, OOM).
        val responseBody = """{ "error": { "message": "model 'gemma4:nonexistent' not found" } }"""
        val correlationId = "corr-err"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("API Error: model 'gemma4:nonexistent' not found", response.errorMessage)
        assertEquals(correlationId, response.correlationId)
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
        assertEquals("Unrecognised response format from Ollama API.", response.errorMessage)
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR && it.tag == "ollama" }
        assertNotNull(log, "An error should be logged for an unrecognised response.")
        assertTrue(log.message.contains(responseBody))
    }

    @Test
    fun `parseResponse handles choice with null content field`() {
        // ARRANGE: content can be null in some edge cases.
        val responseBody = """{ "choices": [{ "message": { "role": "assistant", "content": null } }] }"""
        val correlationId = "corr-null-content"

        // ACT
        val response = provider.parseResponse(responseBody, correlationId)

        // ASSERT
        assertNull(response.rawContent)
        assertEquals("Unrecognised response format from Ollama API.", response.errorMessage)
    }

    // -------------------------------------------------------------------------
    // generatePreview
    // -------------------------------------------------------------------------

    @Test
    fun `generatePreview returns a pretty-printed JSON string`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "123"
        )

        // ACT
        val preview = provider.generatePreview(request, emptyMap())

        // ASSERT
        assertTrue(preview.contains("\"model\": \"gemma4:e4b\""), "Should contain model name")
        assertTrue(preview.contains("\"messages\": ["), "Should contain messages array")
        assertTrue(preview.contains("\"role\": \"user\""), "Should contain user role")
        assertTrue(preview.contains("Hello"), "Should contain message content")
    }

    @Test
    fun `generatePreview includes system prompt as a system-role message`() = kotlinx.coroutines.test.runTest {
        // ARRANGE
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
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
    // countTokens
    // -------------------------------------------------------------------------

    @Test
    fun `countTokens returns null - Ollama has no token counting endpoint`() = kotlinx.coroutines.test.runTest {
        // ARRANGE: OllamaProvider intentionally does not override countTokens.
        // Ollama exposes no dedicated counting endpoint — the default null is correct.
        val request = GatewayRequest(
            modelName = "gemma4:e4b",
            contents = listOf(GatewayMessage("user", "Hello", "u1", "User", 1000L)),
            correlationId = "corr-count"
        )

        // ACT & ASSERT
        val result = (provider as UniversalGatewayProvider).countTokens(request, emptyMap())
        assertNull(result, "OllamaProvider should return null for countTokens — no endpoint exists.")
    }
}