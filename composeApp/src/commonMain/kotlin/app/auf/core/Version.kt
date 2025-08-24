package app.auf.core

/**
 * A single source of truth for the application's version information.
 *
 * ---
 * ## Mandate
 * This object's sole responsibility is to provide a static, easily accessible version
 * string for the entire application. This prevents version information from becoming
 * desynchronized in different parts of the codebase (e.g., UI, system prompts).
 *
 * ---
 * ## Dependencies
 * None.
 *
 * @version 1.0
 * @since 2025-08-23
 */
object Version {
    const val APP_VERSION = "1.3.2"
}