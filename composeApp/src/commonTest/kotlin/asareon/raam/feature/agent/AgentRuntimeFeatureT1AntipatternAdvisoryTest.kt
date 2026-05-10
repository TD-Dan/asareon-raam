package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.AppState
import asareon.raam.core.Identity
import asareon.raam.core.PermissionGrant
import asareon.raam.core.PermissionLevel
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 tests for the agent-action antipattern advisory.
 *
 * When an agent dispatches `session.POST` targeting its own primary (output)
 * session, AgentRuntimeFeature posts a non-blocking SYSTEM SENTINEL message
 * into that same session so the agent sees the advisory in its next turn.
 * The flagged action still runs — we surface the misuse rather than masking it.
 */
class AgentRuntimeFeatureT1AntipatternAdvisoryTest {

    private val platform = FakePlatformDependencies("test")

    private val agentUuid = "agent-uuid-1"
    private val agentHandle = "agent.test-agent"
    private val sessionUuid = "session-uuid-1"
    private val sessionHandle = "session.testing-session"
    private val sessionLocalHandle = "testing-session"
    private val sessionName = "Testing session"

    private val agent = testAgent(
        id = agentUuid,
        name = "Test Agent",
        cognitiveStrategyId = "agent.strategy.minimal",
        subscribedSessionIds = listOf(sessionUuid),
        privateSessionId = sessionUuid,
        modelProvider = "test",
        modelName = "test-model"
    )

    private fun createTestSetup(): Pair<AgentRuntimeFeature, FakeStore> {
        val feature = AgentRuntimeFeature(platform, CoroutineScope(Job()))
        val store = FakeStore(
            AppState(
                actionDescriptors = ActionRegistry.byActionName,
                identityRegistry = mapOf(
                    agentHandle to Identity(
                        uuid = agentUuid,
                        localHandle = "test-agent",
                        handle = agentHandle,
                        name = "Test Agent",
                        parentHandle = "agent",
                        permissions = mapOf(
                            "session:write" to PermissionGrant(PermissionLevel.YES),
                            "session:read" to PermissionGrant(PermissionLevel.YES),
                        ),
                    ),
                    sessionHandle to Identity(
                        uuid = sessionUuid,
                        localHandle = sessionLocalHandle,
                        handle = sessionHandle,
                        name = sessionName,
                        parentHandle = "session",
                    ),
                )
            ),
            platform
        )
        return feature to store
    }

    private fun actionCreatedFor(targetSessionRef: String, message: String = "Hi"): Action {
        return Action(
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED,
            buildJsonObject {
                put("correlationId", "corr-1")
                put("originatorId", agentHandle)
                put("originatorName", "Test Agent")
                put("sessionId", sessionUuid)
                put("actionName", ActionRegistry.Names.SESSION_POST)
                put("actionPayload", buildJsonObject {
                    put("session", targetSessionRef)
                    put("message", message)
                    // senderId is intentionally omitted — the agent feature auto-fills it
                    // from the originator handle. Tests that need to verify the autofill
                    // hardening should add it explicitly to confirm it gets overwritten.
                })
            }
        )
    }

    private fun runDispatch(action: Action): FakeStore {
        val (feature, store) = createTestSetup()
        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agentUuid) to agent),
        )
        feature.handleSideEffects(action, store, null, agentState)
        return store
    }

    /** Finds the antipattern advisory specifically (not the sanitization sentinel). */
    private fun findSentinelPost(store: FakeStore): JsonObject? {
        return store.dispatchedActions
            .filter { it.name == ActionRegistry.Names.SESSION_POST }
            .map { it.payload }
            .firstOrNull { p ->
                p?.get("senderId")?.jsonPrimitive?.contentOrNull == "system" &&
                    (p["message"]?.jsonPrimitive?.contentOrNull ?: "").contains("Unnecessary session.POST")
            }
    }

    // =========================================================================
    // Positive cases — advisory fires
    // =========================================================================

    @Test
    fun `session-POST targeting primary by full handle triggers advisory`() {
        val store = runDispatch(actionCreatedFor(sessionHandle))
        val sentinel = findSentinelPost(store)
        assertNotNull(sentinel, "Advisory must be posted when targeting primary by full handle.")
        val msg = sentinel["message"]?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(msg.contains("Unnecessary session.POST", ignoreCase = true),
            "Sentinel message should call out the antipattern; got: $msg")
        assertEquals(sessionUuid, sentinel["session"]?.jsonPrimitive?.contentOrNull,
            "Advisory must be posted into the agent's primary session.")
    }

    @Test
    fun `session-POST targeting primary by localHandle triggers advisory`() {
        val store = runDispatch(actionCreatedFor(sessionLocalHandle))
        assertNotNull(findSentinelPost(store), "Advisory must fire on localHandle match.")
    }

    @Test
    fun `session-POST targeting primary by UUID triggers advisory`() {
        val store = runDispatch(actionCreatedFor(sessionUuid))
        assertNotNull(findSentinelPost(store), "Advisory must fire on UUID match.")
    }

    @Test
    fun `session-POST targeting primary by display name triggers advisory`() {
        val store = runDispatch(actionCreatedFor(sessionName))
        assertNotNull(findSentinelPost(store), "Advisory must fire on display-name match.")
    }

    @Test
    fun `advisory does not block the actual session-POST from dispatching`() {
        val store = runDispatch(actionCreatedFor(sessionHandle, "Hello, Daniel!"))

        // Two SESSION_POSTs are expected: the advisory + the actual reply.
        val agentPost = store.dispatchedActions
            .filter { it.name == ActionRegistry.Names.SESSION_POST }
            .map { it.payload }
            .firstOrNull { p ->
                p?.get("senderId")?.jsonPrimitive?.contentOrNull == agentHandle
            }
        assertNotNull(agentPost,
            "The agent's session.POST must still be dispatched after the advisory — we surface the misuse, we don't hide it.")
        assertEquals("Hello, Daniel!", agentPost["message"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // Negative cases — advisory does NOT fire
    // =========================================================================

    @Test
    fun `session-POST to a different subscribed session does not trigger advisory`() {
        val otherSessionUuid = "session-uuid-other"
        val otherSessionHandle = "session.other"
        // Build a store that has BOTH sessions registered, agent subscribed to both,
        // primary still pointing at sessionUuid.
        val feature = AgentRuntimeFeature(platform, CoroutineScope(Job()))
        val store = FakeStore(
            AppState(
                actionDescriptors = ActionRegistry.byActionName,
                identityRegistry = mapOf(
                    agentHandle to Identity(
                        uuid = agentUuid,
                        localHandle = "test-agent",
                        handle = agentHandle,
                        name = "Test Agent",
                        parentHandle = "agent",
                        permissions = mapOf(
                            "session:write" to PermissionGrant(PermissionLevel.YES),
                            "session:read" to PermissionGrant(PermissionLevel.YES),
                        ),
                    ),
                    sessionHandle to Identity(
                        uuid = sessionUuid, localHandle = sessionLocalHandle,
                        handle = sessionHandle, name = sessionName, parentHandle = "session",
                    ),
                    otherSessionHandle to Identity(
                        uuid = otherSessionUuid, localHandle = "other",
                        handle = otherSessionHandle, name = "Other", parentHandle = "session",
                    ),
                )
            ),
            platform
        )
        val multiSessionAgent = agent.copy(
            subscribedSessionIds = listOf(uid(sessionUuid), uid(otherSessionUuid)),
        )
        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agentUuid) to multiSessionAgent),
        )
        feature.handleSideEffects(actionCreatedFor(otherSessionHandle), store, null, agentState)

        assertNull(findSentinelPost(store),
            "Posting to a non-primary session must NOT trigger the advisory.")
    }

    @Test
    fun `non-session-POST actions do not trigger advisory`() {
        // filesystem.LIST has no antipattern logic — no advisory.
        val (feature, store) = createTestSetup()
        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agentUuid) to agent),
        )
        val action = Action(
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED,
            buildJsonObject {
                put("correlationId", "corr-1")
                put("originatorId", agentHandle)
                put("originatorName", "Test Agent")
                put("sessionId", sessionUuid)
                put("actionName", ActionRegistry.Names.FILESYSTEM_LIST)
                put("actionPayload", buildJsonObject { put("path", "") })
            }
        )
        feature.handleSideEffects(action, store, null, agentState)

        assertNull(findSentinelPost(store),
            "Antipattern detector should be a no-op for non-session.POST actions.")
    }
}
