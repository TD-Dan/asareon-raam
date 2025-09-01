package app.auf.core

import app.auf.model.*
import app.auf.service.*
import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.auf.service.AufTextParser
import kotlinx.serialization.builtins.ListSerializer
import app.auf.feature.systemclock.ClockAction

/**
 * A simple data class to hold the aggregated compilation statistics.
 */
data class AggregatedCompilationStats(
    val totalOriginalChars: Int,
    val totalCompiledChars: Int
)

/**
 * The core state management class for the AUF application.
 *
 * @version 5.7
 * @since 2025-08-28
 */
open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    private val graphService: GraphService,
    internal val sourceCodeService: SourceCodeService,
    private val chatService: ChatService,
    private val gatewayService: GatewayService,
    private val actionExecutor: ActionExecutor,
    private val parser: AufTextParser,
    val settingsManager: SettingsManager,
    private val sessionManager: SessionManager,
    val importExportViewModel: ImportExportViewModel,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) {

    open val state: StateFlow<AppState> = store.state

    fun initialize() {
        var loadedHistory = sessionManager.loadSession()
        if (loadedHistory != null) {
            loadedHistory = ChatMessage.Factory.reId(loadedHistory)
            store.dispatch(AppAction.LoadSessionSuccess(loadedHistory))
        }

        backupManager.createBackup("on-launch")
        loadHolonGraph()
        loadAvailableModels()
    }

    fun loadHolonGraph() {
        coroutineScope.launch {
            store.dispatch(AppAction.LoadGraph)
            val result = graphService.loadGraph(state.value.aiPersonaId)
            if (result.fatalError != null && result.holonGraph.isEmpty()) {
                store.dispatch(AppAction.LoadGraphFailure(result.fatalError))
            } else {
                store.dispatch(AppAction.LoadGraphSuccess(result))
            }
        }
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val modelNames = gatewayService.listTextModels()
            store.dispatch(AppAction.SetAvailableModels(modelNames))
        }
    }

    // --- Chat Logic Delegation ---
    fun sendMessage(message: String) {
        if (state.value.isProcessing || state.value.aiPersonaId == null && state.value.holonGraph.isNotEmpty()) return
        store.dispatch(AppAction.AddUserMessage(message))
        // --- MODIFICATION START: Handle Conversational Command-Line ---
        handleCcl(message)
        // --- MODIFICATION END ---
        chatService.sendMessage()
    }

    /**
     * Parses the raw user message for `auf_action` code blocks and dispatches
     * the corresponding AppAction, enabling the Conversational Command-Line (CCL).
     */
    private fun handleCcl(rawContent: String) {
        val blocks = parser.parse(rawContent)
        blocks.filterIsInstance<CodeBlock>()
            .filter { it.language.lowercase() == "auf_action" }
            .forEach { block ->
                val action = when (block.content.trim()) {
                    "ClockAction.Start" -> ClockAction.Start // Needs to be updated! Core does not know of features!
                    "ClockAction.Stop" -> ClockAction.Stop // Needs to be updated! Core does not know of features!
                    "ClockAction.Tick" -> ClockAction.Tick // Needs to be updated! Core does not know of features!
                    else -> null
                }
                if (action != null) {
                    store.dispatch(action)
                    store.dispatch(AppAction.ShowToast("Dispatched: ${block.content.trim()}"))
                } else {
                    store.dispatch(AppAction.ShowToast("Unknown CCL action: ${block.content.trim()}"))
                }
            }
    }


    fun cancelMessage() {
        chatService.cancelMessage()
    }

    fun getSystemContextForDisplay(): List<ChatMessage> {
        return chatService.buildSystemContextMessages()
    }

    fun getAggregatedCompilationStats(): AggregatedCompilationStats {
        val systemMessages = chatService.buildSystemContextMessages()
        var totalOriginal = 0
        var totalCompiled = 0
        systemMessages.forEach { msg ->
            msg.compilationStats?.let {
                totalOriginal += it.originalCharCount
                totalCompiled += it.compiledCharCount
            }
        }
        return AggregatedCompilationStats(totalOriginal, totalCompiled)
    }

    fun getPromptForClipboard(): String {
        return chatService.buildFullPromptAsString()
    }

    fun formatDisplayTimestamp(timestamp: Long): String {
        return platform.formatDisplayTimestamp(timestamp)
    }

    fun deleteMessage(id: Long) {
        store.dispatch(AppAction.DeleteMessage(id))
    }

    fun toggleMessageCollapsed(id: Long) {
        store.dispatch(AppAction.ToggleMessageCollapsed(id))
    }

    fun rerunFromMessage(id: Long) {
        if (state.value.isProcessing) return

        val messageToRerun = state.value.chatHistory.find { it.id == id }
        if (messageToRerun != null && messageToRerun.author == Author.USER) {
            store.dispatch(AppAction.RerunFromMessage(id))
            chatService.sendMessage()
        }
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        if (state.value.isProcessing) return

        coroutineScope.launch {
            val message = state.value.chatHistory.find { it.timestamp == messageTimestamp }
            val codeBlock = message?.contentBlocks
                ?.filterIsInstance<CodeBlock>()
                ?.firstOrNull { it.language.lowercase() == "json" }

            if (codeBlock == null || codeBlock.status != ActionStatus.PENDING) {
                store.dispatch(AppAction.ExecuteActionManifestFailure("Actionable JSON block not found or already resolved.", messageTimestamp))
                return@launch
            }

            val manifest = try {
                JsonProvider.appJson.decodeFromString(ListSerializer(Action.serializer()), codeBlock.content)
            } catch (e: Exception) {
                store.dispatch(AppAction.ExecuteActionManifestFailure("Failed to parse Action Manifest JSON: ${e.message}", messageTimestamp))
                return@launch
            }

            store.dispatch(AppAction.ExecuteActionManifest(messageTimestamp))

            val personaId = state.value.aiPersonaId ?: ""
            val currentGraphHeaders = state.value.holonGraph.map { it.header }

            val result = actionExecutor.execute(manifest, personaId, currentGraphHeaders)

            when (result) {
                is ActionExecutorResult.Success -> {
                    store.dispatch(AppAction.UpdateActionStatus(messageTimestamp, ActionStatus.EXECUTED))
                    store.dispatch(AppAction.ExecuteActionManifestSuccess(result.summary, messageTimestamp))
                    loadHolonGraph()
                }
                is ActionExecutorResult.Failure -> {
                    store.dispatch(AppAction.ExecuteActionManifestFailure(result.error, messageTimestamp))
                }
            }
        }
    }

    fun rejectActionFromMessage(messageTimestamp: Long) {
        store.dispatch(AppAction.UpdateActionStatus(messageTimestamp, ActionStatus.REJECTED))
        store.dispatch(AppAction.ShowToast("Action Manifest Rejected."))
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
        inspectHolon(holonId)
        if (state.value.currentViewMode == ViewMode.CHAT) {
            toggleHolonActive(holonId)
        }
    }

    fun retryLoadHolonGraph() {
        loadHolonGraph()
    }

    fun toggleHolonActive(holonId: String) {
        store.dispatch(AppAction.ToggleHolonActive(holonId))
        inspectHolon(holonId)
    }

    fun toggleHolonForExport(holonId: String) {
        store.dispatch(AppAction.ToggleHolonExport(holonId))
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

    fun selectAllForExport() {
        store.dispatch(AppAction.SelectAllForExport)
    }

    fun deselectAllForExport() {
        store.dispatch(AppAction.DeselectAllForExport)
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