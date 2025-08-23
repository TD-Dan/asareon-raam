package app.auf.service

import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ContentBlock
import app.auf.core.Holon
import app.auf.core.Store
import app.auf.core.TextBlock
import app.auf.core.Version
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.auf.model.Action
import kotlinx.serialization.builtins.ListSerializer
import app.auf.core.ActionBlock

/**
 * A data class to hold the self-documenting definition of a host tool.
 * This structure is the single source of truth for generating the tool manifest.
 */
private data class ToolDefinition(
    val name: String,
    val description: String,
    val format: String,
    val availableSubtypes: String? = null
)

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 *
 * @version 1.6
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser,
    private val coroutineScope: CoroutineScope
) {

    private val toolRegistry: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "Atomic Change Manifest",
            description = "Use this tool to propose any changes to the file system.",
            format = "Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags.",
            availableSubtypes = "Action Types: `CreateHolon`, `UpdateHolonContent`, `CreateFile`. Each action object *must* include a `\"type\"` field with the action's class name."
        ),
        ToolDefinition(
            name = "Application Request",
            description = "Use this to request the host application to perform a pre-defined action.",
            format = "Enclose the request type string within `[AUF_APP_REQUEST]` and `[/AUF_APP_REQUEST]` tags.",
            availableSubtypes = "Available Requests: `START_DREAM_CYCLE`"
        ),
        ToolDefinition(
            name = "File Content View",
            description = "Use this tool to display the content of a non-Holon file within the chat.",
            format = "Use `[AUF_FILE_VIEW: path/to/your/file.kt]` followed by the content and `[/AUF_FILE_VIEW]`."
        ),
        ToolDefinition(
            name = "State Anchor",
            description = "Use this to create a persistent, context-immune memory waypoint.",
            format = "Use `[AUF_STATE_ANCHOR]` and enclose a JSON object with at least an `anchorId`."
        )
    )

    private var activeJob: Job? = null

    open fun sendMessage() {
        val state = store.state.value
        if (state.isProcessing || state.aiPersonaId == null) return

        store.dispatch(AppAction.SendMessageLoading)

        val historyForApi = state.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
        val systemMessages = buildSystemContextMessages()
        val fullContextForApi = systemMessages + historyForApi

        activeJob = coroutineScope.launch(Dispatchers.Default) {
            val response = gatewayService.sendMessage(state.selectedModel, fullContextForApi)

            if (response.errorMessage != null) {
                println("GATEWAY ERROR: ${response.errorMessage}")
                store.dispatch(AppAction.SendMessageFailure(response.errorMessage, platform.getSystemTimeMillis()))
                activeJob = null
                return@launch
            }

            store.dispatch(AppAction.SendMessageSuccess(response, platform.getSystemTimeMillis()))
            activeJob = null

            val latestState = store.state.value
            val lastMessage = latestState.chatHistory.lastOrNull()
            if (lastMessage != null && lastMessage.author == Author.AI) {
                handleAppRequests(lastMessage)
            }
        }
    }

    open fun cancelMessage() {
        activeJob?.cancel()
        store.dispatch(AppAction.CancelMessage)
        activeJob = null
    }

    open fun buildSystemContextMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val appState = store.state.value
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
        // --- MODIFIED: Replaced JSON with natural language system status ---
        messages.add(
            ChatMessage(
                Author.SYSTEM,
                contentBlocks = listOf(TextBlock(generateSystemStatusMessage())),
                title = "REAL TIME SYSTEM STATUS",
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

        appState.activeHolons.values.forEach { holon ->
            if (holon.header.type != "Quarantined_File") {
                val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holon)
                messages.add(
                    ChatMessage(
                        Author.SYSTEM,
                        contentBlocks = listOf(TextBlock(holonContentString)),
                        title = platform.getFileName(holon.header.id + ".json"),
                        timestamp = platform.getSystemTimeMillis()
                    )
                )
            }
        }
        return messages
    }

    open fun buildFullPromptAsString(): String {
        val state = store.state.value
        val historyForApi = state.chatHistory.filter { it.author == Author.USER || it.author == Author.AI }
        val systemMessages = buildSystemContextMessages()
        val fullContext = systemMessages + historyForApi

        return fullContext.joinToString("\n\n") { msg ->
            val reconstructedContent = msg.contentBlocks.joinToString(separator = "\n") { block ->
                when (block) {
                    is TextBlock -> block.text
                    is ActionBlock -> "[AUF_ACTION_MANIFEST]\n${JsonProvider.appJson.encodeToString(ListSerializer(Action.serializer()), block.actions)}\n[/AUF_ACTION_MANIFEST]"
                    else -> "[System placeholder for block type: ${block::class.simpleName}]"
                }
            }
            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "USER"
                    "--- $role MESSAGE ---\n$reconstructedContent"
                }
                Author.SYSTEM -> {
                    "--- START OF FILE ${msg.title} ---\n$reconstructedContent"
                }
            }
        }
    }

    private fun sendSystemMessage(message: String) {
        val contentBlocks: List<ContentBlock> = parser.parse(message)
        store.dispatch(AppAction.AddSystemMessage(contentBlocks, platform.getSystemTimeMillis()))
        sendMessage()
    }

    private fun handleAppRequests(message: ChatMessage) {
        message.contentBlocks.filterIsInstance<AppRequestBlock>().forEach { request ->
            when (request.requestType) {
                "START_DREAM_CYCLE" -> {
                    sendSystemMessage(
                        "Please perform a 'Dream Cycle Simulation' based on our recent interaction."
                    )
                }
            }
        }
    }

    private fun generateSystemStatusMessage(): String {
        val state = store.state.value
        val personaName = state.availableAiPersonas.find { it.id == state.aiPersonaId }?.name ?: "Unknown"
        val lastTx = state.chatHistory.lastOrNull { it.author == Author.AI }?.usageMetadata
        val tokenInfo = lastTx?.totalTokenCount?.let { "Approximate context window size of last transaction: $it tokens." }
            ?: "Token count for the last transaction is not available."

        return """
        *   You are running on host LLM: `${state.selectedModel}`
        *   Your current runtime platform is '${Version.APP_VERSION}'
        *   You are embodying the persona: '$personaName' (holon: ${state.aiPersonaId})
        *   The time of this request is: `${platform.formatIsoTimestamp(platform.getSystemTimeMillis())}`
        *   $tokenInfo
        """.trimIndent()
    }

    private fun generateDynamicToolManifest(): String {
        return toolRegistry.joinToString("\n\n") { tool ->
            val subtypes = if (tool.availableSubtypes != null) "\n*   **${tool.availableSubtypes}**" else ""
            """
            **Tool: ${tool.name}**
            *   **Description:** ${tool.description}
            *   **Format:** ${tool.format}$subtypes
            """.trimIndent()
        }
    }
}