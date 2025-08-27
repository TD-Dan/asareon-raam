package app.auf.service

import app.auf.core.GraphLoadResult
import app.auf.core.Holon
import app.auf.core.HolonHeader
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * ---
 * ## Mandate
 * Handles all logic related to discovering and loading the Holon Knowledge Graph from the file system.
 * It contains only business logic for graph traversal and data parsing. It delegates all actual
 * file I/O (reading files, listing directories) to the injected `PlatformDependencies` instance.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For parsing Holon files.
 *
 * @version 3.2
 * @since 2025-08-17
 */
open class GraphLoader(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {
    private val holonsBasePath by lazy { platform.getBasePathFor(BasePath.HOLONS) }

    /**
     * The primary public method to load the entire graph for a given persona.
     * It discovers available personas, determines which one to load, and then traverses its directory.
     * @param currentPersonaId The currently selected persona ID from the app state can be null.
     * @return A [GraphLoadResult] containing the complete outcome of the operation.
     */
    open fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        val parsingErrors = mutableListOf<String>()
        try {
            if (!platform.fileExists(holonsBasePath)) {
                // --- MODIFICATION START: Allow app to start if holons dir is missing ---
                // If the holons directory doesn't even exist, return a successful empty state.
                // This allows a first-time user to launch the app and use the import tool.
                return GraphLoadResult(
                    holonGraph = emptyList(),
                    availableAiPersonas = emptyList(),
                    parsingErrors = emptyList(),
                    determinedPersonaId = null,
                    fatalError = null // Not a fatal error
                )
                // --- MODIFICATION END ---
            }

            val availablePersonas = discoverAvailablePersonas(holonsBasePath, parsingErrors)
            val determinedPersonaId = determinePersonaToLoad(currentPersonaId, availablePersonas)


            // --- MODIFICATION START: Handle no-persona and multi-persona scenarios gracefully ---
            if (determinedPersonaId == null) {
                // Scenario 1: No personas exist at all. This is a valid "clean slate" for a new user.
                if (availablePersonas.isEmpty()) {
                    return GraphLoadResult(
                        holonGraph = emptyList(),
                        availableAiPersonas = emptyList(),
                        parsingErrors = parsingErrors,
                        determinedPersonaId = null,
                        fatalError = null // Not a fatal error
                    )
                }
                // Scenario 2: Multiple personas exist, but none is selected. Prompt the user.
                else {
                    val errorMsg = "Please select an Active Agent to begin."
                    return GraphLoadResult(
                        availableAiPersonas = availablePersonas,
                        fatalError = errorMsg
                    )
                }
            }
            // --- MODIFICATION END ---


            val graph = mutableListOf<Holon>()
            val rootDirectoryPath = holonsBasePath + platform.pathSeparator + determinedPersonaId
            traverseAndLoad(rootDirectoryPath, null, 0, graph, parsingErrors)


            if (graph.isEmpty() && parsingErrors.isEmpty()) {
                return GraphLoadResult(
                    availableAiPersonas = availablePersonas,
                    determinedPersonaId = determinedPersonaId,
                    fatalError = "Failed to load any holons from root: $rootDirectoryPath"
                )
            }

            return GraphLoadResult(
                holonGraph = graph,
                availableAiPersonas = availablePersonas,
                parsingErrors = parsingErrors,
                determinedPersonaId = determinedPersonaId
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return GraphLoadResult(fatalError = "FATAL: ${e.message}")
        }
    }

    private fun discoverAvailablePersonas(holonsDirPath: String, parsingErrors: MutableList<String>): List<HolonHeader> {
        return platform.listDirectory(holonsDirPath).filter { it.isDirectory }.mapNotNull { dirEntry ->
            val dirName = platform.getFileName(dirEntry.path)
            val holonFilePath = dirEntry.path + platform.pathSeparator + "$dirName.json"
            if (platform.fileExists(holonFilePath)) {
                try {
                    val content = platform.readFileContent(holonFilePath)
                    val holon = jsonParser.decodeFromString<Holon>(content)
                    if (holon.header.type == "AI_Persona_Root") holon.header.copy(filePath = holonFilePath) else null
                } catch (e: Exception) {
                    val error = "Parse failed for potential persona $dirName: ${e.message?.substringBefore('\n')}"
                    // --- FIX IS HERE: Explicit console print for immediate debugging ---
                    println("GRAPHLOADER_ERROR: $error")
                    parsingErrors.add(error)
                    null
                }
            } else null
        }
    }

    private fun determinePersonaToLoad(currentId: String?, personas: List<HolonHeader>): String? {
        return if (personas.none { it.id == currentId }) {
            if (personas.size == 1) personas.first().id else null
        } else {
            currentId
        }
    }

    private fun traverseAndLoad(
        holonDirectoryPath: String,
        parentId: String?,
        depth: Int,
        graph: MutableList<Holon>,
        parsingErrors: MutableList<String>
    ) {
        if (!platform.fileExists(holonDirectoryPath)) {
            parsingErrors.add("Directory not found for holon referenced by parent '$parentId': $holonDirectoryPath")
            return
        }

        val holonId = platform.getFileName(holonDirectoryPath)
        val holonFilePath = holonDirectoryPath + platform.pathSeparator + "$holonId.json"

        if (holonId == "quarantined-imports") return

        if (!platform.fileExists(holonFilePath)) {
            parsingErrors.add("File not found for dir: $holonDirectoryPath")
            return
        }

        try {
            val fileContentString = platform.readFileContent(holonFilePath)
            var parsedHolon = jsonParser.decodeFromString<Holon>(fileContentString)

            val updatedHeader = parsedHolon.header.copy(
                filePath = holonFilePath,
                parentId = parentId,
                depth = depth
            )
            parsedHolon = parsedHolon.copy(header = updatedHeader)
            graph.add(parsedHolon)

            parsedHolon.header.subHolons.forEach { subRef ->
                val subHolonDirectoryPath = holonDirectoryPath + platform.pathSeparator + subRef.id
                traverseAndLoad(subHolonDirectoryPath, parsedHolon.header.id, depth + 1, graph, parsingErrors)
            }
        } catch (e: Exception) {
            val error = "Parse failed for $holonId: ${e.message?.substringBefore('\n')}"
            println("GRAPHLOADER_ERROR: $error")
            parsingErrors.add(error)
            e.printStackTrace()
        }
    }
}