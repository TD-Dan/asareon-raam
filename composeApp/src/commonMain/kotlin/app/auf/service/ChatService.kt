// --- FILE: commonMain/kotlin/app/auf/service/ChatService.kt ---
package app.auf.service

import app.auf.core.AppAction
import app.auf.core.AppRequestBlock
import app.auf.core.Author
import app.auf.core.ChatMessage
import app.auf.core.ContentBlock
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
import app.auf.core.ActionBlock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 *
 * @version 1.5
 * @since 2025-08-17
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser, // <<< MODIFIED: Re-introduced the parser dependency
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
                contentBlocks = listOf(TextBlock(generateSystemStatusJson())),
                title = "system_state.json",
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
                    val role = if (msg.author == Author.AI) "model" else "user"
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

    private fun generateSystemStatusJson(): String {
        val state = store.state.value
        val lastTx = state.chatHistory.lastOrNull { it.author == Author.AI }?.usageMetadata
        val statusObject = buildJsonObject {
            put("host_llm", state.selectedModel)
            put("runtime", "AUF App v1.0")
            put("active_agent_id", state.aiPersonaId)
            put("timestamp_iso", platform.formatIsoTimestamp(platform.getSystemTimeMillis()))
            lastTx?.let {
                put("last_transaction_tokens", buildJsonObject {
                    put("prompt", it.promptTokenCount)
                    put("output", it.candidatesTokenCount)
                    put("total", it.totalTokenCount)
                })
            }
        }
        return JsonProvider.appJson.encodeToString(JsonObject.serializer(), statusObject)
    }

    private fun generateDynamicToolManifest(): String {
        val tools = listOf(
            """
            **Tool: Atomic Change Manifest**
            *   **Description:** Use this tool to propose any changes to the file system.
            *   **Format:** Enclose a JSON array of `Action` objects within `[AUF_ACTION_MANIFEST]` and `[/AUF_ACTION_MANIFEST]` tags.
            *   **Action Types:** `CreateHolon`, `UpdateHolonContent`, `CreateFile`. Each action object *must* include a `"type"` field with the action's class name.
            """.trimIndent(),
            """
            **Tool: Application Request**
            *   **Description:** Use this to request the host application to perform a pre-defined action.
            *   **Format:** Enclose the request type string within `[AUF_APP_REQUEST]` and `[/AUF_APP_REQUEST]` tags.
            *   **Available Requests:** `START_DREAM_CYCLE`
            """.trimIndent(),
            """
            **Tool: File Content View**
            *   **Description:** Use this tool to display the content of a non-Holon file within the chat.
            *   **Format:** Use `[AUF_FILE_VIEW: path/to/your/file.kt]` followed by the content and `[/AUF_FILE_VIEW]`.
            """.trimIndent(),
            """
            **Tool: State Anchor**
            *   **Description:** Use this to create a persistent, context-immune memory waypoint.
            *   **Format:** Use `[AUF_STATE_ANCHOR]` and enclose a JSON object with at least an `anchorId`.
            """.trimIndent()
        )
        return tools.joinToString("\n\n")
    }
}