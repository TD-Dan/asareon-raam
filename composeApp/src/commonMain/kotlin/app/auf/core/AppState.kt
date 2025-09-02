package app.auf.core

import app.auf.model.CompilerSettings
import app.auf.service.AufTextParser
import app.auf.service.UsageMetadata
import app.auf.util.PlatformDependencies
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Defines the core, immutable data models for the entire AUF application state.
 * As of v2.0, this object is lean, holding only top-level session state.
 * Feature-specific state is managed within the `featureStates` map.
 */

data class AppState(
    // --- DEPRECATED: chatHistory has been moved to SessionFeature ---
    // val chatHistory: List<ChatMessage> = emptyList(),
    val isSystemVisible: Boolean = false,
    val isProcessing: Boolean = false, // Is the gateway currently busy?
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val toastMessage: String? = null,
    val compilerSettings: CompilerSettings = CompilerSettings(),

    /**
     * The single entry point for all modular feature states. The key is the
     * unique feature name (e.g., "KnowledgeGraphFeature"), and the value is the
     * feature's specific state object (e.g., KnowledgeGraphState).
     */
    val featureStates: Map<String, Any> = emptyMap()
)

data class GatewayResponse(
    val contentBlocks: List<ContentBlock> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
    val errorMessage: String? = null,
    val rawContent: String? = null
)


@Serializable
sealed interface ContentBlock {
    val summary: String
}

@Serializable
@SerialName("TextBlock")
data class TextBlock(
    val text: String,
    override val summary: String = "Text block: \"${text.take(50).replace("\n", " ")}...\""
) : ContentBlock

/**
 * A universal container for any content wrapped in ```markdown code fences```.
 * The `language` hint (e.g., "json", "kotlin" ,"auf_tool_name") is used by the UI to determine
 * how to render the block and what actions to offer.
 */
@Serializable
@SerialName("CodeBlock")
data class CodeBlock(
    val language: String,
    val content: String,
    var status: ActionStatus = ActionStatus.PENDING, // Used for actionable blocks like json
    override val summary: String = "Code Block: $language"
) : ContentBlock


enum class ActionStatus {
    PENDING,
    EXECUTED,
    REJECTED
}

@Serializable
data class CompilationStats(
    val originalCharCount: Int,
    val compiledCharCount: Int
)

@Serializable
data class ChatMessage internal constructor(
    @Transient val id: Long = 0L,
    val author: Author,
    val title: String?,
    val timestamp: Long,
    val contentBlocks: List<ContentBlock>,
    val usageMetadata: UsageMetadata?,
    val rawContent: String?,
    val compiledContent: String? = null,
    val compilationStats: CompilationStats? = null,
    val isCollapsed: Boolean = false
) {
    companion object Factory {
        private var nextId = 0L
        private var platform: PlatformDependencies? = null
        private var parser: AufTextParser? = null

        fun initialize(platform: PlatformDependencies, parser: AufTextParser) {
            this.platform = platform
            this.parser = parser
            nextId = 0L
        }

        fun reId(loadedMessages: List<ChatMessage>): List<ChatMessage> {
            nextId = 0L
            return loadedMessages.map { it.copy(id = ++nextId) }
        }

        private fun getTimestamp(): Long {
            return platform?.getSystemTimeMillis()
                ?: throw IllegalStateException("ChatMessage.Factory has not been initialized.")
        }



        private fun getParser(): AufTextParser {
            return parser
                ?: throw IllegalStateException("ChatMessage.Factory has not been initialized with a parser.")
        }

        fun createUser(rawContent: String): ChatMessage {
            return ChatMessage(
                id = ++nextId,
                author = Author.USER,
                title = null,
                timestamp = getTimestamp(),
                contentBlocks = getParser().parse(rawContent),
                usageMetadata = null,
                rawContent = rawContent,
                isCollapsed = false
            )
        }

        fun createAi(
            rawContent: String,
            usageMetadata: UsageMetadata?
        ): ChatMessage {
            return ChatMessage(
                id = ++nextId,
                author = Author.AI,
                title = "AI",
                timestamp = getTimestamp(),
                contentBlocks = getParser().parse(rawContent),
                usageMetadata = usageMetadata,
                rawContent = rawContent,
                isCollapsed = false
            )
        }

        fun createSystem(
            title: String,
            rawContent: String
        ): ChatMessage {
            val shouldCollapse = title != "Gateway Error" && title != "Graph Parsing Warning"
            return ChatMessage(
                id = ++nextId,
                author = Author.SYSTEM,
                title = title,
                timestamp = getTimestamp(),
                contentBlocks = getParser().parse(rawContent),
                usageMetadata = null,
                rawContent = rawContent,
                isCollapsed = shouldCollapse
            )
        }
    }
}


enum class Author {
    USER, AI, SYSTEM
}