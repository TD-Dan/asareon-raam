package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session

/**
 * Test utility to construct AgentInstance with Identity.
 * Mirrors the old positional constructor for minimal test churn.
 */
fun testAgent(
    id: String,
    name: String,
    knowledgeGraphId: String? = null,
    modelProvider: String = "",
    modelName: String = "",
    subscribedSessionIds: List<String> = emptyList(),
    privateSessionId: String? = null,
    cognitiveStrategyId: String = "vanilla_v1",
    resources: Map<String, String> = emptyMap(),
    automaticMode: Boolean = false,
    autoWaitTimeSeconds: Int = 5,
    autoMaxWaitTimeSeconds: Int = 30,
    isAgentActive: Boolean = true
) = AgentInstance(
    identity = Identity(
        uuid = id,
        localHandle = name.lowercase().replace(" ", "-"),
        handle = "agent.${name.lowercase().replace(" ", "-")}",
        name = name,
        parentHandle = "agent"
    ),
    knowledgeGraphId = knowledgeGraphId,
    modelProvider = modelProvider,
    modelName = modelName,
    subscribedSessionIds = subscribedSessionIds,
    privateSessionId = privateSessionId,
    cognitiveStrategyId = cognitiveStrategyId,
    resources = resources,
    automaticMode = automaticMode,
    autoWaitTimeSeconds = autoWaitTimeSeconds,
    autoMaxWaitTimeSeconds = autoMaxWaitTimeSeconds,
    isAgentActive = isAgentActive
)

/**
 * Test utility to construct Session with Identity.
 * Mirrors the old positional constructor for minimal test churn.
 */
fun testSession(
    id: String,
    name: String,
    ledger: List<LedgerEntry> = emptyList(),
    timestamp: Long = 1L
) = Session(
    identity = Identity(
        uuid = id,
        localHandle = name.lowercase().replace(" ", "-"),
        handle = "session.${name.lowercase().replace(" ", "-")}",
        name = name,
        parentHandle = "session"
    ),
    ledger = ledger,
    createdAt = timestamp
)