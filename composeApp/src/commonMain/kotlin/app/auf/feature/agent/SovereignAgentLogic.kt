package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * To provide pure, testable, static functions for the specialized business logic
 * of Sovereign (HKG-backed) agents. This isolates complex state transition logic
 * from the orchestration and side effect management of the AgentRuntimeFeature.
 */
object SovereignAgentLogic {

    fun handleSovereignAssignment(store: Store, oldAgent: AgentInstance?, newAgent: AgentInstance) {
        val justBecameSovereign = newAgent.knowledgeGraphId != null && oldAgent?.knowledgeGraphId == null
        if (justBecameSovereign) {
            val privateSessionName = "p-cognition: ${newAgent.name} (${newAgent.id})"
            store.deferredDispatch("agent", Action(ActionNames.SESSION_CREATE, buildJsonObject { put("name", privateSessionName) }))
            store.deferredDispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", newAgent.knowledgeGraphId) }))
        }
    }

    fun handleSovereignRevocation(store: Store, oldAgent: AgentInstance?, newAgent: AgentInstance) {
        val justBecameVanilla = oldAgent?.knowledgeGraphId != null && newAgent.knowledgeGraphId == null
        if (justBecameVanilla) {
            val truncatedSubscriptions = oldAgent.subscribedSessionIds.take(1)
            store.deferredDispatch("agent", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                put("agentId", newAgent.id)
                put("privateSessionId", JsonNull)
                put("subscribedSessionIds", buildJsonArray { truncatedSubscriptions.forEach { add(it) } })
            }))
            store.deferredDispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG, buildJsonObject {
                put("personaId", oldAgent.knowledgeGraphId)
            }))
        }
    }

    fun validateAndCorrectStartupState(store: Store, agentState: AgentRuntimeState) {
        agentState.agents.values.forEach { agent ->
            if (agent.knowledgeGraphId != null) {
                if (!agentState.hkgReservedIds.contains(agent.knowledgeGraphId)) {
                    store.deferredDispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", agent.knowledgeGraphId) }))
                }
                val expectedSessionName = "p-cognition: ${agent.name} (${agent.id})"
                if (agentState.sessionNames.none { it.value == expectedSessionName }) {
                    store.deferredDispatch("agent", Action(ActionNames.SESSION_CREATE, buildJsonObject { put("name", expectedSessionName) }))
                }
            }
        }
    }

    fun linkPrivateSessionOnCreation(store: Store, agentState: AgentRuntimeState) {
        val agent = agentState.agents.values.find { it.knowledgeGraphId != null && it.privateSessionId == null } ?: return
        val expectedName = "p-cognition: ${agent.name} (${agent.id})"
        val session = agentState.sessionNames.entries.find { it.value == expectedName } ?: return
        store.deferredDispatch("agent", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", agent.id)
            put("privateSessionId", session.key)
        }))
    }

    /**
     * [CRITICAL FIX] switched from PrivateDataEnvelope (invalid) to Public Action (valid).
     */
    fun requestContextIfSovereign(store: Store, agent: AgentInstance): Boolean {
        val kgId = agent.knowledgeGraphId
        val kgFeatureExists = store.features.any { it.name == "knowledgegraph" }

        if (kgId != null && kgFeatureExists) {
            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agent.id); put("step", "Requesting HKG")
            }))

            // FIX: Use the public action that KnowledgeGraphFeature listens for.
            store.deferredDispatch("agent", Action(
                name = ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agent.id)
                    put("personaId", kgId)
                }
            ))
            return true
        }

        if (kgId != null && !kgFeatureExists) {
            store.platformDependencies.log(LogLevel.WARN, "agent", "Agent '${agent.id}' has HKG but KnowledgeGraphFeature is missing.")
        }
        return false
    }
}