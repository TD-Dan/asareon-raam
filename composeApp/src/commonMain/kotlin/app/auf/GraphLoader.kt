package app.auf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File


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
 * Handles all logic related to discovering and loading the Holon Knowledge Graph from the file system.
 */
class GraphLoader(
    private val holonsBasePath: String,
    private val jsonParser: Json
) {

    /**
     * The primary public method to load the entire graph for a given persona.
     * It discovers available personas, determines which one to load, and then traverses its directory.
     * @param currentPersonaId The currently selected persona ID from the app state, can be null.
     * @return A [GraphLoadResult] containing the complete outcome of the operation.
     */
    fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        val parsingErrors = mutableListOf<String>()
        try {
            val holonsDir = File(holonsBasePath)
            if (!holonsDir.exists() || !holonsDir.isDirectory) {
                return GraphLoadResult(fatalError = "Holon directory not found at resolved path: ${holonsDir.absolutePath}")
            }

            val availablePersonas = discoverAvailablePersonas(holonsDir)

            val determinedPersonaId = determinePersonaToLoad(currentPersonaId, availablePersonas)

            if (determinedPersonaId == null) {
                val errorMsg = if (availablePersonas.isNotEmpty()) "Please select an Active Agent to begin." else "No AI_Persona_Root holons found in the 'holons' directory."
                return GraphLoadResult(availableAiPersonas = availablePersonas, fatalError = errorMsg)
            }

            val graph = mutableListOf<HolonHeader>()
            val rootDirectory = File(holonsDir, determinedPersonaId)
            traverseAndLoad(rootDirectory, null, 0, graph, parsingErrors)

            if (graph.isEmpty()) {
                return GraphLoadResult(
                    availableAiPersonas = availablePersonas,
                    determinedPersonaId = determinedPersonaId,
                    fatalError = "Failed to load any holons from root: ${rootDirectory.absolutePath}"
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

    private fun discoverAvailablePersonas(holonsDir: File): List<HolonHeader> {
        return holonsDir.listFiles { file ->
            file.isDirectory
        }?.mapNotNull { dir ->
            val holonFile = File(dir, "${dir.name}.json")
            if (holonFile.exists()) {
                try {
                    val content = holonFile.readText()
                    val header = jsonParser.decodeFromString<HolonFileContent>(content).header
                    if (header.type == "AI_Persona_Root") header.copy(filePath = holonFile.path) else null
                } catch (e: Exception) {
                    println("Warning: Malformed persona found and ignored in dir: ${dir.name}")
                    null
                }
            } else null
        } ?: emptyList()
    }

    private fun determinePersonaToLoad(currentId: String?, personas: List<HolonHeader>): String? {
        return if (personas.none { it.id == currentId }) {
            // If current selection is invalid, auto-select if there's only one option
            if (personas.size == 1) personas.first().id else null
        } else {
            currentId
        }
    }


    private fun traverseAndLoad(
        holonDirectory: File,
        parentId: String?,
        depth: Int,
        graph: MutableList<HolonHeader>,
        parsingErrors: MutableList<String>
    ) {
        if (!holonDirectory.exists() || !holonDirectory.isDirectory) {
            return
        }

        val holonId = holonDirectory.name
        val holonFile = File(holonDirectory, "$holonId.json")

        if (!holonFile.exists()) {
            parsingErrors.add("File not found for dir: ${holonDirectory.path}")
            return
        }

        try {
            val fileContentString = holonFile.readText()
            val parsedFile = jsonParser.decodeFromString<HolonFileContent>(fileContentString)
            var header = parsedFile.header

            header = header.copy(
                filePath = holonFile.path,
                parentId = parentId,
                depth = depth
            )
            graph.add(header)

            header.subHolons.forEach { subRef ->
                val subHolonDirectory = File(holonDirectory, subRef.id)
                traverseAndLoad(subHolonDirectory, header.id, depth + 1, graph, parsingErrors)
            }
        } catch (e: Exception) {
            val error = "Parse failed for $holonId: ${e.message?.substringBefore('\n')}"
            parsingErrors.add(error)
            e.printStackTrace()
        }
    }
}