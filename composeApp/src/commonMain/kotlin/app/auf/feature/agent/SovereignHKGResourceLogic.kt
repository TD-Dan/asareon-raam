package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SovereignHKGResourceLogic {

    fun handleSovereignAssignment(store: Store, oldAgent: AgentInstance?, newAgent: AgentInstance) {
        val justBecameSovereign = newAgent.knowledgeGraphId != null && oldAgent?.knowledgeGraphId == null
        if (justBecameSovereign) {
            // We do NOT create session here. We let the ensureSovereignSessions logic handle it
            // naturally in the next loop or immediate check to keep logic centralized.
            // However, ensuring reservation is still good practice here.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", newAgent.knowledgeGraphId) }))
        }
    }

    fun handleSovereignRevocation(store: Store, oldAgent: AgentInstance?, newAgent: AgentInstance) {
        val justBecameVanilla = oldAgent?.knowledgeGraphId != null && newAgent.knowledgeGraphId == null
        if (justBecameVanilla) {
            val truncatedSubscriptions = oldAgent.subscribedSessionIds.take(1)
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
                put("agentId", newAgent.id)
                put("privateSessionId", JsonNull)
                put("subscribedSessionIds", buildJsonArray { truncatedSubscriptions.forEach { add(it) } })
            }))
            store.deferredDispatch("agent", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG, buildJsonObject {
                put("personaId", oldAgent.knowledgeGraphId)
            }))
        }
    }

    /**
     * Implements the "Trust or Bootstrap" protocol.
     *
     * RULE 1: If ID exists, Trust it (Return).
     * RULE 2: If ID is null, Bootstrap it (Find or Create).
     */
    fun ensureSovereignSessions(store: Store, agentState: AgentRuntimeState) {
        // 1. HKG Reservation (Always ensure reservation for Sovereigns)
        agentState.agents.values.forEach { agent ->
            if (agent.knowledgeGraphId != null && !agentState.hkgReservedIds.contains(agent.knowledgeGraphId)) {
                store.deferredDispatch("agent", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", agent.knowledgeGraphId) }))
            }
        }

        // 2. Session Linking (Trust or Bootstrap)
        agentState.agents.values.filter { it.knowledgeGraphId != null }.forEach { agent ->

            // [RULE 1] The Existing Pointer Boundary
            if (agent.privateSessionId != null) {
                // We trust the ID. We do not check if it exists.
                // We do not check if it matches the name.
                // If it is broken, the user must clear it to trigger Rule 2.
                return@forEach
            }

            // [RULE 2] The Void (Bootstrap)
            val expectedSessionName = "p-cognition: ${agent.name} (${agent.id})"

            // A. Check for Name Match (To link an existing but unlinked session)
            val existingSessionEntry = agentState.sessionNames.entries.find { it.value == expectedSessionName }

            if (existingSessionEntry != null) {
                // FOUND: Link it.
                store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
                    put("agentId", agent.id)
                    put("privateSessionId", existingSessionEntry.key)
                }))
            } else {
                // NOT FOUND: Create it.
                // This will trigger SESSION_NAMES_UPDATED later, which will hit case A.
                store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_CREATE, buildJsonObject {
                    put("name", expectedSessionName)
                    put("isHidden", true)
                    put("isAgentPrivate", true)
                }))
            }
        }
    }

    fun requestContextIfSovereign(store: Store, agent: AgentInstance): Boolean {
        val kgId = agent.knowledgeGraphId
        val kgFeatureExists = store.features.any { it.identity.handle == "knowledgegraph" }

        if (kgId != null && kgFeatureExists) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agent.id); put("step", "Requesting HKG")
            }))

            store.deferredDispatch("agent", Action(
                name = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
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