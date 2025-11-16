package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * To provide pure, testable, static functions for the specialized business logic
 * of Sovereign (HKG-backed) agents. This isolates complex state transition logic
 * from the orchestration and side-effect management of the AgentRuntimeFeature.
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
            store.dispatch("SovereignAgentLogic", Action(
                name = ActionNames.SESSION_CREATE,
                payload = buildJsonObject {
                    put("name", privateSessionName)
                }
            ))

            // A Sovereign agent must acquire an exclusive lock on its HKG.
            store.dispatch("SovereignAgentLogic", Action(
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

            store.dispatch("SovereignAgentLogic", Action(
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
            store.dispatch("SovereignAgentLogic", Action(
                name = ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG,
                payload = buildJsonObject {
                    // Use the oldAgent's ID, as the newAgent's is now null.
                    put("personaId", oldAgent.knowledgeGraphId)
                }
            ))
        }
    }
}