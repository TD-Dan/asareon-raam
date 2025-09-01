package app.auf.core

import app.auf.feature.knowledgegraph.KnowledgeGraphAction
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
 */
open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    internal val sourceCodeService: SourceCodeService,
    private val chatService: ChatService,
    private val gatewayService: GatewayService,
    private val actionExecutor: ActionExecutor,
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
            loadedHistory = ChatMessage.Factory.reId(loadedHistory)
            store.dispatch(AppAction.LoadSessionSuccess(loadedHistory))
        }

        backupManager.createBackup("on-launch")
        // Graph loading is now handled by the KnowledgeGraphFeature's start() lifecycle.
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
        val kgState = state.value.knowledgeGraphState
        if (state.value.isProcessing || kgState.aiPersonaId == null && kgState.holonGraph.isNotEmpty()) return

        store.dispatch(AppAction.AddUserMessage(message))
        handleCcl(message) // Still handle core CCL here for now
        chatService.sendMessage()
    }

    private fun handleCcl(rawContent: String) {
        // This could be migrated to its own feature in the future.
        val blocks = parser.parse(rawContent)
        blocks.filterIsInstance<CodeBlock>()
            .filter { it.language.lowercase() == "auf_action" }
            .forEach { block ->
                val action = when (block.content.trim()) {
                    // "ClockAction.Start" -> ClockAction.Start // This will be handled by feature interop later
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

            val kgState = state.value.knowledgeGraphState
            val personaId = kgState.aiPersonaId ?: ""
            val currentGraphHeaders = kgState.holonGraph.map { it.header }

            val result = actionExecutor.execute(manifest, personaId, currentGraphHeaders)

            when (result) {
                is ActionExecutorResult.Success -> {
                    store.dispatch(AppAction.UpdateActionStatus(messageTimestamp, ActionStatus.EXECUTED))
                    store.dispatch(AppAction.ExecuteActionManifestSuccess(result.summary, messageTimestamp))
                    store.dispatch(KnowledgeGraphAction.LoadGraph)
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

    // --- Knowledge Graph Actions ---
    fun retryLoadHolonGraph() {
        store.dispatch(KnowledgeGraphAction.RetryLoadGraph)
    }

    fun onHolonClicked(holonId: String) {
        store.dispatch(KnowledgeGraphAction.InspectHolon(holonId))
        // Logic for which view mode is active is now handled in the UI layer,
        // which can inspect the state and dispatch the appropriate action.
        if (state.value.knowledgeGraphState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
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