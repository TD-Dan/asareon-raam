package app.auf.service

import app.auf.core.AssignParent
import app.auf.core.CreateRoot
import app.auf.core.Holon
import app.auf.core.HolonHeader
import app.auf.core.Ignore
import app.auf.core.ImportAction
import app.auf.core.ImportItem
import app.auf.core.Integrate
import app.auf.core.Quarantine
import app.auf.core.SubHolonRef
import app.auf.core.Update
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ---
 * ## Mandate
 * Provides a platform-agnostic service for managing all business logic for import and export operations.
 * This class orchestrates the analysis and execution of file transfers, delegating all actual
 * file I/O to the injected `PlatformDependencies` instance. It has no knowledge of the
 * underlying file system implementation.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: The contract for all platform-specific I/O.
 * - `kotlinx.serialization.json.Json`: For parsing and writing Holon files.
 *
 * @version 2.2
 * @since 2025-08-28
 */
open class ImportExportManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {

    // --- MODIFICATION START: Added a recursive file discovery function ---
    private fun discoverJsonFiles(startPath: String): List<FileEntry> {
        val allFiles = mutableListOf<FileEntry>()
        val entries = platform.listDirectory(startPath)

        for (entry in entries) {
            if (entry.isDirectory) {
                allFiles.addAll(discoverJsonFiles(entry.path))
            } else if (entry.path.endsWith(".json")) {
                allFiles.add(entry)
            }
        }
        return allFiles
    }
    // --- MODIFICATION END ---


    /**
     * Analyzes a source folder against the current knowledge graph to determine
     * the initial proposed action for each file.
     */
    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        if (!platform.fileExists(sourcePath)) {
            return emptyList()
        }

        val currentGraphMap = currentGraph.associateBy { it.id }
        // --- MODIFICATION: Scan recursively ---
        val sourceFiles = discoverJsonFiles(sourcePath)

        // --- MODIFICATION: Pre-parse all source holons to resolve internal relationships ---
        val sourceHolons = sourceFiles.mapNotNull {
            try {
                val content = platform.readFileContent(it.path)
                it.path to jsonParser.decodeFromString<Holon>(content).header
            } catch (e: Exception) {
                null
            }
        }.toMap()
        val sourceParentMap = sourceHolons.values.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        // --- END MODIFICATION ---

        return sourceFiles.mapNotNull { sourceFileEntry ->
            try {
                val holonId = platform.getFileName(sourceFileEntry.path).removeSuffix(".json")
                val sourceHeader = sourceHolons[sourceFileEntry.path]
                val existingHeader = currentGraphMap[holonId]

                // --- MODIFICATION: Re-ordered logic for clarity and correctness ---
                when {
                    // 1. If it's a malformed file, quarantine it.
                    sourceHeader == null -> {
                        ImportItem(sourceFileEntry.path, Quarantine("Malformed JSON or file read error."))
                    }
                    // 2. If it's a new AI Persona Root, propose creating a new root.
                    sourceHeader.type == "AI_Persona_Root" && existingHeader == null -> {
                        ImportItem(sourceFileEntry.path, CreateRoot())
                    }
                    // 3. If it's an update to an existing holon, propose update.
                    existingHeader != null -> {
                        if (platform.getLastModified(sourceFileEntry.path) > platform.getLastModified(existingHeader.filePath)) {
                            ImportItem(sourceFileEntry.path, Update(holonId), existingHeader.filePath)
                        } else {
                            ImportItem(sourceFileEntry.path, Ignore())
                        }
                    }
                    // 4. If its parent is IN THE SOURCE SET, propose integration.
                    sourceParentMap.containsKey(holonId) -> {
                        val parentId = sourceParentMap[holonId]!!
                        // We can't determine the full target path yet, so we leave it null.
                        // The execution logic will handle placing it relative to its parent.
                        ImportItem(sourceFileEntry.path, Integrate(parentId))
                    }
                    // 5. Otherwise, it's a new, untethered holon that needs a parent from the existing graph.
                    else -> {
                        ImportItem(sourceFileEntry.path, AssignParent())
                    }
                }

            } catch (e: Exception) {
                println("Could not analyze file ${platform.getFileName(sourceFileEntry.path)}, ignoring. Error: ${e.message}")
                null
            }
        }
    }


    /**
     * Executes the file copy operations for the export feature.
     */
    open fun executeExport(destinationPath: String, headersToExport: List<HolonHeader>) {
        if (!platform.fileExists(destinationPath)) platform.createDirectories(destinationPath)
        try {
            val manualProtocolPath = platform.getBasePathFor(BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol_manual.md"
            if (platform.fileExists(manualProtocolPath)) {
                val destPath = destinationPath + platform.pathSeparator + "framework_protocol_manual.md"
                platform.copyFile(manualProtocolPath, destPath)
            }
        } catch (e: Exception) {
            println("Failed to copy manual protocol file: ${e.message}")
        }
        headersToExport.forEach { holonHeader ->
            val sourcePath = holonHeader.filePath
            val destPath = destinationPath + platform.pathSeparator + platform.getFileName(sourcePath)
            try {
                platform.copyFile(sourcePath, destPath)
            } catch (e: Exception) {
                println("Failed to copy ${platform.getFileName(sourcePath)}: ${e.message}")
            }
        }
    }

    /**
     * Executes all file modifications for the import feature based on the finalized actions.
     */
    open suspend fun executeImport(
        sourcePath: String,
        actions: Map<String, ImportAction>,
        graph: List<HolonHeader>,
        personaId: String
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val holonsBasePath = platform.getBasePathFor(BasePath.HOLONS)

            // --- MODIFICATION: Build a map of NEWLY created holon paths for dependency resolution ---
            val newHolonPaths = mutableMapOf<String, String>()

            actions.forEach { (sourceFilePath, finalAction) ->
                if (!platform.fileExists(sourceFilePath)) {
                    println("Skipping import for $sourceFilePath as it does not exist.")
                    return@forEach
                }

                try {
                    when (finalAction) {
                        is CreateRoot -> {
                            val newHolonContent = jsonParser.decodeFromString<Holon>(platform.readFileContent(sourceFilePath))
                            val newPersonaId = newHolonContent.header.id
                            val newPersonaRootPath = holonsBasePath + platform.pathSeparator + newPersonaId
                            platform.createDirectories(newPersonaRootPath)
                            val destPath = newPersonaRootPath + platform.pathSeparator + platform.getFileName(sourceFilePath)
                            platform.copyFile(sourceFilePath, destPath)
                        }
                        is Update -> {
                            val targetHolon = graph.find { it.id == finalAction.targetHolonId }
                            if (targetHolon != null) {
                                platform.copyFile(sourceFilePath, targetHolon.filePath)
                            }
                        }
                        is Integrate -> {
                            val parentHolonHeader = graph.find { it.id == finalAction.parentHolonId }
                                ?: sourcePath.let { sp -> newHolonPaths[finalAction.parentHolonId]?.let { hp -> HolonHeader(id=finalAction.parentHolonId, filePath=hp, type="", name="", summary = "") } } // Check existing graph OR newly added holons

                            if (parentHolonHeader != null) {
                                val parentDir = platform.getParentDirectory(parentHolonHeader.filePath)!!
                                val holonId = platform.getFileName(sourceFilePath).removeSuffix(".json")
                                val newHolonDir = parentDir + platform.pathSeparator + holonId
                                platform.createDirectories(newHolonDir)
                                val destPath = newHolonDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                platform.copyFile(sourceFilePath, destPath)
                                newHolonPaths[holonId] = destPath
                            }
                        }
                        is AssignParent -> {
                            finalAction.assignedParentId?.let { parentId ->
                                val parentHolonHeader = graph.find { it.id == parentId }
                                if (parentHolonHeader != null) {
                                    val parentPath = parentHolonHeader.filePath
                                    val parentContentString = platform.readFileContent(parentPath)
                                    val parentContent = jsonParser.decodeFromString<Holon>(parentContentString)
                                    val newHolonContentString = platform.readFileContent(sourceFilePath)
                                    val newHolonHeader = jsonParser.decodeFromString<Holon>(newHolonContentString).header

                                    val newSummary = "[IMPORTED-UNVALIDATED]: ${newHolonHeader.summary}"
                                    val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, newSummary)

                                    if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
                                        val updatedSubHolons = parentContent.header.subHolons + newSubRef
                                        val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = updatedSubHolons))
                                        platform.writeFileContent(parentPath, jsonParser.encodeToString(updatedParent))
                                    }

                                    val parentDir = platform.getParentDirectory(parentPath)!!
                                    val newHolonDir = parentDir + platform.pathSeparator + platform.getFileName(sourceFilePath).removeSuffix(".json")
                                    platform.createDirectories(newHolonDir)
                                    val destPath = newHolonDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                    platform.copyFile(sourceFilePath, destPath)
                                    newHolonPaths[newHolonHeader.id] = destPath
                                }
                            }
                        }
                        is Quarantine -> {
                            val personaRootPath = holonsBasePath + platform.pathSeparator + personaId
                            val quarantineDirPath = personaRootPath + platform.pathSeparator + "quarantined-imports"
                            platform.createDirectories(quarantineDirPath)
                            val destPath = quarantineDirPath + platform.pathSeparator + platform.getFileName(sourceFilePath)
                            platform.copyFile(sourceFilePath, destPath)
                        }
                        is Ignore -> { /* Do nothing */ }
                    }
                } catch (e: Exception) {
                    println("Error processing import for ${platform.getFileName(sourceFilePath)}: ${e.message}")
                    e.printStackTrace()
                }
            }
            Result.success("Import completed successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}