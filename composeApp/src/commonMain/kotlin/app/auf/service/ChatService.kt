package app.auf.service

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.Holon
import app.auf.core.ParseErrorBlock
import app.auf.core.SentinelBlock
import app.auf.core.Store
import app.auf.core.TextBlock
import app.auf.core.Version
import app.auf.model.Action
import app.auf.model.ToolDefinition
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 * It uses a provided tool registry to generate manifests and understand AI commands.
 *
 * @version 2.2
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser,
    private val toolRegistry: List<ToolDefinition>,
    private val coroutineScope: CoroutineScope
) {

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

        // --- FIX: Add the required 'id' parameter to all ChatMessage constructors ---
        messages.add(
            ChatMessage(
                id = platform.getSystemTimeMillis(), // Placeholder ID
                author = Author.SYSTEM,
                contentBlocks = listOf(TextBlock(platform.readFileContent(protocolPath))),
                title = "framework_protocol.md",
                timestamp = platform.getSystemTimeMillis()
            )
        )
        messages.add(
            ChatMessage(
                id = platform.getSystemTimeMillis() + 1, // Placeholder ID
                author = Author.SYSTEM,
                contentBlocks = listOf(TextBlock(generateSystemStatusMessage())),
                title = "REAL TIME SYSTEM STATUS",
                timestamp = platform.getSystemTimeMillis()
            )
        )
        messages.add(
            ChatMessage(
                id = platform.getSystemTimeMillis() + 2, // Placeholder ID
                author = Author.SYSTEM,
                contentBlocks = listOf(TextBlock(generateDynamicToolManifest())),
                title = "Host Tool Manifest",
                timestamp = platform.getSystemTimeMillis()
            )
        )

        appState.activeHolons.values.forEachIndexed { index, holon ->
            if (holon.header.type != "Quarantined_File") {
                val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holon)
                messages.add(
                    ChatMessage(
                        id = platform.getSystemTimeMillis() + 3 + index, // Placeholder ID
                        author = Author.SYSTEM,
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
            // --- FIX: Use rawContent first, then fall back to robust reconstruction ---
            val reconstructedContent = msg.rawContent ?: msg.contentBlocks.joinToString(separator = "\n") { block ->
                reconstructBlockToString(block)
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
        *   Your current runtime platform is 'AUF App v${Version.APP_VERSION}'
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

    // --- NEW HELPER FUNCTION ---
    private fun reconstructBlockToString(block: ContentBlock): String {
        return when (block) {
            is TextBlock -> block.text
            is ActionBlock -> {
                val command = toolRegistry.find { it.name == "Action Manifest" }?.command ?: "ACTION_MANIFEST"
                val content = JsonProvider.appJson.encodeToString(ListSerializer(Action.serializer()), block.actions)
                "[AUF_${command}]\n$content\n[/AUF_${command}]"
            }
            is FileContentBlock -> {
                val command = toolRegistry.find { it.name == "File View" }?.command ?: "FILE_VIEW"
                val langParam = block.language?.let { ", language=\"$it\"" } ?: ""
                "[AUF_${command}(path=\"${block.fileName}\"$langParam)]\n${block.content}\n[/AUF_${command}]"
            }
            is AppRequestBlock -> {
                val command = toolRegistry.find { it.name == "App Request" }?.command ?: "APP_REQUEST"
                "[AUF_${command}]${block.requestType}[/AUF_${command}]"
            }
            is AnchorBlock -> {
                val command = toolRegistry.find { it.name == "State Anchor" }?.command ?: "STATE_ANCHOR"
                val content = JsonProvider.appJson.encodeToString(JsonObject.serializer(), block.content)
                "[AUF_${command}]\n$content\n[/AUF_${command}]"
            }
            is ParseErrorBlock -> "<!-- PARSE ERROR: ${block.errorMessage} | RAW: ${block.rawContent} -->"
            is SentinelBlock -> "<!-- SENTINEL: ${block.message} -->"
        }
    }
}