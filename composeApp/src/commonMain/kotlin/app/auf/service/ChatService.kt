package app.auf.service

import app.auf.core.*
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Service dedicated to handling all business logic related to AI chat interactions.
 */
open class ChatService(
    private val store: Store,
    private val gatewayService: GatewayService,
    private val platform: PlatformDependencies,
    private val parser: AufTextParser,
    private val promptCompiler: PromptCompiler,
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
        val compilerSettings = appState.compilerSettings

        fun createAndCompileSystemMessage(title: String, rawContent: String): ChatMessage {
            val result = promptCompiler.compile(rawContent, compilerSettings)
            return ChatMessage.createSystem(title, rawContent).copy(
                compiledContent = result.compiledText,
                compilationStats = result.stats
            )
        }

        val frameworkBasePath = platform.getBasePathFor(BasePath.FRAMEWORK)
        val protocolPath = frameworkBasePath + platform.pathSeparator + "framework_protocol.md"
        messages.add(createAndCompileSystemMessage("framework_protocol.md", platform.readFileContent(protocolPath)))
        messages.add(createAndCompileSystemMessage("REAL TIME SYSTEM STATUS", generateSystemStatusMessage()))

        appState.activeHolons.values.forEach { holon ->
            if (holon.header.type != "Quarantined_File") {
                val holonContentString = JsonProvider.appJson.encodeToString(Holon.serializer(), holon)
                messages.add(
                    createAndCompileSystemMessage(
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
        val historyForApi = state.chatHistory.map {
            if (it.author == Author.USER || it.author == Author.AI) {
                it.copy(compiledContent = it.rawContent)
            } else {
                val result = promptCompiler.compile(it.rawContent ?: "", state.compilerSettings)
                it.copy(compiledContent = result.compiledText, compilationStats = result.stats)
            }
        }

        val systemMessages = buildSystemContextMessages()
        val fullContext = systemMessages + historyForApi

        return fullContext.joinToString("\n\n") { msg ->
            val content = msg.compiledContent ?: msg.rawContent ?: ""
            when (msg.author) {
                Author.USER, Author.AI -> {
                    val role = if (msg.author == Author.AI) "model" else "USER"
                    "--- $role MESSAGE ---\n$content"
                }
                Author.SYSTEM -> "--- START OF FILE ${msg.title} ---\n$content"
            }
        }
    }

    private fun handleAppRequests(message: ChatMessage) {
        message.contentBlocks.filterIsInstance<CodeBlock>().forEach { block ->
            if(block.language.lowercase() == "app_request") {
                when (block.content) {
                    "START_DREAM_CYCLE" -> {
                        store.dispatch(
                            AppAction.AddSystemMessage(
                                title = "App Request",
                                rawContent = "Please perform a 'Dream Cycle Simulation' based on our recent interaction."
                            )
                        )
                        sendMessage()
                    }
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

        val activeHolonCount = state.activeHolons.size
        val totalHolonCount = state.holonGraph.size
        val holonStats = "Your Holon Knowledge Graph has $activeHolonCount/$totalHolonCount holons active in context."

        return """
        *   You are running on host LLM: `${state.selectedModel}`
        *   Your current runtime platform is 'AUF App v${Version.APP_VERSION}'
        *   You are embodying the persona: '$personaName' (holon: ${state.aiPersonaId})
        *   $holonStats
        *   The time of this request is: `${platform.formatIsoTimestamp(platform.getSystemTimeMillis())}`
        *   $tokenInfo
        """.trimIndent()
    }
}