// FILE: composeApp/src/commonMain/kotlin/app/auf/StateManager.kt
package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive


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
 * - `app.auf.GatewayManager`
 * - `app.auf.BackupManager`
 * - `app.auf.GraphLoader`
 * - `app.auf.ActionExecutor`
 * - `app.auf.ImportExportViewModel`
 * - `app.auf.PlatformDependencies`: The single bridge to the host OS.
 * - `kotlinx.coroutines.CoroutineScope`
 *
 * @version 2.2
 * @since 2025-08-15
 */
open class StateManager(
    private val gatewayManager: GatewayManager,
    private val backupManager: BackupManager,
    private val graphLoader: GraphLoader,
    private val actionExecutor: ActionExecutor,
    val importExportViewModel: ImportExportViewModel,
    private val platform: PlatformDependencies,
    private val initialSettings: UserSettings,
    private val coroutineScope: CoroutineScope
) {

    private val _state = MutableStateFlow(
        AppState(
            gatewayStatus = GatewayStatus.IDLE,
            selectedModel = initialSettings.selectedModel,
            aiPersonaId = initialSettings.selectedAiPersonaId,
            contextualHolonIds = initialSettings.activeContextualHolonIds
        )
    )
    open val state: StateFlow<AppState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun initialize() {
        backupManager.createBackup("on-launch")
        loadHolonGraph()
        loadAvailableModels()
    }

    fun sendMessage(message: String, from: Author = Author.USER) {
        if (_state.value.isProcessing || _state.value.aiPersonaId == null) return

        _state.update { it.copy(isProcessing = true, errorMessage = null) }

        val userChatMessage = ChatMessage(Author.USER, title = "USER", contentBlocks = listOf(TextBlock(message)), timestamp = platform.getSystemTimeMillis())

        if (from == Author.USER) {
            _state.update { it.copy(chatHistory = it.chatHistory + userChatMessage) }
        }

        val historyForApi = _state.value.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
        val systemMessages = buildSystemContextMessages()
        val fullContextForApi = systemMessages + historyForApi

        activeJob = coroutineScope.launch {
            val response = gatewayManager.sendMessage(_state.value.selectedModel, fullContextForApi)

            if (response.errorMessage != null) {
                println("GATEWAY ERROR: ${response.errorMessage}")
                val errorChatMessage = ChatMessage(
                    author = Author.SYSTEM,
                    title = "Gateway Error",
                    contentBlocks = listOf(TextBlock(response.errorMessage)),
                    timestamp = platform.getSystemTimeMillis()
                )
                _state.update {
                    it.copy(
                        isProcessing = false,
                        chatHistory = it.chatHistory + errorChatMessage,
                    )
                }
                activeJob = null
                return@launch
            }

            val aiMessage = ChatMessage(
                author = Author.AI,
                title = "AI",
                contentBlocks = response.contentBlocks,
                usageMetadata = response.usageMetadata,
                rawContent = response.rawContent,
                timestamp = platform.getSystemTimeMillis()
            )

            _state.update {
                it.copy(
                    isProcessing = false,
                    chatHistory = it.chatHistory + aiMessage
                )
            }
            activeJob = null
            handleAppRequests(aiMessage)
        }
    }

    fun cancelMessage() {
        activeJob?.cancel()
        _state.update { it.copy(isProcessing = false, errorMessage = "Request cancelled by user.") }
        activeJob = null
    }

    fun deleteMessage(timestamp: Long) {
        _state.update { currentState ->
            val updatedHistory = currentState.chatHistory.filterNot { it.timestamp == timestamp }
            currentState.copy(chatHistory = updatedHistory)
        }
    }

    fun rerunMessage(timestamp: Long) {
        val history = _state.value.chatHistory
        val messageIndex = history.indexOfFirst { it.timestamp == timestamp }

        if (messageIndex == -1 || history[messageIndex].author != Author.USER) {
            return
        }

        val messageToRerun = history[messageIndex]
        val originalContent = messageToRerun.contentBlocks
            .filterIsInstance<TextBlock>()
            .joinToString("\n") { it.text }

        val truncatedHistory = history.subList(0, messageIndex)

        _state.update { it.copy(chatHistory = truncatedHistory) }

        sendMessage(originalContent, from = Author.USER)
    }

    private fun handleAppRequests(message: ChatMessage) {
        message.contentBlocks.filterIsInstance<AppRequestBlock>().forEach { request ->
            when (request.requestType) {
                "START_DREAM_CYCLE" -> {
                    val dreamAnnouncement = ChatMessage(
                        author = Author.SYSTEM,
                        title = "System Request",
                        contentBlocks = listOf(TextBlock("The AI has requested a dream cycle. Initiating...")),
                        timestamp = platform.getSystemTimeMillis()
                    )
                    _state.update { it.copy(chatHistory = it.chatHistory + dreamAnnouncement) }
                    sendMessage(
                        "Please perform a 'Dream Cycle Simulation' based on our recent interaction.",
                        from = Author.SYSTEM
                    )
                }
            }
        }
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        val originalMessageIndex = _state.value.chatHistory.indexOfFirst { it.timestamp == messageTimestamp }
        if (originalMessageIndex == -1) return

        val originalMessage = _state.value.chatHistory[originalMessageIndex]
        val actionBlock = originalMessage.contentBlocks.filterIsInstance<ActionBlock>().firstOrNull() ?: return

        if (actionBlock.isResolved) return

        backupManager.createBackup("pre-action-manifest")
        _state.update { it.copy(isProcessing = true) }

        coroutineScope.launch(Dispatchers.Default) {
            val result = actionExecutor.execute(
                actionBlock.actions,
                _state.value.aiPersonaId!!,
                _state.value.holonGraph
            )

            val updatedHistory = _state.value.chatHistory.toMutableList()
            updatedHistory[originalMessageIndex] = resolveActionBlockInMessage(originalMessage)

            val confirmationMessage = when (result) {
                is ActionExecutorResult.Success -> ChatMessage(
                    Author.SYSTEM,
                    contentBlocks = listOf(TextBlock(result.summary)),
                    title = "Action Executed",
                    timestamp = platform.getSystemTimeMillis()
                )

                is ActionExecutorResult.Failure -> ChatMessage(
                    Author.SYSTEM,
                    contentBlocks = listOf(TextBlock(result.error)),
                    title = "Action Failed",
                    timestamp = platform.getSystemTimeMillis()
                )
            }
            updatedHistory.add(confirmationMessage)

            // Reload the graph to reflect the changes from the action.
            val loadResult = graphLoader.loadGraph(_state.value.aiPersonaId)

            _state.update {
                it.copy(
                    chatHistory = updatedHistory,
                    isProcessing = false,
                    // Update graph state from the load result
                    holonGraph = loadResult.holonGraph,
                    errorMessage = if (loadResult.parsingErrors.isNotEmpty()) "Warning: ${loadResult.parsingErrors.size} holons failed to parse after action." else null
                )
            }
        }
    }

    fun rejectActionFromMessage(messageTimestamp: Long) {
        val originalMessageIndex = _state.value.chatHistory.indexOfFirst { it.timestamp == messageTimestamp }
        if (originalMessageIndex == -1) return
        val originalMessage = _state.value.chatHistory[originalMessageIndex]

        if (originalMessage.contentBlocks.filterIsInstance<ActionBlock>().firstOrNull()?.isResolved == true) return

        val rejectionMessage = ChatMessage(
            Author.SYSTEM,
            contentBlocks = listOf(TextBlock("User rejected the proposed Action Manifest.")),
            title = "Action Rejected",
            timestamp = platform.getSystemTimeMillis()
        )
        val updatedHistory = _state.value.chatHistory.toMutableList()

        updatedHistory[originalMessageIndex] = resolveActionBlockInMessage(originalMessage)

        updatedHistory.add(rejectionMessage)
        _state.update { it.copy(chatHistory = updatedHistory) }
    }

    private fun resolveActionBlockInMessage(message: ChatMessage): ChatMessage {
        val newBlocks = message.contentBlocks.map { block ->
            if (block is ActionBlock) block.copy(isResolved = true) else block
        }
        return message.copy(contentBlocks = newBlocks)
    }

    private fun buildSystemContextMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val state = _state.value
        val frameworkBasePath = platform.getBasePathFor("framework")
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

        val allActiveIds = (state.contextualHolonIds + listOfNotNull(state.aiPersonaId)).toSet()
        allActiveIds.forEach { holonId ->
            val holonContent = state.activeHolons[holonId]
            val holonHeader = state.holonGraph.find { it.id == holonId }

            if (holonContent != null && holonHeader != null) {
                if (holonHeader.type != "Quarantined_File") {
                    val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holonContent)
                    messages.add(
                        ChatMessage(
                            Author.SYSTEM,
                            contentBlocks = listOf(TextBlock(holonContentString)),
                            title = platform.getFileName(holonHeader.filePath),
                            timestamp = platform.getSystemTimeMillis()
                        )
                    )
                }
            }
        }
        return messages
    }

    private fun generateDynamicToolManifest(): String {
        return """
        **Tool: Atomic Change Manifest**
        *   **Description:** Use this tool to propose any changes to the file system, such as creating or updating Holons.
        *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags. The JSON object for each action *must* include a `"type"` field with the name of the action class.
        *   **Example:**
            ```json
            [AUF_ACTION_MANIFEST]
            ```json
            [
              {
                "type": "CreateFile",
                "filePath": "path/to/new_file.txt",
                "content": "This is the file content.",
                "summary": "Create a new file."
              },
              {
                "type": "CreateHolon",
                "content": "{\"header\":{...},\"payload\":{...}}",
                "parentId": "parent-holon-id-123",
                "summary": "Create a new holon."
              }
            ]
            ```
            [/AUF_ACTION_MANIFEST]
            ```

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
        val historyToProcess = _state.value.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
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
        if (mode == ViewMode.CHAT) {
            _state.update { it.copy(currentViewMode = mode, holonIdsForExport = emptySet()) }
            importExportViewModel.cancelImport()
        } else if (mode == ViewMode.IMPORT) {
            _state.update { it.copy(currentViewMode = mode) }
            importExportViewModel.startImport()
        } else {
            _state.update { it.copy(currentViewMode = mode) }
        }
    }

    fun onHolonClicked(holonId: String) {
        when (_state.value.currentViewMode) {
            ViewMode.CHAT -> {
                inspectHolon(holonId); toggleHolonActive(holonId)
            }

            ViewMode.EXPORT -> {
                toggleHolonForExport(holonId); inspectHolon(holonId)
            }

            ViewMode.IMPORT -> {
                inspectHolon(holonId)
            }
        }
    }

    private fun toggleHolonForExport(holonId: String) {
        val currentSelection = _state.value.holonIdsForExport
        val newSelection =
            if (currentSelection.contains(holonId)) currentSelection - holonId else currentSelection + holonId; _state.update {
            it.copy(
                holonIdsForExport = newSelection
            )
        }
    }

    fun executeExport(destinationPath: String) {
        backupManager.createBackup("pre-export")
        val holonsToExport =
            _state.value.holonGraph.filter { it.id in _state.value.holonIdsForExport }; if (holonsToExport.isEmpty()) return; coroutineScope.launch(
            Dispatchers.Default
        ) { importExportViewModel.importExportManager.executeExport(destinationPath, holonsToExport); setViewMode(ViewMode.CHAT) }
    }

    fun retryLoadHolonGraph() {
        loadHolonGraph()
    }

    fun toggleHolonActive(holonId: String) {
        val state =
            _state.value; if (holonId == state.aiPersonaId || _state.value.holonGraph.find { it.id == holonId }?.type == "Quarantined_File") return
        val newContextIds =
            if (state.contextualHolonIds.contains(holonId)) state.contextualHolonIds - holonId else state.contextualHolonIds + holonId; _state.update {
            it.copy(
                contextualHolonIds = newContextIds
            )
        }; inspectHolon(holonId, forceLoad = true)
    }

    fun selectAiPersona(holonId: String?) {
        if (holonId == null) {
            _state.update {
                it.copy(
                    aiPersonaId = null,
                    holonGraph = emptyList(),
                    activeHolons = emptyMap(),
                    inspectedHolonId = null,
                    contextualHolonIds = emptySet(),
                    gatewayStatus = GatewayStatus.ERROR,
                    errorMessage = "Please select an Active Agent to begin."
                )
            }
        } else {
            _state.update { it.copy(aiPersonaId = holonId) }
            loadHolonGraph()
        }
    }

    fun inspectHolon(holonId: String?, forceLoad: Boolean = false) {
        if (holonId == null) {
            _state.update { it.copy(inspectedHolonId = null) }; return
        }; if (holonId == _state.value.inspectedHolonId && !forceLoad) return
        val holonHeader =
            _state.value.holonGraph.find { it.id == holonId } ?: return; coroutineScope.launch(Dispatchers.Default) {
            try {
                val fileString = platform.readFileContent(holonHeader.filePath)
                val holonToShow = if (holonHeader.type == "Quarantined_File") {
                    val payload = buildJsonObject { put("raw_content", JsonPrimitive(fileString)) }; Holon(
                        header = holonHeader,
                        payload = payload
                    )
                } else {
                    JsonProvider.appJson.decodeFromString<Holon>(fileString)
                }; _state.update {
                    it.copy(
                        inspectedHolonId = holonId,
                        activeHolons = it.activeHolons + (holonId to holonToShow)
                    )
                }
            } catch (e: Exception) {
                println("Error loading holon content for inspection ($holonId): ${e.message}")
            }
        }
    }

    fun selectModel(modelName: String) {
        if (modelName in _state.value.availableModels) _state.update { it.copy(selectedModel = modelName) }
    }



    fun setCatalogueFilter(type: String?) {
        _state.update { it.copy(catalogueFilter = type) }
    }

    fun toggleSystemMessageVisibility() {
        _state.update { it.copy(isSystemVisible = !it.isSystemVisible) }
    }

    fun loadHolonGraph() {
        coroutineScope.launch(Dispatchers.Default) {
            _state.update {
                it.copy(
                    gatewayStatus = GatewayStatus.LOADING,
                    errorMessage = null,
                    holonGraph = emptyList()
                )
            }
            val result = graphLoader.loadGraph(_state.value.aiPersonaId); if (result.fatalError != null) {
            _state.update {
                it.copy(
                    gatewayStatus = GatewayStatus.ERROR,
                    errorMessage = result.fatalError,
                    availableAiPersonas = result.availableAiPersonas
                )
            }; return@launch
        }
            val activeHolonsMap = mutableMapOf<String, Holon>()
            val allActiveIds = (initialSettings.activeContextualHolonIds + result.determinedPersonaId!!).toSet()
            val finalParsingErrors = result.parsingErrors.toMutableList(); allActiveIds.forEach { holonId ->
            result.holonGraph.find { it.id == holonId }?.let { header ->
                try {
                    val content = platform.readFileContent(header.filePath); activeHolonsMap[holonId] =
                        JsonProvider.appJson.decodeFromString<Holon>(content)
                } catch (e: SerializationException) {
                    finalParsingErrors.add("Failed to load/parse content for active holon: $holonId. Error: ${e.message}")
                } catch (e: Exception) {
                    finalParsingErrors.add("An unexpected error occurred loading active holon: $holonId")
                }
            }
        }; _state.update {
            it.copy(
                holonGraph = result.holonGraph,
                gatewayStatus = GatewayStatus.OK,
                availableAiPersonas = result.availableAiPersonas,
                aiPersonaId = result.determinedPersonaId,
                activeHolons = activeHolonsMap,
                errorMessage = if (finalParsingErrors.isNotEmpty()) "Warning: ${finalParsingErrors.size} holons failed to parse." else null
            )
        }
        }
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val models = gatewayManager.listModels(); if (models.isNotEmpty()) {
            _state.update { it.copy(availableModels = models.map { m -> m.name.removePrefix("models/") }) }
        } else {
            _state.update { it.copy(availableModels = listOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest")) }
        }
        }
    }

    /**
     * Public accessor to update state directly, intended for use ONLY in test environments.
     */
    fun updateStateForTesting(newState: AppState) {
        _state.value = newState
    }
}