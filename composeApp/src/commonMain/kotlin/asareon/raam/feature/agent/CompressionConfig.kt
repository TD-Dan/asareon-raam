package asareon.raam.feature.agent

import kotlinx.serialization.Serializable

/**
 * Configuration for passive token compression strategies.
 * Each field corresponds to a toggleable setting in the "Token Compression" section.
 * Consumed by context formatters during prompt assembly.
 */
@Serializable
data class CompressionConfig(
    /** Strategy 1: Render holons as compact TOON text instead of JSON. Subsumes jsonMinify. */
    val useToon: Boolean = false,
    /** Strategy 2: Strip whitespace from holon JSON. Ignored if useToon is true. */
    val jsonMinify: Boolean = false,
    /** Strategy 3: Compress system instructions to minimal phrasing. */
    val terseSystemText: Boolean = false,
    /** Strategy 4: Compact action reference format instead of verbose docs. */
    val terseActions: Boolean = false,
    /** Strategy 5: Replace common long words with standard abbreviations. */
    val abbreviations: Boolean = false,
    /** Strategy 6: Compact session message delimiters. */
    val slimMessageHeaders: Boolean = false,
    /** Strategy 7: Group consecutive collapsed holons into single blocks. */
    val consolidateCollapsed: Boolean = false,
) {
    companion object {
        const val KEY_USE_TOON = "agent.compression.use_toon"
        const val KEY_JSON_MINIFY = "agent.compression.json_minify"
        const val KEY_TERSE_SYSTEM_TEXT = "agent.compression.terse_system_text"
        const val KEY_TERSE_ACTIONS = "agent.compression.terse_actions"
        const val KEY_ABBREVIATIONS = "agent.compression.abbreviations"
        const val KEY_SLIM_MESSAGE_HEADERS = "agent.compression.slim_message_headers"
        const val KEY_CONSOLIDATE_COLLAPSED = "agent.compression.consolidate_collapsed"

        /** All setting keys with their UI labels and descriptions for SETTINGS_ADD registration. */
        val settingDefinitions = listOf(
            Triple(KEY_USE_TOON, "TOON Format", "Render holons as compact text instead of JSON. Subsumes JSON Minify."),
            Triple(KEY_JSON_MINIFY, "JSON Minify", "Remove whitespace from holon JSON. Ignored if TOON is enabled."),
            Triple(KEY_TERSE_SYSTEM_TEXT, "Terse System Text", "Shorten all system-generated instructions and routing text."),
            Triple(KEY_TERSE_ACTIONS, "Terse Action Docs", "Compact reference-card format for available actions."),
            Triple(KEY_ABBREVIATIONS, "Word Abbreviations", "Replace common long words with standard abbreviations (config, auth, impl, etc.)."),
            Triple(KEY_SLIM_MESSAGE_HEADERS, "Slim Message Headers", "Compact session message delimiters."),
            Triple(KEY_CONSOLIDATE_COLLAPSED, "Consolidate Collapsed", "Group collapsed holons into single blocks."),
        )

        /** Build config from settings values map (as returned by SETTINGS_LOADED). */
        fun fromSettings(values: Map<String, String?>): CompressionConfig = CompressionConfig(
            useToon = values[KEY_USE_TOON] == "true",
            jsonMinify = values[KEY_JSON_MINIFY] == "true",
            terseSystemText = values[KEY_TERSE_SYSTEM_TEXT] == "true",
            terseActions = values[KEY_TERSE_ACTIONS] == "true",
            abbreviations = values[KEY_ABBREVIATIONS] == "true",
            slimMessageHeaders = values[KEY_SLIM_MESSAGE_HEADERS] == "true",
            consolidateCollapsed = values[KEY_CONSOLIDATE_COLLAPSED] == "true",
        )

        /** Update a single field by settings key. Returns null if key is not a compression setting. */
        fun updateField(current: CompressionConfig, key: String, value: String?): CompressionConfig? {
            val boolValue = value == "true"
            return when (key) {
                KEY_USE_TOON -> current.copy(useToon = boolValue)
                KEY_JSON_MINIFY -> current.copy(jsonMinify = boolValue)
                KEY_TERSE_SYSTEM_TEXT -> current.copy(terseSystemText = boolValue)
                KEY_TERSE_ACTIONS -> current.copy(terseActions = boolValue)
                KEY_ABBREVIATIONS -> current.copy(abbreviations = boolValue)
                KEY_SLIM_MESSAGE_HEADERS -> current.copy(slimMessageHeaders = boolValue)
                KEY_CONSOLIDATE_COLLAPSED -> current.copy(consolidateCollapsed = boolValue)
                else -> null
            }
        }
    }
}
