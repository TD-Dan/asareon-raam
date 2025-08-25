package app.auf.service

import app.auf.core.CompilationStats
import app.auf.model.CompilerSettings
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A wrapper for the result of a compilation, containing both the
 * transformed text and the statistics of the transformation.
 */
data class CompilationResult(
    val compiledText: String,
    val stats: CompilationStats
)

/**
 * A service responsible for transforming raw prompt strings into token-efficient versions.
 *
 * @version 1.1
 * @since 2025-08-25
 */
class PromptCompiler(private val jsonParser: Json) {

    private val headerFieldsToClean = setOf("version", "created_at", "modified_at", "holon_usage", "filePath", "parentId", "depth", "relationships", "sub_holons")
    private val subHolonRefFieldsToClean = setOf("type", "summary")

    /**
     * The main entry point for the compiler. Takes a raw string and settings, and returns
     * the compiled version along with statistics.
     */
    fun compile(rawContent: String, settings: CompilerSettings): CompilationResult {
        val originalCharCount = rawContent.length
        var tempContent = rawContent

        if (settings.cleanHeaders) {
            tempContent = cleanJsonHeaders(tempContent)
        }
        if (settings.minifyJson) {
            tempContent = minifyJson(tempContent)
        }
        if (settings.removeWhitespace) {
            tempContent = removeExtraneousWhitespace(tempContent)
        }

        val compiledCharCount = tempContent.length
        val stats = CompilationStats(originalCharCount, compiledCharCount)

        return CompilationResult(tempContent, stats)
    }

    private fun removeExtraneousWhitespace(content: String): String {
        return content.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun minifyJson(content: String): String {
        return try {
            val jsonElement = jsonParser.parseToJsonElement(content)
            Json.encodeToString(JsonElement.serializer(), jsonElement)
        } catch (e: Exception) {
            content
        }
    }

    private fun cleanJsonHeaders(content: String): String {
        return try {
            val jsonElement = jsonParser.parseToJsonElement(content)
            if (jsonElement is JsonObject && jsonElement.containsKey("header")) {
                val header = jsonElement["header"]!!.jsonObject
                val cleanedHeaderFields = header.toMutableMap()
                headerFieldsToClean.forEach { cleanedHeaderFields.remove(it) }

                if (cleanedHeaderFields.containsKey("sub_holons")) {
                    cleanedHeaderFields["sub_holons"] = cleanSubHolonRefs(header["sub_holons"])
                }

                val cleanedHolonJson = buildJsonObject {
                    put("header", JsonObject(cleanedHeaderFields))
                    if(jsonElement.containsKey("payload")) {
                        put("payload", jsonElement["payload"]!!)
                    }
                }
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), cleanedHolonJson)
            } else {
                content
            }
        } catch (e: Exception) {
            content
        }
    }

    private fun cleanSubHolonRefs(subHolonsElement: JsonElement?): JsonElement {
        return subHolonsElement ?: buildJsonObject { }
    }


    companion object {
        val SETTING_DEFINITIONS = listOf(
            SettingDefinition(
                key = "compiler.removeWhitespace",
                section = "Prompt Compiler",
                label = "Remove extraneous whitespace",
                description = "Reduces token count by trimming leading/trailing whitespace from each line and removing empty lines.",
                type = SettingType.BOOLEAN
            ),
            SettingDefinition(
                key = "compiler.cleanHeaders",
                section = "Prompt Compiler",
                label = "Clean non-essential Holon headers",
                description = "Removes fields like version, timestamps, and relationships from holon headers before sending.",
                type = SettingType.BOOLEAN
            ),
            SettingDefinition(
                key = "compiler.minifyJson",
                section = "Prompt Compiler",
                label = "Minify Holon JSON",
                description = "Compresses Holon JSON into a single line. Highest token savings, but may impact complex reasoning.",
                type = SettingType.BOOLEAN
            )
        )
    }
}