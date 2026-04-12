package asareon.raam.feature.agent.contextformatters

import kotlinx.serialization.json.*

/**
 * Renders a holon's JSON content as TOON (Terse Object-Oriented Notation) —
 * a compact, human-readable text format that eliminates JSON structural overhead
 * ({}, "", redundant nesting) while preserving all semantic content.
 *
 * TOON is a read-only presentation format. Agents always write holons as JSON
 * via PATCH_HOLON / REPLACE_HOLON actions.
 *
 * ## Format Rules
 *
 * - Header line: `[holonId] Type: Name`
 * - Summary indented below header
 * - Payload rendered as indented key-value pairs
 * - Objects flatten when they have a single string value: `key: value`
 * - Arrays render inline comma-separated: `items: a, b, c`
 * - Nested objects use 2-space indentation
 * - Empty/null values are omitted
 */
object ToonRenderer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Render a holon's raw JSON string as TOON format.
     *
     * @param rawContent The holon's complete JSON string (header + payload + execute).
     * @param stripped If true, render slim header (id, type, name, summary only).
     *                 If false, render full header.
     * @return TOON-formatted string, or the original content if parsing fails.
     */
    fun render(rawContent: String, stripped: Boolean = true): String {
        return try {
            val holonJson = json.parseToJsonElement(rawContent).jsonObject
            val header = holonJson["header"]?.jsonObject ?: return rawContent
            val payload = holonJson["payload"]?.jsonObject
            val execute = holonJson["execute"]?.jsonObject

            buildString {
                // Header line
                val id = header.str("id") ?: "unknown"
                val type = header.str("type") ?: "Unknown"
                val name = header.str("name") ?: id
                appendLine("[$id] $type: $name")

                // Summary
                header.str("summary")?.let { appendLine("  $it") }

                // Non-stripped header fields
                if (!stripped) {
                    header.str("version")?.let { appendLine("  version: $it") }
                    header.str("created_at")?.let { appendLine("  created: $it") }
                    header.str("modified_at")?.let { appendLine("  modified: $it") }
                    header["relationships"]?.jsonArray?.let { rels ->
                        if (rels.isNotEmpty()) {
                            appendLine("  relationships:")
                            rels.forEach { rel ->
                                val rObj = rel.jsonObject
                                val target = rObj.str("target_id") ?: "?"
                                val rType = rObj.str("type") ?: "?"
                                appendLine("    $rType -> $target")
                            }
                        }
                    }
                    header.str("filePath")?.let { appendLine("  path: $it") }
                }

                // Payload
                if (payload != null && payload.isNotEmpty()) {
                    appendLine()
                    renderObject(payload, this, indent = 0)
                }

                // Execute block
                if (execute != null && execute.isNotEmpty()) {
                    appendLine()
                    appendLine("execute:")
                    renderObject(execute, this, indent = 1)
                }
            }.trimEnd()
        } catch (_: Exception) {
            rawContent // Fallback: return original JSON on parse failure
        }
    }

    // =========================================================================
    // Internal rendering
    // =========================================================================

    private fun renderObject(obj: JsonObject, sb: StringBuilder, indent: Int) {
        val prefix = "  ".repeat(indent)
        for ((key, value) in obj) {
            renderElement(key, value, sb, indent)
        }
    }

    private fun renderElement(key: String, element: JsonElement, sb: StringBuilder, indent: Int) {
        val prefix = "  ".repeat(indent)
        when (element) {
            is JsonPrimitive -> {
                val content = element.contentOrNull
                if (!content.isNullOrBlank()) {
                    sb.appendLine("$prefix$key: $content")
                }
            }
            is JsonArray -> {
                if (element.isEmpty()) return
                // Check if all elements are primitives → inline comma-separated
                if (element.all { it is JsonPrimitive }) {
                    val items = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    sb.appendLine("$prefix$key: ${items.joinToString(", ")}")
                } else if (element.all { it is JsonObject }) {
                    // Array of objects
                    sb.appendLine("$prefix$key:")
                    element.forEachIndexed { index, item ->
                        val obj = item.jsonObject
                        // Try to render compact if object is small
                        if (obj.size <= 3 && obj.values.all { it is JsonPrimitive }) {
                            val parts = obj.entries.mapNotNull { (k, v) ->
                                (v as? JsonPrimitive)?.contentOrNull?.let { "$k=$it" }
                            }
                            sb.appendLine("$prefix  - ${parts.joinToString(", ")}")
                        } else {
                            sb.appendLine("$prefix  [$index]:")
                            renderObject(obj, sb, indent + 2)
                        }
                    }
                } else {
                    sb.appendLine("$prefix$key: ${element}")
                }
            }
            is JsonObject -> {
                if (element.isEmpty()) return
                // Single string child → flatten
                if (element.size == 1 && element.values.first() is JsonPrimitive) {
                    val (childKey, childVal) = element.entries.first()
                    val content = (childVal as JsonPrimitive).contentOrNull
                    if (!content.isNullOrBlank()) {
                        sb.appendLine("$prefix$key: $content")
                    }
                    return
                }
                sb.appendLine("$prefix$key:")
                renderObject(element, sb, indent + 1)
            }
            is JsonNull -> { /* skip */ }
        }
    }

    /** Helper to safely extract a string from a JsonObject. */
    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
