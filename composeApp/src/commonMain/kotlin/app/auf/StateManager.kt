package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

@Serializable
private data class HolonFileContent(
    val header: HolonHeader,
    val payload: JsonObject
)

class StateManager(private val apiKey: String, private val initialSettings: UserSettings) {

    companion object {
        private const val HOLONS_BASE_PATH = "holons"
        private const val FRAMEWORK_BASE_PATH = "framework"
    }

    private val actionModule = SerializersModule {
        polymorphic(Action::class) {
            subclass(CreateHolon::class)
            subclass(UpdateHolonContent::class)
            subclass(CreateFile::class)
        }
    }
    private val jsonParser = Json {
        serializersModule = actionModule
        isLenient = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(AppState(
        gatewayStatus = GatewayStatus.IDLE,
        selectedModel = initialSettings.selectedModel,
        aiPersonaId = initialSettings.selectedAiPersonaId,
        contextualHolonIds = initialSettings.activeContextualHolonIds
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val gateway: Gateway = Gateway()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        loadHolonGraph()
        loadAvailableModels()
    }

    fun retryLoadHolonGraph() {
        loadHolonGraph()
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        val originalMessageIndex = _state.value.chatHistory.indexOfFirst { it.timestamp == messageTimestamp }
        if (originalMessageIndex == -1) return

        val originalMessage = _state.value.chatHistory[originalMessageIndex]
        val manifest = originalMessage.actionManifest ?: return

        // For now, we just confirm it. Future logic will execute the actions.
        val confirmationSummary = "Action Manifest Confirmed: \n" + manifest.joinToString("\n") { "- ${it.summary}" }
        val confirmationMessage = ChatMessage(Author.SYSTEM, confirmationSummary, "Action Executed")

        val updatedHistory = _state.value.chatHistory.toMutableList()
        updatedHistory[originalMessageIndex] = originalMessage.copy(isActionResolved = true)
        updatedHistory.add(confirmationMessage)

        _state.update { it.copy(chatHistory = updatedHistory) }
    }

    fun rejectActionFromMessage(messageTimestamp: Long) {
        val originalMessageIndex = _state.value.chatHistory.indexOfFirst { it.timestamp == messageTimestamp }
        if (originalMessageIndex == -1) return

        val originalMessage = _state.value.chatHistory[originalMessageIndex]
        val rejectionMessage = ChatMessage(Author.SYSTEM, "User rejected the proposed Action Manifest.", "Action Rejected")

        val updatedHistory = _state.value.chatHistory.toMutableList()
        updatedHistory[originalMessageIndex] = originalMessage.copy(isActionResolved = true)
        updatedHistory.add(rejectionMessage)

        _state.update { it.copy(chatHistory = updatedHistory) }
    }


    fun toggleHolonActive(holonId: String) {
        val state = _state.value
        if (holonId == state.aiPersonaId) return // Cannot deactivate the main agent
        val newContextIds = if (state.contextualHolonIds.contains(holonId)) {
            state.contextualHolonIds - holonId
        } else {
            state.contextualHolonIds + holonId
        }
        _state.update { it.copy(contextualHolonIds = newContextIds) }
        inspectHolon(holonId, forceLoad = true)
    }

    fun selectAiPersona(holonId: String?) {
        _state.update { it.copy(aiPersonaId = holonId) }
        loadHolonGraph()
    }

    fun inspectHolon(holonId: String?, forceLoad: Boolean = false) {
        if (holonId == null) {
            _state.update { it.copy(inspectedHolonId = null) }
            return
        }
        if (holonId == _state.value.inspectedHolonId && !forceLoad) return
        val currentHolon = _state.value.activeHolons[holonId]
        if (currentHolon != null && !forceLoad) {
            _state.update { it.copy(inspectedHolonId = holonId) }
            return
        }

        val holonHeader = _state.value.holonGraph.find { it.id == holonId }
        if (holonHeader == null) {
            _state.update {
                it.copy(errorMessage = "Cannot inspect holon: $holonId not found in graph.")
            }
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val holonFile = File(holonHeader.filePath)
                val holonContent = holonFile.readText()
                val holon = Holon(header = holonHeader, content = holonContent)
                _state.update {
                    it.copy(
                        inspectedHolonId = holonId,
                        activeHolons = it.activeHolons + (holonId to holon)
                    )
                }
            } catch (e: Exception) {
                println("Error loading holon content for inspection: ${e.message}")
            }
        }
    }

    fun selectModel(modelName: String) {
        if (modelName in _state.value.availableModels) {
            _state.update { it.copy(selectedModel = modelName) }
        }
    }

    fun setCatalogueFilter(type: String?) {
        _state.update { it.copy(catalogueFilter = type) }
    }

    fun toggleSystemMessageVisibility() {
        _state.update { it.copy(isSystemVisible = !it.isSystemVisible) }
    }

    private fun buildSystemContextMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val state = _state.value
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("$FRAMEWORK_BASE_PATH/framework_protocol.md"), "framework_protocol.md"))
        messages.add(ChatMessage(Author.SYSTEM, generateDynamicToolManifest(), "Host Tool Manifest"))
        val allActiveIds = (state.contextualHolonIds + listOfNotNull(state.aiPersonaId)).toSet()

        allActiveIds.forEach { holonId ->
            state.activeHolons[holonId]?.let { holon ->
                messages.add(ChatMessage(Author.SYSTEM, holon.content, File(holon.header.filePath).name))
            }
        }
        return messages
    }

    fun getSystemContextPreview(): List<ChatMessage> {
        return buildSystemContextMessages()
    }


    fun sendMessage(message: String) {
        if (_state.value.isProcessing || _state.value.aiPersonaId == null) return

        _state.update { it.copy(isProcessing = true, errorMessage = null) }
        val userChatMessage = ChatMessage(Author.USER, message)

        // --- CORE LOGIC FIX ---
        // 1. Prepare the full context for the API call, but DO NOT save it to state.
        val systemMessages = buildSystemContextMessages()
        val historyForApi = _state.value.chatHistory
        val fullContextForApi = systemMessages + historyForApi + userChatMessage

        // 2. Add ONLY the user's message to the history to update the UI immediately.
        _state.update {
            it.copy(chatHistory = it.chatHistory + userChatMessage)
        }

        coroutineScope.launch {
            try {
                val apiRequestContents = convertChatToApiContents(fullContextForApi)
                val responseContent = gateway.generateContent(apiKey, _state.value.selectedModel, apiRequestContents)

                // 3. Process the response and add it to history.
                val manifestStartTag = "[AUF_ACTION_MANIFEST]"
                val manifestEndTag = "[/AUF_ACTION_MANIFEST]"

                if (responseContent.startsWith("Error:")) {
                    throw Exception(responseContent)
                }

                if (responseContent.contains(manifestStartTag) && responseContent.contains(manifestEndTag)) {
                    val manifestJson = responseContent.substringAfter(manifestStartTag).substringBeforeLast(manifestEndTag).trim()
                    val parsedActions = jsonParser.decodeFromString<List<Action>>(manifestJson)
                    val summary = "The AI has proposed ${parsedActions.size} action(s). Please review and confirm."
                    val proposalMessage = ChatMessage(
                        author = Author.SYSTEM,
                        title = "Action Manifest Proposed",
                        content = summary,
                        actionManifest = parsedActions,
                        isActionResolved = false
                    )
                    _state.update {
                        it.copy(
                            chatHistory = it.chatHistory + proposalMessage,
                            isProcessing = false
                        )
                    }
                } else {
                    val aiResponse = ChatMessage(Author.AI, responseContent, "AI")
                    _state.update { it.copy(chatHistory = it.chatHistory + aiResponse, isProcessing = false) }
                }

            } catch(e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Gateway Error: ${e.message}"
                    )
                }
            }
        }
    }


    private fun loadHolonGraph() {
        coroutineScope.launch(Dispatchers.IO) {
            _state.update { it.copy(gatewayStatus = GatewayStatus.LOADING, errorMessage = null, holonGraph = emptyList()) }
            val parsingErrors = mutableListOf<String>()

            try {
                val holonsDir = File(HOLONS_BASE_PATH)
                if (!holonsDir.exists() || !holonsDir.isDirectory) {
                    throw IllegalStateException("Holon directory not found at resolved path: ${holonsDir.absolutePath}")
                }

                val availablePersonas = holonsDir.listFiles { file ->
                    file.isDirectory
                }?.mapNotNull { dir ->
                    val holonFile = File(dir, "${dir.name}.json")
                    if (holonFile.exists()) {
                        try {
                            val content = holonFile.readText()
                            val header = jsonParser.decodeFromString<HolonFileContent>(content).header
                            if (header.type == "AI_Persona_Root") header.copy(filePath = holonFile.path) else null
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                } ?: emptyList()

                _state.update { it.copy(availableAiPersonas = availablePersonas) }

                var currentPersonaId = _state.value.aiPersonaId
                if (availablePersonas.none { it.id == currentPersonaId }) {
                    currentPersonaId = if (availablePersonas.size == 1) {
                        availablePersonas.first().id
                    } else {
                        null
                    }
                    _state.update { it.copy(aiPersonaId = currentPersonaId) }
                }


                if (currentPersonaId == null) {
                    val errorMsg = if (availablePersonas.isNotEmpty()) "Please select an Active Agent to begin." else "No AI_Persona_Root holons found in the 'holons' directory."
                    throw IllegalStateException(errorMsg)
                }

                val graph = mutableListOf<HolonHeader>()
                val rootDirectory = File(holonsDir, currentPersonaId)

                traverseAndLoad(rootDirectory, null, 0, graph, parsingErrors)

                if (graph.isEmpty()) {
                    throw IllegalStateException("Failed to load any holons from root: ${rootDirectory.absolutePath}")
                }

                val activeHolonsMap = mutableMapOf<String, Holon>()
                val allActiveIds = (initialSettings.activeContextualHolonIds + currentPersonaId).toSet()

                allActiveIds.forEach { holonId ->
                    graph.find { it.id == holonId }?.let { header ->
                        try {
                            val content = File(header.filePath).readText()
                            activeHolonsMap[holonId] = Holon(header, content)
                        } catch (e: Exception) {
                            parsingErrors.add("Failed to load content for active holon: $holonId")
                        }
                    }
                }

                _state.update { it.copy(
                    holonGraph = graph,
                    gatewayStatus = GatewayStatus.OK,
                    activeHolons = activeHolonsMap,
                    errorMessage = if (parsingErrors.isNotEmpty()) "Warning: ${parsingErrors.size} holons failed to parse." else null
                ) }

            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(
                    gatewayStatus = GatewayStatus.ERROR,
                    errorMessage = "FATAL: ${e.message}"
                ) }
            }
        }
    }

    private fun traverseAndLoad(
        holonDirectory: File,
        parentId: String?,
        depth: Int,
        graph: MutableList<HolonHeader>,
        parsingErrors: MutableList<String>
    ) {
        if (!holonDirectory.exists() || !holonDirectory.isDirectory) {
            return
        }

        val holonId = holonDirectory.name
        val holonFile = File(holonDirectory, "$holonId.json")

        if (!holonFile.exists()) {
            parsingErrors.add("File not found for dir: ${holonDirectory.path}")
            return
        }

        try {
            val fileContentString = holonFile.readText()
            val parsedFile = jsonParser.decodeFromString<HolonFileContent>(fileContentString)
            var header = parsedFile.header

            header = header.copy(
                filePath = holonFile.path,
                parentId = parentId,
                depth = depth
            )
            graph.add(header)

            header.subHolons.forEach { subRef ->
                val subHolonDirectory = File(holonDirectory, subRef.id)
                traverseAndLoad(subHolonDirectory, header.id, depth + 1, graph, parsingErrors)
            }
        } catch(e: Exception) {
            val error = "Parse failed for $holonId: ${e.message?.substringBefore('\n')}"
            parsingErrors.add(error)
            e.printStackTrace()
        }
    }

    private fun readFileContent(filePath: String): String {
        return try {
            File(filePath).readText()
        } catch (e: Exception) {
            "Error reading file: $filePath. Check path and permissions."
        }
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val models = gateway.listModels(apiKey)
            if (models.isNotEmpty()) {
                _state.update { it.copy(availableModels = models.map { m -> m.name.removePrefix("models/") }) }
            } else {
                _state.update { it.copy(availableModels = listOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest")) }
            }
        }
    }

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        val apiContents = mutableListOf<Content>()

        messages.forEach { msg ->
            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "user"
                    apiContents.add(Content(role, listOf(Part(msg.content))))
                }
                Author.SYSTEM -> {
                    // Critical: System messages for the API should be from the USER role
                    val fullContent = "--- START OF FILE ${msg.title} ---\n${msg.content}"
                    apiContents.add(Content("user", listOf(Part(fullContent))))
                }
            }
        }

        // Merge consecutive messages from the same author to optimize for the Gemini API
        val mergedContents = mutableListOf<Content>()
        if (apiContents.isNotEmpty()) {
            var currentRole = apiContents.first().role
            val currentParts = mutableListOf<String>()

            apiContents.forEach { content ->
                if (content.role == currentRole) {
                    currentParts.add(content.parts.first().text)
                } else {
                    mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
                    currentRole = content.role
                    currentParts.clear()
                    currentParts.add(content.parts.first().text)
                }
            }
            mergedContents.add(Content(currentRole, listOf(Part(currentParts.joinToString("\n\n")))))
        }
        return mergedContents
    }

    private fun generateDynamicToolManifest(): String {
        return """
        --- START OF FILE Host Tool Manifest ---
        **Tool: Atomic Change Manifest**
        *   **Description:** Use this tool to propose any changes to the file system, such as creating or updating Holons.
        *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags. The JSON MUST include a `type` field to identify the action.

        *   **Available Action Contracts:**

            1.  **CreateHolon**
                *   **JSON Structure:**
                    ```json
                    {
                      "type": "app.auf.CreateHolon",
                      "parentId": "...",
                      "content": "...",
                      "summary": "..."
                    }
                    ```
                *   `parentId`: The `id` of the existing holon that will be this new holon's parent.
                *   `content`: The complete, valid JSON string for the new holon.
                *   `summary`: A brief, human-readable description of this action.

            2.  **UpdateHolonContent**
                *   **JSON Structure:**
                    ```json
                    {
                      "type": "app.auf.UpdateHolonContent",
                      "holonId": "...",
                      "newContent": "...",
                      "summary": "..."
                    }
                    ```
                *   `holonId`: The `id` of the holon to update.
                *   `newContent`: The complete, new JSON string that will replace the existing file's content.
                *   `summary`: A brief description of the update.

            3.  **CreateFile**
                *   **JSON Structure:**
                    ```json
                    {
                      "type": "app.auf.CreateFile",
                      "filePath": "...",
                      "content": "...",
                      "summary": "..."
                    }
                    ```
                *   `filePath`: The relative path from the `framework/` directory (e.g., `dreams/dream-1.md`).
                *   `content`: The raw text content of the file.
                *   `summary`: A brief description of the file being created.
        --- END OF FILE Host Tool Manifest ---
        """.trimIndent()
    }
}