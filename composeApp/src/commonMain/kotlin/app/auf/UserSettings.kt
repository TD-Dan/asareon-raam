package app.auf

import kotlinx.serialization.Serializable

/**
 * A data class representing all user-configurable settings.
 *
 * ---
 * ## Mandate
 * This class serves as the single source of truth for application settings that
 * persist between sessions. It is designed to be serializable so it can be easily
 * saved to and loaded from a file by the SettingsManager. All properties must have
 * default values to ensure a graceful fallback if a settings file is missing or corrupt.
 *
 * ---
 * ## Dependencies
 * - None
 *
 * @version 1.1
 * @since 2025-08-15
 */
@Serializable
data class UserSettings(
    // Window settings
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,

    // AI interaction settings
    val selectedModel: String = "gemini-1.5-flash-latest",
    val selectedAiPersonaId: String? = null,
    val activeContextualHolonIds: Set<String> = emptySet(),

    // Feature-specific settings
    val lastUsedExportPath: String? = null,
    val lastUsedImportPath: String? = null
)