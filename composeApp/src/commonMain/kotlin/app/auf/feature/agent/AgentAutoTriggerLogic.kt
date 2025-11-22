package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Watcher: Responsible for checking time-based triggers for automatic agents.
 *
 * This logic is invoked periodically by the AgentRuntimeFeature's heartbeat.
 * It strictly implements the "Debounce" and "Timeout" contract:
 * 1. Debounce: Has enough time passed since the last message received?
 * 2. Timeout: Has the agent been waiting too long overall?
 */
object AgentAutoTriggerLogic {

    fun checkAndDispatchTriggers(
        store: Store,
        state: AgentRuntimeState,
        platformDependencies: PlatformDependencies,
        featureName: String
    ) {
        val currentTime = platformDependencies.getSystemTimeMillis()

        state.agents.values.forEach { agent ->
            // REF: Slice 3 - Access runtime status from `agentStatuses`
            val statusInfo = state.agentStatuses[agent.id] ?: AgentStatusInfo()

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
                    store.deferredDispatch(featureName, Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                        put("agentId", agent.id)
                        put("preview", false)
                    }))
                }
            }
        }
    }
}