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
 * Provides a platform-agnostic service for managing all business logic for import and export operations.
 *
 * @version 2.3
 * @since 2025-08-28
 */
open class ImportExportManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {

    private fun discoverJsonFiles(startPath: String, recursive: Boolean): List<FileEntry> {
        val allFiles = mutableListOf<FileEntry>()
        val entries = platform.listDirectory(startPath)

        for (entry in entries) {
            if (entry.isDirectory && recursive) {
                allFiles.addAll(discoverJsonFiles(entry.path, true))
            } else if (!entry.isDirectory && entry.path.endsWith(".json")) {
                allFiles.add(entry)
            }
        }
        return allFiles
    }


    /**
     * Analyzes a source folder against the current knowledge graph to determine
     * the initial proposed action for each file.
     */
    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>, recursive: Boolean): List<ImportItem> {
        if (!platform.fileExists(sourcePath)) {
            return emptyList()
        }

        val currentGraphMap = currentGraph.associateBy { it.id }
        val sourceFiles = discoverJsonFiles(sourcePath, recursive)

        val sourceHolons = sourceFiles.mapNotNull {
            try {
                val content = platform.readFileContent(it.path)
                it.path to jsonParser.decodeFromString<Holon>(content).header
            } catch (e: Exception) {
                null
            }
        }.toMap()
        val sourceParentMap = sourceHolons.values.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()

        return sourceFiles.mapNotNull { sourceFileEntry ->
            try {
                val holonId = platform.getFileName(sourceFileEntry.path).removeSuffix(".json")
                val sourceHeader = sourceHolons[sourceFileEntry.path]
                val existingHeader = currentGraphMap[holonId]

                when {
                    sourceHeader == null -> {
                        ImportItem(sourceFileEntry.path, Quarantine("Malformed JSON or file read error."))
                    }
                    sourceHeader.type == "AI_Persona_Root" && existingHeader == null -> {
                        ImportItem(sourceFileEntry.path, CreateRoot())
                    }
                    existingHeader != null -> {
                        if (platform.getLastModified(sourceFileEntry.path) > platform.getLastModified(existingHeader.filePath)) {
                            ImportItem(sourceFileEntry.path, Update(holonId), existingHeader.filePath)
                        } else {
                            ImportItem(sourceFileEntry.path, Ignore())
                        }
                    }
                    sourceParentMap.containsKey(holonId) -> {
                        val parentId = sourceParentMap[holonId]!!
                        ImportItem(sourceFileEntry.path, Integrate(parentId))
                    }
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

    // --- MODIFICATION START: Complete refactor of executeImport for dependency resolution ---
    open suspend fun executeImport(
        actions: Map<String, ImportAction>,
        graph: List<HolonHeader>,
        personaId: String?
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val holonsBasePath = platform.getBasePathFor(BasePath.HOLONS)
            val existingGraphPaths = graph.associate { it.id to it.filePath }
            val processedHolonPaths = mutableMapOf<String, String>() // Maps holon ID to its new file path
            var remainingActions = actions.toMutableMap()
            var processedInPass: Int

            do {
                processedInPass = 0
                val actionsToProcess = remainingActions.toMap()
                remainingActions = mutableMapOf()

                actionsToProcess.forEach { (sourceFilePath, action) ->
                    var wasProcessed = true
                    try {
                        val holonId = platform.getFileName(sourceFilePath).removeSuffix(".json")
                        when (action) {
                            is CreateRoot -> {
                                val newPersonaId = holonId
                                val newPersonaRootPath = holonsBasePath + platform.pathSeparator + newPersonaId
                                platform.createDirectories(newPersonaRootPath)
                                val destPath = newPersonaRootPath + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                platform.copyFile(sourceFilePath, destPath)
                                processedHolonPaths[holonId] = destPath
                            }
                            is Update -> {
                                existingGraphPaths[action.targetHolonId]?.let { platform.copyFile(sourceFilePath, it) }
                            }
                            is Quarantine -> {
                                personaId?.let {
                                    val quarantineDir = holonsBasePath + platform.pathSeparator + it + platform.pathSeparator + "quarantined-imports"
                                    platform.createDirectories(quarantineDir)
                                    val destPath = quarantineDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                    platform.copyFile(sourceFilePath, destPath)
                                }
                            }
                            is Integrate, is AssignParent -> {
                                val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                                if (parentId == null) {
                                    wasProcessed = false // Cannot process yet, requires parent selection
                                } else {
                                    val parentFilePath = existingGraphPaths[parentId] ?: processedHolonPaths[parentId]
                                    if (parentFilePath == null) {
                                        wasProcessed = false // Parent not found yet, try again next pass
                                    } else {
                                        // Parent found, process this holon
                                        val parentDir = platform.getParentDirectory(parentFilePath)!!
                                        val newHolonDir = parentDir + platform.pathSeparator + holonId
                                        platform.createDirectories(newHolonDir)
                                        val destPath = newHolonDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                        platform.copyFile(sourceFilePath, destPath)
                                        processedHolonPaths[holonId] = destPath

                                        // Update the parent's sub_holons array
                                        val parentContent = jsonParser.decodeFromString<Holon>(platform.readFileContent(parentFilePath))
                                        val newHolonHeader = jsonParser.decodeFromString<Holon>(platform.readFileContent(sourceFilePath)).header
                                        val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, "[IMPORTED] " + newHolonHeader.summary)
                                        if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
                                            val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = parentContent.header.subHolons + newSubRef))
                                            platform.writeFileContent(parentFilePath, jsonParser.encodeToString(updatedParent))
                                        }
                                    }
                                }
                            }
                            is Ignore -> { /* Do nothing, already processed */ }
                        }
                    } catch (e: Exception) {
                        println("Error processing import for ${platform.getFileName(sourceFilePath)}: ${e.message}")
                        e.printStackTrace()
                    }

                    if (wasProcessed) {
                        processedInPass++
                    } else {
                        remainingActions[sourceFilePath] = action
                    }
                }
            } while (processedInPass > 0 && remainingActions.isNotEmpty())

            if (remainingActions.isNotEmpty()) {
                val unresolved = remainingActions.keys.joinToString { platform.getFileName(it) }
                return@withContext Result.failure(Exception("Import completed with errors. Could not resolve parents for: $unresolved"))
            }

            Result.success("Import completed successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
    // --- MODIFICATION END ---
}