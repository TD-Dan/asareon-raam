package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shorthand for [IdentityUUID] construction in tests.
 * Keeps state-builder call sites concise:
 *   `AgentRuntimeState(agents = mapOf(uid("a1") to agent))`
 */
fun uid(s: String) = IdentityUUID(s)

/**
 * Maps legacy strategy ID strings to their canonical [IdentityHandle].
 */
private fun resolveStrategyHandle(raw: String): IdentityHandle = when (raw) {
    "vanilla_v1" -> IdentityHandle("agent.strategy.vanilla")
    "sovereign_v1" -> IdentityHandle("agent.strategy.sovereign")
    else -> IdentityHandle(raw)
}

/**
 * Test utility to construct AgentInstance with Identity.
 * Mirrors the old positional constructor for minimal test churn.
 *
 * Accepts plain [String] parameters and converts internally:
 * - [subscribedSessionIds] → `List<IdentityUUID>`
 * - [privateSessionId] → `outputSessionId: IdentityUUID?`
 * - [cognitiveStrategyId] → `IdentityHandle` (migrates "vanilla_v1" / "sovereign_v1")
 * - [resources] values → `IdentityUUID`
 * - [knowledgeGraphId] → embedded in `cognitiveState` (no longer a direct field)
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
): AgentInstance {
    val resolvedStrategy = resolveStrategyHandle(cognitiveStrategyId)

    // Build cognitiveState: embed knowledgeGraphId if provided (Sovereign),
    // otherwise use JsonNull (Vanilla default).
    val cognitiveState = if (knowledgeGraphId != null) {
        buildJsonObject {
            put("phase", "BOOTING")
            put("knowledgeGraphId", knowledgeGraphId)
        }
    } else {
        JsonNull
    }

    return AgentInstance(
        identity = Identity(
            uuid = id,
            localHandle = name.lowercase().replace(" ", "-"),
            handle = "agent.${name.lowercase().replace(" ", "-")}",
            name = name,
            parentHandle = "agent"
        ),
        modelProvider = modelProvider,
        modelName = modelName,
        subscribedSessionIds = subscribedSessionIds.map { IdentityUUID(it) },
        outputSessionId = privateSessionId?.let { IdentityUUID(it) },
        cognitiveStrategyId = resolvedStrategy,
        cognitiveState = cognitiveState,
        resources = resources.mapValues { IdentityUUID(it.value) },
        automaticMode = automaticMode,
        autoWaitTimeSeconds = autoWaitTimeSeconds,
        autoMaxWaitTimeSeconds = autoMaxWaitTimeSeconds,
        isAgentActive = isAgentActive
    )
}

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