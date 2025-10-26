package app.auf.feature.knowledgegraph

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A rich data model representing a single Holon, adapted from the v1.5.0 implementation.
 * It includes parsed header data for UI display and transient state for interaction.
 */
@Serializable
data class Holon(
    val id: String,
    val type: String,
    val name: String,
    val summary: String? = null,
    @Transient val isSelected: Boolean = false,
    @Transient val content: String? = null, // Content is loaded on demand when selected
    @Transient val parseError: String? = null
)

/**
 * A data model representing a Holon Knowledge Graph, adapted from the v1.5.0 implementation.
 */
@Serializable
data class HolonKnowledgeGraph(
    val id: String,
    val name: String,
    @Transient val holons: List<Holon> = emptyList(),
    @Transient val isSelected: Boolean = false
)

/**
 * The state container for the KnowledgeGraphFeature, adapted from the v1.5.0 implementation
 * to use the new architectural pattern.
 */
@Serializable
data class KnowledgeGraphState(
    val graphs: List<HolonKnowledgeGraph> = emptyList(),
    @Transient val agentNames: Map<String, String> = emptyMap(), // Replaces SelectableAgent
    val activeGraphId: String? = null,
    val activeHolonId: String? = null
) : FeatureState