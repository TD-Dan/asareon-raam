package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.core.Action
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.Session
import app.auf.test.TestHarness
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Ensures all cognitive strategies are registered for test use. Idempotent.
 * Must be called before [testBuiltInResources] or any test that relies on
 * strategy resolution.
 */
private fun ensureTestStrategiesRegistered() {
    if (CognitiveStrategyRegistry.getAll().isEmpty()) {
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.VanillaStrategy, legacyId = "vanilla_v1")
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.SovereignStrategy, legacyId = "sovereign_v1")
    }
}

/**
 * Returns built-in resources from all registered cognitive strategies.
 * Ensures strategies are registered before querying.
 *
 * Replaces the removed `AgentDefaults.builtInResources`.
 */
fun testBuiltInResources(): List<AgentResource> {
    ensureTestStrategiesRegistered()
    return CognitiveStrategyRegistry.getAllBuiltInResources()
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

    // Build strategyConfig: knowledgeGraphId is operator config stored in strategyConfig.
    // SovereignStrategy.getKnowledgeGraphId() reads from here.
    val strategyConfig = if (knowledgeGraphId != null) {
        buildJsonObject {
            put("knowledgeGraphId", knowledgeGraphId)
        }
    } else {
        JsonObject(emptyMap())
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
        strategyConfig = strategyConfig,
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

// ============================================================================
// Identity Registration Helpers
//
// The Store now strictly validates originators and the cognitive pipeline
// requires session UUIDs to be in the identity registry. These helpers
// make it easy to set up the required registrations in integration tests.
// ============================================================================

/**
 * Registers an agent identity in the identity registry so that
 * resolveAgentId (used by INITIATE_TURN) can find it.
 */
fun TestHarness.registerAgentIdentity(agent: AgentInstance) {
    store.dispatch("agent", Action(
        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
        buildJsonObject {
            put("uuid", agent.identity.uuid)
            put("name", agent.identity.name)
        }
    ))
}

/**
 * Registers a session identity in the identity registry so that
 * AgentCognitivePipeline can resolve session UUIDs to handles.
 */
fun TestHarness.registerSessionIdentity(uuid: String, name: String) {
    store.dispatch("session", Action(
        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
        buildJsonObject {
            put("uuid", uuid)
            put("name", name)
        }
    ))
}

/**
 * Registers a session identity from a [Session] object.
 */
fun TestHarness.registerSessionIdentity(session: Session) {
    registerSessionIdentity(session.identity.uuid!!, session.identity.name)
}