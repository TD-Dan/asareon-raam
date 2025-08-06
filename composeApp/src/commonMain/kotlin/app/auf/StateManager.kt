package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class StateManager(private val apiKey: String, private val initialSettings: UserSettings) {

    private val _state = MutableStateFlow(AppState(
        selectedModel = initialSettings.selectedModel,
        aiPersonaId = initialSettings.selectedAiPersonaId,
        contextualHolonIds = initialSettings.activeContextualHolonIds
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val gateway: Gateway = Gateway()
    private val appVersion = "1.0.0"

    // --- NEW: Dynamic Tool Manifest ---
    // The application's code is now the single source of truth for its capabilities.
    private val dynamicToolManifest = """
        --- START OF FILE Host Tool Manifest ---
        **Tool: Atomic Change Manifest**
        *   **Description:** Use this tool to propose any changes to the file system, such as creating or updating Holons.
        *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags.
        *   **Available Actions:**
            *   `CreateHolon(content: String)`: Proposes a new Holon. The content must be a complete, valid JSON string for the holon.
            *   `UpdateHolonContent(holonId: String, newContent: String)`: Proposes replacing the entire content of an existing Holon.
            *   `CreateFile(filePath: String, content: String)`: Proposes creating a new non-Holon file (e.g., a dream transcript).
    """.trimIndent()

    init {
        loadCatalogue()
        loadAvailableModels()
    }

    // --- Core Business Logic (Unchanged and Correct) ---
    fun toggleHolonActive(holonId: String) {
        val state = _state.value
        val newContextIds = if (state.contextualHolonIds.contains(holonId)) {
            state.contextualHolonIds - holonId
        } else {
            state.contextualHolonIds + holonId
        }
        _state.update { it.copy(contextualHolonIds = newContextIds) }
    }
    fun selectAiPersona(holonId: String?) { _state.update { it.copy(aiPersonaId = holonId) } }
    fun inspectHolon(holonId: String?) {
        if (holonId == null) {
            _state.update { it.copy(inspectedHolonId = null) }
            return
        }
        if (_state.value.activeHolons.containsKey(holonId)) {
            _state.update { it.copy(inspectedHolonId = holonId) }
            return
        }
        val holonHeader = _state.value.holonCatalogue.find { it.id == holonId } ?: return
        try {
            val holonFile = File("framework/${holonHeader.filePath}")
            val holon = Holon(header = holonHeader, content = holonFile.readText())
            _state.update { it.copy(inspectedHolonId = holonId, activeHolons = it.activeHolons + (holonId to holon)) }
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
    fun toggleSystemMessageVisibility() { _state.update { it.copy(isSystemVisible = !it.isSystemVisible) } }


    // --- MODIFIED: Prompt Building Logic ---
    private fun readFileContent(filePath: String): String { return try { File(filePath).readText() } catch (e: Exception) { "Error reading file: $filePath" } }

    private fun buildPromptMessages(newMessage: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val catalogue = _state.value.holonCatalogue
        val state = _state.value

        // 1. Core AI Protocol
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("framework/framework_protocol.md"), "framework_protocol.md"))

        // 2. System State & Host Capabilities
        val utcTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val systemStateContent = """
            {
              "utc_timestamp": "$utcTimestamp",
              "host_application": "AUF App",
              "host_version": "$appVersion",
              "host_os": "${System.getProperty("os.name")}",
              "host_llm": "${state.selectedModel}"
            }
        """.trimIndent()
        messages.add(ChatMessage(Author.SYSTEM, systemStateContent, "system_state.json"))
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("framework/auf_app_tool_use.md"), "auf_app_tool_use.md"))
        // **NEW**: Inject the dynamically generated tool manifest.
        messages.add(ChatMessage(Author.SYSTEM, dynamicToolManifest, "Host Tool Manifest"))


        // 3. Knowledge Graph
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("framework/holon_catalogue.json"), "holon_catalogue.json"))
        val allActiveIds = (state.contextualHolonIds + listOfNotNull(state.aiPersonaId)).toSet()
        allActiveIds.forEach { holonId ->
            catalogue.find { it.id == holonId }?.let { header ->
                messages.add(ChatMessage(Author.SYSTEM, readFileContent("framework/${header.filePath}"), File(header.filePath).name))
            }
        }

        // 4. Conversation History
        state.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }.forEach { messages.add(it) }
        messages.add(ChatMessage(Author.USER, newMessage, "User"))

        return messages
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


    // --- Main Interaction Loop (Unchanged and Correct) ---
    fun sendMessage(message: String) {
        if (_state.value.isProcessing) return
        val fullPromptMessages = buildPromptMessages(message)
        val apiRequestContents = convertChatToApiContents(fullPromptMessages)
        _state.update { it.copy(chatHistory = fullPromptMessages, isProcessing = true) }

        coroutineScope.launch {
            val responseContent = gateway.generateContent(apiKey, _state.value.selectedModel, apiRequestContents)
            val manifestStartTag = "[AUF_ACTION_MANIFEST]"
            val manifestEndTag = "[/AUF_ACTION_MANIFEST]"
            if (responseContent.contains(manifestStartTag) && responseContent.contains(manifestEndTag)) {
                val manifestJson = responseContent.substringAfter(manifestStartTag).substringBeforeLast(manifestEndTag).trim()
                try {
                    val parsedActions = jsonParser.decodeFromString<List<Action>>(manifestJson)
                    val proposalMessage = ChatMessage(Author.SYSTEM, "I have proposed a set of changes to the knowledge graph. Please review and confirm to proceed.", "Action Manifest Proposed")
                    _state.update {
                        it.copy(
                            pendingActionManifest = parsedActions,
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


    // --- Action Executor Logic (Unchanged and Correct) ---
    fun confirmManifest() {
        val manifest = _state.value.pendingActionManifest ?: return
        _state.update { it.copy(pendingActionManifest = null, isProcessing = true) }

        coroutineScope.launch {
            val resultMessage = executeActionManifest(manifest)
            _state.update { it.copy(chatHistory = it.chatHistory + resultMessage, isProcessing = false) }
            loadCatalogue()
        }
    }

    fun rejectManifest() {
        _state.update { it.copy(pendingActionManifest = null) }
        val rejectionMessage = ChatMessage(Author.SYSTEM, "The proposed action manifest has been rejected and discarded.", "Manifest Rejected")
        _state.update { it.copy(chatHistory = it.chatHistory + rejectionMessage) }
    }

    private fun executeActionManifest(actions: List<Action>): ChatMessage {
        val results = mutableListOf<String>()
        val preFlightErrors = mutableListOf<String>()
        actions.forEachIndexed { index, action ->
            try {
                when (action) {
                    is CreateHolon -> jsonParser.parseToJsonElement(action.content).jsonObject["header"] ?: throw IllegalStateException("Header missing")
                    is UpdateHolonContent -> if (_state.value.holonCatalogue.none { it.id == action.holonId }) throw NoSuchElementException("Holon ID not found")
                    else -> { /* Other actions valid by structure */ }
                }
            } catch (e: Exception) {
                preFlightErrors.add("Action #${index + 1} (${action::class.simpleName}) failed pre-flight check: ${e.message}")
            }
        }

        if (preFlightErrors.isNotEmpty()) {
            return ChatMessage(Author.SYSTEM, "Manifest execution aborted due to pre-flight errors:\n- ${preFlightErrors.joinToString("\n- ")}", "Execution Aborted")
        }

        actions.forEachIndexed { index, action ->
            val actionName = "${index + 1}: ${action::class.simpleName}"
            try {
                when (action) {
                    is CreateHolon -> {
                        val holonJson = jsonParser.parseToJsonElement(action.content).jsonObject
                        val headerJson = holonJson["header"]!!.jsonObject
                        val type = headerJson["type"]!!.jsonPrimitive.content
                        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
                        val id = headerJson["id"]?.jsonPrimitive?.content ?: "holon-$timestamp"
                        val fileName = when(type) {
                            "Session_Record", "Dream_Record" -> "${type.replace("_", "").lowercase()}-$timestamp.json"
                            else -> "$id.json"
                        }
                        val filePath = "holons/$fileName"
                        File("framework/$filePath").writeText(action.content)

                        val catalogueFile = File("framework/holon_catalogue.json")
                        val catalogueData = jsonParser.decodeFromString<HolonCatalogueFile>(catalogueFile.readText())
                        val newHeader = jsonParser.decodeFromString<HolonHeader>(headerJson.toString())
                        val updatedCatalogue = catalogueData.copy(holon_catalogue = catalogueData.holon_catalogue + newHeader.copy(filePath = "./$filePath"))
                        catalogueFile.writeText(jsonParser.encodeToString(HolonCatalogueFile.serializer(), updatedCatalogue))
                        results.add("✅ $actionName: Success")
                    }
                    is UpdateHolonContent -> {
                        val holonToUpdate = _state.value.holonCatalogue.find { it.id == action.holonId }!!
                        File("framework/${holonToUpdate.filePath}").writeText(action.newContent)
                        results.add("✅ $actionName ('${action.holonId}'): Success")
                    }
                    is CreateFile -> {
                        val file = File("framework/${action.filePath}")
                        file.parentFile.mkdirs()
                        file.writeText(action.content)
                        results.add("✅ $actionName ('${action.filePath}'): Success")
                    }
                    is UpdateHolon -> results.add("⚠️ $actionName: Skipped (granular updates not yet implemented).")
                }
            } catch (e: Exception) {
                results.add("❌ $actionName: FAILED - ${e.message}")
            }
        }
        return ChatMessage(Author.SYSTEM, "Execution complete. Results:\n- ${results.joinToString("\n- ")}", "Action Manifest Executed")
    }


    // --- Data Loading & Initialization ---
    private fun loadCatalogue() {
        try {
            val catalogueFile = File("framework/holon_catalogue.json")
            val catalogueJson = catalogueFile.readText()
            val parsedCatalogue = jsonParser.decodeFromString<HolonCatalogueFile>(catalogueJson)
            val currentActiveHolons = _state.value.activeHolons
            val updatedActiveHolons = currentActiveHolons.filterKeys { key -> parsedCatalogue.holon_catalogue.any { it.id == key } }

            _state.update { it.copy(
                holonCatalogue = parsedCatalogue.holon_catalogue,
                activeHolons = updatedActiveHolons,
                gatewayStatus = GatewayStatus.OK
            ) }
        } catch (e: Exception) {
            e.printStackTrace()
            _state.update { it.copy(gatewayStatus = GatewayStatus.ERROR) }
        }
    }

    private fun loadAvailableModels() { coroutineScope.launch {
        val models = gateway.listModels(apiKey)
        if (models.isNotEmpty()) { _state.update { it.copy(availableModels = models.map { m -> m.name.removePrefix("models/") }) }
        } else { _state.update { it.copy(availableModels = listOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest")) } }
    } }
}