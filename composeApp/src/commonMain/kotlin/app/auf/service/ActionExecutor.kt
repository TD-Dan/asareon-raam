// FILE: composeApp/src/commonMain/kotlin/app/auf/ActionExecutor.kt
package app.auf.service

import app.auf.model.Action
import app.auf.util.BasePath
import app.auf.model.CreateFile
import app.auf.model.CreateHolon
import app.auf.util.PlatformDependencies
import app.auf.model.UpdateHolonContent
import app.auf.core.Holon
import app.auf.core.HolonHeader
import app.auf.core.SubHolonRef
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * A result class for the ActionExecutor's operations.
 */
sealed interface ActionExecutorResult {
    data class Success(val summary: String) : ActionExecutorResult
    data class Failure(val error: String) : ActionExecutorResult
}

/**
 * ---
 * ## Mandate
 * This class is responsible for safely executing a manifest of AI-proposed actions.
 * It contains only business logic for processing `Action` objects. It is designed to be
 * transactional on a per-action basis and provides clear feedback. All file system
 * interactions are delegated to the injected `PlatformDependencies` instance.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For parsing and writing Holon files.
 *
 * @version 2.0
 * @since 2025-08-15
 */
open class ActionExecutor(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {

    /**
     * Executes a list of actions sequentially. Implements a "fail-fast" strategy:
     * if any action fails, the process halts and returns a failure result.
     *
     * @param manifest The list of [Action] objects to execute.
     * @param personaId The ID of the currently active persona.
     * @param currentGraph The current, in-memory representation of the holon graph.
     * @return An [ActionExecutorResult] indicating success or failure.
     */
    open fun execute(
        manifest: List<Action>,
        personaId: String,
        currentGraph: List<HolonHeader>
    ): ActionExecutorResult {
        val successfulSummaries = mutableListOf<String>()

        for (action in manifest) {
            try {
                when (action) {
                    is CreateHolon -> handleCreateHolon(action, currentGraph)
                    is UpdateHolonContent -> handleUpdateHolonContent(action, currentGraph)
                    is CreateFile -> handleCreateFile(action, personaId)
                }
                successfulSummaries.add(action.summary)
            } catch (e: Exception) {
                e.printStackTrace()
                val failureReason = "Action failed: ${action.summary}. Reason: ${e.message}"
                val history = if (successfulSummaries.isNotEmpty()) {
                    " Succeeded before failure: [${successfulSummaries.joinToString(", ")}]."
                } else ""
                return ActionExecutorResult.Failure(failureReason + history)
            }
        }
        return ActionExecutorResult.Success("Manifest executed successfully: ${successfulSummaries.joinToString(", ")}.")
    }

    /**
     * Handles the creation of a new Holon.
     */
    private fun handleCreateHolon(action: CreateHolon, currentGraph: List<HolonHeader>) {
        val parentHeader = currentGraph.find { it.id == action.parentId }
            ?: throw IOException("Parent holon with ID '${action.parentId}' not found in the current graph.")

        val parentPath = parentHeader.filePath
        if (!platform.fileExists(parentPath)) throw IOException("Parent holon file does not exist at path: $parentPath")

        // 1. Read parent and prepare the updated version in-memory first.
        val parentHolon = jsonParser.decodeFromString<Holon>(platform.readFileContent(parentPath))
        val newHolonContent = jsonParser.decodeFromString<Holon>(action.content)
        val newHolonHeader = newHolonContent.header

        val newSubHolonRef = SubHolonRef(
            id = newHolonHeader.id,
            type = newHolonHeader.type,
            summary = newHolonHeader.summary
        )

        val updatedParentHolon = parentHolon.copy(
            header = parentHolon.header.copy(
                subHolons = parentHolon.header.subHolons + newSubHolonRef
            )
        )
        val updatedParentJsonString = jsonParser.encodeToString(Holon.serializer(), updatedParentHolon)

        // 2. Define path and create the new holon file on disk.
        val parentDir = platform.getParentDirectory(parentPath)!!
        val newHolonDir = parentDir + platform.pathSeparator + newHolonHeader.id
        val newHolonPath = newHolonDir + platform.pathSeparator + "${newHolonHeader.id}.json"

        if (platform.fileExists(newHolonPath)) throw IOException("Holon file already exists at path: $newHolonPath")
        platform.createDirectories(newHolonDir)

        // This is the first disk write.
        platform.writeFileContent(newHolonPath, action.content)

        // 3. If and only if the new file was written successfully, update the parent file.
        try {
            platform.writeFileContent(parentPath, updatedParentJsonString)
        } catch (e: Exception) {
            // Attempt a micro-rollback: delete the orphaned child file if the parent update fails.
            platform.deleteFile(newHolonPath)
            throw IOException("Failed to update parent holon '${parentHeader.id}' after creating child. Attempted to clean up orphaned file.", e)
        }
    }

    /**
     * Handles replacing the entire content of an existing Holon file.
     */
    private fun handleUpdateHolonContent(action: UpdateHolonContent, currentGraph: List<HolonHeader>) {
        val holonHeader = currentGraph.find { it.id == action.holonId }
            ?: throw IOException("Holon with ID '${action.holonId}' not found in the current graph.")

        val holonPath = holonHeader.filePath
        if (!platform.fileExists(holonPath)) throw IOException("Holon file does not exist at path: $holonPath")

        // Validate that the new content is at least valid JSON
        jsonParser.parseToJsonElement(action.newContent)

        platform.writeFileContent(holonPath, action.newContent)
    }

    /**
     * Handles creating an arbitrary non-Holon file, like a dream transcript.
     */
    private fun handleCreateFile(action: CreateFile, personaId: String) {
        val holonsBasePath = platform.getBasePathFor(BasePath.HOLONS)
        val personaRootPath = holonsBasePath + platform.pathSeparator + personaId
        val targetPath = personaRootPath + platform.pathSeparator + action.filePath.replace('/', platform.pathSeparator)

        if (platform.fileExists(targetPath)) throw IOException("File already exists at specified path: ${action.filePath}")

        platform.writeFileContent(targetPath, action.content)
    }
}