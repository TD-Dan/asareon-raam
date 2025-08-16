package app.auf

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.AppState
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.FileContentBlock
import app.auf.core.Holon
import app.auf.core.Store
import app.auf.core.TextBlock
import app.auf.core.ViewMode
import app.auf.model.UserSettings
import app.auf.service.ActionExecutor
import app.auf.service.BackupManager
import app.auf.service.GatewayManager
import app.auf.service.GraphService
import app.auf.ui.ImportExportViewModel
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - `app.auf.service.GatewayManager`
 * - `app.auf.service.BackupManager`
 * - `app.auf.service.GraphService`: The new service for graph logic.
 * - `app.auf.service.ActionExecutor`
 * - `app.auf.ui.ImportExportViewModel`
 * - `app.auf.util.PlatformDependencies`: The single bridge to the host OS.
 * - `kotlinx.coroutines.CoroutineScope`
 *
 * @version 3.6
 * @since 2025-08-17
 */
open class StateManager(
    private val store: Store,
    private val gatewayManager: GatewayManager,
    private val backupManager: BackupManager,
    private val graphService: GraphService,
    private val actionExecutor: ActionExecutor,
    val importExportViewModel: ImportExportViewModel,
    private val platform: PlatformDependencies,
    private val initialSettings: UserSettings,
    private val coroutineScope: CoroutineScope
) {

    open val state: StateFlow<AppState> = store.state

    private var activeJob: Job? = null

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
                store.dispatch(AppAction.LoadGraphSuccess(result))
            }
        }
    }

    fun sendMessage(message: String, from: Author = Author.USER) {
        if (state.value.isProcessing || state.value.aiPersonaId == null) return

        if (from == Author.USER) {
            store.dispatch(AppAction.AddUserMessage(message, platform.getSystemTimeMillis()))
        }
        store.dispatch(AppAction.SendMessageLoading)


        val historyForApi = state.value.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
        val systemMessages = buildSystemContextMessages()
        val fullContextForApi = systemMessages + historyForApi

        activeJob = coroutineScope.launch {
            val response = gatewayManager.sendMessage(state.value.selectedModel, fullContextForApi)

            if (response.errorMessage != null) {
                println("GATEWAY ERROR: ${response.errorMessage}")
                store.dispatch(AppAction.SendMessageFailure(response.errorMessage, platform.getSystemTimeMillis()))
                activeJob = null
                return@launch
            }

            store.dispatch(AppAction.SendMessageSuccess(response, platform.getSystemTimeMillis()))
            activeJob = null
            handleAppRequests(state.value.chatHistory.last())
        }
    }

    fun cancelMessage() {
        activeJob?.cancel()
        store.dispatch(AppAction.CancelMessage)
        activeJob = null
    }

    fun deleteMessage(timestamp: Long) {
        println("ACTION: DeleteMessage($timestamp) - (Action not yet implemented)")
    }

    fun rerunMessage(timestamp: Long) {
        println("ACTION: RerunMessage($timestamp) - (Action not yet implemented)")
    }

    private fun handleAppRequests(message: ChatMessage) {
        message.contentBlocks.filterIsInstance<AppRequestBlock>().forEach { request ->
            when (request.requestType) {
                "START_DREAM_CYCLE" -> {
                    sendMessage(
                        "Please perform a 'Dream Cycle Simulation' based on our recent interaction.",
                        from = Author.SYSTEM
                    )
                }
            }
        }
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        println("Side Effect: executeActionFromMessage - (To be moved to a Service)")
    }

    fun rejectActionFromMessage(messageTimestamp: Long) {
        println("ACTION: rejectActionFromMessage - (Action not yet implemented)")
    }

    private fun buildSystemContextMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val appState = state.value
        val frameworkBasePath = platform.getBasePathFor(BasePath.FRAMEWORK)
        val protocolPath = frameworkBasePath + platform.pathSeparator + "framework_protocol.md"

        messages.add(
            ChatMessage(
                Author.SYSTEM,
                contentBlocks = listOf(TextBlock(platform.readFileContent(protocolPath))),
                title = "framework_protocol.md",
                timestamp = platform.getSystemTimeMillis()
            )
        )
        messages.add(
            ChatMessage(
                Author.SYSTEM,
                contentBlocks = listOf(TextBlock(generateDynamicToolManifest())),
                title = "Host Tool Manifest",
                timestamp = platform.getSystemTimeMillis()
            )
        )

        // Now iterates over the activeHolons map directly
        appState.activeHolons.values.forEach { holon ->
            if (holon.header.type != "Quarantined_File") {
                val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holon)
                messages.add(
                    ChatMessage(
                        Author.SYSTEM,
                        contentBlocks = listOf(TextBlock(holonContentString)),
                        title = platform.getFileName(holon.header.filePath),
                        timestamp = platform.getSystemTimeMillis()
                    )
                )
            }
        }
        return messages
    }

    private fun generateDynamicToolManifest(): String {
        return """
        **Tool: Atomic Change Manifest**
        *   **Description:** Use this tool to propose any changes to the file system, such as creating or updating Holons.
        *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags. The JSON object for each action *must* include a `"type"` field with the name of the action class.
        
        **Tool: Application Request**
        *   **Description:** Use this tool to request the host application to perform an action.
        *   **Format:** Enclose the request type string within `[AUF_APP_REQUEST]` and `[/AUF_APP_REQUEST]` tags.
        *   **Available Requests:**
            *   `START_DREAM_CYCLE`: Initiates a consolidation and synthesis cycle.

        **Tool: File Content View**
        *   **Description:** Use this tool to display the content of a file within the chat.
        *   **Format:** Use the tag `[AUF_FILE_VIEW: path/to/your/file.kt]` followed by the content and the closing tag `[/AUF_FILE_VIEW]`.

        **Tool: State Anchor**
        *   **Description:** Use this to create a persistent, context-immune memory waypoint.
        *   **Format:** Use the tag `[AUF_STATE_ANCHOR]` and enclose a JSON object with at least an `anchorId`.
        """.trimIndent()
    }


    fun getPromptAsString(): String {
        val historyToProcess = state.value.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
        val allMessages = buildSystemContextMessages() + historyToProcess

        return allMessages.joinToString("\n\n") { message ->
            val content = message.contentBlocks.joinToString("\n") { block ->
                when (block) {
                    is TextBlock -> block.text
                    is ActionBlock -> "[ACTION_MANIFEST_BLOCK]"
                    is FileContentBlock -> "[FILE_CONTENT_BLOCK: ${block.fileName}]"
                    is AppRequestBlock -> "[APP_REQUEST_BLOCK: ${block.requestType}]"
                    is AnchorBlock -> "[ANCHOR_BLOCK: ${block.anchorId}]"
                }
            }

            when (message.author) {
                Author.AI, Author.USER -> {
                    val formattedTimestamp = platform.formatIsoTimestamp(message.timestamp)
                    "[${message.author.name.lowercase()} - $formattedTimestamp]\n$content"
                }
                Author.SYSTEM -> {
                    val title = message.title ?: "system_file"
                    "--- START OF FILE $title ---\n$content\n--- END OF FILE $title ---"
                }
            }
        }
    }


    fun getSystemContextPreview(): List<ChatMessage> {
        return buildSystemContextMessages()
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
        println("ACTION: toggleSystemMessageVisibility - (Action not yet implemented)")
    }

    fun executeExport(destinationPath: String) {
        val holonsToExport = state.value.holonGraph.filter { it.header.id in state.value.holonIdsForExport }
        val headersToExport = holonsToExport.map { it.header }
        importExportViewModel.importExportManager.executeExport(destinationPath, headersToExport)
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val models = gatewayManager.listModels()
        }
    }
}