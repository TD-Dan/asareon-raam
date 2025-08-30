package app.auf.core

import app.auf.model.Action
import app.auf.model.CompilerSettings
import app.auf.service.AufTextParser
import app.auf.service.UsageMetadata
import app.auf.util.PlatformDependencies
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Defines the core, immutable data models for the entire AUF application state.
 *
 * @version 2.6
 * @since 2025-08-28
 */

data class GatewayResponse(
    val contentBlocks: List<ContentBlock> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
    val errorMessage: String? = null,
    val rawContent: String? = null
)

data class GraphLoadResult(
    val holonGraph: List<Holon> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val fatalError: String? = null,
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val determinedPersonaId: String? = null
)

enum class ViewMode {
    CHAT, EXPORT, IMPORT, SETTINGS
}

data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String? = null
)

@Serializable
sealed interface ImportAction {
    val actionType: ImportActionType
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE, CREATE_ROOT
}

@Serializable
@SerialName("Update")
data class Update(
    val targetHolonId: String,
    override val actionType: ImportActionType = ImportActionType.UPDATE,
    override val summary: String = "Update existing holon."
) : ImportAction

@Serializable
@SerialName("Integrate")
data class Integrate(
    val parentHolonId: String,
    override val actionType: ImportActionType = ImportActionType.INTEGRATE,
    override val summary: String = "Integrate with known parent."
) : ImportAction

@Serializable
@SerialName("AssignParent")
data class AssignParent(
    var assignedParentId: String? = null,
    override val actionType: ImportActionType = ImportActionType.ASSIGN_PARENT,
    override val summary: String = "New holon - requires parent."
) : ImportAction

@Serializable
@SerialName("Quarantine")
data class Quarantine(
    val reason: String,
    override val actionType: ImportActionType = ImportActionType.QUARANTINE,
    override val summary: String = "Quarantine File"
) : ImportAction

@Serializable
@SerialName("Ignore")
data class Ignore(
    override val actionType: ImportActionType = ImportActionType.IGNORE,
    override val summary: String = "Do not import."
) : ImportAction

@Serializable
@SerialName("CreateRoot")
data class CreateRoot(
    override val actionType: ImportActionType = ImportActionType.CREATE_ROOT,
    override val summary: String = "IMPORT AS NEW ROOT PERSONA"
) : ImportAction


data class ImportState(
    val sourcePath: String,
    val items: List<ImportItem> = emptyList(),
    val selectedActions: Map<String, ImportAction> = emptyMap()
)

data class AppState(
    val holonGraph: List<Holon> = emptyList(),
    val catalogueFilter: String? = null,
    val activeHolons: Map<String, Holon> = emptyMap(),
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val aiPersonaId: String? = null,
    val contextualHolonIds: Set<String> = emptySet(),
    val inspectedHolonId: String? = null,
    val chatHistory: List<ChatMessage> = emptyList(),
    val isSystemVisible: Boolean = false,
    val gatewayStatus: GatewayStatus = GatewayStatus.IDLE,
    val isProcessing: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val errorMessage: String? = null,
    val currentViewMode: ViewMode = ViewMode.CHAT,
    val holonIdsForExport: Set<String> = emptySet(),
    val importState: ImportState? = null,
    val toastMessage: String? = null,
    val compilerSettings: CompilerSettings = CompilerSettings()
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

@Serializable
@SerialName("ActionBlock")
data class ActionBlock(
    val actions: List<Action>,
    var status: ActionStatus = ActionStatus.PENDING
) : ContentBlock {
    override val summary: String
        get() = "Action Manifest (${actions.size} actions)"
}

enum class ActionStatus {
    PENDING,
    EXECUTED,
    REJECTED
}


@Serializable
@SerialName("FileContentBlock")
data class FileContentBlock(
    val fileName: String,
    val content: String,
    val language: String? = null,
    override val summary: String = "File View: $fileName"
) : ContentBlock

@Serializable
@SerialName("AppRequestBlock")
data class AppRequestBlock(
    val requestType: String,
    override val summary: String = "App Request: $requestType"
) : ContentBlock

@Serializable
@SerialName("AnchorBlock")
data class AnchorBlock(
    val anchorId: String,
    val content: JsonObject,
    override val summary: String = "State Anchor: $anchorId"
) : ContentBlock

@Serializable
@SerialName("ParseErrorBlock")
data class ParseErrorBlock(
    val originalTag: String,
    val rawContent: String,
    val errorMessage: String,
    override val summary: String = "Parse Error: $originalTag"
) : ContentBlock

@Serializable
@SerialName("SentinelBlock")
data class SentinelBlock(
    val message: String,
    override val summary: String = "Parser Sentinel"
) : ContentBlock

@Serializable
data class CompilationStats(
    val originalCharCount: Int,
    val compiledCharCount: Int
)

@Serializable
@ConsistentCopyVisibility
data class ChatMessage internal constructor(
    @Transient val id: Long = 0L,
    val author: Author,
    val title: String?,
    val timestamp: Long,
    val contentBlocks: List<ContentBlock>,
    val usageMetadata: UsageMetadata?,
    val rawContent: String?,
    val compiledContent: String? = null,
    val compilationStats: CompilationStats? = null
) {
    companion object Factory {
        private var nextId = 0L
        private var platform: PlatformDependencies? = null
        private var parser: AufTextParser? = null

        fun initialize(platform: PlatformDependencies, parser: AufTextParser) {
            this.platform = platform
            this.parser = parser
            nextId = 0L // Ensure counter is reset on initialization
        }

        /**
         * Takes a list of messages (presumably from a deserialized session file) and
         * returns a new list where each message has a fresh, unique, sequential ID.
         */
        fun reId(loadedMessages: List<ChatMessage>): List<ChatMessage> {
            nextId = 0L // Reset counter before re-IDing
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
                compiledContent = null,
                compilationStats = null
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
                compiledContent = null,
                compilationStats = null
            )
        }

        fun createSystem(
            title: String,
            rawContent: String
        ): ChatMessage {
            return ChatMessage(
                id = ++nextId,
                author = Author.SYSTEM,
                title = title,
                timestamp = getTimestamp(),
                contentBlocks = getParser().parse(rawContent),
                usageMetadata = null,
                rawContent = rawContent,
                compiledContent = null,
                compilationStats = null
            )
        }
    }
}


enum class Author {
    USER, AI, SYSTEM
}

enum class GatewayStatus {
    OK, IDLE, ERROR, LOADING
}

@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonObject
)

@Serializable
data class HolonUsage(
    val scope: String? = null,
    val description: String? = null
)

@Serializable
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String,
    val version: String = "0.0.0",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    @SerialName("holon_usage")
    val holonUsage: HolonUsage? = null,
    val relationships: List<Relationship> = emptyList(),
    @SerialName("sub_holons")
    val subHolons: List<SubHolonRef> = emptyList(),
    @Transient val filePath: String = "",
    @Transient val parentId: String? = null,
    @Transient val depth: Int = 0
)

@Serializable
data class Relationship(
    @SerialName("target_id")
    val targetId: String,
    val type: String
)

@Serializable
data class SubHolonRef(
    val id: String,
    val type: String,
    val summary: String
)