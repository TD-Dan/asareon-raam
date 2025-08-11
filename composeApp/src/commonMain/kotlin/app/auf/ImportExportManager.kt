package app.auf

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerializationException // --- MODIFIED: Using the correct exception type

/**
 * A stateless service class that handles all business logic for import and export operations.
 * It interacts directly with the file system but does not hold or modify application state.
 */
class ImportExportManager(
    private val frameworkBasePath: String,
    private val jsonParser: Json
) {
    // --- REMOVED: The non-KMP 'objectMapper' has been deleted. ---

    /**
     * Analyzes a source folder against the current knowledge graph to determine
     * the initial proposed action for each file.
     * @param sourcePath The path to the folder with flat holon files.
     * @param currentGraph The current list of holon headers from the app state.
     * @return A list of [ImportItem] objects representing the analysis result.
     */
    fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        val sourceDir = File(sourcePath)
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return emptyList()
        }

        val currentGraphMap = currentGraph.associateBy { it.id }
        val parentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceFiles = sourceDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()

        return sourceFiles.mapNotNull { sourceFile ->
            try {
                // --- MODIFIED: Replaced ObjectMapper with the KMP-compliant Json.parseToJsonElement ---
                val fileContent = sourceFile.readText()
                jsonParser.parseToJsonElement(fileContent) // This will throw SerializationException if JSON is malformed.

                // If valid, proceed with analysis
                val holonId = sourceFile.nameWithoutExtension
                val existingHeader = currentGraphMap[holonId]

                if (existingHeader != null) {
                    val existingFile = File(existingHeader.filePath)
                    if (existingFile.exists() && sourceFile.lastModified() > existingFile.lastModified()) {
                        ImportItem(sourceFile, Update(holonId), existingHeader.filePath)
                    } else {
                        ImportItem(sourceFile, Ignore())
                    }
                } else {
                    val knownParentId = parentMap[holonId]
                    if (knownParentId != null) {
                        val parentPath = currentGraphMap[knownParentId]?.filePath?.let { File(it).parent } ?: ""
                        ImportItem(sourceFile, Integrate(knownParentId), "$parentPath/$holonId/")
                    } else {
                        ImportItem(sourceFile, AssignParent())
                    }
                }
            } catch (e: SerializationException) { // --- MODIFIED: Catching the correct exception
                ImportItem(sourceFile, Quarantine("Malformed JSON: ${e.message?.substringBefore('\n')}"))
            } catch (e: Exception) {
                println("Could not analyze file ${sourceFile.name}, ignoring. Error: ${e.message}")
                null
            }
        }
    }


    /**
     * Executes the file copy operations for the export feature.
     * @param destinationPath The target folder for the exported files.
     * @param holonsToExport The list of holon headers to be exported.
     */
    fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>) {
        val destDir = File(destinationPath)
        if (!destDir.exists()) destDir.mkdirs()
        try {
            val manualProtocolFile = File("$frameworkBasePath/framework_protocol_manual.md")
            if (manualProtocolFile.exists()) {
                Files.copy(
                    manualProtocolFile.toPath(),
                    File(destDir, manualProtocolFile.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            println("Failed to copy manual protocol file: ${e.message}")
        }
        holonsToExport.forEach { holonHeader ->
            val sourceFile = File(holonHeader.filePath)
            val destFile = File(destDir, sourceFile.name)
            try {
                Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                println("Failed to copy ${sourceFile.name}: ${e.message}")
            }
        }
    }

    /**
     * Executes all file modifications for the import feature based on the finalized actions.
     * @param importState The final state of the import workbench.
     * @param currentGraph The current list of holon headers.
     * @param personaId The ID of the currently active AI persona.
     * @param holonsBasePath The base path of the holons directory.
     */
    fun executeImport(importState: ImportState, currentGraph: List<HolonHeader>, personaId: String, holonsBasePath: String) {
        val personaRoot = File(holonsBasePath, personaId)
        val quarantineDir = File(personaRoot, "quarantined-imports").apply { mkdirs() }

        importState.items.forEach { item ->
            val finalAction = importState.selectedActions[item.sourceFile.absolutePath] ?: item.initialAction
            try {
                when (finalAction) {
                    is Update -> {
                        val targetHolon = currentGraph.find { it.id == finalAction.targetHolonId }
                        if (targetHolon != null) {
                            val destFile = File(targetHolon.filePath)
                            item.sourceFile.copyTo(destFile, overwrite = true)
                        }
                    }
                    is Integrate -> {
                        val parentHolon = currentGraph.find { it.id == finalAction.parentHolonId }
                        if (parentHolon != null) {
                            val parentDir = File(parentHolon.filePath).parentFile
                            val newHolonDir = File(parentDir, item.sourceFile.nameWithoutExtension)
                            newHolonDir.mkdirs()
                            val destFile = File(newHolonDir, item.sourceFile.name)
                            item.sourceFile.copyTo(destFile, overwrite = true)
                        }
                    }
                    is AssignParent -> {
                        finalAction.assignedParentId?.let { parentId ->
                            val parentHolonHeader = currentGraph.find { it.id == parentId }
                            if (parentHolonHeader != null) {
                                val parentFile = File(parentHolonHeader.filePath)
                                val parentContent = jsonParser.decodeFromString<Holon>(parentFile.readText())
                                val newHolonHeader = jsonParser.decodeFromString<Holon>(item.sourceFile.readText()).header

                                val newSummary = "[IMPORTED-UNVALIDATED]: ${newHolonHeader.summary}. AI must treat this as foreign material until reviewed and integrated."
                                val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, newSummary)

                                if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
                                    val updatedSubHolons = parentContent.header.subHolons + newSubRef
                                    val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = updatedSubHolons))
                                    parentFile.writeText(jsonParser.encodeToString(Holon.serializer(), updatedParent))
                                }

                                val parentDir = parentFile.parentFile
                                val newHolonDir = File(parentDir, item.sourceFile.nameWithoutExtension)
                                newHolonDir.mkdirs()
                                val destFile = File(newHolonDir, item.sourceFile.name)
                                item.sourceFile.copyTo(destFile, overwrite = true)
                            }
                        }
                    }
                    is Quarantine -> {
                        val destFile = File(quarantineDir, item.sourceFile.name)
                        item.sourceFile.copyTo(destFile, overwrite = true)
                    }
                    is Ignore -> { /* Do nothing */
                    }
                }
            } catch (e: Exception) {
                println("Error processing import for ${item.sourceFile.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}