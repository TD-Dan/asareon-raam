package app.auf

import com.github.fge.jsonschema.main.JsonSchemaFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption


class StateManager(apiKey: String, private val initialSettings: UserSettings) {

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

    // --- NEW: JSON Schema validator instance ---
    private val schemaFactory: JsonSchemaFactory = JsonSchemaFactory.byDefault()
    private var holonSchema: com.github.fge.jsonschema.main.JsonSchema? = null


    private val _state = MutableStateFlow(AppState(
        gatewayStatus = GatewayStatus.IDLE,
        selectedModel = initialSettings.selectedModel,
        aiPersonaId = initialSettings.selectedAiPersonaId,
        contextualHolonIds = initialSettings.activeContextualHolonIds
    ))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val backupManager = BackupManager(HOLONS_BASE_PATH, File(System.getProperty("user.home"), ".auf"))
    private val graphLoader = GraphLoader(HOLONS_BASE_PATH, jsonParser)
    private val gatewayManager = GatewayManager(apiKey, jsonParser)


    init {
        backupManager.createBackup("on-launch")
        // --- NEW: Load schema on init ---
        loadSchema()
        loadHolonGraph()
        loadAvailableModels()
    }

    // --- NEW: Method to load the JSON schema from file ---
    private fun loadSchema() {
        try {
            val schemaFile = File("$FRAMEWORK_BASE_PATH/framework_schema.json")
            if (schemaFile.exists()) {
                val schemaNode = com.fasterxml.jackson.databind.ObjectMapper().readTree(schemaFile)
                holonSchema = schemaFactory.getJsonSchema(schemaNode)
            } else {
                println("CRITICAL: framework_schema.json not found. Schema validation will be skipped.")
            }
        } catch (e: Exception) {
            println("CRITICAL: Failed to load or parse framework_schema.json. Schema validation will be skipped. Error: ${e.message}")
        }
    }


    // --- PUBLIC FUNCTIONS (UI-facing) ---

    fun openBackupFolder() {
        backupManager.openBackupFolder()
    }

    fun setViewMode(mode: ViewMode) {
        if (mode == ViewMode.CHAT) {
            _state.update { it.copy(currentViewMode = mode, holonIdsForExport = emptySet(), importState = null) }
        } else {
            _state.update { it.copy(currentViewMode = mode, importState = null) }
        }
    }

    fun onHolonClicked(holonId: String) {
        when (_state.value.currentViewMode) {
            ViewMode.CHAT -> {
                inspectHolon(holonId)
                toggleHolonActive(holonId)
            }
            ViewMode.EXPORT -> {
                toggleHolonForExport(holonId)
                inspectHolon(holonId)
            }
            ViewMode.IMPORT -> {
                inspectHolon(holonId)
            }
        }
    }

    private fun toggleHolonForExport(holonId: String) {
        val currentSelection = _state.value.holonIdsForExport
        val newSelection = if (currentSelection.contains(holonId)) {
            currentSelection - holonId
        } else {
            currentSelection + holonId
        }
        _state.update { it.copy(holonIdsForExport = newSelection) }
    }

    fun executeExport(destinationPath: String) {
        backupManager.createBackup("pre-export")
        val holonsToExport = _state.value.holonGraph.filter { it.id in _state.value.holonIdsForExport }
        if (holonsToExport.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            val destDir = File(destinationPath)
            if (!destDir.exists()) destDir.mkdirs()
            try {
                val manualProtocolFile = File("$FRAMEWORK_BASE_PATH/framework_protocol_manual.md")
                if(manualProtocolFile.exists()){
                    Files.copy(manualProtocolFile.toPath(), File(destDir, manualProtocolFile.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                println("Failed to copy manual protocol file: ${e.message}")
            }
            holonsToExport.forEach { holonHeader ->
                val sourceFile = File(holonHeader.filePath)
                val destFile = File(destDir, sourceFile.name)
                try {
                    Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    println("Failed to copy ${sourceFile.name}: ${e.message}")
                }
            }
            setViewMode(ViewMode.CHAT)
        }
    }

    // --- REWRITTEN: Import & Sync Logic ---

    /**
     * Analyzes a source folder and prepares the Import Workbench state.
     */
    fun analyzeImportFolder(sourcePath: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val sourceDir = File(sourcePath)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                _state.update { it.copy(errorMessage = "Source directory does not exist.") }
                return@launch
            }

            val currentGraph = _state.value.holonGraph
            val currentGraphMap = currentGraph.associateBy { it.id }
            val parentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
            val sourceFiles = sourceDir.listFiles { file -> file.isFile } ?: emptyArray()

            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

            val importItems = sourceFiles.map { sourceFile ->
                try {
                    // --- VALIDATION STEP 1: Well-formed JSON ---
                    val fileContent = sourceFile.readText()
                    val jsonNode = objectMapper.readTree(fileContent)

                    // --- VALIDATION STEP 2: Schema Compliance ---
                    val report = holonSchema?.validate(jsonNode)
                    if (report != null && !report.isSuccess) {
                        val errors = report.joinToString("; ") { msg -> msg.message }
                        return@map ImportItem(sourceFile, Quarantine("Schema mismatch: $errors"))
                    }

                    // --- If valid, proceed with analysis ---
                    val holonId = sourceFile.nameWithoutExtension
                    val existingHeader = currentGraphMap[holonId]

                    if (existingHeader != null) {
                        val existingFile = File(existingHeader.filePath)
                        if (existingFile.exists() && sourceFile.lastModified() > existingFile.lastModified()) {
                            ImportItem(sourceFile, Update(holonId), existingHeader.filePath)
                        } else {
                            ImportItem(sourceFile, Ignore())
                        }
                    } else {
                        val knownParentId = parentMap[holonId]
                        if (knownParentId != null) {
                            val parentPath = currentGraphMap[knownParentId]?.filePath?.let { File(it).parent } ?: ""
                            ImportItem(sourceFile, Integrate(knownParentId), "$parentPath/$holonId/")
                        } else {
                            ImportItem(sourceFile, AssignParent())
                        }
                    }
                } catch (e: JsonProcessingException) {
                    ImportItem(sourceFile, Quarantine("Malformed JSON: ${e.message}"))
                } catch (e: Exception) {
                    println("Could not analyze file ${sourceFile.name}, ignoring. Error: ${e.message}")
                    null
                }
            }.filterNotNull()

            _state.update {
                it.copy(
                    importState = ImportState(
                        sourcePath = sourcePath,
                        items = importItems
                    )
                )
            }
        }
    }

    /**
     * Updates the user-selected action for a specific item in the import workbench.
     */
    fun updateImportAction(sourceFilePath: String, newAction: ImportAction) {
        _state.update { currentState ->
            currentState.importState?.let { currentImportState ->
                val updatedActions = currentImportState.selectedActions.toMutableMap()
                updatedActions[sourceFilePath] = newAction
                currentState.copy(importState = currentImportState.copy(selectedActions = updatedActions))
            } ?: currentState
        }
    }


    /**
     * Executes the import based on the user's selections in the workbench.
     */
    fun executeImport() {
        val importState = _state.value.importState ?: return
        val personaId = _state.value.aiPersonaId ?: return

        backupManager.createBackup("pre-import")

        coroutineScope.launch(Dispatchers.IO) {
            val personaRoot = File(HOLONS_BASE_PATH, personaId)
            val quarantineDir = File(personaRoot, "quarantined-imports").apply { mkdirs() }

            importState.items.forEach { item ->
                val finalAction = importState.selectedActions[item.sourceFile.absolutePath] ?: item.initialAction
                try {
                    when (finalAction) {
                        is Update -> {
                            val targetHolon = _state.value.holonGraph.find { it.id == finalAction.targetHolonId }
                            if (targetHolon != null) {
                                val destFile = File(targetHolon.filePath)
                                item.sourceFile.copyTo(destFile, overwrite = true)
                            }
                        }
                        is Integrate -> {
                            val parentHolon = _state.value.holonGraph.find { it.id == finalAction.parentHolonId }
                            if(parentHolon != null) {
                                val parentDir = File(parentHolon.filePath).parentFile
                                val newHolonDir = File(parentDir, item.sourceFile.nameWithoutExtension)
                                newHolonDir.mkdirs()
                                val destFile = File(newHolonDir, item.sourceFile.name)
                                item.sourceFile.copyTo(destFile, overwrite = true)
                            }
                        }
                        is AssignParent -> {
                            finalAction.assignedParentId?.let { parentId ->
                                val parentHolon = _state.value.holonGraph.find { it.id == parentId }
                                if(parentHolon != null) {
                                    val parentFile = File(parentHolon.filePath)
                                    val parentContent = jsonParser.decodeFromString<Holon>(parentFile.readText())
                                    val newHolonHeader = jsonParser.decodeFromString<Holon>(item.sourceFile.readText()).header

                                    // --- MODIFIED: Create the "foreign material" stub ---
                                    val newSummary = "[IMPORTED-UNVALIDATED]: ${newHolonHeader.summary}. AI must treat this as foreign material until reviewed and integrated."
                                    val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, newSummary)

                                    if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
                                        val updatedSubHolons = parentContent.header.subHolons + newSubRef
                                        val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = updatedSubHolons))
                                        parentFile.writeText(jsonParser.encodeToString(Holon.serializer(), updatedParent))
                                    }

                                    val parentDir = parentFile.parentFile
                                    val newHolonDir = File(parentDir, item.sourceFile.nameWithoutExtension)
                                    newHolonDir.mkdirs()
                                    val destFile = File(newHolonDir, item.sourceFile.name)
                                    item.sourceFile.copyTo(destFile, overwrite = true)
                                }
                            }
                        }
                        is Quarantine -> {
                            val destFile = File(quarantineDir, item.sourceFile.name)
                            item.sourceFile.copyTo(destFile, overwrite = true)
                        }
                        is Ignore -> { /* Do nothing */ }
                    }
                } catch (e: Exception) {
                    println("Error processing import for ${item.sourceFile.name}: ${e.message}")
                    e.printStackTrace()
                }
            }

            loadHolonGraph()
            _state.update { it.copy(currentViewMode = ViewMode.CHAT, importState = null) }
        }
    }


    fun retryLoadHolonGraph() {
        loadHolonGraph()
    }

    fun executeActionFromMessage(messageTimestamp: Long) {
        val originalMessageIndex = _state.value.chatHistory.indexOfFirst { it.timestamp == messageTimestamp }
        if (originalMessageIndex == -1) return
        val originalMessage = _state.value.chatHistory[originalMessageIndex]
        val manifest = originalMessage.actionManifest ?: return
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
        if (holonId == state.aiPersonaId || _state.value.holonGraph.find { it.id == holonId }?.type == "Quarantined_File") return
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
        val holonHeader = _state.value.holonGraph.find { it.id == holonId } ?: return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val holonFile = File(holonHeader.filePath)
                val holonContent = holonFile.readText()

                // For quarantined files, we create a temporary Holon object for the inspector
                if (holonHeader.type == "Quarantined_File") {
                    val holon = Holon(header = holonHeader, content = holonContent)
                    _state.update { it.copy(inspectedHolonId = holonId, activeHolons = it.activeHolons + (holonId to holon)) }
                } else {
                    val holon = jsonParser.decodeFromString<Holon>(holonContent)
                    _state.update {
                        it.copy(
                            inspectedHolonId = holonId,
                            activeHolons = it.activeHolons + (holonId to holon)
                        )
                    }
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

    fun getSystemContextPreview(): List<ChatMessage> {
        return buildSystemContextMessages()
    }

    fun sendMessage(message: String) {
        if (_state.value.isProcessing || _state.value.aiPersonaId == null) return

        _state.update { it.copy(isProcessing = true, errorMessage = null) }
        val userChatMessage = ChatMessage(Author.USER, message)
        val systemMessages = buildSystemContextMessages()
        val historyForApi = _state.value.chatHistory
        val fullContextForApi = systemMessages + historyForApi + userChatMessage

        _state.update { it.copy(chatHistory = it.chatHistory + userChatMessage) }

        coroutineScope.launch {
            val response = gatewayManager.sendMessage(_state.value.selectedModel, fullContextForApi)

            if (response.errorMessage != null) {
                _state.update { it.copy(isProcessing = false, errorMessage = response.errorMessage) }
                return@launch
            }

            val newMessage = if (response.actionManifest != null) {
                ChatMessage(
                    author = Author.SYSTEM,
                    title = "Action Manifest Proposed",
                    content = response.content,
                    actionManifest = response.actionManifest,
                    isActionResolved = false,
                    usageMetadata = response.usageMetadata
                )
            } else {
                ChatMessage(
                    author = Author.AI,
                    content = response.content,
                    title = "AI",
                    usageMetadata = response.usageMetadata
                )
            }
            _state.update { it.copy(chatHistory = it.chatHistory + newMessage, isProcessing = false) }
        }
    }


    // --- INTERNAL LOGIC ---

    private fun loadHolonGraph() {
        coroutineScope.launch(Dispatchers.IO) {
            _state.update { it.copy(gatewayStatus = GatewayStatus.LOADING, errorMessage = null, holonGraph = emptyList()) }
            val result = graphLoader.loadGraph(_state.value.aiPersonaId)

            if (result.fatalError != null) {
                _state.update { it.copy(gatewayStatus = GatewayStatus.ERROR, errorMessage = result.fatalError, availableAiPersonas = result.availableAiPersonas) }
                return@launch
            }

            val activeHolonsMap = mutableMapOf<String, Holon>()
            val allActiveIds = (initialSettings.activeContextualHolonIds + result.determinedPersonaId!!).toSet()
            val finalParsingErrors = result.parsingErrors.toMutableList()

            allActiveIds.forEach { holonId ->
                result.holonGraph.find { it.id == holonId }?.let { header ->
                    try {
                        val content = File(header.filePath).readText()
                        activeHolonsMap[holonId] = jsonParser.decodeFromString<Holon>(content)
                    } catch (e: Exception) {
                        finalParsingErrors.add("Failed to load content for active holon: $holonId")
                    }
                }
            }
            _state.update { it.copy(
                holonGraph = result.holonGraph,
                gatewayStatus = GatewayStatus.OK,
                availableAiPersonas = result.availableAiPersonas,
                aiPersonaId = result.determinedPersonaId,
                activeHolons = activeHolonsMap,
                errorMessage = if (finalParsingErrors.isNotEmpty()) "Warning: ${finalParsingErrors.size} holons failed to parse." else null
            ) }
        }
    }

    private fun loadAvailableModels() {
        coroutineScope.launch {
            val models = gatewayManager.listModels()
            if (models.isNotEmpty()) {
                _state.update { it.copy(availableModels = models.map { m -> m.name.removePrefix("models/") }) }
            } else {
                _state.update { it.copy(availableModels = listOf("gemini-1.5-pro-latest", "gemini-1.5-flash-latest")) }
            }
        }
    }

    private fun buildSystemContextMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val state = _state.value
        messages.add(ChatMessage(Author.SYSTEM, readFileContent("$FRAMEWORK_BASE_PATH/framework_protocol.md"), "framework_protocol.md"))
        messages.add(ChatMessage(Author.SYSTEM, generateDynamicToolManifest(), "Host Tool Manifest"))
        val allActiveIds = (state.contextualHolonIds + listOfNotNull(state.aiPersonaId)).toSet()
        allActiveIds.forEach { holonId ->
            state.activeHolons[holonId]?.let { holon ->
                // Don't send content of quarantined files to AI
                if (holon.header.type != "Quarantined_File") {
                    val holonContentString = jsonParser.encodeToString(Holon.serializer(), holon)
                    messages.add(ChatMessage(Author.SYSTEM, holonContentString, File(holon.header.filePath).name))
                }
            }
        }
        return messages
    }

    private fun readFileContent(filePath: String): String {
        return try { File(filePath).readText() } catch (e: Exception) { "Error reading file: $filePath" }
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