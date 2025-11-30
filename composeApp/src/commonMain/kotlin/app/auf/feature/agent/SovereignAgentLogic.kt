package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
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

    /**
     * This function is the canonical gate for an agent's transition to sovereignty.
     * It observes a state change and, if an agent has just been assigned an HKG for
     * the first time, it orchestrates the creation of its mandatory private session and
     * the reservation of its Knowledge Graph.
     */
    fun handleSovereignAssignment(
        store: Store,
        oldAgent: AgentInstance?,
        newAgent: AgentInstance
    ) {
        val justBecameSovereign = newAgent.knowledgeGraphId != null &&
                oldAgent?.knowledgeGraphId == null

        if (justBecameSovereign) {
            // The session name is deliberately made unique to avoid collisions if agents are renamed.
            val privateSessionName = "p-cognition: ${newAgent.name} (${newAgent.id})"
            // [FIX] Use deferredDispatch
            store.deferredDispatch("agent", Action(
                name = ActionNames.SESSION_CREATE,
                payload = buildJsonObject {
                    put("name", privateSessionName)
                }
            ))

            // A Sovereign agent must acquire an exclusive lock on its HKG.
            // [FIX] Use deferredDispatch
            store.deferredDispatch("agent", Action(
                name = ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG,
                payload = buildJsonObject {
                    put("personaId", newAgent.knowledgeGraphId)
                }
            ))
        }
    }

    /**
     * This function is the canonical gate for an agent's de-transition from sovereignty.
     * It observes a state change and, if an agent has just had its HKG unassigned,
     * it orchestrates the cleanup of its state and the release of its Knowledge Graph reservation.
     */
    fun handleSovereignRevocation(
        store: Store,
        oldAgent: AgentInstance?,
        newAgent: AgentInstance
    ) {
        val justBecameVanilla = oldAgent?.knowledgeGraphId != null && newAgent.knowledgeGraphId == null

        if (justBecameVanilla) {
            val truncatedSubscriptions = oldAgent.subscribedSessionIds.take(1)

            // [FIX] Use deferredDispatch
            store.deferredDispatch("agent", Action(
                name = ActionNames.AGENT_UPDATE_CONFIG,
                payload = buildJsonObject {
                    put("agentId", newAgent.id)
                    // Explicitly detach the private session by setting it to null.
                    put("privateSessionId", JsonNull)
                    // Truncate public subscriptions to conform to Vanilla agent rules (max 1).
                    put("subscribedSessionIds", buildJsonArray {
                        truncatedSubscriptions.forEach { add(it) }
                    })
                }
            ))

            // The agent must release its exclusive lock on the HKG.
            // [FIX] Use deferredDispatch
            store.deferredDispatch("agent", Action(
                name = ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG,
                payload = buildJsonObject {
                    // Use the oldAgent's ID, as the newAgent's is now null.
                    put("personaId", oldAgent.knowledgeGraphId)
                }
            ))
        }
    }

    /**
     * [NEW] Validates that all sovereign agents in the state meet their startup requirements.
     * It checks for HKG reservations and private sessions, creating them if they are missing.
     */
    fun validateAndCorrectStartupState(store: Store, agentState: AgentRuntimeState) {
        agentState.agents.values.forEach { agent ->
            if (agent.knowledgeGraphId != null) {
                // Validate Reservation
                if (!agentState.hkgReservedIds.contains(agent.knowledgeGraphId)) {
                    // [FIX] Use deferredDispatch
                    store.deferredDispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject {
                        put("personaId", agent.knowledgeGraphId)
                    }))
                }
                // Validate Private Session
                val expectedSessionName = "p-cognition: ${agent.name} (${agent.id})"
                val sessionExists = agentState.sessionNames.any { it.value == expectedSessionName }
                if (!sessionExists) {
                    // [FIX] Use deferredDispatch
                    store.deferredDispatch("agent", Action(ActionNames.SESSION_CREATE, buildJsonObject {
                        put("name", expectedSessionName)
                    }))
                }
            }
        }
    }

    /**
     * [NEW] Connects a newly created private session to a sovereign agent that is waiting for it.
     */
    fun linkPrivateSessionOnCreation(store: Store, agentState: AgentRuntimeState) {
        val agentAwaitingSession = agentState.agents.values.find {
            it.knowledgeGraphId != null && it.privateSessionId == null
        } ?: return

        val expectedSessionName = "p-cognition: ${agentAwaitingSession.name} (${agentAwaitingSession.id})"
        val privateSession = agentState.sessionNames.entries.find { (_, name) ->
            name == expectedSessionName
        } ?: return

        // [FIX] Use deferredDispatch
        store.deferredDispatch("agent", Action(
            name = ActionNames.AGENT_UPDATE_CONFIG,
            payload = buildJsonObject {
                put("agentId", agentAwaitingSession.id)
                put("privateSessionId", privateSession.key)
            }
        ))
    }

    /**
     * [NEW] If the agent is sovereign, requests its HKG context. Otherwise, does nothing.
     * @return `true` if the context was requested (halting the normal flow), `false` otherwise.
     */
    fun requestContextIfSovereign(store: Store, agent: AgentInstance): Boolean {
        val kgId = agent.knowledgeGraphId
        val kgFeatureExists = store.features.any { it.name == "knowledgegraph" }

        if (kgId != null && kgFeatureExists) {
            // [FIX] Use deferredDispatch
            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agent.id); put("step", "Requesting HKG")
            }))

            // [CRITICAL FIX] Use Public Action instead of Private Envelope.
            // The KnowledgeGraphFeature listens for the public `KNOWLEDGEGRAPH_REQUEST_CONTEXT` action.
            // Sending a private envelope here was an architectural violation and caused the request to be ignored.
            store.deferredDispatch("agent", Action(
                name = ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                payload = buildJsonObject {
                    put("correlationId", agent.id)
                    put("personaId", kgId)
                }
            ))
            return true // Context was requested, so the caller should wait.
        }

        if (kgId != null && !kgFeatureExists) {
            // Log the warning, but proceed without context.
            store.platformDependencies.log(
                LogLevel.WARN,
                "agent",
                "Agent '${agent.id}' has an HKG configured, but KnowledgeGraphFeature not found. Proceeding without HKG context."
            )
        }
        return false // No context requested, caller can proceed.
    }
}