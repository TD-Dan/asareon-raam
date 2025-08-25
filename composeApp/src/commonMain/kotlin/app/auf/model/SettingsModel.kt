package app.auf.model

import kotlinx.serialization.Serializable

/**
 * Defines the decoupled, declarative contracts for application settings.
 *
 * ---
 * ## Mandate
 * This file contains the data classes that define the *schema* for a setting, completely
 * separating its definition (what it is) from its implementation (what it does). This allows
 * services to be self-describing and enables the UI to dynamically build a settings
 * page without being coupled to any specific service.
 *
 * ---
 * ## Dependencies
 * - `kotlinx.serialization`: For the `SettingValue` container.
 *
 * @version 1.0
 * @since 2025-08-25
 */

/**
 * Represents the complete, declarative definition of a single setting.
 *
 * @param key A globally unique identifier for the setting (e.g., "compiler.cleanHeaders").
 * @param section The UI section to group this setting under (e.g., "Prompt Compiler").
 * @param label The human-readable name for the setting control.
 * @param description A tooltip or helper text explaining what the setting does.
 * @param type The data type of the setting, used to render the correct UI control.
 */
data class SettingDefinition(
    val key: String,
    val section: String,
    val label: String,
    val description: String,
    val type: SettingType
)

/**
 * Enumerates the types of UI controls that can be rendered for a setting.
 */
enum class SettingType {
    BOOLEAN
    // Future types could include: STRING_CHOICE, NUMERIC, etc.
}

/**
 * A generic container for dispatching updates for any setting value via an AppAction.
 *
 * @param key The unique key of the setting to update.
 * @param value The new value for the setting. For now, it's a Boolean, but can be
 *              expanded to `Any` with polymorphic serialization if other types are needed.
 */
@Serializable
data class SettingValue(val key: String, val value: Boolean)