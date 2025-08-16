// FILE: composeApp/src/commonMain/kotlin/app/auf/GatewayManager.kt
// VERDICT: UPDATE (with temporary diagnostics)
// REASON: Added detailed println statements to the parsing function to diagnose
// why the regex is failing to match multi-line content in the test environment.
// This will give us the data needed to craft the final, correct solution.

package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AIResponse(
    val contentBlocks: List<ContentBlock>,
    val rawContent: String?,
    val usageMetadata: UsageMetadata?,
    val errorMessage: String? = null
)

open class GatewayManager(
    private val gateway: Gateway,
    private val jsonParser: Json,
    private val apiKey: String
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    open suspend fun sendMessage(selectedModel: String, messages: List<ChatMessage>): AIResponse {
        return withContext(coroutineScope.coroutineContext) {
            try {
                val apiRequestContents = convertChatToApiContents(messages)
                val response = gateway.generateContent(apiKey, selectedModel, apiRequestContents)

                response.error?.let {
                    return@withContext AIResponse(
                        contentBlocks = emptyList(),
                        rawContent = "API Error",
                        usageMetadata = null,
                        errorMessage = "API Error: ${it.message} (Code: ${it.code})"
                    )
                }

                val rawTextResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.promptFeedback?.blockReason?.let { "Blocked: $it" }
                    ?: "No content received, but no error was reported."

                val parsedBlocks = parseRawContentToBlocks(rawTextResponse)

                AIResponse(
                    contentBlocks = parsedBlocks,
                    usageMetadata = response.usageMetadata,
                    rawContent = rawTextResponse
                )
            } catch (e: Exception) {
                AIResponse(
                    contentBlocks = emptyList(),
                    rawContent = "Gateway Error",
                    usageMetadata = null,
                    errorMessage = "Gateway Error: ${e.message}"
                )
            }
        }
    }

    open suspend fun listModels(): List<ModelInfo> {
        return withContext(coroutineScope.coroutineContext) {
            gateway.listModels(apiKey)
        }
    }

    private fun parseRawContentToBlocks(rawText: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val regex = Regex("""\[AUF_([A-Z_]+)(?::\s*(.*?))?\]\s*([\s\S]*?)\s*\[/AUF_\1\]""", setOf(RegexOption.MULTILINE))
        var lastIndex = 0

        // --- START OF DIAGNOSTIC BLOCK ---
        println("\n\n--- DIAGNOSTICS: Parsing Raw Text ---")
        println(">>> RAW TEXT (length: ${rawText.length}):")
        println("=======================================")
        println(rawText)
        println("=======================================")
        var matchesFound = 0
        // --- END OF DIAGNOSTIC BLOCK ---

        regex.findAll(rawText).forEach { matchResult ->
            // --- START OF DIAGNOSTIC BLOCK ---
            matchesFound++
            println(">>> Regex Match #$matchesFound Found!")
            println("    Full Match Range: ${matchResult.range}")
            println("    Group Count: ${matchResult.groupValues.size - 1}")
            matchResult.groupValues.forEachIndexed { index, value ->
                if (index > 0) println("    Group [$index]: \"$value\"")
            }
            // --- END OF DIAGNOSTIC BLOCK ---

            if (matchResult.range.first > lastIndex) {
                val precedingText = rawText.substring(lastIndex, matchResult.range.first).trim()
                if (precedingText.isNotEmpty()) {
                    blocks.add(TextBlock(precedingText))
                }
            }

            val tag = matchResult.groupValues[1]
            val params = matchResult.groupValues[2]
            val content = matchResult.groupValues[3]

            try {
                when (tag) {
                    "ACTION_MANIFEST" -> {
                        val cleanContent = content.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```")
                        val actions = jsonParser.decodeFromString<List<Action>>(cleanContent)
                        blocks.add(ActionBlock(actions = actions))
                    }
                    "FILE_VIEW" -> {
                        blocks.add(FileContentBlock(fileName = params.trim(), content = content.trim()))
                    }
                    "APP_REQUEST" -> {
                        blocks.add(AppRequestBlock(requestType = content.trim()))
                    }
                    "STATE_ANCHOR" -> {
                        val jsonObject = jsonParser.decodeFromString<JsonObject>(content)
                        val anchorId = jsonObject["anchorId"]?.jsonPrimitive?.content ?: "unknown-anchor"
                        blocks.add(AnchorBlock(anchorId, jsonObject))
                    }
                }
            } catch (e: Exception) {
                blocks.add(TextBlock("--- ERROR PARSING BLOCK ---\nTAG: $tag\nERROR: ${e.message}\nCONTENT:\n$content\n--- END ERROR ---"))
            }

            lastIndex = matchResult.range.last + 1
        }

        // --- START OF DIAGNOSTIC BLOCK ---
        println(">>> Total Matches Found: $matchesFound")
        // --- END OF DIAGNOSTIC BLOCK ---

        if (lastIndex < rawText.length) {
            val trailingText = rawText.substring(lastIndex).trim()
            if (trailingText.isNotEmpty()) {
                blocks.add(TextBlock(trailingText))
            }
        }

        if (blocks.isEmpty() && rawText.isNotBlank()) {
            blocks.add(TextBlock(rawText))
        }

        // --- START OF DIAGNOSTIC BLOCK ---
        println(">>> Final Block Count: ${blocks.size}")
        println("--- END OF DIAGNOSTICS ---\n\n")
        // --- END OF DIAGNOSTIC BLOCK ---

        return blocks
    }

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        // ... (rest of the file is unchanged) ...
        val apiContents = mutableListOf<Content>()
        messages.forEach { msg ->
            val reconstructedContent = msg.contentBlocks.joinToString(separator = "\n") { block ->
                when (block) {
                    is TextBlock -> block.text
                    is ActionBlock -> "[AUF_ACTION_MANIFEST]\n${jsonParser.encodeToString(ListSerializer(Action.serializer()), block.actions)}\n[/AUF_ACTION_MANIFEST]"
                    else -> "[System placeholder for block type: ${block::class.simpleName}]"
                }
            }

            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "user"
                    apiContents.add(Content(role, listOf(Part(reconstructedContent))))
                }
                Author.SYSTEM -> {
                    val fullContent = "--- START OF FILE ${msg.title} ---\n$reconstructedContent"
                    apiContents.add(Content("user", listOf(Part(fullContent))))
                }
            }
        }
        val mergedContents = mutableListOf<Content>()
        if (apiContents.isNotEmpty()) {
            var currentRole = apiContents.first().role
            val currentParts = mutableListOf<String>()
            apiContents.forEach { content ->
                if (content.role == currentRole) {
                    currentParts.add(content.parts.first().text)
                } else {
                    mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
                    currentRole = content.role
                    currentParts.clear()
                    currentParts.add(content.parts.first().text)
                }
            }
            mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
        }
        return mergedContents
    }
}