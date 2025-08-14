package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.io.File

enum class ViewMode {
    CHAT, EXPORT, IMPORT
}

data class ImportItem(
    val sourceFile: File,
    val initialAction: ImportAction,
    val targetPath: String? = null
)

@Serializable
sealed interface ImportAction {
    val type: ImportActionType
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE
}

@Serializable
data class Update(
    val targetHolonId: String,
    override val type: ImportActionType = ImportActionType.UPDATE,
    override val summary: String = "Update existing holon."
) : ImportAction

@Serializable
data class Integrate(
    val parentHolonId: String,
    override val type: ImportActionType = ImportActionType.INTEGRATE,
    override val summary: String = "Integrate with known parent."
) : ImportAction

@Serializable
data class AssignParent(
    var assignedParentId: String? = null,
    override val type: ImportActionType = ImportActionType.ASSIGN_PARENT,
    override val summary: String = "New holon - requires parent."
) : ImportAction

@Serializable
data class Quarantine(
    val reason: String,
    override val type: ImportActionType = ImportActionType.QUARANTINE,
    override val summary: String = "Quarantine File"
) : ImportAction

@Serializable
data class Ignore(
    override val type: ImportActionType = ImportActionType.IGNORE,
    override val summary: String = "Do not import."
) : ImportAction

data class ImportState(
    val sourcePath: String,
    val items: List<ImportItem> = emptyList(),
    val selectedActions: Map<String, ImportAction> = emptyMap()
)

data class AppState(
    val holonGraph: List<HolonHeader> = emptyList(),
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
    val importState: ImportState? = null
)

/**
 * A sealed interface representing a single block of content within a ChatMessage.
 * The AI's response is parsed into a list of these blocks.
 */
@Serializable
sealed interface ContentBlock {
    // A human-readable summary of the block's content for UI display or logging.
    val summary: String
}

@Serializable
@SerialName("TextBlock")
data class TextBlock(
    val text: String,
    // Provide a default summary derived from the content.
    override val summary: String = "Text block: \"${text.take(50).replace("\n", " ")}...\""
) : ContentBlock

@Serializable
@SerialName("ActionBlock")
data class ActionBlock(
    val actions: List<Action>,
    // --- FIX APPLIED: Removed redundant summary. The summary is now computed from the contained actions. ---
    val isResolved: Boolean = false
) : ContentBlock {
    // The summary is now a computed property, not a stored one.
    override val summary: String
        get() = "Action Manifest (${actions.size} actions)"
}

@Serializable
@SerialName("FileContentBlock")
data class FileContentBlock(
    val fileName: String,
    val content: String,
    val language: String? = null,
    // --- FIX APPLIED: Added the missing 'summary' field. ---
    override val summary: String = "File View: $fileName"
) : ContentBlock

@Serializable
@SerialName("AppRequestBlock")
data class AppRequestBlock(
    val requestType: String,
    // --- FIX APPLIED: Added the missing 'summary' field. ---
    override val summary: String = "App Request: $requestType"
) : ContentBlock

@Serializable
@SerialName("AnchorBlock")
data class AnchorBlock(
    val anchorId: String,
    val content: JsonObject,
    // --- FIX APPLIED: Added the missing 'summary' field. ---
    override val summary: String = "State Anchor: $anchorId"
) : ContentBlock


data class ChatMessage(
    val author: Author,
    val title: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val contentBlocks: List<ContentBlock>,
    val usageMetadata: UsageMetadata? = null,
    val rawContent: String? = null
)

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
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String,
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