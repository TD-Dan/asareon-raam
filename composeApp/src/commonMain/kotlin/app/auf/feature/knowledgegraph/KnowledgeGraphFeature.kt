package app.auf.feature.knowledgegraph

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

// --- 1. MODEL ---
/**
 * The state slice for the KnowledgeGraph feature. This is the single source of truth for
 * all data related to the holon graph, including its in-memory representation, UI state,
 * and the state of any import/export operations.
 */
@Serializable
data class KnowledgeGraphState(
    val holonGraph: List<Holon> = emptyList(),
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val aiPersonaId: String? = null,
    val isLoading: Boolean = false,
    val fatalError: String? = null,
    val parsingErrors: List<String> = emptyList(),

    // UI State
    val catalogueFilter: String? = null,
    val inspectedHolonId: String? = null,
    val contextualHolonIds: Set<String> = emptySet(),

    // Import/Export State
    val viewMode: KnowledgeGraphViewMode = KnowledgeGraphViewMode.INSPECTOR,
    val holonIdsForExport: Set<String> = emptySet(),
    val importSourcePath: String = "",
    val importItems: List<ImportItem> = emptyList(),
    val importSelectedActions: Map<String, ImportAction> = emptyMap(),
    val isImportRecursive: Boolean = true,
    val showOnlyChangedImportItems: Boolean = false
)

enum class KnowledgeGraphViewMode {
    INSPECTOR, IMPORT, EXPORT, SETTINGS
}

// --- Data classes for the feature's domain ---

data class GraphLoadResult(
    val holonGraph: List<Holon> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val fatalError: String? = null,
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val determinedPersonaId: String? = null
)

@Serializable
data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String? = null
)

data class ImportResult(
    val successfulImports: List<String>,
    val failedImports: Map<String, String>
)

@Serializable
sealed interface ImportAction {
    val actionType: ImportActionType
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE, CREATE_ROOT
}

@Serializable
@SerialName("Update")
data class Update(
    val targetHolonId: String,
    override val actionType: ImportActionType = ImportActionType.UPDATE,
    override val summary: String = "Update existing holon."
) : ImportAction

@Serializable
@SerialName("Integrate")
data class Integrate(
    val parentHolonId: String,
    override val actionType: ImportActionType = ImportActionType.INTEGRATE,
    override val summary: String = "Integrate with known parent."
) : ImportAction

@Serializable
@SerialName("AssignParent")
data class AssignParent(
    var assignedParentId: String? = null,
    override val actionType: ImportActionType = ImportActionType.ASSIGN_PARENT,
    override val summary: String = "New holon - requires parent."
) : ImportAction

@Serializable
@SerialName("Quarantine")
data class Quarantine(
    val reason: String,
    override val actionType: ImportActionType = ImportActionType.QUARANTINE,
    override val summary: String = "Quarantine File"
) : ImportAction

@Serializable
@SerialName("Ignore")
data class Ignore(
    override val actionType: ImportActionType = ImportActionType.IGNORE,
    override val summary: String = "Do not import."
) : ImportAction

@Serializable
@SerialName("CreateRoot")
data class CreateRoot(
    override val actionType: ImportActionType = ImportActionType.CREATE_ROOT,
    override val summary: String = "IMPORT AS NEW ROOT PERSONA"
) : ImportAction

@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonElement
)

@Serializable
data class HolonUsage(
    val scope: String? = null,
    val description: String? = null
)

@Serializable
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String,
    val version: String = "0.0.0",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    @SerialName("holon_usage")
    val holonUsage: HolonUsage? = null,
    val relationships: List<Relationship> = emptyList(),
    @SerialName("sub_holons")
    val subHolons: List<SubHolonRef> = emptyList(),
    @Transient val filePath: String = "",
    @Transient val parentId: String? = null,
    @Transient val depth: Int = 0
)

@Serializable
data class Relationship(
    @SerialName("target_id")
    val targetId: String,
    val type: String
)

@Serializable
data class SubHolonRef(
    val id: String,
    val type: String,
    val summary: String
)


// --- 2. ACTIONS ---
sealed interface KnowledgeGraphAction : AppAction {
    data object LoadGraph : KnowledgeGraphAction
    data class LoadGraphSuccess(val result: GraphLoadResult) : KnowledgeGraphAction
    data class LoadGraphFailure(val error: String) : KnowledgeGraphAction
    data object RetryLoadGraph : KnowledgeGraphAction
    data class SelectAiPersona(val holonId: String?) : KnowledgeGraphAction
    data class InspectHolon(val holonId: String?) : KnowledgeGraphAction
    data class ToggleHolonActive(val holonId: String) : KnowledgeGraphAction
    data class SetCatalogueFilter(val type: String?) : KnowledgeGraphAction
    data class SetViewMode(val mode: KnowledgeGraphViewMode) : KnowledgeGraphAction
    data class ToggleHolonForExport(val holonId: String) : KnowledgeGraphAction
    data object SelectAllForExport : KnowledgeGraphAction
    data object DeselectAllForExport : KnowledgeGraphAction
    data class ExecuteExport(val destinationPath: String) : KnowledgeGraphAction
    data class StartImportAnalysis(val sourcePath: String) : KnowledgeGraphAction
    data class SetImportRecursive(val isRecursive: Boolean) : KnowledgeGraphAction
    data object ToggleShowOnlyChangedImportItems : KnowledgeGraphAction
    data class UpdateImportAction(val sourcePath: String, val action: ImportAction) : KnowledgeGraphAction
    data class AnalysisComplete(val items: List<ImportItem>) : KnowledgeGraphAction
    data object ExecuteImport : KnowledgeGraphAction
    data class ImportComplete(val result: ImportResult) : KnowledgeGraphAction
}


// --- 3. FEATURE IMPLEMENTATION ---
class KnowledgeGraphFeature(
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "KnowledgeGraphFeature"
    private var store: Store? = null
    private val holonsBasePath by lazy { platform.getBasePathFor(BasePath.HOLONS) }

    /**
     * A private, self-contained JSON parser configured specifically for this feature's needs.
     */
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


    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is KnowledgeGraphAction) return state
        val currentState = state.featureStates[name] as? KnowledgeGraphState ?: KnowledgeGraphState()
        val newFeatureState = when (action) {
            is KnowledgeGraphAction.LoadGraph -> currentState.copy(isLoading = true, fatalError = null, holonGraph = emptyList())
            is KnowledgeGraphAction.LoadGraphSuccess -> {
                val result = action.result
                currentState.copy(
                    isLoading = false,
                    holonGraph = result.holonGraph,
                    parsingErrors = result.parsingErrors,
                    availableAiPersonas = result.availableAiPersonas,
                    aiPersonaId = result.determinedPersonaId,
                    fatalError = if (result.determinedPersonaId == null && result.availableAiPersonas.isNotEmpty()) "Please select an Active Agent to begin." else result.fatalError
                )
            }
            is KnowledgeGraphAction.LoadGraphFailure -> currentState.copy(isLoading = false, fatalError = action.error)
            is KnowledgeGraphAction.SelectAiPersona -> currentState.copy(aiPersonaId = action.holonId, contextualHolonIds = emptySet(), inspectedHolonId = null)
            is KnowledgeGraphAction.InspectHolon -> currentState.copy(inspectedHolonId = action.holonId)
            is KnowledgeGraphAction.ToggleHolonActive -> {
                if (action.holonId == currentState.aiPersonaId) return state
                val newContextIds = if (currentState.contextualHolonIds.contains(action.holonId)) {
                    currentState.contextualHolonIds - action.holonId
                } else {
                    currentState.contextualHolonIds + action.holonId
                }
                currentState.copy(contextualHolonIds = newContextIds)
            }
            is KnowledgeGraphAction.SetCatalogueFilter -> currentState.copy(catalogueFilter = action.type)
            is KnowledgeGraphAction.SetViewMode -> {
                val newExportIds = if (action.mode == KnowledgeGraphViewMode.EXPORT) {
                    (currentState.contextualHolonIds + (currentState.aiPersonaId ?: "")).filter { it.isNotBlank() }.toSet()
                } else {
                    emptySet()
                }
                currentState.copy(viewMode = action.mode, holonIdsForExport = newExportIds)
            }
            is KnowledgeGraphAction.ToggleHolonForExport -> {
                if (action.holonId == currentState.aiPersonaId) return state
                val newExportIds = if (currentState.holonIdsForExport.contains(action.holonId)) {
                    currentState.holonIdsForExport - action.holonId
                } else {
                    currentState.holonIdsForExport + action.holonId
                }
                currentState.copy(holonIdsForExport = newExportIds)
            }
            is KnowledgeGraphAction.SelectAllForExport -> {
                val allIds = currentState.holonGraph.map { it.header.id }.toSet()
                currentState.copy(holonIdsForExport = allIds)
            }
            is KnowledgeGraphAction.DeselectAllForExport -> {
                val personaId = currentState.aiPersonaId
                if (personaId != null) {
                    currentState.copy(holonIdsForExport = setOf(personaId))
                } else {
                    currentState.copy(holonIdsForExport = emptySet())
                }
            }
            is KnowledgeGraphAction.StartImportAnalysis -> currentState.copy(importSourcePath = action.sourcePath, isImportRecursive = true, showOnlyChangedImportItems = false)
            is KnowledgeGraphAction.SetImportRecursive -> currentState.copy(isImportRecursive = action.isRecursive)
            is KnowledgeGraphAction.ToggleShowOnlyChangedImportItems -> currentState.copy(showOnlyChangedImportItems = !currentState.showOnlyChangedImportItems)
            is KnowledgeGraphAction.AnalysisComplete -> currentState.copy(importItems = action.items, importSelectedActions = action.items.associate { it.sourcePath to it.initialAction })
            is KnowledgeGraphAction.UpdateImportAction -> currentState.copy(importSelectedActions = currentState.importSelectedActions + (action.sourcePath to action.action))
            else -> currentState
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store
        dispatch(KnowledgeGraphAction.LoadGraph)
    }

    private fun dispatch(action: AppAction) {
        val store = this.store ?: return
        when (action) {
            is KnowledgeGraphAction.LoadGraph, KnowledgeGraphAction.RetryLoadGraph -> {
                coroutineScope.launch(Dispatchers.Default) {
                    val currentPersonaId = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.aiPersonaId
                    val result = _loadGraph(currentPersonaId)
                    withContext(Dispatchers.Main) {
                        if (result.fatalError != null && result.holonGraph.isEmpty()) {
                            store.dispatch(KnowledgeGraphAction.LoadGraphFailure(result.fatalError))
                        } else {
                            store.dispatch(KnowledgeGraphAction.LoadGraphSuccess(result))
                        }
                    }
                }
            }
            is KnowledgeGraphAction.SelectAiPersona -> {
                store.dispatch(action)
                dispatch(KnowledgeGraphAction.LoadGraph)
            }
            is KnowledgeGraphAction.StartImportAnalysis -> {
                store.dispatch(action)
                _analyzeImportFolder(action.sourcePath)
            }
            is KnowledgeGraphAction.SetImportRecursive -> {
                store.dispatch(action)
                val sourcePath = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.importSourcePath
                if (!sourcePath.isNullOrBlank()) {
                    _analyzeImportFolder(sourcePath)
                }
            }
            is KnowledgeGraphAction.ExecuteImport -> {
                coroutineScope.launch(Dispatchers.Default) {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val result = _executeImport(kgState.importSelectedActions, kgState.holonGraph.map { it.header }, kgState.aiPersonaId)
                    withContext(Dispatchers.Main) {
                        store.dispatch(KnowledgeGraphAction.ImportComplete(result))
                        if (result.failedImports.isNotEmpty()) {
                            val failedFiles = result.failedImports.keys.joinToString { it.substringAfterLast('/') }
                            val errorMessage = "Import completed with ${result.failedImports.size} errors: $failedFiles"
                            store.dispatch(AppAction.ShowToast(errorMessage))
                        }
                        if (result.successfulImports.isNotEmpty()) {
                            store.dispatch(AppAction.ShowToast("Import successful! Reloading graph..."))
                        }
                        dispatch(KnowledgeGraphAction.LoadGraph)
                        store.dispatch(KnowledgeGraphAction.SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
                    }
                }
            }
            is KnowledgeGraphAction.ExecuteExport -> {
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return
                val holonsToExport = kgState.holonGraph.filter { it.header.id in kgState.holonIdsForExport }
                _executeExport(action.destinationPath, holonsToExport.map { it.header })
                store.dispatch(AppAction.ShowToast("Export successful!"))
                store.dispatch(KnowledgeGraphAction.SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
            }
            else -> store.dispatch(action)
        }
    }

    private fun _loadGraph(currentPersonaId: String?): GraphLoadResult {
        val parsingErrors = mutableListOf<String>()
        try {
            if (!platform.fileExists(holonsBasePath)) {
                return GraphLoadResult(holonGraph = emptyList(), availableAiPersonas = emptyList(), parsingErrors = emptyList(), determinedPersonaId = null, fatalError = null)
            }
            val availablePersonas = _discoverAvailablePersonas(holonsBasePath, parsingErrors)
            val determinedPersonaId = _determinePersonaToLoad(currentPersonaId, availablePersonas)
            if (determinedPersonaId == null) {
                return if (availablePersonas.isEmpty()) {
                    GraphLoadResult(holonGraph = emptyList(), availableAiPersonas = emptyList(), parsingErrors = parsingErrors, determinedPersonaId = null, fatalError = null)
                } else {
                    GraphLoadResult(availableAiPersonas = availablePersonas, fatalError = "Please select an Active Agent to begin.")
                }
            }
            val graph = mutableListOf<Holon>()
            val rootDirectoryPath = holonsBasePath + platform.pathSeparator + determinedPersonaId
            _traverseAndLoad(rootDirectoryPath, null, 0, graph, parsingErrors)
            if (graph.isEmpty() && parsingErrors.isEmpty()) {
                return GraphLoadResult(availableAiPersonas = availablePersonas, determinedPersonaId = determinedPersonaId, fatalError = "Failed to load any holons from root: $rootDirectoryPath")
            }
            return GraphLoadResult(holonGraph = graph, availableAiPersonas = availablePersonas, parsingErrors = parsingErrors, determinedPersonaId = determinedPersonaId)
        } catch (e: Exception) {
            e.printStackTrace()
            return GraphLoadResult(fatalError = "FATAL: ${e.message}")
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

    private fun _analyzeImportFolder(sourcePath: String) {
        val store = this.store ?: return
        coroutineScope.launch(Dispatchers.Default) {
            val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
            val items = _analyzeFolder(sourcePath, kgState.holonGraph.map { it.header }, kgState.isImportRecursive)
            withContext(Dispatchers.Main) { store.dispatch(KnowledgeGraphAction.AnalysisComplete(items)) }
        }
    }

    private suspend fun _executeImport(actions: Map<String, ImportAction>, graph: List<HolonHeader>, personaId: String?): ImportResult = withContext(Dispatchers.Default) {
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

    private fun _executeExport(destinationPath: String, headersToExport: List<HolonHeader>) {
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

    private fun _analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>, recursive: Boolean): List<ImportItem> {
        if (!platform.fileExists(sourcePath)) return emptyList()
        val currentGraphMap = currentGraph.associateBy { it.id }
        val sourceFiles = _discoverJsonFiles(sourcePath, recursive)
        val sourceHolons = sourceFiles.mapNotNull {
            try { it.path to featureJsonParser.decodeFromString<Holon>(platform.readFileContent(it.path)).header } catch (_: Exception) { null }
        }.toMap()
        val existingParentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceParentMap = sourceHolons.values.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val combinedParentMap = existingParentMap + sourceParentMap
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