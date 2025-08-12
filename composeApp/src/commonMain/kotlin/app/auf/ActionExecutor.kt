package app.auf

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A result class for the ActionExecutor's operations.
 */
sealed interface ActionExecutorResult {
    data class Success(val summary: String) : ActionExecutorResult
    data class Failure(val error: String) : ActionExecutorResult
}

/**
 * This class is responsible for safely executing a manifest of AI-proposed actions
 * against the file system. It is designed to be transactional on a per-action basis
 * and to provide clear, unambiguous feedback on its outcome.
 */
class ActionExecutor(private val jsonParser: Json) {

    /**
     * Executes a list of actions sequentially. Implements a "fail-fast" strategy:
     * if any action fails, the process halts and returns a failure result.
     *
     * @param manifest The list of [Action] objects to execute.
     * @param holonsBasePath The base path of the "holons" directory.
     * @param personaId The ID of the currently active persona.
     * @param currentGraph The current, in-memory representation of the holon graph, used for lookups.
     * @return An [ActionExecutorResult] indicating success or failure.
     */
    fun execute(
        manifest: List<Action>,
        holonsBasePath: String,
        personaId: String,
        currentGraph: List<HolonHeader>
    ): ActionExecutorResult {
        val successfulSummaries = mutableListOf<String>()
        val personaRootPath = File(holonsBasePath, personaId).absolutePath

        for (action in manifest) {
            try {
                when (action) {
                    is CreateHolon -> handleCreateHolon(action, personaRootPath, currentGraph)
                    is UpdateHolonContent -> handleUpdateHolonContent(action, currentGraph)
                    is CreateFile -> handleCreateFile(action, personaRootPath)
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
     * Handles the creation of a new Holon. This is a critical transaction that involves
     * creating the new holon file and updating its parent's sub_holons array.
     */
    private fun handleCreateHolon(action: CreateHolon, personaRootPath: String, currentGraph: List<HolonHeader>) {
        val parentHeader = currentGraph.find { it.id == action.parentId }
            ?: throw IOException("Parent holon with ID '${action.parentId}' not found in the current graph.")

        val parentFile = File(parentHeader.filePath)
        if (!parentFile.exists()) throw IOException("Parent holon file does not exist at path: ${parentFile.path}")

        // 1. Read parent and prepare the updated version in-memory first.
        val parentHolon = jsonParser.decodeFromString<Holon>(parentFile.readText())
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
        val newHolonDir = File(parentFile.parentFile, newHolonHeader.id)
        val newHolonFile = File(newHolonDir, "${newHolonHeader.id}.json")

        if (newHolonFile.exists()) throw IOException("Holon file already exists at path: ${newHolonFile.path}")
        newHolonDir.mkdirs()

        // This is the first disk write.
        newHolonFile.writeText(action.content)

        // 3. If and only if the new file was written successfully, update the parent file.
        try {
            parentFile.writeText(updatedParentJsonString)
        } catch (e: Exception) {
            // Attempt a micro-rollback: delete the orphaned child file if the parent update fails.
            newHolonFile.delete()
            newHolonDir.delete()
            throw IOException("Failed to update parent holon '${parentHeader.id}' after creating child. Attempted to clean up orphaned file.", e)
        }
    }

    /**
     * Handles replacing the entire content of an existing Holon file.
     */
    private fun handleUpdateHolonContent(action: UpdateHolonContent, currentGraph: List<HolonHeader>) {
        val holonHeader = currentGraph.find { it.id == action.holonId }
            ?: throw IOException("Holon with ID '${action.holonId}' not found in the current graph.")

        val holonFile = File(holonHeader.filePath)
        if (!holonFile.exists()) throw IOException("Holon file does not exist at path: ${holonFile.path}")

        // Validate that the new content is at least valid JSON
        jsonParser.parseToJsonElement(action.newContent)

        holonFile.writeText(action.newContent)
    }

    /**
     * Handles creating an arbitrary non-Holon file, like a dream transcript.
     */
    private fun handleCreateFile(action: CreateFile, personaRootPath: String) {
        val targetFile = File(personaRootPath, action.filePath)

        // Ensure parent directories exist
        targetFile.parentFile.mkdirs()

        if (targetFile.exists()) throw IOException("File already exists at specified path: ${action.filePath}")

        targetFile.writeText(action.content)
    }
}