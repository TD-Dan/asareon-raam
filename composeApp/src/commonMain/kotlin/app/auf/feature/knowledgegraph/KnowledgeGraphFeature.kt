package app.auf.feature.knowledgegraph

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- 3. FEATURE IMPLEMENTATION ---
class KnowledgeGraphFeature(
    private val service: KnowledgeGraphService,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "KnowledgeGraphFeature"
    private var store: Store? = null
    override val composableProvider: Feature.ComposableProvider = KnowledgeGraphComposableProvider()


    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is KnowledgeGraphAction) return state
        val currentState = state.featureStates[name] as? KnowledgeGraphState ?: KnowledgeGraphState()
        val newFeatureState = when (action) {
            is LoadGraph -> currentState.copy(isLoading = true, fatalError = null, holonGraph = emptyList())
            is LoadGraphSuccess -> {
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
            is LoadGraphFailure -> currentState.copy(isLoading = false, fatalError = action.error)
            is SelectAiPersona -> currentState.copy(aiPersonaId = action.holonId, contextualHolonIds = emptySet(), inspectedHolonId = null)
            is InspectHolon -> currentState.copy(inspectedHolonId = action.holonId)
            is ToggleHolonActive -> {
                if (action.holonId == currentState.aiPersonaId) return state
                val newContextIds = if (currentState.contextualHolonIds.contains(action.holonId)) {
                    currentState.contextualHolonIds - action.holonId
                } else {
                    currentState.contextualHolonIds + action.holonId
                }
                currentState.copy(contextualHolonIds = newContextIds)
            }
            is SetCatalogueFilter -> currentState.copy(catalogueFilter = action.type)
            is SetViewMode -> {
                val newExportIds = if (action.mode == KnowledgeGraphViewMode.EXPORT) {
                    (currentState.contextualHolonIds + (currentState.aiPersonaId ?: "")).filter { it.isNotBlank() }.toSet()
                } else {
                    emptySet()
                }
                currentState.copy(viewMode = action.mode, holonIdsForExport = newExportIds)
            }
            is ToggleHolonForExport -> {
                if (action.holonId == currentState.aiPersonaId) return state
                val newExportIds = if (currentState.holonIdsForExport.contains(action.holonId)) {
                    currentState.holonIdsForExport - action.holonId
                } else {
                    currentState.holonIdsForExport + action.holonId
                }
                currentState.copy(holonIdsForExport = newExportIds)
            }
            is SelectAllForExport -> {
                val allIds = currentState.holonGraph.map { it.header.id }.toSet()
                currentState.copy(holonIdsForExport = allIds)
            }
            is DeselectAllForExport -> {
                val personaId = currentState.aiPersonaId
                if (personaId != null) {
                    currentState.copy(holonIdsForExport = setOf(personaId))
                } else {
                    currentState.copy(holonIdsForExport = emptySet())
                }
            }
            is StartImportAnalysis -> currentState.copy(importSourcePath = action.sourcePath, isImportRecursive = true, showOnlyChangedImportItems = false)
            is SetImportRecursive -> currentState.copy(isImportRecursive = action.isRecursive)
            is ToggleShowOnlyChangedImportItems -> currentState.copy(showOnlyChangedImportItems = !currentState.showOnlyChangedImportItems)
            is AnalysisComplete -> currentState.copy(importItems = action.items, importSelectedActions = action.items.associate { it.sourcePath to it.initialAction })
            is UpdateImportAction -> currentState.copy(importSelectedActions = currentState.importSelectedActions + (action.sourcePath to action.action))
            else -> currentState
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store
        dispatch(LoadGraph)
    }

    private fun dispatch(action: AppAction) {
        val store = this.store ?: return

        when (action) {
            is LoadGraph, is RetryLoadGraph -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val currentPersonaId = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.aiPersonaId
                    val result = service.loadGraph(currentPersonaId)
                    withContext(Dispatchers.Main) {
                        if (result.fatalError != null && result.holonGraph.isEmpty()) {
                            store.dispatch(LoadGraphFailure(result.fatalError))
                        } else {
                            store.dispatch(LoadGraphSuccess(result))
                        }
                    }
                }
            }
            is SelectAiPersona -> {
                store.dispatch(action)
                dispatch(LoadGraph)
            }
            is StartImportAnalysis -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val items = service.analyzeFolder(action.sourcePath, kgState.holonGraph.map { it.header }, kgState.isImportRecursive)
                    withContext(Dispatchers.Main) { store.dispatch(AnalysisComplete(items)) }
                }
            }
            is SetImportRecursive -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    if (kgState.importSourcePath.isNotBlank()) {
                        val items = service.analyzeFolder(kgState.importSourcePath, kgState.holonGraph.map { it.header }, action.isRecursive)
                        withContext(Dispatchers.Main) { store.dispatch(AnalysisComplete(items)) }
                    }
                }
            }
            is ExecuteImport -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val result = service.executeImport(kgState.importSelectedActions, kgState.holonGraph.map { it.header }, kgState.aiPersonaId)
                    withContext(Dispatchers.Main) {
                        store.dispatch(ImportComplete(result))
                        if (result.failedImports.isNotEmpty()) {
                            val failedFiles = result.failedImports.keys.joinToString { it.substringAfterLast('/') }
                            val errorMessage = "Import completed with ${result.failedImports.size} errors: $failedFiles"
                            store.dispatch(ShowToast(errorMessage))
                        }
                        if (result.successfulImports.isNotEmpty()) {
                            store.dispatch(ShowToast("Import successful! Reloading graph..."))
                        }
                        dispatch(LoadGraph)
                        store.dispatch(SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
                    }
                }
            }
            is ExecuteExport -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val holonsToExport = kgState.holonGraph.filter { it.header.id in kgState.holonIdsForExport }
                    service.executeExport(action.destinationPath, holonsToExport.map { it.header })
                    withContext(Dispatchers.Main) {
                        store.dispatch(ShowToast("Export successful!"))
                        store.dispatch(SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
                    }
                }
            }
            else -> store.dispatch(action)
        }
    }

    inner class KnowledgeGraphComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.knowledgegraph.main"
        @Composable
        override fun RibbonButton(stateManager: StateManager, isActive: Boolean) {
            IconButton(onClick = { stateManager.dispatch(SetActiveView(viewKey)) }) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = "Knowledge Graph",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        @Composable
        override fun StageContent(stateManager: StateManager) {
            KnowledgeGraphView(stateManager)
        }
    }
}