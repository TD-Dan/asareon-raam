package app.auf.model

import kotlinx.serialization.Serializable

/**
 * A sealed interface representing a single, universal, and atomic action
 * that the AI can request the application to perform.
 * The application will process a list of these actions as a single transaction.
 */
@Serializable
sealed interface Action {
    // A human-readable summary of the action's intent for UI display.
    val summary: String
}

/**
 * Instructs the app to create a new Holon. The app is responsible for
 * creating the file with the provided content and, crucially,
 * updating the parent Holon's `sub_holons` array to include a reference to this new Holon.
 */
@Serializable
data class CreateHolon(
    val parentId: String,
    // The AI provides the full, valid JSON content for the new holon as a string.
    val content: String,
    override val summary: String
) : Action

/**
 * A robust action to replace the entire content of an existing Holon.
 * This is the preferred method for updates, as it is less complex and less
 * error-prone than granular JSON path operations.
 */
@Serializable
data class UpdateHolonContent(
    val holonId: String,
    val newContent: String,
    override val summary: String
) : Action

/**
 * Instructs the app to create an arbitrary non-Holon file. This is for
 * artifacts like dream transcripts or reports. The app will not add this
 * to the holon catalogue.
 */
@Serializable
data class CreateFile(
    // The full, relative path for the new file (e.g., "./dreams/dream-transcript-XYZ.md")
    val filePath: String,
    val content: String,
    override val summary: String
) : Action