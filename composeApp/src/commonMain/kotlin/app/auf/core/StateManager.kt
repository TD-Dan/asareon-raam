package app.auf.core

import app.auf.feature.knowledgegraph.ImportAction
import app.auf.feature.knowledgegraph.KnowledgeGraphAction
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode
import app.auf.model.*
import app.auf.service.*
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * The core state management class for the AUF application. As of v2.0, this class
 * is a thin layer responsible for orchestrating interactions between the UI, services,
 * and the central Store. It does not contain business logic itself but dispatches
 * actions to the appropriate features.
 *
 * Is part of CORE: Does not know anything of specific features!
 */
open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    internal val sourceCodeService: SourceCodeService,
    private val chatService: ChatService,
    private val gatewayService: GatewayService,
    private val parser: AufTextParser,
    val settingsManager: SettingsManager,
    private val sessionManager: SessionManager,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) {

    open val state: StateFlow<AppState> = store.state

    fun initialize() {
        var loadedHistory = sessionManager.loadSession()
        if (loadedHistory != null) {
            loadedHistory = ChatMessage.reId(loadedHistory)
            store.dispatch(AppAction.LoadSessionSuccess(loadedHistory))
        }

        backupManager.createBackup("on-launch")
        loadAvailableModels()
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val modelNames = gatewayService.listTextModels()
            store.dispatch(AppAction.SetAvailableModels(modelNames))
        }
    }

    // --- Chat Logic Delegation ---
    fun sendMessage(message: String) {
        val appState = state.value
        val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
        if (appState.isProcessing || kgState.aiPersonaId == null && kgState.holonGraph.isNotEmpty()) return

        store.dispatch(AppAction.AddUserMessage(message))
        handleCcl(message)
        chatService.sendMessage()
    }

    private fun handleCcl(rawContent: String) {
        val blocks = parser.parse(rawContent)
        blocks.filterIsInstance<CodeBlock>()
            .filter { it.language.lowercase() == "auf_action" }
            .forEach { block ->
                val action = when (block.content.trim()) {
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

    data class AggregatedCompilationStats(val totalOriginalChars: Int, val totalCompiledChars: Int)
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

    fun rejectActionFromMessage(messageTimestamp: Long) {
        store.dispatch(AppAction.UpdateActionStatus(messageTimestamp, ActionStatus.REJECTED))
        store.dispatch(AppAction.ShowToast("Action Manifest Rejected."))
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

    fun selectAiPersona(holonId: String?) {
        store.dispatch(KnowledgeGraphAction.SelectAiPersona(holonId))
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