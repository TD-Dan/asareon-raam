package app.auf

import kotlinx.serialization.Serializable

/**
 * A sealed interface representing a single, universal, and atomic action
 * that the AI can request the application to perform.
 * The application will process a list of these actions as a single transaction.
 */
@Serializable
sealed interface Action

/**
 * Instructs the app to create a new Holon. The app is responsible for
 * generating the timestamped file name (e.g., `session-record-[timestamp].json`),
 * creating the file with the provided content, and automatically updating
 * the `holon_catalogue.json` with the new entry.
 */
@Serializable
data class CreateHolon(
    // The AI provides the full, valid JSON content for the new holon as a string.
    val content: String
) : Action

/**
 * A robust action to replace the entire content of an existing Holon.
 * This is the preferred method for updates, as it is less complex and less
 * error-prone than granular JSON path operations.
 */
@Serializable
data class UpdateHolonContent(
    val holonId: String,
    val newContent: String
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
    val content: String
) : Action

/**
 * Instructs the app to apply a series of granular changes to an existing Holon.
 * NOTE: The execution logic for this is complex and deferred to a future version.
 * The AI should prefer using UpdateHolonContent for now.
 */
@Serializable
data class UpdateHolon(
    val holonId: String,
    val operations: List<UpdateSubOperation>
) : Action

/**
 * Defines a single, granular update within an UpdateHolon action.
 * NOTE: Execution logic is not yet implemented in the StateManager.
 */
@Serializable
data class UpdateSubOperation(
    // e.g., "REPLACE", "ADD_TO_LIST"
    val operation: String,
    // e.g., "payload.knowledge.project_backlog"
    val jsonPath: String,
    // The new value, encoded as a valid JSON string.
    val newValue: String
)