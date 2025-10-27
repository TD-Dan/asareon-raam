package app.auf.feature.knowledgegraph

import app.auf.core.FeatureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

// --- CANONICAL HOLON MODELS (ADAPTED FROM V1.5.0 & SAGE HKG) ---

/**
 * The canonical, in-memory representation of a Holon.
 * This data class is a faithful implementation of the definition found in
 * 'system-holon-definition-20250809T101500Z.json'.
 */
@Serializable
data class Holon(
    /** The structured metadata defining the holon's identity and relationships. */
    val header: HolonHeader,

    /** The flexible, narrative core containing the holon's cognitive content. */
    val payload: JsonElement,

    /**
     * An optional block for agentic holons (e.g., AI_Persona_Root) that defines
     * their core, machine-readable behaviors, and boot sequences.
     */
    val execute: JsonElement? = null,

    /**
     * The full, raw JSON content of the holon file.
     * Per the eager-loading directive, this is non-nullable and loaded at startup.
     */
    val content: String
)

/**
 * The holon's header, containing structured metadata for indexing, linking,
 * and understanding the holon's role in the system.
 */
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

    // Transient properties are enriched during the loading process and are not persisted.
    @Transient val filePath: String = "",
    @Transient val parentId: String? = null,
    @Transient val depth: Int = 0
)

/** Defines a semantic link between this holon and a target holon. */
@Serializable
data class Relationship(
    @SerialName("target_id")
    val targetId: String,
    val type: String
)

/** A lightweight reference to a child holon, used to define the HKG tree structure. */
@Serializable
data class SubHolonRef(
    val id: String,
    val type: String,
    val summary: String
)


// --- FEATURE STATE ---

/**
 * The state container for the KnowledgeGraphFeature. It is architected to manage
 * multiple, distinct Holon Knowledge Graph trees simultaneously in memory.
 */
@Serializable
data class KnowledgeGraphState(
    /** A single, unified map of ALL holons from ALL loaded HKGs, keyed by holon ID. */
    val holons: Map<String, Holon> = emptyMap(),

    /** A map of all discovered AI Persona names to their root holon IDs, used for UI selection. */
    val personaRoots: Map<String, String> = emptyMap(),

    // --- UI State ---
    /** The root persona ID of the HKG currently being displayed in the UI. */
    val activePersonaIdForView: String? = null,
    /** The holon ID of the item currently selected for detail inspection in the UI. */
    val activeHolonIdForView: String? = null,

    // --- Loading & Error State ---
    val isLoading: Boolean = false,
    val fatalError: String? = null,
) : FeatureState