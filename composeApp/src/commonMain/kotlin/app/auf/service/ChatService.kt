package app.auf.service

import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.Holon
import app.auf.core.Store
import app.auf.core.TextBlock
import app.auf.core.Version
import app.auf.model.ToolDefinition
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 *
 * @version 2.5
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser, // Still needed for parsing content blocks for system status display for example, but NOT for reconstruction
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
                store.dispatch(AppAction.SendMessageFailure(response.errorMessage))
            } else {
                store.dispatch(AppAction.SendMessageSuccess(response))
            }
            activeJob = null

            // MODIFICATION: Check for app requests *after* the AI message has been added
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
            ChatMessage.createSystem(
                title = "framework_protocol.md",
                rawContent = platform.readFileContent(protocolPath)
            )
        )
        messages.add(
            ChatMessage.createSystem(
                title = "REAL TIME SYSTEM STATUS",
                rawContent = generateSystemStatusMessage()
            )
        )
        messages.add(
            ChatMessage.createSystem(
                title = "Host Tool Manifest",
                rawContent = generateDynamicToolManifest()
            )
        )

        appState.activeHolons.values.forEach { holon ->
            if (holon.header.type != "Quarantined_File") {
                val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holon)
                messages.add(
                    ChatMessage.createSystem(
                        title = platform.getFileName(holon.header.id + ".json"),
                        rawContent = holonContentString
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
            // MODIFICATION: Logic simplified. Prioritize rawContent, fall back to TextBlocks only if rawContent is null.
            val content = msg.rawContent ?: msg.contentBlocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "USER"
                    "--- $role MESSAGE ---\n$content"
                }
                Author.SYSTEM -> "--- START OF FILE ${msg.title} ---\n$content"
            }
        }
    }

    // DELETED: sendSystemMessage helper function is removed as AppAction.AddSystemMessage is now direct.

    private fun handleAppRequests(message: ChatMessage) {
        message.contentBlocks.filterIsInstance<AppRequestBlock>().forEach { request ->
            when (request.requestType) {
                "START_DREAM_CYCLE" -> {
                    // MODIFICATION: Dispatch the new AddSystemMessage action directly, providing title and raw content.
                    store.dispatch(
                        AppAction.AddSystemMessage(
                            title = "App Request", // Provide a descriptive title for this system message
                            rawContent = "Please perform a 'Dream Cycle Simulation' based on our recent interaction."
                        )
                    )
                    sendMessage() // Send the new system message to the AI
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
        }.trim() // Ensure no trailing newlines for consistent comparison
    }
}