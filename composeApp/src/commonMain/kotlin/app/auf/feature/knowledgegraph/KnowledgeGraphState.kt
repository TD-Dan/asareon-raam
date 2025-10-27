package app.auf.feature.knowledgegraph

import app.auf.core.FeatureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

// --- CANONICAL HOLON MODELS (ADAPTED FROM V1.5.0) ---

@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonElement,
    @Transient val content: String? = null // Full raw content is stored when loaded
)

@Serializable
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String? = null,
    val version: String = "0.0.0",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    val relationships: List<Relationship> = emptyList(),
    @SerialName("sub_holons")
    val subHolons: List<SubHolonRef> = emptyList(),
    // Transient properties are enriched during the loading process
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


// --- FEATURE STATE ---

/**
 * The state container for the KnowledgeGraphFeature. It now represents a single,
 * hierarchical Holon Knowledge Graph tree, not a collection of graphs.
 */
@Serializable
data class KnowledgeGraphState(
    // Core Data: The complete graph is stored as a map for efficient lookups.
    val holons: Map<String, Holon> = emptyMap(),
    val rootHolonId: String? = null,

    // Persona Management
    val availablePersonas: List<HolonHeader> = emptyList(),
    val activePersonaId: String? = null,

    // UI & Loading State
    val isLoading: Boolean = false,
    val fatalError: String? = null,
    val activeHolonId: String? = null,

    // Caches for UI rendering and lookups
    @Transient val agentNames: Map<String, String> = emptyMap()
) : FeatureState