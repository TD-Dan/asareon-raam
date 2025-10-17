package app.auf.feature.agent

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class AgentStatus { IDLE, WAITING, PROCESSING, ERROR }

@Serializable
data class AgentInstance(
    val id: String,
    val name: String,
    val personaId: String,
    val modelProvider: String,
    val modelName: String,
    val primarySessionId: String? = null,
    val automaticMode: Boolean = false,

    @Transient
    val status: AgentStatus = AgentStatus.IDLE,

    @Transient
    val errorMessage: String? = null
)

@Serializable
data class AgentRuntimeState(
    val agents: Map<String, AgentInstance> = emptyMap(),
    val sessionNames: Map<String, String> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),

    @Transient
    val editingAgentId: String? = null,

    /**
     * A transient map tracking the message ID of each agent's active avatar cards.
     * This enables tracking multiple "frontiers" (e.g., PROCESSING, WAITING).
     *
     * Structure: Map<agentId, Map<AgentStatus, messageId>>
     * Example: { "agent-1": { "PROCESSING": "msg-abc", "WAITING": "msg-xyz" } }
     *
     * This is a runtime state and is NOT persisted.
     */
    @Transient
    val agentAvatarCardIds: Map<String, Map<AgentStatus, String>> = emptyMap()
) : FeatureState