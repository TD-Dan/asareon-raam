package app.auf.core

import app.auf.model.UserSettings
import app.auf.service.ActionExecutor
import app.auf.service.BackupManager
import app.auf.service.ChatService
import app.auf.service.GatewayManager
import app.auf.service.GraphService
import app.auf.service.SourceCodeService
import app.auf.ui.ImportExportViewModel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The core state management class for the AUF application.
 *
 * ---
 * ## Mandate
 * This class is the single source of truth for the application's UI state (AppState).
 * It orchestrates data loading, AI interaction, and user actions by delegating to
 * specialized service classes. It is fully platform-agnostic and testable.
 *
 * ---
 * ## Dependencies
 * - `app.auf.core.Store`: The UDF state container.
 * - `app.auf.service.ChatService`: The new service for chat logic.
 * - `app.auf.service.BackupManager`
 * - `app.auf.service.GraphService`
 * - `app.auf.service.SourceCodeService`
 * - `app.auf.service.ActionExecutor`
 * - `app.auf.ui.ImportExportViewModel`
 * - `app.auf.util.PlatformDependencies`: The single bridge to the host OS.
 * - `kotlinx.coroutines.CoroutineScope`
 *
 * @version 4.4
 * @since 2025-08-17
 */
open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    private val graphService: GraphService,
    private val sourceCodeService: SourceCodeService,
    private val chatService: ChatService,
    private val gatewayManager: GatewayManager,
    private val actionExecutor: ActionExecutor,
    val importExportViewModel: ImportExportViewModel,
    private val platform: PlatformDependencies,
    private val initialSettings: UserSettings,
    private val coroutineScope: CoroutineScope
) {

    open val state: StateFlow<AppState> = store.state

    fun initialize() {
        backupManager.createBackup("on-launch")
        loadHolonGraph()
        loadAvailableModels()
    }

    fun loadHolonGraph() {
        coroutineScope.launch(Dispatchers.Default) {
            store.dispatch(AppAction.LoadGraph)
            val result = graphService.loadGraph(state.value.aiPersonaId)
            if (result.fatalError != null) {
                store.dispatch(AppAction.LoadGraphFailure(result.fatalError))
            } else {
                store.dispatch(AppAction.LoadGraphSuccess(result, platform.getSystemTimeMillis()))
            }
        }
    }

    private fun loadAvailableModels() {
        coroutineScope.launch(Dispatchers.Default) {
            // --- MODIFIED: Delegate filtering to the GatewayManager ---
            val modelNames = gatewayManager.listTextModels()
            store.dispatch(AppAction.SetAvailableModels(modelNames))
        }
    }

    // --- Chat Logic Delegation ---
    fun sendMessage(message: String) {
        if (state.value.isProcessing || state.value.aiPersonaId == null) return
        store.dispatch(AppAction.AddUserMessage(message, platform.getSystemTimeMillis()))
        chatService.sendMessage()
    }

    fun cancelMessage() {
        chatService.cancelMessage()
    }

    fun getSystemContextForDisplay(): List<ChatMessage> {
        return chatService.buildSystemContextMessages()
    }

    fun getPromptForClipboard(): String {
        return chatService.buildFullPromptAsString()
    }

    fun formatDisplayTimestamp(timestamp: Long): String {
        return platform.formatDisplayTimestamp(timestamp)
    }

    fun deleteMessage(timestamp: Long) {
        store.dispatch(AppAction.DeleteMessage(timestamp))
    }

    fun rerunMessage(timestamp: Long) {
        println("ACTION: RerunMessage($timestamp) - (Action not yet implemented)")
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        println("Side Effect: executeActionFromMessage - (To be moved to a Service)")
    }



    fun rejectActionFromMessage(messageTimestamp: Long) {
        println("ACTION: rejectActionFromMessage - (Action not yet implemented)")
    }

    fun openBackupFolder() {
        backupManager.createBackup("on-export-view")
        backupManager.openBackupFolder()
    }

    fun setViewMode(mode: ViewMode) {
        store.dispatch(AppAction.SetViewMode(mode))
        if (mode == ViewMode.CHAT) {
            importExportViewModel.cancelImport()
        } else if (mode == ViewMode.IMPORT) {
            importExportViewModel.startImport()
        }
    }

    fun onHolonClicked(holonId: String) {
        when (state.value.currentViewMode) {
            ViewMode.CHAT -> {
                inspectHolon(holonId); toggleHolonActive(holonId)
            }
            ViewMode.EXPORT, ViewMode.IMPORT -> {
                inspectHolon(holonId)
            }
        }
    }

    fun retryLoadHolonGraph() {
        loadHolonGraph()
    }

    fun toggleHolonActive(holonId: String) {
        store.dispatch(AppAction.ToggleHolonActive(holonId))
        inspectHolon(holonId)
    }

    fun selectAiPersona(holonId: String?) {
        store.dispatch(AppAction.SelectAiPersona(holonId))
        if (holonId != null) {
            loadHolonGraph()
        }
    }

    fun inspectHolon(holonId: String?) {
        store.dispatch(AppAction.InspectHolon(holonId))
    }

    fun selectModel(modelName: String) {
        if (modelName in state.value.availableModels) {
            store.dispatch(AppAction.SelectModel(modelName))
        }
    }

    fun setCatalogueFilter(type: String?) {
        store.dispatch(AppAction.SetCatalogueFilter(type))
    }

    fun toggleSystemMessageVisibility() {
        store.dispatch(AppAction.ToggleSystemVisibility)
    }

    fun executeExport(destinationPath: String) {
        val holonsToExport = state.value.holonGraph.filter { it.header.id in state.value.holonIdsForExport }
        val headersToExport = holonsToExport.map { it.header }
        importExportViewModel.importExportManager.executeExport(destinationPath, headersToExport)
    }

    fun copyCodebaseToClipboard() {
        coroutineScope.launch(Dispatchers.Default) {
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
}