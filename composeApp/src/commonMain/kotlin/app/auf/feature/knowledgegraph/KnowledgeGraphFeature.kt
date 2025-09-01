package app.auf.feature.knowledgegraph

import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.service.GraphService
import app.auf.service.ImportExportManager
import app.auf.service.ImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

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
    INSPECTOR, IMPORT, EXPORT
}

// --- Data classes moved from AppState.kt for co-location ---

data class GraphLoadResult(
    val holonGraph: List<Holon> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val fatalError: String? = null,
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val determinedPersonaId: String? = null
)

data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String? = null
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
/**
 * Defines all AppActions specific to the KnowledgeGraph feature.
 */
sealed interface KnowledgeGraphAction : AppAction {
    // --- Graph Loading ---
    data object LoadGraph : KnowledgeGraphAction
    data class LoadGraphSuccess(val result: GraphLoadResult) : KnowledgeGraphAction
    data class LoadGraphFailure(val error: String) : KnowledgeGraphAction
    data object RetryLoadGraph : KnowledgeGraphAction

    // --- Persona Management ---
    data class SelectAiPersona(val holonId: String?) : KnowledgeGraphAction

    // --- UI Interaction ---
    data class InspectHolon(val holonId: String?) : KnowledgeGraphAction
    data class ToggleHolonActive(val holonId: String) : KnowledgeGraphAction
    data class SetCatalogueFilter(val type: String?) : KnowledgeGraphAction

    // --- View Mode & Export ---
    data class SetViewMode(val mode: KnowledgeGraphViewMode) : KnowledgeGraphAction
    data class ToggleHolonForExport(val holonId: String) : KnowledgeGraphAction
    data object SelectAllForExport : KnowledgeGraphAction
    data object DeselectAllForExport : KnowledgeGraphAction
    data class ExecuteExport(val destinationPath: String) : KnowledgeGraphAction


    // --- Import ---
    data class StartImportAnalysis(val sourcePath: String) : KnowledgeGraphAction
    data class SetImportRecursive(val isRecursive: Boolean) : KnowledgeGraphAction
    data object ToggleShowOnlyChangedImportItems : KnowledgeGraphAction
    data class UpdateImportAction(val sourcePath: String, val action: ImportAction) : KnowledgeGraphAction
    data class AnalysisComplete(val items: List<ImportItem>) : KnowledgeGraphAction
    data object ExecuteImport : KnowledgeGraphAction
    data class ImportComplete(val result: ImportResult) : KnowledgeGraphAction
}


// --- 3. FEATURE IMPLEMENTATION ---
/**
 * ## Mandate
 * Provides a self-contained plugin for all logic and state related to the Holon Knowledge Graph.
 * It manages loading, viewing, and I/O (import/export) of holons.
 */
class KnowledgeGraphFeature(
    private val graphService: GraphService,
    private val importExportManager: ImportExportManager,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "KnowledgeGraphFeature"
    private var store: Store? = null

    /**
     * The feature's dedicated reducer.
     */
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
                    fatalError = if (result.determinedPersonaId == null && result.availableAiPersonas.isNotEmpty()) "Please select an Active Agent to begin." else null
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
            is KnowledgeGraphAction.StartImportAnalysis -> {
                currentState.copy(
                    importSourcePath = action.sourcePath,
                    isImportRecursive = true,
                    showOnlyChangedImportItems = false
                )
            }
            is KnowledgeGraphAction.SetImportRecursive -> currentState.copy(isImportRecursive = action.isRecursive)
            is KnowledgeGraphAction.ToggleShowOnlyChangedImportItems -> currentState.copy(showOnlyChangedImportItems = !currentState.showOnlyChangedImportItems)
            is KnowledgeGraphAction.AnalysisComplete -> {
                currentState.copy(
                    importItems = action.items,
                    importSelectedActions = action.items.associate { it.sourcePath to it.initialAction }
                )
            }
            is KnowledgeGraphAction.UpdateImportAction -> {
                currentState.copy(
                    importSelectedActions = currentState.importSelectedActions + (action.sourcePath to action.action)
                )
            }

            else -> currentState
        }

        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    /**
     * The lifecycle start method, which launches the middleware-like logic.
     */
    override fun start(store: Store) {
        this.store = store
        coroutineScope.launch {
            store.state.collect { appState ->
                val kgState = appState.featureStates[name] as? KnowledgeGraphState ?: return@collect
                // You could react to state changes here if needed, e.g. auto-reloading
            }
        }
        // Initial load
        dispatch(KnowledgeGraphAction.LoadGraph)
    }

    // --- Middleware-like Action Handlers ---

    private fun dispatch(action: AppAction) {
        val store = this.store ?: return

        // Handle async logic before dispatching to the reducer
        when (action) {
            is KnowledgeGraphAction.LoadGraph, KnowledgeGraphAction.RetryLoadGraph -> {
                coroutineScope.launch(Dispatchers.Default) {
                    val currentPersonaId = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.aiPersonaId
                    val result = graphService.loadGraph(currentPersonaId)
                    if (result.fatalError != null && result.holonGraph.isEmpty()) {
                        withContext(Dispatchers.Main) { store.dispatch(KnowledgeGraphAction.LoadGraphFailure(result.fatalError)) }
                    } else {
                        withContext(Dispatchers.Main) { store.dispatch(KnowledgeGraphAction.LoadGraphSuccess(result)) }
                    }
                }
            }
            is KnowledgeGraphAction.SelectAiPersona -> {
                store.dispatch(action)
                dispatch(KnowledgeGraphAction.LoadGraph) // Trigger a reload for the new persona
            }
            is KnowledgeGraphAction.StartImportAnalysis -> {
                store.dispatch(action) // Update the source path immediately
                analyzeImportFolder(action.sourcePath)
            }
            is KnowledgeGraphAction.SetImportRecursive -> {
                store.dispatch(action)
                val sourcePath = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.importSourcePath
                if (!sourcePath.isNullOrBlank()) {
                    analyzeImportFolder(sourcePath)
                }
            }
            is KnowledgeGraphAction.ExecuteImport -> {
                coroutineScope.launch(Dispatchers.Default) {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val result = importExportManager.executeImport(
                        actions = kgState.importSelectedActions,
                        graph = kgState.holonGraph.map { it.header },
                        personaId = kgState.aiPersonaId
                    )
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
                        dispatch(KnowledgeGraphAction.LoadGraph) // Reload the graph to show changes
                        store.dispatch(KnowledgeGraphAction.SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
                    }
                }
            }
            is KnowledgeGraphAction.ExecuteExport -> {
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return
                val holonsToExport = kgState.holonGraph.filter { it.header.id in kgState.holonIdsForExport }
                importExportManager.executeExport(action.destinationPath, holonsToExport.map { it.header })
                store.dispatch(AppAction.ShowToast("Export successful!"))
                store.dispatch(KnowledgeGraphAction.SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
            }
            else -> {
                // For actions without async logic, dispatch directly.
                store.dispatch(action)
            }
        }
    }

    private fun analyzeImportFolder(sourcePath: String) {
        val store = this.store ?: return
        coroutineScope.launch(Dispatchers.Default) {
            val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
            val items = importExportManager.analyzeFolder(sourcePath, kgState.holonGraph.map { it.header }, kgState.isImportRecursive)
            withContext(Dispatchers.Main) {
                store.dispatch(KnowledgeGraphAction.AnalysisComplete(items))
            }
        }
    }
}