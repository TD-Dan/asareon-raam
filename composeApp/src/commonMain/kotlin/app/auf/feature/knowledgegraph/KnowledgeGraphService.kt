package app.auf.feature.knowledgegraph

import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * ## Mandate
 * Encapsulates all business logic and file system interactions for the KnowledgeGraph feature.
 * This service is responsible for loading, parsing, analyzing, importing, and exporting holons.
 * It is completely stateless and operates only on the inputs it is given, returning result objects.
 */
open class KnowledgeGraphService(
    private val platform: PlatformDependencies
) {
    private val holonsBasePath by lazy { platform.getBasePathFor(BasePath.HOLONS) }
    private val featureJsonParser = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            polymorphic(ImportAction::class) {
                subclass(Update::class)
                subclass(Integrate::class)
                subclass(AssignParent::class)
                subclass(Quarantine::class)
                subclass(Ignore::class)
                subclass(CreateRoot::class)
            }
        }
    }

    suspend fun loadGraph(currentPersonaId: String?): GraphLoadResult = withContext(Dispatchers.Default) {
        val parsingErrors = mutableListOf<String>()
        try {
            if (!platform.fileExists(holonsBasePath)) {
                return@withContext GraphLoadResult(holonGraph = emptyList(), availableAiPersonas = emptyList(), parsingErrors = emptyList(), determinedPersonaId = null, fatalError = null)
            }
            val availablePersonas = _discoverAvailablePersonas(holonsBasePath, parsingErrors)
            val determinedPersonaId = _determinePersonaToLoad(currentPersonaId, availablePersonas)
            if (determinedPersonaId == null) {
                return@withContext if (availablePersonas.isEmpty()) {
                    GraphLoadResult(holonGraph = emptyList(), availableAiPersonas = emptyList(), parsingErrors = parsingErrors, determinedPersonaId = null, fatalError = null)
                } else {
                    GraphLoadResult(availableAiPersonas = availablePersonas, fatalError = "Please select an Active Agent to begin.")
                }
            }
            val graph = mutableListOf<Holon>()
            val rootDirectoryPath = holonsBasePath + platform.pathSeparator + determinedPersonaId
            _traverseAndLoad(rootDirectoryPath, null, 0, graph, parsingErrors)
            if (graph.isEmpty() && parsingErrors.isEmpty()) {
                return@withContext GraphLoadResult(availableAiPersonas = availablePersonas, determinedPersonaId = determinedPersonaId, fatalError = "Failed to load any holons from root: $rootDirectoryPath")
            }
            return@withContext GraphLoadResult(holonGraph = graph, availableAiPersonas = availablePersonas, parsingErrors = parsingErrors, determinedPersonaId = determinedPersonaId)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext GraphLoadResult(fatalError = "FATAL: ${e.message}")
        }
    }

    suspend fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>, recursive: Boolean): List<ImportItem> = withContext(Dispatchers.Default) {
        if (!platform.fileExists(sourcePath)) return@withContext emptyList()
        val currentGraphMap = currentGraph.associateBy { it.id }
        val sourceFiles = _discoverJsonFiles(sourcePath, recursive)
        val sourceHolons = sourceFiles.mapNotNull {
            try { it.path to featureJsonParser.decodeFromString<Holon>(platform.readFileContent(it.path)).header } catch (_: Exception) { null }
        }.toMap()
        val existingParentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceParentMap = sourceHolons.values.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val combinedParentMap = existingParentMap + sourceParentMap
        return@withContext sourceFiles.mapNotNull { sourceFileEntry ->
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
                    combinedParentMap.containsKey(holonId) -> {
                        val parentId = combinedParentMap[holonId]!!
                        ImportItem(sourceFileEntry.path, Integrate(parentId))
                    }
                    else -> ImportItem(sourceFileEntry.path, AssignParent())
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun executeImport(actions: Map<String, ImportAction>, graph: List<HolonHeader>, personaId: String?): ImportResult = withContext(Dispatchers.Default) {
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
                        is CreateRoot, is Update, is Quarantine, is Ignore -> _handleSimpleImportAction(action, sourceFilePath, holonId, personaId, existingGraphPaths, processedHolonPaths)
                        is Integrate, is AssignParent -> {
                            val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                            if (parentId == null) {
                                wasProcessedThisPass = false
                                failedImports[sourceFilePath] = "Parent not selected."
                            } else {
                                val parentFilePath = existingGraphPaths[parentId] ?: processedHolonPaths[parentId]
                                if (parentFilePath == null) {
                                    wasProcessedThisPass = false
                                } else {
                                    _handleHierarchicalImportAction(sourceFilePath, parentFilePath, processedHolonPaths)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    failedImports[sourceFilePath] = e.message ?: "Unknown error"
                }
                if (wasProcessedThisPass) {
                    if (!failedImports.containsKey(sourceFilePath)) successfulImports.add(sourceFilePath)
                    processedInPass++
                } else {
                    if (!failedImports.containsKey(sourceFilePath)) remainingActions[sourceFilePath] = action
                }
            }
        } while (processedInPass > 0 && remainingActions.isNotEmpty())
        remainingActions.forEach { (path, _) -> if (!failedImports.containsKey(path)) failedImports[path] = "Could not resolve parent dependency." }
        return@withContext ImportResult(successfulImports.distinct(), failedImports)
    }

    suspend fun executeExport(destinationPath: String, headersToExport: List<HolonHeader>) = withContext(Dispatchers.Default) {
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

    private fun _discoverAvailablePersonas(holonsDirPath: String, parsingErrors: MutableList<String>): List<HolonHeader> {
        return platform.listDirectory(holonsDirPath).filter { it.isDirectory }.mapNotNull { dirEntry ->
            val dirName = platform.getFileName(dirEntry.path)
            val holonFilePath = dirEntry.path + platform.pathSeparator + "$dirName.json"
            if (platform.fileExists(holonFilePath)) {
                try {
                    val content = platform.readFileContent(holonFilePath)
                    val holon = featureJsonParser.decodeFromString<Holon>(content)
                    if (holon.header.type == "AI_Persona_Root") holon.header.copy(filePath = holonFilePath) else null
                } catch (e: Exception) {
                    parsingErrors.add("Parse failed for potential persona $dirName: ${e.message?.substringBefore('\n')}")
                    null
                }
            } else null
        }
    }

    private fun _determinePersonaToLoad(currentId: String?, personas: List<HolonHeader>): String? {
        return if (personas.none { it.id == currentId }) {
            if (personas.size == 1) personas.first().id else null
        } else {
            currentId
        }
    }

    private fun _traverseAndLoad(holonDirectoryPath: String, parentId: String?, depth: Int, graph: MutableList<Holon>, parsingErrors: MutableList<String>) {
        if (!platform.fileExists(holonDirectoryPath)) return
        val holonId = platform.getFileName(holonDirectoryPath)
        val holonFilePath = holonDirectoryPath + platform.pathSeparator + "$holonId.json"
        if (holonId == "quarantined-imports" || !platform.fileExists(holonFilePath)) return
        try {
            val fileContentString = platform.readFileContent(holonFilePath)
            val parsedHolon = featureJsonParser.decodeFromString<Holon>(fileContentString)
            val updatedHolon = parsedHolon.copy(header = parsedHolon.header.copy(filePath = holonFilePath, parentId = parentId, depth = depth))
            graph.add(updatedHolon)
            updatedHolon.header.subHolons.forEach { subRef ->
                val subHolonDirectoryPath = holonDirectoryPath + platform.pathSeparator + subRef.id
                _traverseAndLoad(subHolonDirectoryPath, updatedHolon.header.id, depth + 1, graph, parsingErrors)
            }
        } catch (e: Exception) {
            parsingErrors.add("Parse failed for $holonId: ${e.message?.substringBefore('\n')}")
        }
    }

    private fun _handleSimpleImportAction(action: ImportAction, sourceFilePath: String, holonId: String, personaId: String?, existingGraphPaths: Map<String, String>, processedHolonPaths: MutableMap<String, String>) {
        when (action) {
            is CreateRoot -> {
                val destDir = holonsBasePath + platform.pathSeparator + holonId
                platform.createDirectories(destDir)
                val destPath = destDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
                platform.copyFile(sourceFilePath, destPath)
                processedHolonPaths[holonId] = destPath
            }
            is Update -> existingGraphPaths[action.targetHolonId]?.let { platform.copyFile(sourceFilePath, it) }
            is Quarantine -> personaId?.let {
                val quarantineDir = holonsBasePath + platform.pathSeparator + it + platform.pathSeparator + "quarantined-imports"
                platform.createDirectories(quarantineDir)
                platform.copyFile(sourceFilePath, quarantineDir + platform.pathSeparator + platform.getFileName(sourceFilePath))
            }
            else -> {}
        }
    }

    private fun _handleHierarchicalImportAction(sourceFilePath: String, parentFilePath: String, processedHolonPaths: MutableMap<String, String>) {
        val holonId = platform.getFileName(sourceFilePath).removeSuffix(".json")
        val parentDir = platform.getParentDirectory(parentFilePath)!!
        val newHolonDir = parentDir + platform.pathSeparator + holonId
        platform.createDirectories(newHolonDir)
        val destPath = newHolonDir + platform.pathSeparator + platform.getFileName(sourceFilePath)
        platform.copyFile(sourceFilePath, destPath)
        processedHolonPaths[holonId] = destPath
        val parentContent = featureJsonParser.decodeFromString<Holon>(platform.readFileContent(parentFilePath))
        val newHolonHeader = featureJsonParser.decodeFromString<Holon>(platform.readFileContent(sourceFilePath)).header
        val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, "[IMPORTED] " + newHolonHeader.summary)
        if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
            val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = parentContent.header.subHolons + newSubRef))
            platform.writeFileContent(parentFilePath, featureJsonParser.encodeToString(updatedParent))
        }
    }

    private fun _discoverJsonFiles(startPath: String, recursive: Boolean): List<FileEntry> {
        val allFiles = mutableListOf<FileEntry>()
        val entries = platform.listDirectory(startPath)
        for (entry in entries) {
            if (entry.isDirectory && recursive) {
                allFiles.addAll(_discoverJsonFiles(entry.path, true))
            } else if (!entry.isDirectory && entry.path.endsWith(".json")) {
                allFiles.add(entry)
            }
        }
        return allFiles
    }
}