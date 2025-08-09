package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.toList

class StateManager(private val apiKey: String, private val initialSettings: UserSettings) {

    private val _state = MutableStateFlow(AppState(
        selectedModel = initialSettings.selectedModel,
        // --- CORRECTED: Using the correct property name from UserSettings ---
        aiPersonaId = initialSettings.selectedAiPersonaId,
        contextualHolonIds = initialSettings.activeContextualHolonIds
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val gateway: Gateway = Gateway()
    private val appVersion = "1.0.0"

    init {
        loadHolonGraph()
        loadAvailableModels()
    }

    fun toggleHolonActive(holonId: String) {
        val state = _state.value
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
        if (_state.value.activeHolons.containsKey(holonId) && !forceLoad) {
            _state.update { it.copy(inspectedHolonId = holonId) }
            return
        }
        val holonHeader = _state.value.holonGraph.find { it.id == holonId } ?: return
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

    private fun buildPromptMessages(newMessage: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val state = _state.value
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("framework/framework_protocol.md"), "framework_protocol.md"))
        messages.add(ChatMessage(Author.SYSTEM, generateDynamicToolManifest(), "Host Tool Manifest"))
        val allActiveIds = (state.contextualHolonIds + listOfNotNull(state.aiPersonaId)).toSet()
        allActiveIds.forEach { holonId ->
            state.activeHolons[holonId]?.let { holon ->
                messages.add(ChatMessage(Author.SYSTEM, holon.content, File(holon.header.filePath).name))
            }
        }
        state.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }.forEach { messages.add(it) }
        messages.add(ChatMessage(Author.USER, newMessage, "User"))
        return messages
    }

    fun sendMessage(message: String) {
        if (_state.value.isProcessing) return
        val userChatMessage = ChatMessage(Author.USER, message)
        _state.update { it.copy(chatHistory = it.chatHistory + userChatMessage, isProcessing = true) }
        val fullPromptMessages = buildPromptMessages(message)
        val apiRequestContents = convertChatToApiContents(fullPromptMessages)

        coroutineScope.launch {
            val responseContent = gateway.generateContent(apiKey, _state.value.selectedModel, apiRequestContents)
            val manifestStartTag = "[AUF_ACTION_MANIFEST]"
            val manifestEndTag = "[/AUF_ACTION_MANIFEST]"
            if (responseContent.contains(manifestStartTag) && responseContent.contains(manifestEndTag)) {
                val manifestJson = responseContent.substringAfter(manifestStartTag).substringBeforeLast(manifestEndTag).trim()
                try {
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
                } catch (e: Exception) {
                    val errorMessage = ChatMessage(Author.SYSTEM, "I attempted to propose an Action Manifest, but it was malformed. Error: ${e.message}", "Manifest Parse Error")
                    _state.update { it.copy(chatHistory = it.chatHistory + errorMessage, isProcessing = false) }
                }
            } else {
                val aiResponse = ChatMessage(Author.AI, responseContent, "AI")
                _state.update { it.copy(chatHistory = it.chatHistory + aiResponse, isProcessing = false) }
            }
        }
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        val message = _state.value.chatHistory.find { it.timestamp == messageTimestamp }
        val manifest = message?.actionManifest ?: return
        if (message.isActionResolved) return
        _state.update { it.copy(isProcessing = true) }
        updateMessageInHistory(messageTimestamp) { it.copy(isActionResolved = true) }
        coroutineScope.launch {
            val resultMessage = executeActionManifest(manifest)
            _state.update { it.copy(chatHistory = it.chatHistory + resultMessage, isProcessing = false) }
            loadHolonGraph()
        }
    }

    fun rejectActionFromMessage(messageTimestamp: Long) {
        val message = _state.value.chatHistory.find { it.timestamp == messageTimestamp }
        if (message?.isActionResolved == true) return
        updateMessageInHistory(messageTimestamp) { it.copy(isActionResolved = true) }
        val rejectionMessage = ChatMessage(Author.SYSTEM, "The proposed action manifest has been rejected and discarded.", "Manifest Rejected")
        _state.update { it.copy(chatHistory = it.chatHistory + rejectionMessage) }
    }

    private fun executeActionManifest(actions: List<Action>): ChatMessage {
        val results = mutableListOf<String>()
        actions.forEachIndexed { index, action ->
            val actionName = "${index + 1}: ${action::class.simpleName}"
            try {
                when (action) {
                    is CreateHolon -> {
                        val parentHolonHeader = _state.value.holonGraph.find { it.id == action.parentId }
                            ?: throw NoSuchElementException("Parent holon with ID '${action.parentId}' not found in the graph.")
                        val newHolonJson = jsonParser.parseToJsonElement(action.content).jsonObject
                        val newHolonHeaderJson = newHolonJson["header"]?.jsonObject
                            ?: throw IllegalArgumentException("New holon content is missing a 'header'.")
                        val newHolonId = newHolonHeaderJson["id"]?.jsonPrimitive?.content
                            ?: throw IllegalArgumentException("New holon header is missing an 'id'.")
                        val newHolonType = newHolonHeaderJson["type"]?.jsonPrimitive?.content ?: "Unknown"
                        val newHolonSummary = newHolonHeaderJson["summary"]?.jsonPrimitive?.content ?: ""
                        val newHolonFileName = "$newHolonId.json"
                        val newHolonFilePath = "framework/holons/$newHolonFileName"
                        File(newHolonFilePath).writeText(jsonParser.encodeToString(JsonElement.serializer(), newHolonJson))
                        val parentFile = File(parentHolonHeader.filePath)
                        val parentJson = jsonParser.parseToJsonElement(parentFile.readText()).jsonObject
                        val parentHeader = parentJson["header"]!!.jsonObject
                        val parentSubHolons = parentHeader["sub_holons"]!!.jsonArray.toMutableList()
                        parentSubHolons.add(
                            buildJsonObject {
                                put("id", newHolonId)
                                put("type", newHolonType)
                                put("summary", newHolonSummary)
                            }
                        )
                        val updatedParentJson = buildJsonObject {
                            put("header", buildJsonObject {
                                parentHeader.entries.forEach { (key, value) ->
                                    if (key != "sub_holons") put(key, value)
                                }
                                put("sub_holons", JsonArray(parentSubHolons))
                            })
                            put("payload", parentJson["payload"]!!)
                        }
                        parentFile.writeText(jsonParser.encodeToString(JsonElement.serializer(), updatedParentJson))
                        results.add("✅ $actionName: Success. Created '$newHolonId' and linked to parent '${action.parentId}'.")
                    }
                    is UpdateHolonContent -> {
                        val holonToUpdate = _state.value.holonGraph.find { it.id == action.holonId }
                            ?: throw NoSuchElementException("Holon with ID '${action.holonId}' not found in graph.")
                        File(holonToUpdate.filePath).writeText(action.newContent)
                        results.add("✅ $actionName ('${action.holonId}'): Success")
                    }
                    is CreateFile -> {
                        val file = File("framework/${action.filePath}")
                        file.parentFile.mkdirs()
                        file.writeText(action.content)
                        results.add("✅ $actionName ('${action.filePath}'): Success")
                    }
                }
            } catch (e: Exception) {
                results.add("❌ $actionName: FAILED - ${e.message}")
                e.printStackTrace()
            }
        }
        return ChatMessage(Author.SYSTEM, "Execution complete. Results:\n- ${results.joinToString("\n- ")}", "Action Manifest Executed")
    }

    private fun updateMessageInHistory(timestamp: Long, transform: (ChatMessage) -> ChatMessage) {
        _state.update { currentState ->
            currentState.copy(
                chatHistory = currentState.chatHistory.map {
                    if (it.timestamp == timestamp) transform(it) else it
                }
            )
        }
    }

    private fun loadHolonGraph() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val holonsDir = File("framework/holons")
                if (!holonsDir.exists()) throw IllegalStateException("Framework directory 'framework/holons' not found.")
                val allHolonFiles = Files.walk(Paths.get(holonsDir.toURI()))
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                    .map { it.toFile() }
                    .toList()
                var rootId = _state.value.aiPersonaId
                if (rootId == null) {
                    val rootCandidates = allHolonFiles.mapNotNull { file ->
                        try {
                            val header = jsonParser.parseToJsonElement(file.readText()).jsonObject["header"]?.jsonObject
                            if (header?.get("type")?.jsonPrimitive?.content == "AI_Persona_Root") {
                                header["id"]?.jsonPrimitive?.content
                            } else null
                        } catch (e: Exception) { null }
                    }
                    if (rootCandidates.size != 1) {
                        throw IllegalStateException("Found ${rootCandidates.size} AI_Persona_Root holons. Expected exactly 1 for a cold start.")
                    }
                    rootId = rootCandidates.first()
                    _state.update { it.copy(aiPersonaId = rootId) }
                }
                val holonFileMap = allHolonFiles.associateBy { it.nameWithoutExtension }
                val graph = mutableListOf<HolonHeader>()
                val activeHolons = mutableMapOf<String, Holon>()
                traverseAndLoad(rootId, null, 0, holonFileMap, graph, activeHolons)
                _state.update { it.copy(
                    holonGraph = graph,
                    activeHolons = activeHolons,
                    gatewayStatus = GatewayStatus.OK
                ) }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(gatewayStatus = GatewayStatus.ERROR) }
            }
        }
    }

    private fun traverseAndLoad(holonId: String, parentId: String?, depth: Int, fileMap: Map<String, File>, graph: MutableList<HolonHeader>, activeHolons: MutableMap<String, Holon>) {
        val file = fileMap[holonId] ?: return
        try {
            val content = file.readText()
            var header = jsonParser.decodeFromString<Holon>(content).header
            header = header.copy(
                filePath = file.path,
                parentId = parentId,
                depth = depth
            )
            graph.add(header)
            if ((_state.value.contextualHolonIds + _state.value.aiPersonaId).contains(holonId)) {
                activeHolons[holonId] = Holon(header, content)
            }
            header.subHolons.forEach { subRef ->
                traverseAndLoad(subRef.id, holonId, depth + 1, fileMap, graph, activeHolons)
            }
        } catch(e: Exception) {
            println("Failed to load or parse holon: $holonId. Error: ${e.message}")
        }
    }

    private fun readFileContent(filePath: String): String { return try { File(filePath).readText() } catch (e: Exception) { "Error reading file: $filePath" } }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val models = gateway.listModels(apiKey)
            if (models.isNotEmpty()) { _state.update { it.copy(availableModels = models.map { m -> m.name.removePrefix("models/") }) }
            } else { _state.update { it.copy(availableModels = listOf("gemini-1.5-pro-latest", "gemini-1.s5-flash-latest")) } }
        }
    }

    private fun convertChatToApiContents(messages: List<ChatMessage>): List<Content> {
        val apiContents = mutableListOf<Content>()
        val userPromptParts = mutableListOf<String>()
        var historyProcessed = false
        messages.forEach { msg ->
            when (msg.author) {
                Author.SYSTEM -> userPromptParts.add("--- START OF FILE ${msg.title} ---\n${msg.content}")
                Author.USER, Author.AI -> {
                    if (!historyProcessed) {
                        if (userPromptParts.isNotEmpty()) {
                            apiContents.add(Content("user", listOf(Part(userPromptParts.joinToString("\n\n")))))
                            userPromptParts.clear()
                        }
                        historyProcessed = true
                    }
                    val role = if (msg.author == Author.AI) "model" else "user"
                    apiContents.add(Content(role, listOf(Part(msg.content))))
                }
            }
        }
        if (userPromptParts.isNotEmpty()) { apiContents.add(Content("user", listOf(Part(userPromptParts.joinToString("\n\n"))))) }
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
        *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags. Each action object MUST include a "summary" field explaining its purpose.
        *   **Available Action Contracts:**

            1.  `CreateHolon(parentId: String, content: String, summary: String)`
                *   **Purpose:** Proposes a new Holon as a child of an existing one.
                *   `parentId`: The `id` of the existing holon that will be this new holon's parent.
                *   `content`: The complete, valid JSON string for the new holon.
                *   `summary`: A brief, human-readable description of this action (e.g., "Create project task for UI refactor").

            2.  `UpdateHolonContent(holonId: String, newContent: String, summary: String)`
                *   **Purpose:** Proposes replacing the entire content of an existing Holon.
                *   `holonId`: The `id` of the holon to update.
                *   `newContent`: The complete, new JSON string that will replace the existing file's content.
                *   `summary`: A brief description of the update (e.g., "Update project status to Active").

            3.  `CreateFile(filePath: String, content: String, summary: String)`
                *   **Purpose:** Proposes creating a new non-Holon file (e.g., a dream transcript).
                *   `filePath`: The relative path from the `framework/` directory (e.g., `dreams/dream-1.md`).
                *   `content`: The raw text content of the file.
                *   `summary`: A brief description of the file being created.
        --- END OF FILE Host Tool Manifest ---
        """.trimIndent()
    }
}