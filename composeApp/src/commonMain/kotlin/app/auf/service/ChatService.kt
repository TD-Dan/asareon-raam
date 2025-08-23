// --- FILE: commonMain/kotlin/app/auf/service/ChatService.kt ---
package app.auf.service

import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.Holon
import app.auf.core.Store
import app.auf.core.TextBlock
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.auf.model.Action
import kotlinx.serialization.builtins.ListSerializer
import app.auf.core.ActionBlock // <<< MODIFIED: Added this line

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 *
 * ---
 * ## Mandate
 * This class is the single entry point for chat-related operations. It orchestrates
 * sending messages, building context, handling cancellations, and processing AI-initiated
 * application requests. It contains all asynchronous logic for AI communication.
 *
 * ---
 * ## Dependencies
 * - `app.auf.core.Store`: To dispatch actions and read the current state.
 * - `app.auf.service.GatewayManager`: To communicate with the AI model.
 * - `app.auf.util.PlatformDependencies`: For platform-specific utilities like timestamps.
 * - `kotlinx.coroutines.CoroutineScope`: To manage asynchronous tasks.
 *
 * @version 1.2
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) {

    private var activeJob: Job? = null

    /**
     * Initiates sending the current chat history to the AI.
     * It reads the current state from the Store to build the context.
     */
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

            // After the success action is processed, check the last message for app requests.
            val latestState = store.state.value
            val lastMessage = latestState.chatHistory.lastOrNull()
            if (lastMessage != null && lastMessage.author == Author.AI) {
                handleAppRequests(lastMessage)
            }
        }
    }

    /**
     * Cancels the currently active AI message request.
     */
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
                    val role = if (msg.author == Author.AI) "AI" else "USER"
                    "--- $role MESSAGE ---\n$reconstructedContent"
                }
                Author.SYSTEM -> {
                    "--- START OF FILE ${msg.title} ---\n$reconstructedContent"
                }
            }
        }
    }


    /**
     * Adds a system-level message to the chat history, then triggers the AI.
     * Used for automated processes like dream cycles.
     */
    private fun sendSystemMessage(message: String) {
        store.dispatch(AppAction.AddSystemMessage(message, platform.getSystemTimeMillis()))
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
}