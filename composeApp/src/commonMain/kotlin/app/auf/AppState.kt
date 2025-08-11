package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

enum class ViewMode {
    CHAT, EXPORT, IMPORT
}


// --- NEW / MODIFIED: Data classes for the Import & Sync Workbench ---

/**
 * Represents a single row in the Import Workbench UI.
 * It holds the source file and the result of the initial analysis.
 */
data class ImportItem(
    val sourceFile: File,
    val initialAction: ImportAction,
    val targetPath: String? = null // For previewing where the file will go
)

/**
 * A sealed interface representing the possible actions a user can select for an import item.
 */
@Serializable
sealed interface ImportAction {
    val type: ImportActionType
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, IGNORE
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
data class Ignore(
    override val type: ImportActionType = ImportActionType.IGNORE,
    override val summary: String = "Do not import."
) : ImportAction


// Represents the state of the entire Import & Sync view.
data class ImportState(
    val sourcePath: String,
    val items: List<ImportItem> = emptyList(),
    // We need a map to track user-overridden actions, separate from the initial analysis
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

    // --- MODIFIED STATE PROPERTIES ---
    val currentViewMode: ViewMode = ViewMode.CHAT,
    val holonIdsForExport: Set<String> = emptySet(),
    // --- MODIFIED: The old analysis result is replaced with the new workbench state model ---
    val importState: ImportState? = null
)

data class ChatMessage(
    val author: Author,
    val content: String,
    val title: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val actionManifest: List<Action>? = null,
    val isActionResolved: Boolean = false,
    val usageMetadata: UsageMetadata? = null
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
    val content: String
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