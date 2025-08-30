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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// --- MODIFICATION START: New data class for granular import results ---
data class ImportResult(
    val successfulImports: List<String>,
    val failedImports: Map<String, String> // Map of sourcePath to error messages
)
// --- MODIFICATION END ---


/**
 * Provides a platform-agnostic service for managing all business logic for import and export operations.
 *
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


    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>, recursive: Boolean): List<ImportItem> {
        if (!platform.fileExists(sourcePath)) return emptyList()

        val currentGraphMap = currentGraph.associateBy { it.id }
        val sourceFiles = discoverJsonFiles(sourcePath, recursive)

        val sourceHolons = sourceFiles.mapNotNull {
            try {
                it.path to jsonParser.decodeFromString<Holon>(platform.readFileContent(it.path)).header
            } catch (_: Exception) { null }
        }.toMap()

        // --- MODIFICATION START: Create a combined parent map for robust detection ---
        val existingParentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceParentMap = sourceHolons.values.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val combinedParentMap = existingParentMap + sourceParentMap
        // --- MODIFICATION END ---

        return sourceFiles.mapNotNull { sourceFileEntry ->
            try {
                val holonId = platform.getFileName(sourceFileEntry.path).removeSuffix(".json")
                val sourceHeader = sourceHolons[sourceFileEntry.path]
                val existingHeader = currentGraphMap[holonId]

                when {
                    sourceHeader == null -> ImportItem(sourceFileEntry.path, Quarantine("Malformed JSON or file read error."))
                    sourceHeader.type == "AI_Persona_Root" && existingHeader == null -> ImportItem(sourceFileEntry.path, CreateRoot())
                    existingHeader != null -> {
                        if (platform.getLastModified(sourceFileEntry.path) > platform.getLastModified(existingHeader.filePath)) {
                            ImportItem(sourceFileEntry.path, Update(holonId), existingHeader.filePath)
                        } else {
                            ImportItem(sourceFileEntry.path, Ignore())
                        }
                    }
                    // --- MODIFICATION: Use the combined map for lookup ---
                    combinedParentMap.containsKey(holonId) -> {
                        val parentId = combinedParentMap[holonId]!!
                        ImportItem(sourceFileEntry.path, Integrate(parentId))
                    }
                    else -> ImportItem(sourceFileEntry.path, AssignParent())
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
                platform.copyFile(manualProtocolPath, destinationPath + platform.pathSeparator + "framework_protocol_manual.md")
            }
        } catch (e: Exception) {
            println("Failed to copy manual protocol file: ${e.message}")
        }
        headersToExport.forEach { holonHeader ->
            try {
                platform.copyFile(holonHeader.filePath, destinationPath + platform.pathSeparator + platform.getFileName(holonHeader.filePath))
            } catch (e: Exception) {
                println("Failed to copy ${platform.getFileName(holonHeader.filePath)}: ${e.message}")
            }
        }
    }

    // --- MODIFICATION START: Complete refactor of executeImport for granular error handling ---
    open suspend fun executeImport(
        actions: Map<String, ImportAction>,
        graph: List<HolonHeader>,
        personaId: String?
    ): ImportResult = withContext(Dispatchers.Default) {
        val successfulImports = mutableListOf<String>()
        val failedImports = mutableMapOf<String, String>()

        val existingGraphPaths = graph.associate { it.id to it.filePath }
        val processedHolonPaths = mutableMapOf<String, String>()
        var remainingActions = actions.toMutableMap()
        var processedInPass: Int

        do {
            processedInPass = 0
            val actionsToProcess = remainingActions.toMap()
            remainingActions = mutableMapOf()

            for ((sourceFilePath, action) in actionsToProcess) {
                var wasProcessedThisPass = true
                try {
                    val holonId = platform.getFileName(sourceFilePath).removeSuffix(".json")
                    when (action) {
                        is CreateRoot, is Update, is Quarantine, is Ignore -> {
                            handleSimpleImportAction(action, sourceFilePath, holonId, personaId, existingGraphPaths, processedHolonPaths)
                        }
                        is Integrate, is AssignParent -> {
                            val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                            if (parentId == null) {
                                wasProcessedThisPass = false // Needs parent selection, cannot proceed.
                                failedImports[sourceFilePath] = "Parent not selected."
                            } else {
                                val parentFilePath = existingGraphPaths[parentId] ?: processedHolonPaths[parentId]
                                if (parentFilePath == null) {
                                    wasProcessedThisPass = false // Parent not processed yet, defer to a next pass.
                                } else {
                                    handleHierarchicalImportAction(sourceFilePath, parentFilePath, processedHolonPaths)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    failedImports[sourceFilePath] = e.message ?: "Unknown error"
                }

                if (wasProcessedThisPass) {
                    if (!failedImports.containsKey(sourceFilePath)) {
                        successfulImports.add(sourceFilePath)
                    }
                    processedInPass++
                } else {
                    // If not processed, add it back to the list for the next pass
                    if(!failedImports.containsKey(sourceFilePath)) {
                        remainingActions[sourceFilePath] = action
                    }
                }
            }
        } while (processedInPass > 0 && remainingActions.isNotEmpty())

        // Any actions still remaining are considered failures due to unresolved dependencies.
        remainingActions.forEach { (path, _) ->
            if (!failedImports.containsKey(path)) {
                failedImports[path] = "Could not resolve parent dependency."
            }
        }

        return@withContext ImportResult(successfulImports.distinct(), failedImports)
    }

    private fun handleSimpleImportAction(
        action: ImportAction,
        sourceFilePath: String,
        holonId: String,
        personaId: String?,
        existingGraphPaths: Map<String, String>,
        processedHolonPaths: MutableMap<String, String>
    ) {
        val holonsBasePath = platform.getBasePathFor(BasePath.HOLONS)
        when (action) {
            is CreateRoot -> {
                val destDir = holonsBasePath + platform.pathSeparator + holonId
                platform.createDirectories(destDir)
                val destPath = destDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
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
                    platform.copyFile(sourceFilePath, quarantineDir + platform.pathSeparator + platform.getFileName(sourceFilePath))
                }
            }
            is Ignore -> {}
            else -> {} // Should not happen
        }
    }

    private fun handleHierarchicalImportAction(
        sourceFilePath: String,
        parentFilePath: String,
        processedHolonPaths: MutableMap<String, String>
    ) {
        val holonId = platform.getFileName(sourceFilePath).removeSuffix(".json")
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
    // --- MODIFICATION END ---
}