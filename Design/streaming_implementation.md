# Gateway Streaming Implementation Spec

**Status:** Ready for implementation  
**Author:** Claude (spec), Daniel (design decisions)  
**Date:** 2026-04-12

---

## 1. End State

When a provider generates content, tokens appear in the session UI as they are produced by the API. The response is a real, persisted ledger entry from the first chunk onward. If the operator cancels mid-stream, the partial content is preserved as a valid response. If the app crashes mid-stream, the last coalesced state is on disk.

### Design Principles

1. **Partial content is valuable.** A cancelled stream is treated as a valid `RETURN_RESPONSE` with whatever content has arrived so far. Three minutes of tokens at API pricing is not disposable.
2. **No transient entries.** The streaming ledger entry is a real `SESSION_POST` from chunk one. It is persisted to disk. `is_transient` is NOT used.
3. **Disk write coalescing.** To avoid thrashing the filesystem, `SESSION_UPDATE_MESSAGE` dispatches are coalesced at a tunable interval (default 1000ms). This is a top-of-file constant for easy tuning.
4. **Final response is authoritative.** When the stream completes naturally, `RETURN_RESPONSE` carries the full assembled content, token usage, and rate limits. The existing `handleGatewayResponse` replaces the streaming entry with the post-processed final version (cognitive strategy, sentinels).
5. **Cancellation is a first-class completion path.** When the operator cancels a streaming request, the gateway assembles a `RETURN_RESPONSE` from whatever content has accumulated, with `rawContent` set to the partial text and `outputTokens` set to null (unknown for partial streams).

---

## 2. Data Contracts

### 2.1 New: `StreamChunk`

```kotlin
// UniversalGatewayProvider.kt
data class StreamChunk(
    val text: String,
    val correlationId: String
)
```

Not `@Serializable` — this is never persisted or sent over the wire. It flows internally from provider to `GatewayFeature` via `Flow<StreamChunk>`.

### 2.2 New: Provider Interface Addition

```kotlin
// UniversalGatewayProvider.kt — add to interface
interface UniversalGatewayProvider {
    // ... existing methods unchanged ...

    /**
     * Streaming variant of [generateContent]. Returns a Flow that emits text chunks
     * as they arrive from the provider's API.
     *
     * The Flow MUST:
     * - Emit only the new text delta per chunk (not the accumulated text)
     * - Throw on API errors (the collector handles error responses)
     * - Support cancellation via standard coroutine cancellation
     *
     * The default implementation calls [generateContent] and emits the full response
     * as a single chunk, providing transparent fallback for providers that don't
     * implement streaming natively.
     *
     * @return A Flow of text deltas, plus a terminal [GatewayResponse] with token
     *         usage and rate limit data (emitted as the return value, not in the Flow).
     */
    suspend fun generateContentStreaming(
        request: GatewayRequest,
        settings: Map<String, String>
    ): StreamingResult {
        // Default: non-streaming fallback
        val response = generateContent(request, settings)
        return StreamingResult(
            chunks = flow {
                if (response.rawContent != null) {
                    emit(StreamChunk(response.rawContent, request.correlationId))
                }
            },
            finalResponse = CompletableDeferred(response)
        )
    }
}
```

### 2.3 New: `StreamingResult`

```kotlin
// UniversalGatewayProvider.kt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Encapsulates a streaming generation session.
 *
 * [chunks] emits text deltas as they arrive. The collector is responsible for
 * coalescing and dispatching them.
 *
 * [finalResponse] completes when the HTTP response finishes. It contains the
 * full GatewayResponse with token usage, rate limit info, and the complete
 * assembled rawContent. For the non-streaming fallback, it completes immediately.
 */
data class StreamingResult(
    val chunks: Flow<StreamChunk>,
    val finalResponse: CompletableDeferred<GatewayResponse>
)
```

**Why `CompletableDeferred` instead of a suspend function?** Because token usage and rate limits arrive at different times depending on the provider:
- Anthropic: `message_start` event has input tokens, `message_delta` event at stream end has output tokens
- OpenAI: final chunk or separate usage chunk at end of stream
- Gemini: `usageMetadata` in the final SSE frame

The provider completes the deferred when it has all the data. The gateway awaits it after the chunk flow completes.

### 2.4 New Action: `gateway.STREAM_CHUNK`

Add to `gateway.actions.json`:

```json
{
    "action_name": "gateway.STREAM_CHUNK",
    "summary": "Delivers a coalesced text delta from an in-flight streaming generation to the originator. Dispatched periodically (not per-SSE-event) to avoid flooding the action bus.",
    "public": false,
    "broadcast": false,
    "targeted": true,
    "payload_schema": {
        "type": "object",
        "properties": {
            "correlationId": {
                "type": "string"
            },
            "text": {
                "type": "string",
                "description": "The coalesced text delta since the last STREAM_CHUNK. NOT the accumulated total."
            },
            "accumulatedContent": {
                "type": "string",
                "description": "The full accumulated content so far. Used to update the ledger entry in-place without the consumer needing to track partial state."
            }
        },
        "required": ["correlationId", "text", "accumulatedContent"]
    }
}
```

**Design decision: `accumulatedContent` in every chunk.** This makes the consumer stateless. The `CognitivePipeline` doesn't need to buffer or concatenate — it receives the full text and writes it directly to `SESSION_UPDATE_MESSAGE`. This is slightly more bytes per action but eliminates an entire class of ordering/lost-chunk bugs and keeps `CognitivePipeline` trivially simple.

---

## 3. Implementation by Layer

### 3.1 Provider Layer — SSE Parsing

All 5 providers need `generateContentStreaming()`. The SSE parsing is nearly identical for 4 of them (OpenAI-compatible: OpenAI, Inception, MiniMax, and with minor differences, Gemini). Anthropic is the outlier.

#### 3.1.1 Shared SSE Utility

Create a new file: **`SseStreamParser.kt`** in `feature/gateway/`.

```kotlin
package asareon.raam.feature.gateway

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Reads an SSE (Server-Sent Events) stream from a Ktor HttpResponse and emits
 * parsed SSE frames as a Flow.
 *
 * Handles the standard SSE protocol:
 * - Lines starting with "data: " are data lines
 * - Empty lines delimit events
 * - Lines starting with ":" are comments (ignored)
 * - "data: [DONE]" signals stream end (OpenAI convention)
 *
 * @param response The HTTP response with streaming body
 * @return Flow of raw data strings (one per SSE event, excluding [DONE])
 */
internal fun parseSSEStream(response: HttpResponse): Flow<String> = flow {
    val channel: ByteReadChannel = response.bodyAsChannel()
    val buffer = StringBuilder()

    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break

        when {
            line.startsWith("data: [DONE]") -> break
            line.startsWith("data: ") -> buffer.append(line.removePrefix("data: "))
            line.isEmpty() && buffer.isNotEmpty() -> {
                emit(buffer.toString())
                buffer.clear()
            }
            // "event:", "id:", "retry:", comments (":") — ignored
        }
    }
    // Flush any remaining buffered data
    if (buffer.isNotEmpty()) {
        emit(buffer.toString())
    }
}
```

#### 3.1.2 Anthropic Provider

Anthropic uses a typed event stream. Key events:

| Event | Purpose |
|-------|---------|
| `message_start` | Contains `message.usage.input_tokens` |
| `content_block_delta` | Contains `delta.text` (the chunk) |
| `message_delta` | Contains `usage.output_tokens` at stream end |
| `message_stop` | Terminal event |
| `error` | Error mid-stream |

The Anthropic SSE format uses `event: <type>` lines before `data:` lines. Modify the SSE parser call to also capture event types, or parse inline.

```kotlin
// AnthropicProvider.kt — new method

override suspend fun generateContentStreaming(
    request: GatewayRequest,
    settings: Map<String, String>
): StreamingResult {
    val apiKey = settings[apiKeySettingKey].orEmpty()
    if (apiKey.isBlank()) {
        val errorResponse = GatewayResponse(null, "Anthropic API Key is not configured.", request.correlationId)
        return StreamingResult(
            chunks = emptyFlow(),
            finalResponse = CompletableDeferred(errorResponse)
        )
    }

    val apiRequest = buildRequestPayload(request).jsonObject.toMutableMap().apply {
        put("stream", JsonPrimitive(true))
    }.let { JsonObject(it) }

    val deferred = CompletableDeferred<GatewayResponse>()

    val chunks = flow {
        val currentTimeMs = platformDependencies.currentTimeMillis()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var rateLimitInfo: RateLimitInfo? = null
        val accumulated = StringBuilder()

        try {
            val httpResponse: HttpResponse = client.post("https://$apiHost/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }

            rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            if (isRateLimited(httpResponse.status.value)) {
                deferred.complete(buildRateLimitedResponse(request.correlationId, rateLimitInfo, "Anthropic", currentTimeMs))
                return@flow
            }

            val channel = httpResponse.bodyAsChannel()
            var currentEventType = ""

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("event: ") -> {
                        currentEventType = line.removePrefix("event: ").trim()
                    }
                    line.startsWith("data: ") -> {
                        val data = line.removePrefix("data: ")
                        when (currentEventType) {
                            "message_start" -> {
                                // Extract input token count from message.usage
                                try {
                                    val obj = json.parseToJsonElement(data).jsonObject
                                    inputTokens = obj["message"]?.jsonObject
                                        ?.get("usage")?.jsonObject
                                        ?.get("input_tokens")?.jsonPrimitive?.intOrNull
                                } catch (_: Exception) {}
                            }
                            "content_block_delta" -> {
                                try {
                                    val obj = json.parseToJsonElement(data).jsonObject
                                    val text = obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                                    if (text != null) {
                                        accumulated.append(text)
                                        emit(StreamChunk(text, request.correlationId))
                                    }
                                } catch (_: Exception) {}
                            }
                            "message_delta" -> {
                                // Extract output token count
                                try {
                                    val obj = json.parseToJsonElement(data).jsonObject
                                    outputTokens = obj["usage"]?.jsonObject
                                        ?.get("output_tokens")?.jsonPrimitive?.intOrNull
                                } catch (_: Exception) {}
                            }
                            "error" -> {
                                try {
                                    val obj = json.parseToJsonElement(data).jsonObject
                                    val errorMsg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                        ?: "Unknown streaming error"
                                    deferred.complete(GatewayResponse(
                                        rawContent = accumulated.toString().ifEmpty { null },
                                        errorMessage = errorMsg,
                                        correlationId = request.correlationId,
                                        inputTokens = inputTokens,
                                        outputTokens = outputTokens,
                                        rateLimitInfo = rateLimitInfo
                                    ))
                                    return@flow
                                } catch (_: Exception) {}
                            }
                            "message_stop" -> { /* Normal termination — handled after flow */ }
                        }
                    }
                }
            }

            // Stream completed naturally
            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = null,
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                rateLimitInfo = rateLimitInfo
            ))

        } catch (e: CancellationException) {
            // CANCELLATION PATH: Partial content is valid.
            // Complete the deferred with what we have so the gateway can
            // dispatch a RETURN_RESPONSE with the partial content.
            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = null,
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                outputTokens = null, // Unknown for partial streams
                rateLimitInfo = rateLimitInfo
            ))
            throw e // Re-throw so coroutine cancellation propagates
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, id, "Streaming failed: ${e.stackTraceToString()}")
            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = mapExceptionToUserMessage(e),
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                rateLimitInfo = rateLimitInfo
            ))
        }
    }

    return StreamingResult(chunks = chunks, finalResponse = deferred)
}
```

#### 3.1.3 OpenAI-Compatible Providers (OpenAI, Inception, MiniMax)

These three share the same SSE format. The streaming response is a sequence of:

```
data: {"choices":[{"delta":{"content":"Hello"}}]}\n\n
data: {"choices":[{"delta":{"content":" world"}}]}\n\n
data: [DONE]\n\n
```

Token usage is either in a final chunk (OpenAI with `stream_options: { include_usage: true }`) or absent (Inception, MiniMax). For OpenAI, add `"stream_options": { "include_usage": true }` to the request payload.

The implementation pattern is identical across all three — only the host, auth header, and request payload builder differ. Each provider implements its own `generateContentStreaming()` calling the shared `parseSSEStream()`.

```kotlin
// Pattern for all OpenAI-compatible providers:

override suspend fun generateContentStreaming(
    request: GatewayRequest,
    settings: Map<String, String>
): StreamingResult {
    val apiKey = settings[apiKeySettingKey].orEmpty()
    if (apiKey.isBlank()) { /* ... error response ... */ }

    val apiRequest = buildStreamingRequestPayload(request) // adds "stream": true

    val deferred = CompletableDeferred<GatewayResponse>()

    val chunks = flow {
        val currentTimeMs = platformDependencies.currentTimeMillis()
        val accumulated = StringBuilder()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var rateLimitInfo: RateLimitInfo? = null

        try {
            val httpResponse = client.post("https://$apiHost/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }

            rateLimitInfo = httpResponse.extractRateLimitInfo(currentTimeMs)

            if (isRateLimited(httpResponse.status.value)) {
                deferred.complete(buildRateLimitedResponse(request.correlationId, rateLimitInfo, id, currentTimeMs))
                return@flow
            }

            parseSSEStream(httpResponse).collect { data ->
                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    // Extract text delta
                    val delta = obj["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")?.jsonObject
                    val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
                    if (text != null) {
                        accumulated.append(text)
                        emit(StreamChunk(text, request.correlationId))
                    }
                    // Extract usage if present (OpenAI final chunk)
                    obj["usage"]?.jsonObject?.let { usage ->
                        inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
                        outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull
                    }
                } catch (_: Exception) {}
            }

            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = null,
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                rateLimitInfo = rateLimitInfo
            ))

        } catch (e: CancellationException) {
            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = null,
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                outputTokens = null,
                rateLimitInfo = rateLimitInfo
            ))
            throw e
        } catch (e: Exception) {
            deferred.complete(GatewayResponse(
                rawContent = accumulated.toString().ifEmpty { null },
                errorMessage = mapExceptionToUserMessage(e),
                correlationId = request.correlationId,
                inputTokens = inputTokens,
                rateLimitInfo = rateLimitInfo
            ))
        }
    }

    return StreamingResult(chunks = chunks, finalResponse = deferred)
}
```

**OpenAI-specific:** Add `"stream_options": { "include_usage": true }` to request payload alongside `"stream": true`. This causes OpenAI to include a final chunk with usage data.

**OpenAI maxTokensParamFor():** The existing logic for `max_completion_tokens` vs `max_tokens` applies unchanged to the streaming payload.

#### 3.1.4 Gemini Provider

Gemini uses `?alt=sse` query parameter for streaming. The SSE format wraps the standard `GenerateContentResponse` object in each frame. Text is at `candidates[0].content.parts[0].text`. Usage metadata appears in the final frame.

```kotlin
// GeminiProvider — URL changes to:
val apiUrl = "https://$API_HOST/v1beta/models/${request.modelName}:streamGenerateContent?alt=sse"
// Query parameter "key" still added via parameter("key", apiKey)
```

The frame parsing extracts text the same way as `parseResponse()` but only pulls the text delta from `candidates[0].content.parts[0].text`.

#### 3.1.5 MiniMax `reasoning_content`

MiniMax M2.7 can return `reasoning_content` in streaming deltas. During streaming, if `delta.reasoning_content` is present, it should be accumulated separately and prepended as `<think>...</think>` in the final assembled content — matching the existing non-streaming logic at `MiniMaxProvider.kt:154-159`.

During streaming, thinking content should NOT be emitted as visible chunks. It is assembled internally and prepended to the final `rawContent` in the `GatewayResponse`.

---

### 3.2 Gateway Feature Layer

#### 3.2.1 Tunable Constants

Add to top of **`GatewayFeature.kt`**:

```kotlin
/**
 * Minimum interval (ms) between STREAM_CHUNK action dispatches.
 * Incoming SSE events are coalesced into a single dispatch at this cadence.
 * Lower = more responsive UI, higher Store/reducer/disk load.
 * Higher = chunkier updates, less system load.
 *
 * This also governs the maximum data loss window on crash — the last
 * N ms of tokens may not have been dispatched yet.
 *
 * 1000ms is the default. Tune based on observed performance.
 */
private const val STREAM_COALESCE_INTERVAL_MS = 1000L
```

#### 3.2.2 Modified `handleGenerateContent`

Replace the coroutine body in `handleGenerateContent` (lines 206-246):

```kotlin
val job = coroutineScope.launch {
    val request = GatewayRequest(modelName, contents, correlationId, systemPrompt, effectiveMaxTokens)
    val streamingResult = provider.generateContentStreaming(request, gatewayState.apiKeys)

    // ── Chunk collection with coalescing ──────────────────────────
    val accumulated = StringBuilder()
    var pendingDelta = StringBuilder()
    var lastDispatchTime = 0L
    var firstChunk = true

    try {
        streamingResult.chunks.collect { chunk ->
            accumulated.append(chunk.text)
            pendingDelta.append(chunk.text)

            val now = platformDependencies.currentTimeMillis()
            val elapsed = now - lastDispatchTime

            if (elapsed >= STREAM_COALESCE_INTERVAL_MS || firstChunk) {
                store.deferredDispatch(this@GatewayFeature.identity.handle, Action(
                    name = ActionRegistry.Names.GATEWAY_STREAM_CHUNK,
                    payload = buildJsonObject {
                        put("correlationId", correlationId)
                        put("text", pendingDelta.toString())
                        put("accumulatedContent", accumulated.toString())
                    },
                    targetRecipient = originator
                ))
                pendingDelta.clear()
                lastDispatchTime = now
                firstChunk = false
            }
        }

        // Flush any remaining coalesced content
        if (pendingDelta.isNotEmpty()) {
            store.deferredDispatch(this@GatewayFeature.identity.handle, Action(
                name = ActionRegistry.Names.GATEWAY_STREAM_CHUNK,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("text", pendingDelta.toString())
                    put("accumulatedContent", accumulated.toString())
                },
                targetRecipient = originator
            ))
        }
    } catch (e: CancellationException) {
        // Operator cancelled. Flush any pending content before the final response.
        if (pendingDelta.isNotEmpty()) {
            store.deferredDispatch(this@GatewayFeature.identity.handle, Action(
                name = ActionRegistry.Names.GATEWAY_STREAM_CHUNK,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("text", pendingDelta.toString())
                    put("accumulatedContent", accumulated.toString())
                },
                targetRecipient = originator
            ))
        }
        // Don't re-throw yet — let the final response dispatch below
    }

    // ── Final response ───────────────────────────────────────────
    // Await the provider's final response (has token usage + rate limits).
    // For cancellation, the provider's CancellationException handler has
    // already completed the deferred with partial content.
    val response = streamingResult.finalResponse.await()

    // Log token usage if available
    if (response.inputTokens != null || response.outputTokens != null) {
        platformDependencies.log(
            LogLevel.INFO, identity.handle,
            "Token usage for $correlationId: input=${response.inputTokens ?: "N/A"}, output=${response.outputTokens ?: "N/A"}"
        )
    }

    val responsePayload = try {
        Json.encodeToJsonElement(response).jsonObject
    } catch (e: Exception) {
        platformDependencies.log(
            LogLevel.FATAL, identity.handle,
            "CRITICAL: Failed to serialize GatewayResponse for originator '$originator'. Error: ${e.message}"
        )
        val errorResponse = GatewayResponse(
            rawContent = null,
            errorMessage = "FATAL: GatewayFeature failed to serialize its own response.",
            correlationId = correlationId
        )
        Json.encodeToJsonElement(errorResponse).jsonObject
    }

    store.deferredDispatch(this@GatewayFeature.identity.handle, Action(
        name = ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
        payload = responsePayload,
        targetRecipient = originator
    ))
}
```

**Key detail: Cancellation handling.** When the operator dispatches `CANCEL_REQUEST`, the Job is cancelled. The `CancellationException` propagates into the `streamingResult.chunks.collect {}` block. We catch it, flush pending content, then fall through to await the `finalResponse` (which the provider has completed with partial content in its own `CancellationException` handler). The `RETURN_RESPONSE` is dispatched with whatever content was accumulated.

The `activeRequests` registration and `invokeOnCompletion` cleanup (lines 250-255) remain unchanged.

---

### 3.3 Consumer Layer (CognitivePipeline + AgentRuntimeFeature)

#### 3.3.1 Routing in AgentRuntimeFeature

Add `GATEWAY_STREAM_CHUNK` to the targeted response routing:

```kotlin
// AgentRuntimeFeature.kt — in the when(action.name) block that routes targeted responses:
ActionRegistry.Names.GATEWAY_STREAM_CHUNK -> {
    CognitivePipeline.handleStreamChunk(action, store)
}
```

Also add it to the outer `when` that matches action names to `handleTargetedResponse`:

```kotlin
ActionRegistry.Names.GATEWAY_STREAM_CHUNK,  // Add to existing list
ActionRegistry.Names.GATEWAY_RETURN_RESPONSE,
```

#### 3.3.2 New: `CognitivePipeline.handleStreamChunk`

Add a tunable constant at top of `CognitivePipeline.kt`:

```kotlin
/**
 * Minimum interval (ms) between SESSION_UPDATE_MESSAGE dispatches during streaming.
 * Controls how often the ledger entry is updated and persisted to disk.
 * Must be >= GatewayFeature.STREAM_COALESCE_INTERVAL_MS to avoid
 * dispatching faster than chunks arrive. Set to the same value by default.
 */
private const val STREAM_SESSION_UPDATE_INTERVAL_MS = 1000L
```

Add a tracking structure (companion-level or object-level, matching existing patterns in CognitivePipeline):

```kotlin
/**
 * Tracks the messageId of the streaming ledger entry for each agent.
 * Key: correlationId (= agent UUID). Value: messageId of the entry being streamed into.
 * Populated on first STREAM_CHUNK, cleared on RETURN_RESPONSE.
 */
private val streamingMessageIds = mutableMapOf<String, String>()

/**
 * Tracks the last time a SESSION_UPDATE_MESSAGE was dispatched for each streaming agent.
 * Used to coalesce updates and avoid disk write thrashing.
 */
private val streamingLastUpdateTime = mutableMapOf<String, Long>()
```

The handler:

```kotlin
fun handleStreamChunk(action: Action, store: Store) {
    val payload = action.payload ?: return
    val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
    val accumulatedContent = payload["accumulatedContent"]?.jsonPrimitive?.contentOrNull ?: return

    val agentId = IdentityUUID(correlationId)
    val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
    val agent = agentState.agents[agentId] ?: return

    val targetSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: return

    val existingMessageId = streamingMessageIds[correlationId]

    if (existingMessageId == null) {
        // ── First chunk: create the ledger entry via SESSION_POST ─────
        val messageId = "stream-$correlationId"
        streamingMessageIds[correlationId] = messageId

        store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", targetSessionUUID.uuid)
            put("senderId", agent.identityHandle.handle)
            put("message", accumulatedContent)
            put("messageId", messageId)
        }))

        streamingLastUpdateTime[correlationId] = store.platformDependencies.currentTimeMillis()

        // Update agent status to show it's actively generating
        AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.GENERATING)
    } else {
        // ── Subsequent chunks: update the existing entry ──────────────
        val now = store.platformDependencies.currentTimeMillis()
        val lastUpdate = streamingLastUpdateTime[correlationId] ?: 0L
        val elapsed = now - lastUpdate

        if (elapsed >= STREAM_SESSION_UPDATE_INTERVAL_MS) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_UPDATE_MESSAGE, buildJsonObject {
                put("session", targetSessionUUID.uuid)
                put("messageId", existingMessageId)
                put("newContent", accumulatedContent)
            }))
            streamingLastUpdateTime[correlationId] = now
        }
        // If within the coalesce window, skip — the next chunk or the
        // final RETURN_RESPONSE will update the entry.
    }
}
```

#### 3.3.3 Modified: `handleGatewayResponse` (Streaming Completion)

At the top of the existing `handleGatewayResponse` method (line 1690), add cleanup for streaming state and a final update of the streaming entry:

```kotlin
private fun handleGatewayResponse(payload: JsonObject, store: Store) {
    val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull
    if (agentIdStr == null) { /* ... existing error handling ... */ }

    // ── Streaming cleanup ────────────────────────────────────────
    // If this response is completing a streaming session, clean up tracking state.
    // The streaming entry will be updated below with the final post-processed content.
    val streamingMessageId = streamingMessageIds.remove(agentIdStr)
    streamingLastUpdateTime.remove(agentIdStr)

    // ... rest of existing method unchanged until the SESSION_POST dispatch ...
```

Then, modify the SESSION_POST dispatch at line 1822 to UPDATE the existing streaming entry instead of creating a new one:

```kotlin
// Replace the existing SESSION_POST dispatch:

if (streamingMessageId != null) {
    // Stream was active — update the existing entry with post-processed content.
    // This replaces the raw streaming text with the sentinel-cleaned, strategy-processed version.
    store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_UPDATE_MESSAGE, buildJsonObject {
        put("session", targetSessionUUID.uuid)
        put("messageId", streamingMessageId)
        put("newContent", contentToPost)
    }))
} else {
    // Non-streaming path (fallback provider, or provider with no streaming support).
    // Create the entry as before.
    store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
        put("session", targetSessionUUID.uuid)
        put("senderId", agent.identityHandle.handle)
        put("message", contentToPost)
    }))
}
```

For the `HALT_AND_SILENCE` sentinel (line 1792), if a streaming entry exists, delete it:

```kotlin
if (result.action == SentinelAction.HALT_AND_SILENCE) {
    store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "Agent '$agentUuid' halted by Cognitive Strategy.")
    if (streamingMessageId != null) {
        store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
            put("session", targetSessionUUID.uuid)
            put("messageId", streamingMessageId)
        }))
    }
    AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.IDLE, "Halted by Internal Sentinel.")
    return
}
```

#### 3.3.4 New AgentStatus: `GENERATING`

If `AgentStatus.GENERATING` doesn't already exist, add it to the `AgentStatus` enum. This allows the UI to show that an agent is actively receiving tokens (vs `THINKING` which means waiting for the API, vs `IDLE`).

If adding a new enum value is disruptive, reuse `THINKING` instead — the distinction is cosmetic.

---

### 3.4 Session Layer

**No changes required.** The existing machinery handles everything:

- `SESSION_POST` with an explicit `messageId` creates the entry with that ID (reducer line 1495: `decoded.messageId ?: platformDependencies.generateUUID()`)
- `SESSION_UPDATE_MESSAGE` finds by `messageId` and updates `rawContent` + re-parses via `blockParser` (reducer line 1562-1587)
- Persistence is triggered by the side effect handler for both `SESSION_POST` and `SESSION_UPDATE_MESSAGE` (the entry is NOT transient, so `isTransient` is false, and `persistSession` runs)
- The 1000ms coalescing in `CognitivePipeline` ensures `SESSION_UPDATE_MESSAGE` dispatches are capped at ~1/sec, so disk writes are at most 1/sec

### 3.5 UI Layer

**No changes required.**

- `SessionView.kt` uses `itemsIndexed(activeSession.ledger, key = { _, entry -> entry.id })`. When the entry's content changes via `SESSION_UPDATE_MESSAGE`, Compose detects the state change and recomposes that single `LedgerEntryCard`.
- The `LedgerEntryCard` renders `entry.content` (parsed blocks from `blockParser`). As more text accumulates, the card grows. This is standard Compose behavior.

---

## 4. Cancellation Flow (End-to-End)

This is the most important flow to get right. Here's the exact sequence:

```
1. Operator clicks "Stop Generation"
   └→ Dispatches gateway.CANCEL_REQUEST { correlationId }

2. GatewayFeature.handleCancelRequest()
   └→ activeRequests[correlationId].cancel()

3. The Job's coroutine receives CancellationException
   └→ In the chunks.collect {} catch block:
       a. Flush any pending coalesced content as a final STREAM_CHUNK
       b. Fall through to await streamingResult.finalResponse

4. The provider's CancellationException handler (inside the flow builder)
   └→ Completes finalResponse with:
       - rawContent = accumulated text so far
       - errorMessage = null (NOT an error — this is valid content)
       - outputTokens = null (unknown for partial)
       - inputTokens = whatever was in message_start (if received)

5. GatewayFeature awaits finalResponse, gets the partial GatewayResponse
   └→ Dispatches RETURN_RESPONSE { rawContent: "partial...", errorMessage: null }
       targeted to originator

6. CognitivePipeline.handleGatewayResponse()
   └→ Cleans up streamingMessageIds
   └→ errorMessage is null, so SUCCESS PATH runs
   └→ Cognitive strategy post-processes the partial content
   └→ SESSION_UPDATE_MESSAGE updates the streaming entry with final content
   └→ Agent status → IDLE

7. Result: The partial generation is in the ledger, persisted to disk,
   post-processed by the cognitive strategy. The operator can read it
   and respond.
```

**Critical invariant:** At no point is `errorMessage` set for a user-initiated cancellation. The partial content flows through the normal success path. The only way to distinguish a cancelled stream from a completed one is `outputTokens == null`.

---

## 5. Error Mid-Stream Flow

```
1. SSE stream breaks (network error, API 500, etc.)

2. Provider catch block:
   └→ Completes finalResponse with:
       - rawContent = accumulated text so far (may be null if no chunks arrived)
       - errorMessage = mapExceptionToUserMessage(e)

3. Flow terminates (throws), GatewayFeature catch block flushes pending chunks

4. GatewayFeature awaits finalResponse
   └→ Dispatches RETURN_RESPONSE with errorMessage set

5. CognitivePipeline.handleGatewayResponse()
   └→ Cleans up streamingMessageIds
   └→ errorMessage is NOT null → ERROR PATH
   └→ If streamingMessageId existed, the entry remains in the ledger
      with whatever content was accumulated (it was persisted via
      SESSION_UPDATE_MESSAGE during streaming)
   └→ Agent status → ERROR

6. Result: Partial content is preserved in the ledger. Agent shows error.
   The operator sees what was generated before the failure.
```

---

## 6. Files to Modify

| # | File | Change | Lines |
|---|------|--------|-------|
| 1 | `UniversalGatewayProvider.kt` | Add `StreamChunk`, `StreamingResult`, `generateContentStreaming()` default impl | ~30 new |
| 2 | `gateway.actions.json` | Add `gateway.STREAM_CHUNK` action | ~25 new |
| 3 | `SseStreamParser.kt` | **New file.** Shared SSE frame parser | ~40 new |
| 4 | `GatewayFeature.kt` | Add `STREAM_COALESCE_INTERVAL_MS`, rewrite `handleGenerateContent` coroutine body, add `STREAM_CHUNK` to side effects routing | ~60 modified |
| 5 | `AnthropicProvider.kt` | Add `generateContentStreaming()` with Anthropic-specific SSE parsing | ~100 new |
| 6 | `OpenAIProvider.kt` | Add `generateContentStreaming()` using shared SSE parser | ~70 new |
| 7 | `GeminiProvider.kt` | Add `generateContentStreaming()` with `?alt=sse` | ~70 new |
| 8 | `InceptionProvider.kt` | Add `generateContentStreaming()` using shared SSE parser | ~70 new |
| 9 | `MiniMaxProvider.kt` | Add `generateContentStreaming()` with `reasoning_content` handling | ~80 new |
| 10 | `CognitivePipeline.kt` | Add `handleStreamChunk()`, streaming state maps, modify `handleGatewayResponse` for streaming completion | ~80 new/modified |
| 11 | `AgentRuntimeFeature.kt` | Route `GATEWAY_STREAM_CHUNK` to CognitivePipeline | ~5 new |

**Total: ~630 lines of new/modified code across 11 files (1 new, 10 modified).**

No new dependencies. No new state classes. No UI changes. No architectural changes.

---

## 7. Testing Strategy

### 7.1 Unit Tests

- **SSE parser:** Feed it raw SSE byte streams (including malformed ones, empty data lines, multi-line data, [DONE] variants). Assert correct frame extraction.
- **Provider `buildRequestPayload` with stream flag:** Assert `"stream": true` is present. Assert OpenAI includes `stream_options`.
- **GatewayFeature coalescing:** Mock a provider that emits N chunks at known intervals. Assert STREAM_CHUNK dispatches are coalesced at the configured interval.
- **Cancellation path:** Mock a provider that streams indefinitely. Cancel the job. Assert RETURN_RESPONSE contains accumulated content with null errorMessage.

### 7.2 Integration Tests

- **Full flow with mock provider:** Dispatch GENERATE_CONTENT, collect STREAM_CHUNK actions, verify SESSION_POST created on first chunk, SESSION_UPDATE_MESSAGE on subsequent, RETURN_RESPONSE at end, entry updated with final content.
- **Cancel mid-stream:** Start streaming, cancel after N chunks, verify partial content persisted.
- **Error mid-stream:** Provider throws after N chunks, verify error handling preserves partial content.

### 7.3 Manual Smoke Tests

- Anthropic streaming (Claude model) — verify thinking blocks handled correctly
- OpenAI streaming (GPT-4o) — verify token usage appears in final response
- Cancel a long generation — verify partial content is readable
- Kill the app during streaming — restart, verify last coalesced content is on disk

---

## 8. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Ktor `readUTF8Line()` blocks indefinitely on stalled connection | Low | Medium | Existing `HttpTimeout.requestTimeoutMillis = 360_000` covers this |
| SSE format changes in provider APIs | Very low | Low | Each provider's parser is isolated; fix in one place |
| `blockParser.parse()` on large accumulated content becomes slow | Very low | Low | Parser is single-pass regex; 100K chars in <1ms |
| Compose recomposition lag on rapid updates | Low | Low | 1000ms coalescing caps updates at 1/sec |
| `streamingMessageIds` map leaks entries on edge cases | Low | Medium | Cleared on RETURN_RESPONSE and on agent deletion. Add defensive cleanup in `SYSTEM_RUNNING` if needed. |
| Platform-specific ByteReadChannel behavior differences (Wasm, iOS) | Medium | Medium | Test on each platform. Ktor's channel abstraction should handle it, but Wasm may need attention. |

---

## 9. Implementation Order

Recommended sequence for the implementation:

1. **`StreamChunk` + `StreamingResult` + default impl** in `UniversalGatewayProvider.kt` — establishes the contract
2. **`gateway.STREAM_CHUNK`** in `gateway.actions.json` — generates the action constant
3. **`SseStreamParser.kt`** — shared utility, testable in isolation
4. **`AnthropicProvider.generateContentStreaming()`** — most complex SSE format, prove the pattern
5. **`GatewayFeature.handleGenerateContent`** — coalescing + dispatch logic
6. **`CognitivePipeline.handleStreamChunk`** + **`AgentRuntimeFeature` routing** — consumer side
7. **`CognitivePipeline.handleGatewayResponse` modifications** — streaming completion path
8. **Remaining providers** (OpenAI, Gemini, Inception, MiniMax) — mechanical, use shared parser
9. **Test, tune `STREAM_COALESCE_INTERVAL_MS`**
