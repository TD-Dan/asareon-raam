package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

// MODIFIED: Added IMPORT mode
enum class ViewMode {
    CHAT, EXPORT, IMPORT
}

// --- NEW: Data classes for import analysis results ---
data class ImportAnalysisResult(
    val updatedHolons: List<HolonComparison> = emptyList(),
    val newHolons: List<File> = emptyList(),
    val unchangedHolons: List<HolonHeader> = emptyList(),
    val sourcePath: String
)
data class HolonComparison(
    val existingHeader: HolonHeader,
    val incomingFile: File,
    val incomingLastModified: Long
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
    val importAnalysisResult: ImportAnalysisResult? = null
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