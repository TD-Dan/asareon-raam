package app.auf.model

/**
 * Defines the core data contracts for the AUF Tool System.
 *
 * ---
 * ## Mandate
 * This file contains the data classes that serve as the single source of truth for
 * defining, validating, and executing AI-invoked tools. It is the schema against
 * which the AufCommandParser operates.
 *
 * ---
 * ## Dependencies
 * None.
 *
 * @version 1.0
 * @since 2025-08-23
 */

/**
 * Represents a single parameter within a tool's definition.
 *
 * @param name The name of the parameter (e.g., "path").
 * @param type The expected data type (e.g., "String", "Int"). Used for future validation.
 * @param isRequired A flag indicating if the parser should fail if this parameter is missing.
 * @param defaultValue The value to use if an optional parameter is not provided.
 */
data class Parameter(
    val name: String,
    val type: String,
    val isRequired: Boolean,
    val defaultValue: Any? = null
)

/**
 * The definitive contract for a host tool available to the AI.
 *
 * @param name The human-readable name for UI and manifests (e.g., "Atomic Change Manifest").
 * @param command The normalized, all-caps command the parser looks for (e.g., "ACTION_MANIFEST").
 * @param description A brief explanation of the tool's purpose.
 * @param parameters A list of formal Parameter definitions the tool accepts.
 * @param expectsPayload A boolean indicating if the tool requires content between its start and end tags.
 * @param usage An example string demonstrating correct invocation syntax.
 */
data class ToolDefinition(
    val name: String,
    val command: String,
    val description: String,
    val parameters: List<Parameter>,
    val expectsPayload: Boolean,
    val usage: String
)