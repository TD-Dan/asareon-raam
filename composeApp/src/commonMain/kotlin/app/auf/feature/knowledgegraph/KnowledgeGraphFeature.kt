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
                store.dispatch(action)
                coroutineScope.launch {
                    val currentPersonaId = (store.state.value.featureStates[name] as? KnowledgeGraphState)?.aiPersonaId
                    val result = service.loadGraph(currentPersonaId)
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
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val items = service.analyzeFolder(action.sourcePath, kgState.holonGraph.map { it.header }, kgState.isImportRecursive)
                    withContext(Dispatchers.Main) { store.dispatch(KnowledgeGraphAction.AnalysisComplete(items)) }
                }
            }
            is KnowledgeGraphAction.SetImportRecursive -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    if (kgState.importSourcePath.isNotBlank()) {
                        val items = service.analyzeFolder(kgState.importSourcePath, kgState.holonGraph.map { it.header }, action.isRecursive)
                        withContext(Dispatchers.Main) { store.dispatch(KnowledgeGraphAction.AnalysisComplete(items)) }
                    }
                }
            }
            is KnowledgeGraphAction.ExecuteImport -> {
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val result = service.executeImport(kgState.importSelectedActions, kgState.holonGraph.map { it.header }, kgState.aiPersonaId)
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
                store.dispatch(action)
                coroutineScope.launch {
                    val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return@launch
                    val holonsToExport = kgState.holonGraph.filter { it.header.id in kgState.holonIdsForExport }
                    service.executeExport(action.destinationPath, holonsToExport.map { it.header })
                    withContext(Dispatchers.Main) {
                        store.dispatch(AppAction.ShowToast("Export successful!"))
                        store.dispatch(KnowledgeGraphAction.SetViewMode(KnowledgeGraphViewMode.INSPECTOR))
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
            IconButton(onClick = { stateManager.dispatch(AppAction.SetActiveView(viewKey)) }) {
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