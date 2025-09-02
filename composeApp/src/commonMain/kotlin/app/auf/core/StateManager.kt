package app.auf.core

import app.auf.feature.knowledgegraph.ImportAction
import app.auf.feature.knowledgegraph.KnowledgeGraphAction
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode
import app.auf.model.SettingDefinition
import app.auf.model.SettingValue
import app.auf.service.*
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The core state management class for the AUF application. As of v2.0, this class
 * is a thin layer responsible for orchestrating interactions between the UI, services,
 * and the central Store. It does not contain business logic itself but dispatches
 * actions to the appropriate features.
 *
 * @version 8.0
 * @since 2025-09-02
 */
open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    internal val sourceCodeService: SourceCodeService,
    private val gatewayService: GatewayService, // Still needed by HkgAgentFeature
    val settingsManager: SettingsManager,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) {

    open val state: StateFlow<AppState> = store.state

    fun initialize() {
        backupManager.createBackup("on-launch")
        loadAvailableModels()
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val modelNames = gatewayService.listTextModels()
            store.dispatch(AppAction.SetAvailableModels(modelNames))
        }
    }

    // --- DEPRECATED: All Chat Logic is removed ---
    fun cancelMessage() {
        // TODO: This needs to be re-wired to the HkgAgentFeature
        // For now, it's a no-op.
    }

    fun formatDisplayTimestamp(timestamp: Long): String {
        return platform.formatDisplayTimestamp(timestamp)
    }

    fun openBackupFolder() {
        backupManager.createBackup("on-export-view")
        backupManager.openBackupFolder()
    }

    // --- Knowledge Graph Actions ---
    fun retryLoadHolonGraph() {
        store.dispatch(KnowledgeGraphAction.RetryLoadGraph)
    }

    fun onHolonClicked(holonId: String) {
        store.dispatch(KnowledgeGraphAction.InspectHolon(holonId))
        val kgState = state.value.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
        if (kgState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
            store.dispatch(KnowledgeGraphAction.ToggleHolonActive(holonId))
        }
    }

    fun setCatalogueFilter(type: String?) {
        store.dispatch(KnowledgeGraphAction.SetCatalogueFilter(type))
    }

    fun executeExport(destinationPath: String) {
        store.dispatch(KnowledgeGraphAction.ExecuteExport(destinationPath))
    }

    fun selectAllForExport() {
        store.dispatch(KnowledgeGraphAction.SelectAllForExport)
    }

    fun deselectAllForExport() {
        store.dispatch(KnowledgeGraphAction.DeselectAllForExport)
    }

    fun toggleHolonForExport(holonId: String) {
        store.dispatch(KnowledgeGraphAction.ToggleHolonForExport(holonId))
    }

    fun setKnowledgeGraphViewMode(mode: KnowledgeGraphViewMode) {
        store.dispatch(KnowledgeGraphAction.SetViewMode(mode))
    }

    fun startImportAnalysis(sourcePath: String) {
        store.dispatch(KnowledgeGraphAction.StartImportAnalysis(sourcePath))
    }

    fun executeImport() {
        store.dispatch(KnowledgeGraphAction.ExecuteImport)
    }

    fun updateImportAction(sourcePath: String, action: ImportAction) {
        store.dispatch(KnowledgeGraphAction.UpdateImportAction(sourcePath, action))
    }

    fun setImportRecursive(isRecursive: Boolean) {
        store.dispatch(KnowledgeGraphAction.SetImportRecursive(isRecursive))
    }

    fun toggleShowOnlyChangedImportItems() {
        store.dispatch(KnowledgeGraphAction.ToggleShowOnlyChangedImportItems)
    }


    // --- Core App Actions ---
    fun selectModel(modelName: String) {
        if (modelName in state.value.availableModels) {
            store.dispatch(AppAction.SelectModel(modelName))
        }
    }

    fun toggleSystemMessageVisibility() {
        store.dispatch(AppAction.ToggleSystemVisibility)
    }

    fun copyCodebaseToClipboard() {
        coroutineScope.launch {
            val codebaseString = sourceCodeService.collateKtFilesToString()
            if (codebaseString.startsWith("ERROR:")) {
                store.dispatch(AppAction.ShowToast(codebaseString))
            } else {
                platform.copyToClipboard(codebaseString)
                store.dispatch(AppAction.ShowToast("Source code copied to clipboard!"))
            }
        }
    }

    fun clearToast() {
        store.dispatch(AppAction.ClearToast)
    }

    fun getSettingDefinitions(): List<SettingDefinition> {
        return settingsManager.getSettingDefinitions()
    }

    fun updateSetting(settingValue: SettingValue) {
        store.dispatch(AppAction.UpdateSetting(settingValue))
    }
}