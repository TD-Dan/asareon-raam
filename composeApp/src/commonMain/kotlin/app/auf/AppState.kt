package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Defines the core, immutable data models for the entire AUF application state.
 *
 * ---
 * ## Mandate
 * This file is the single source of truth for the application's state structure. It contains
 * all data classes that represent the UI, session data, and user settings. Its primary
 * responsibility is to provide stable, serializable data contracts that the rest of the
 * application can rely on for state management and UI rendering.
 *
 * ---
 * ## Dependencies
 * - `Action` (from ActionModels.kt): Used within the `ActionBlock` content type.
 * - `kotlinx.serialization`: Used extensively for defining serializable data contracts.
 *
 * @version 1.6
 * @since 2025-08-15
 */

// ... (enums and other data classes remain the same) ...
enum class ViewMode {
    CHAT, EXPORT, IMPORT
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
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE
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

data class ImportState(
    val sourcePath: String,
    val items: List<ImportItem> = emptyList(),
    val selectedActions: Map<String, ImportAction> = emptyMap()
)

data class AppState(
    // Graph and Holon State
    val holonGraph: List<HolonHeader> = emptyList(),
    val catalogueFilter: String? = null,
    val activeHolons: Map<String, Holon> = emptyMap(),
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val aiPersonaId: String? = null,
    val contextualHolonIds: Set<String> = emptySet(),
    val inspectedHolonId: String? = null,

    // Chat and AI State
    val chatHistory: List<ChatMessage> = emptyList(),
    val isSystemVisible: Boolean = false,
    val gatewayStatus: GatewayStatus = GatewayStatus.IDLE,
    val isProcessing: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val errorMessage: String? = null,

    // UI and View State
    val currentViewMode: ViewMode = ViewMode.CHAT,

    val holonIdsForExport: Set<String> = emptySet(),
    val lastUsedExportPath: String? = null,
    val lastUsedImportPath: String? = null
)

@Serializable
sealed interface ContentBlock {
    val summary: String
}

// ... (ContentBlock subclasses remain the same) ...
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
    val isResolved: Boolean = false
) : ContentBlock {
    override val summary: String
        get() = "Action Manifest (${actions.size} actions)"
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


data class ChatMessage(
    val author: Author,
    val title: String? = null,
    // --- ARCHITECTURAL FIX ---
    // The default value has been removed. The responsibility for generating the timestamp
    // now lies with the creator of the ChatMessage instance (i.e., the StateManager),
    // which centralizes the logic and the required experimental API opt-in.
    val timestamp: Long,
    val contentBlocks: List<ContentBlock>,
    val usageMetadata: UsageMetadata? = null,
    val rawContent: String? = null
)

// ... (rest of the file remains the same) ...
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