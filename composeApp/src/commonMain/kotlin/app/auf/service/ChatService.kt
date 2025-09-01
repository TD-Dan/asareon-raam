package app.auf.service

import app.auf.core.*
import app.auf.feature.knowledgegraph.Holon
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.util.BasePath
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
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
        val kgState = state.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
        if (state.isProcessing || kgState.aiPersonaId == null) return

        store.dispatch(AppAction.SendMessageLoading)

        val historyForApi = state.chatHistory
        val systemMessages = buildSystemContextMessages()
        val fullContextForApi = systemMessages + historyForApi

        activeJob = coroutineScope.launch {
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
        val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
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

        val activeIds = kgState.contextualHolonIds + (kgState.aiPersonaId ?: "")
        val activeHolons = kgState.holonGraph.filter { it.header.id in activeIds }

        activeHolons.forEach { holon ->
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
        val historyForApi = state.chatHistory
        val systemMessages = buildSystemContextMessages()
        val fullContext = systemMessages + historyForApi

        return fullContext.joinToString("\n\n") { msg ->
            // For clipboard accuracy, we ensure system messages always use their compiled form.
            val content = when {
                msg.author == Author.SYSTEM -> msg.compiledContent ?: promptCompiler.compile(msg.rawContent ?: "", state.compilerSettings).compiledText
                else -> msg.rawContent ?: ""
            }

            when (msg.author) {
                Author.USER -> "--- USER MESSAGE ---\n$content"
                Author.AI -> "--- model MESSAGE ---\n$content" // The test expects "model"
                Author.SYSTEM -> "--- START OF FILE ${msg.title} ---\n$content"
            }
        }
    }
    // --- FIX END ---


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
        val kgState = state.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
        val personaName = kgState.availableAiPersonas.find { it.id == kgState.aiPersonaId }?.name ?: "Unknown"
        val lastTx = state.chatHistory.lastOrNull { it.author == Author.AI }?.usageMetadata
        val tokenInfo = lastTx?.totalTokenCount?.let { "Approximate context window size of last transaction: $it tokens." }
            ?: "Token count for the last transaction is not available."

        val activeHolonCount = kgState.contextualHolonIds.size + (if(kgState.aiPersonaId != null) 1 else 0)
        val totalHolonCount = kgState.holonGraph.size
        val holonStats = "Your Holon Knowledge Graph has $activeHolonCount/$totalHolonCount holons active in context."

        return """
        *   You are running on host LLM: `${state.selectedModel}`
        *   Your current runtime platform is 'AUF App v${Version.APP_VERSION}'
        *   You are embodying the persona: '$personaName' (holon: ${kgState.aiPersonaId})
        *   $holonStats
        *   The time of this request is: `${platform.formatIsoTimestamp(platform.getSystemTimeMillis())}`
        *   $tokenInfo
        """.trimIndent()
    }
}