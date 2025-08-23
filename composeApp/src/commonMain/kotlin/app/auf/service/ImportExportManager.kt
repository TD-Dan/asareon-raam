package app.auf.service

import app.auf.core.AssignParent
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
 * @version 2.1
 * @since 2025-08-15
 */
open class ImportExportManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {

    /**
     * Analyzes a source folder against the current knowledge graph to determine
     * the initial proposed action for each file.
     */
    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        if (!platform.fileExists(sourcePath)) {
            return emptyList()
        }

        val currentGraphMap = currentGraph.associateBy { it.id }
        val parentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceFiles = platform.listDirectory(sourcePath)
            .filter { !it.isDirectory && it.path.endsWith(".json") }

        return sourceFiles.mapNotNull { sourceFileEntry ->
            try {
                // Read the file to ensure it's at least processable.
                platform.readFileContent(sourceFileEntry.path)
                val holonId = platform.getFileName(sourceFileEntry.path).removeSuffix(".json")
                val existingHeader = currentGraphMap[holonId]

                if (existingHeader != null) {
                    if (platform.getLastModified(sourceFileEntry.path) > platform.getLastModified(existingHeader.filePath)) {
                        ImportItem(sourceFileEntry.path, Update(holonId), existingHeader.filePath)
                    } else {
                        ImportItem(sourceFileEntry.path, Ignore())
                    }
                } else {
                    val knownParentId = parentMap[holonId]
                    if (knownParentId != null) {
                        val parentDir = platform.getParentDirectory(currentGraphMap[knownParentId]!!.filePath) ?: ""
                        val targetPath = parentDir + platform.pathSeparator + holonId
                        ImportItem(sourceFileEntry.path, Integrate(knownParentId), targetPath)
                    } else {
                        ImportItem(sourceFileEntry.path, AssignParent())
                    }
                }
            } catch (e: SerializationException) {
                ImportItem(sourceFileEntry.path, Quarantine("Malformed JSON: ${e.message?.substringBefore('\n')}"))
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
     * --- MODIFIED: This is now a suspend function returning a Result for asynchronous UI feedback. ---
     */
    open suspend fun executeImport(
        sourcePath: String, // Note: The sourcePath from ImportState is passed directly now
        actions: Map<String, ImportAction>,
        graph: List<HolonHeader>,
        personaId: String
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val holonsBasePath = platform.getBasePathFor(BasePath.HOLONS)
            val personaRootPath = holonsBasePath + platform.pathSeparator + personaId
            val quarantineDirPath = personaRootPath + platform.pathSeparator + "quarantined-imports"
            platform.createDirectories(quarantineDirPath)

            actions.forEach { (sourceFilePath, finalAction) ->
                if (!platform.fileExists(sourceFilePath)) {
                    println("Skipping import for $sourceFilePath as it does not exist.")
                    return@forEach // 'continue' in a forEach loop
                }

                try {
                    when (finalAction) {
                        is Update -> {
                            val targetHolon = graph.find { it.id == finalAction.targetHolonId }
                            if (targetHolon != null) {
                                platform.copyFile(sourceFilePath, targetHolon.filePath)
                            }
                        }
                        is Integrate -> {
                            val parentHolon = graph.find { it.id == finalAction.parentHolonId }
                            if (parentHolon != null) {
                                val parentDir = platform.getParentDirectory(parentHolon.filePath)!!
                                val newHolonDir = parentDir + platform.pathSeparator + platform.getFileName(sourceFilePath).removeSuffix(".json")
                                platform.createDirectories(newHolonDir)
                                val destPath = newHolonDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                                platform.copyFile(sourceFilePath, destPath)
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

                                    val newSummary = "[IMPORTED-UNVALIDATED]: ${newHolonHeader.summary}. AI must treat this as foreign material until reviewed and integrated."
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
                                }
                            }
                        }
                        is Quarantine -> {
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