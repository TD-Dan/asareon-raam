package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Watcher: Responsible for checking time-based triggers for automatic agents.
 *
 * This logic is invoked periodically by the AgentRuntimeFeature's heartbeat.
 * It strictly implements the "Debounce" and "Timeout" contract:
 * 1. Debounce: Has enough time passed since the last message received?
 * 2. Timeout: Has the agent been waiting too long overall?
 *
 * Additionally, it handles automatic retry after API rate limit windows expire.
 * Rate limit retries apply to ALL agents (not just automatic mode) because any
 * agent — manual or automatic — may hit a rate limit during generation.
 *
 */
object AutoTriggerLogic {

    fun checkAndDispatchTriggers(
        store: Store,
        state: AgentRuntimeState,
        platformDependencies: PlatformDependencies,
        featureName: String
    ) {
        val currentTime = platformDependencies.currentTimeMillis()

        state.agents.values.forEach { agent ->
            val agentUuid = agent.identityUUID
            val statusInfo = state.agentStatuses[agentUuid] ?: AgentStatusInfo()

            // ================================================================
            // Rate Limit Auto-Retry (applies to ALL agents, not just automatic)
            //
            // When an agent is RATE_LIMITED and the retry window has expired,
            // dispatch INITIATE_TURN to retry the failed request. The reducer's
            // time-based guard will allow the turn through since the window has passed.
            // ================================================================
            if (statusInfo.status == AgentStatus.RATE_LIMITED &&
                statusInfo.rateLimitedUntilMs != null &&
                currentTime >= statusInfo.rateLimitedUntilMs &&
                agent.isAgentActive
            ) {
                store.deferredDispatch(featureName, Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                    put("agentId", agentUuid.uuid)
                    put("preview", false)
                }))
                return@forEach // Don't also check auto-trigger for this agent on this tick
            }

            // ================================================================
            // Automatic Mode Debounce/Timeout (only for automatic agents)
            // ================================================================
            // Condition: Must be Automatic, Active, Waiting, and have valid timestamps
            if (agent.automaticMode &&
                agent.isAgentActive &&
                statusInfo.status == AgentStatus.WAITING &&
                statusInfo.waitingSinceTimestamp != null &&
                statusInfo.lastMessageReceivedTimestamp != null
            ) {
                val waitedFor = (currentTime - statusInfo.lastMessageReceivedTimestamp) / 1000
                val totalWait = (currentTime - statusInfo.waitingSinceTimestamp) / 1000

                val debounceTrigger = waitedFor >= agent.autoWaitTimeSeconds
                val timeoutTrigger = totalWait >= agent.autoMaxWaitTimeSeconds

                if (debounceTrigger || timeoutTrigger) {
                    store.deferredDispatch(featureName, Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                        put("agentId", agentUuid.uuid)
                        put("preview", false)
                    }))
                }
            }
        }
    }
}