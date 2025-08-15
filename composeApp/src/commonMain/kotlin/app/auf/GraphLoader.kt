package app.auf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
private data class HolonFileContent(
    val header: HolonHeader,
    val payload: JsonObject
)

/**
 * A result class to holistically capture the outcome of a graph loading operation.
 */
data class GraphLoadResult(
    val holonGraph: List<HolonHeader> = emptyList(),
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val determinedPersonaId: String? = null,
    val fatalError: String? = null
)

/**
 * ---
 * ## Mandate
 * Handles all logic related to discovering and loading the Holon Knowledge Graph from the file system.
 * It contains only business logic for graph traversal and data parsing. It delegates all actual
 * file I/O (reading files, listing directories) to the injected `PlatformDependencies` instance.
 *
 * ---
 * ## Dependencies
 * - `app.auf.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For parsing Holon files.
 *
 * @version 2.0
 * @since 2025-08-15
 */
class GraphLoader(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {
    private val holonsBasePath = platform.getBasePathFor("holons")

    /**
     * The primary public method to load the entire graph for a given persona.
     * It discovers available personas, determines which one to load, and then traverses its directory.
     * @param currentPersonaId The currently selected persona ID from the app state, can be null.
     * @return A [GraphLoadResult] containing the complete outcome of the operation.
     */
    fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        val parsingErrors = mutableListOf<String>()
        try {
            if (!platform.fileExists(holonsBasePath)) {
                return GraphLoadResult(fatalError = "Holon directory not found at resolved path: $holonsBasePath")
            }

            val availablePersonas = discoverAvailablePersonas(holonsBasePath)
            val determinedPersonaId = determinePersonaToLoad(currentPersonaId, availablePersonas)

            if (determinedPersonaId == null) {
                val errorMsg = if (availablePersonas.isNotEmpty()) "Please select an Active Agent to begin." else "No AI_Persona_Root holons found in the 'holons' directory."
                return GraphLoadResult(availableAiPersonas = availablePersonas, fatalError = errorMsg)
            }

            val graph = mutableListOf<HolonHeader>()
            val rootDirectoryPath = holonsBasePath + platform.pathSeparator + determinedPersonaId
            traverseAndLoad(rootDirectoryPath, null, 0, graph, parsingErrors)

            // --- Scan and add quarantined files ---
            val quarantineDirPath = rootDirectoryPath + platform.pathSeparator + "quarantined-imports"
            if (platform.fileExists(quarantineDirPath)) {
                platform.listDirectory(quarantineDirPath).forEach { fileEntry ->
                    val dummyHeader = HolonHeader(
                        id = platform.getFileName(fileEntry.path),
                        type = "Quarantined_File",
                        name = platform.getFileName(fileEntry.path),
                        summary = "This file is in quarantine. Inspect it to see its raw content. It may be malformed or fail schema validation.",
                        filePath = fileEntry.path,
                        depth = 1
                    )
                    graph.add(dummyHeader)
                }
            }

            if (graph.isEmpty()) {
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

    private fun discoverAvailablePersonas(holonsDirPath: String): List<HolonHeader> {
        return platform.listDirectory(holonsDirPath).filter { it.isDirectory }.mapNotNull { dirEntry ->
            val dirName = platform.getFileName(dirEntry.path)
            val holonFilePath = dirEntry.path + platform.pathSeparator + "$dirName.json"
            if (platform.fileExists(holonFilePath)) {
                try {
                    val content = platform.readFileContent(holonFilePath)
                    val header = jsonParser.decodeFromString<HolonFileContent>(content).header
                    if (header.type == "AI_Persona_Root") header.copy(filePath = holonFilePath) else null
                } catch (e: Exception) {
                    println("Warning: Malformed persona found and ignored in dir: $dirName")
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
        graph: MutableList<HolonHeader>,
        parsingErrors: MutableList<String>
    ) {
        if (!platform.fileExists(holonDirectoryPath)) {
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
            val parsedFile = jsonParser.decodeFromString<HolonFileContent>(fileContentString)
            var header = parsedFile.header

            header = header.copy(
                filePath = holonFilePath,
                parentId = parentId,
                depth = depth
            )
            graph.add(header)

            header.subHolons.forEach { subRef ->
                val subHolonDirectoryPath = holonDirectoryPath + platform.pathSeparator + subRef.id
                traverseAndLoad(subHolonDirectoryPath, header.id, depth + 1, graph, parsingErrors)
            }
        } catch (e: Exception) {
            val error = "Parse failed for $holonId: ${e.message?.substringBefore('\n')}"
            parsingErrors.add(error)
            e.printStackTrace()
        }
    }
}