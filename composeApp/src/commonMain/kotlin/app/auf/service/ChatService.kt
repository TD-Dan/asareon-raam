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
import app.auf.model.Parameter
import app.auf.model.ToolDefinition

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 * It is the single source of truth for available host tools.
 *
 * @version 2.0
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser, // This will be replaced by the new parser
    private val coroutineScope: CoroutineScope
) {

    // --- NEW: The definitive, structured Tool Registry ---
    val toolRegistry: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "Atomic Change Manifest",
            command = "ACTION_MANIFEST",
            description = "Propose a transactional set of changes to the Holon Knowledge Graph file system.",
            parameters = emptyList(), // Parameters are implicit in the JSON payload
            expectsPayload = true,
            usage = "[AUF_ACTION_MANIFEST]\n[...json array of Action objects...]\n[/AUF_ACTION_MANIFEST]"
        ),
        ToolDefinition(
            name = "Application Request",
            command = "APP_REQUEST",
            description = "Request the host application to perform a pre-defined, non-file-system action.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = "[AUF_APP_REQUEST]START_DREAM_CYCLE[/AUF_APP_REQUEST]"
        ),
        ToolDefinition(
            name = "File Content View",
            command = "FILE_VIEW",
            description = "Display the content of a non-Holon file within the chat.",
            parameters = listOf(
                Parameter(name = "path", type = "String", isRequired = true, defaultValue = null)
            ),
            expectsPayload = true,
            usage = "[AUF_FILE_VIEW(path=\"path/to/your/file.kt\")]\n...file content...\n[/AUF_FILE_VIEW]"
        ),
        ToolDefinition(
            name = "State Anchor",
            command = "STATE_ANCHOR",
            description = "Create a persistent, context-immune memory waypoint within the chat history.",
            parameters = emptyList(),
            expectsPayload = true,
            usage = "[AUF_STATE_ANCHOR]\n{\"anchorId\": \"...\", ...}\n[/AUF_STATE_ANCHOR]"
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
            """
            **Tool: ${tool.name}**
            *   **Description:** ${tool.description}
            *   **Usage:** `${tool.usage}`
            """.trimIndent()
        }
    }
}