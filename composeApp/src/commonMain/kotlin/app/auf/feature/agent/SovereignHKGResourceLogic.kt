package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
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
            store.deferredDispatch("agent", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject { put("personaId", newAgent.knowledgeGraphId) }))
        }
    }

    fun handleSovereignRevocation(store: Store, oldAgent: AgentInstance?, newAgent: AgentInstance) {
        val justBecameVanilla = oldAgent?.knowledgeGraphId != null && newAgent.knowledgeGraphId == null
        if (justBecameVanilla) {
            val truncatedSubscriptions = oldAgent.subscribedSessionIds.take(1)
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
                put("agentId", newAgent.identityUUID.uuid)
                put("privateSessionId", JsonNull)
                put("subscribedSessionIds", buildJsonArray { truncatedSubscriptions.forEach { add(it.handle) } })
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
                return@forEach
            }

            // [RULE 2] The Void (Bootstrap)
            val expectedSessionName = "p-cognition: ${agent.identity.name} (${agent.identityUUID})"

            // A. Check for Name Match in the identity registry (To link an existing but unlinked session)
            val existingSessionIdentity = store.state.value.identityRegistry.values.find {
                it.parentHandle == "session" && it.name == expectedSessionName
            }

            if (existingSessionIdentity != null) {
                // FOUND: Link it by localHandle.
                store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
                    put("agentId", agent.identityUUID.uuid)
                    put("privateSessionId", existingSessionIdentity.localHandle)
                }))
            } else {
                // NOT FOUND: Create it.
                store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_CREATE, buildJsonObject {
                    put("name", expectedSessionName)
                    put("isHidden", true)
                    put("isPrivate", true)
                }))
            }
        }
    }

    fun requestContextIfSovereign(store: Store, agent: AgentInstance): Boolean {
        val kgId = agent.knowledgeGraphId
        val kgFeatureExists = store.features.any { it.identity.handle == "knowledgegraph" }
        val agentUuid = agent.identityUUID

        if (kgId != null && kgFeatureExists) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agentUuid.uuid); put("step", "Requesting HKG")
            }))

            store.deferredDispatch("agent", Action(
                name = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agentUuid.uuid)
                    put("personaId", kgId)
                }
            ))
            return true
        }

        if (kgId != null && !kgFeatureExists) {
            store.platformDependencies.log(LogLevel.WARN, "agent", "Agent '$agentUuid' has HKG but KnowledgeGraphFeature is missing.")
        }
        return false
    }
}