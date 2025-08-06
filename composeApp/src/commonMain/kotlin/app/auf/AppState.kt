package app.auf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HolonCatalogueFile(
    val holon_catalogue: List<HolonHeader>
)

data class AppState(
    val holonCatalogue: List<HolonHeader> = emptyList(),
    val catalogueFilter: String? = null,
    val activeHolons: Map<String, Holon> = emptyMap(),

    // --- The "Me" ---
    val aiPersonaId: String? = null,
    // --- The "World" ---
    val contextualHolonIds: Set<String> = emptySet(),

    val inspectedHolonId: String? = null,
    val chatHistory: List<ChatMessage> = emptyList(),
    val isSystemVisible: Boolean = false,
    val gatewayStatus: GatewayStatus = GatewayStatus.IDLE,
    val isProcessing: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",

    // --- NEW: Awaiting User Command ---
    // Holds a parsed action manifest that has been proposed by the AI
    // but not yet confirmed by the user. The UI will react to this state.
    val pendingActionManifest: List<Action>? = null
)

data class ChatMessage(
    val author: Author,
    val content: String,
    val title: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Author {
    USER, AI, SYSTEM
}

enum class GatewayStatus {
    OK, IDLE, ERROR
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
    @SerialName("file_path")
    val filePath: String
)