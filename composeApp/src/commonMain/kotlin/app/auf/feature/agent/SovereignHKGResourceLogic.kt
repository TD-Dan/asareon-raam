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
object SovereignHKGResourceLogic {

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

    /**
     * Ensures that every Sovereign Agent has a linked Private Session.
     * This function is idempotent and robust to race conditions.
     * It should be called when Agents are loaded AND when Session Names are updated.
     */
    fun ensureSovereignSessions(store: Store, agentState: AgentRuntimeState) {
        // 1. HKG Reservation Check
        agentState.agents.values.forEach { agent ->
            if (agent.knowledgeGraphId != null && !agentState.hkgReservedIds.contains(agent.knowledgeGraphId)) {
                store.deferredDispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", agent.knowledgeGraphId) }))
            }
        }

        // 2. Private Session Check & Creation/Linking
        agentState.agents.values.filter { it.knowledgeGraphId != null }.forEach { agent ->
            val expectedSessionName = "p-cognition: ${agent.name} (${agent.id})"

            // Check if the currently linked session (if any) is valid (exists in known sessions)
            val currentSessionId = agent.privateSessionId
            val isLinkedSessionValid = currentSessionId != null && agentState.sessionNames.containsKey(currentSessionId)

            if (isLinkedSessionValid) {
                // Agent is correctly linked. Do nothing.
                return@forEach
            }

            // Agent is unlinked OR points to a dead session ID (Stale).
            // Attempt to find the session by name.
            val existingSessionEntry = agentState.sessionNames.entries.find { it.value == expectedSessionName }

            if (existingSessionEntry != null) {
                // The session exists but isn't linked (or ID mismatch). Link it.
                store.deferredDispatch("agent", Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
                    put("agentId", agent.id)
                    put("privateSessionId", existingSessionEntry.key)
                }))
            } else {
                // The session does not exist. Create it.
                // NOTE: This relies on SessionFeature eventually publishing the new name,
                // which will trigger this function again to perform the link.
                store.deferredDispatch("agent", Action(ActionNames.SESSION_CREATE, buildJsonObject { put("name", expectedSessionName) }))
            }
        }
    }

    // Deprecated helpers maintained for compatibility during refactor, but can be removed if unused.
    fun validateAndCorrectStartupState(store: Store, agentState: AgentRuntimeState) {
        ensureSovereignSessions(store, agentState)
    }

    fun linkPrivateSessionOnCreation(store: Store, agentState: AgentRuntimeState) {
        ensureSovereignSessions(store, agentState)
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