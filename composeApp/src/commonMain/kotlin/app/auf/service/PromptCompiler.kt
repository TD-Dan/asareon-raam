package app.auf.service

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
 * A service responsible for transforming raw prompt strings into token-efficient versions.
 *
 * ---
 * ## Mandate
 * This service's sole responsibility is to take a raw string and a set of `CompilerSettings`
 * and apply a series of configurable transformations. It is designed to operate on any
 * string content, making it a generic utility for prompt optimization. It also acts as
 * the single source of truth for its own setting definitions, allowing for a decoupled UI.
 *
 * ---
 * ## Dependencies
 * - `kotlinx.serialization.json.Json`: For intelligently cleaning JSON strings.
 *
 * @version 1.0
 * @since 2025-08-25
 */
class PromptCompiler(private val jsonParser: Json) {

    private val headerFieldsToClean = setOf("version", "created_at", "modified_at", "holon_usage", "filePath", "parentId", "depth", "relationships", "sub_holons")
    private val subHolonRefFieldsToClean = setOf("type", "summary")

    /**
     * The main entry point for the compiler. Takes a raw string and settings, and returns
     * the compiled version.
     */
    fun compile(rawContent: String, settings: CompilerSettings): String {
        var tempContent = rawContent

        if (settings.cleanHeaders) {
            tempContent = cleanJsonHeaders(tempContent)
        }
        if (settings.minifyJson) {
            tempContent = minifyJson(tempContent)
        }
        if (settings.removeWhitespace) {
            // Apply this after other transformations to catch all possible whitespace
            tempContent = removeExtraneousWhitespace(tempContent)
        }

        return tempContent
    }

    private fun removeExtraneousWhitespace(content: String): String {
        return content.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun minifyJson(content: String): String {
        return try {
            val jsonElement = jsonParser.parseToJsonElement(content)
            Json.encodeToString(JsonElement.serializer(), jsonElement)
        } catch (e: Exception) {
            // If it's not valid JSON, return the original content
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

                // Specifically process sub_holons if they exist after the first cleaning pass
                if (cleanedHeaderFields.containsKey("sub_holons")) {
                    val subHolons = header["sub_holons"]
                    // Re-add the cleaned version
                    cleanedHeaderFields["sub_holons"] = cleanSubHolonRefs(subHolons)
                }

                val cleanedHolonJson = buildJsonObject {
                    put("header", JsonObject(cleanedHeaderFields))
                    // Only add payload if it exists
                    if(jsonElement.containsKey("payload")) {
                        put("payload", jsonElement["payload"]!!)
                    }
                }
                // Use the pretty printer to maintain readability for non-minified output
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), cleanedHolonJson)
            } else {
                content
            }
        } catch (e: Exception) {
            content // Not a JSON object or not a Holon, return as-is
        }
    }

    private fun cleanSubHolonRefs(subHolonsElement: JsonElement?): JsonElement {
        // A more robust implementation is needed here. For now, this is a placeholder.
        // The ideal solution would correctly parse the array and rebuild it.
        // Due to the complexity of robustly editing raw JSON arrays, we will
        // defer a more aggressive sub_holon cleaning strategy.
        // Returning the original is the safest option.
        return subHolonsElement ?: buildJsonObject { }
    }


    companion object {
        /**
         * The declarative schema for all settings this service uses.
         * This list is the single source of truth for the settings UI.
         */
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