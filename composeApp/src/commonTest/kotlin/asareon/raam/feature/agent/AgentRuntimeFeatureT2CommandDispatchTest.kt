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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Side-Effect Tests for AgentRuntimeFeature — Command Dispatch Ordering.
 *
 * These tests call `handleSideEffects` directly with controlled state and a
 * FakeStore, avoiding coroutine infrastructure entirely. AgentRuntimeFeature's
 * `init()` is never called, so the infinite auto-trigger polling loop does
 * not start.
 *
 * ## Regression Target
 * When AgentRuntimeFeature handles `COMMANDBOT_ACTION_CREATED`, it dispatches
 * the domain action (e.g., `filesystem.LIST`) and `AGENT_REGISTER_PENDING_COMMAND`.
 * The domain action's targeted response (e.g., `FILESYSTEM_RETURN_LIST`) must
 * be routed via `routeCommandResponseToSession`, which requires the pending
 * command entry to exist in state. If the domain action is dispatched first,
 * and the Store processes it before `REGISTER_PENDING_COMMAND`, the response
 * arrives to an empty `pendingCommands` map and gets misrouted to the cognitive
 * pipeline instead of the session.
 *
 * ## Invariant Under Test
 * `AGENT_REGISTER_PENDING_COMMAND` must be dispatched **before** the domain
 * action. This test verifies the ordering of `deferredDispatch` calls captured
 * by FakeStore.
 *
 * ## Observable Symptom (Production)
 * Agent types `raam_filesystem.LIST` → CommandBot posts status report
 * (`OK ✓ filesystem.LIST — Listed 3 items`) but the actual listing data never
 * appears in the session.
 */
class AgentRuntimeFeatureT1CommandDispatchTest {

    private val platform = FakePlatformDependencies("test")

    // Use testAgent() factory for a predictable identity.
    // handle = "agent.test-agent", uuid = "agent-1"
    private val agent = testAgent(
        id = "agent-1",
        name = "Test Agent",
        cognitiveStrategyId = "agent.strategy.minimal",
        subscribedSessionIds = listOf("test-session"),
        modelProvider = "test",
        modelName = "test-model"
    )

    private val agentHandle = "agent.test-agent"

    /**
     * Creates a feature + FakeStore pair for T1 side-effect testing.
     *
     * The CoroutineScope passed to AgentRuntimeFeature is a dummy — `init()` is
     * never called in T1 tests, so the auto-trigger loop doesn't start.
     *
     * The FakeStore is seeded with:
     * - ActionRegistry descriptors (for sandbox rule lookups)
     * - The agent identity in the registry (for originator resolution and
     *   permission checks in the ACTION_CREATED handler)
     */
    private fun createTestSetup(): Triple<AgentRuntimeFeature, FakeStore, FakePlatformDependencies> {
        val feature = AgentRuntimeFeature(platform, CoroutineScope(Job()))
        val store = FakeStore(
            AppState(
                actionDescriptors = ActionRegistry.byActionName,
                identityRegistry = mapOf(
                    agentHandle to Identity(
                        uuid = agent.identity.uuid,
                        localHandle = agent.identity.localHandle,
                        handle = agentHandle,
                        name = agent.identity.name,
                        parentHandle = "agent",
                        permissions = mapOf(
                            "filesystem:workspace" to PermissionGrant(PermissionLevel.YES),
                            "session:write" to PermissionGrant(PermissionLevel.YES),
                            "session:read" to PermissionGrant(PermissionLevel.YES)
                        )
                    )
                )
            ),
            platform
        )
        return Triple(feature, store, platform)
    }

    /**
     * Builds a COMMANDBOT_ACTION_CREATED action payload simulating an agent
     * command that was validated and published by CommandBot.
     */
    private fun actionCreatedPayload(
        actionName: String,
        correlationId: String = "corr-1",
        originatorId: String = agentHandle,
        sessionId: String = "test-session",
        actionPayload: kotlinx.serialization.json.JsonObject = buildJsonObject {}
    ): Action {
        return Action(
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED,
            buildJsonObject {
                put("correlationId", correlationId)
                put("originatorId", originatorId)
                put("originatorName", "Test Agent")
                put("sessionId", sessionId)
                put("actionName", actionName)
                put("actionPayload", actionPayload)
            }
        )
    }

    // ========================================================================
    // Dispatch Ordering Invariant
    // ========================================================================

    /**
     * AGENT_REGISTER_PENDING_COMMAND must be dispatched BEFORE the domain action.
     *
     * This is the root cause of the production bug where filesystem.LIST data
     * was not delivered to the session: the domain action was dispatched first,
     * its response arrived before the pending command was registered, and
     * `handleTargetedResponse` misrouted the data to the cognitive pipeline.
     */
    @Test
    fun `ACTION_CREATED dispatches REGISTER_PENDING_COMMAND before the domain action`() {
        val (feature, store, _) = createTestSetup()

        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agent.identity.uuid!!) to agent)
        )

        val action = actionCreatedPayload(
            actionName = "filesystem.LIST",
            actionPayload = buildJsonObject { put("recursive", true) }
        )

        // ACT: Simulate the ACTION_CREATED side-effect handler.
        feature.handleSideEffects(action, store, null, agentState)

        // ASSERT: Check ordering of dispatched actions.
        val registerIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND
        }
        val domainActionIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.FILESYSTEM_LIST
        }

        assertTrue(registerIndex >= 0,
            "AGENT_REGISTER_PENDING_COMMAND should be dispatched. " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")
        assertTrue(domainActionIndex >= 0,
            "filesystem.LIST domain action should be dispatched. " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")

        // THE CRITICAL INVARIANT
        assertTrue(registerIndex < domainActionIndex,
            "AGENT_REGISTER_PENDING_COMMAND (index=$registerIndex) must be dispatched " +
                    "BEFORE filesystem.LIST (index=$domainActionIndex). " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")
    }

    /**
     * Same invariant for filesystem.READ — the bug affects all data-returning
     * commands, not just LIST.
     */
    @Test
    fun `ACTION_CREATED dispatches REGISTER_PENDING_COMMAND before filesystem READ`() {
        val (feature, store, _) = createTestSetup()

        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agent.identity.uuid!!) to agent)
        )

        val action = actionCreatedPayload(
            actionName = "filesystem.READ",
            actionPayload = buildJsonObject { put("path", "notes.txt") }
        )

        feature.handleSideEffects(action, store, null, agentState)

        val registerIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND
        }
        val domainActionIndex = store.dispatchedActions.indexOfFirst {
            it.name == ActionRegistry.Names.FILESYSTEM_READ
        }

        assertTrue(registerIndex >= 0,
            "AGENT_REGISTER_PENDING_COMMAND should be dispatched. " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")
        assertTrue(domainActionIndex >= 0,
            "filesystem.READ domain action should be dispatched. " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")

        assertTrue(registerIndex < domainActionIndex,
            "AGENT_REGISTER_PENDING_COMMAND (index=$registerIndex) must be dispatched " +
                    "BEFORE filesystem.READ (index=$domainActionIndex). " +
                    "Dispatched: ${store.dispatchedActions.map { it.name }}")
    }

    // ========================================================================
    // Pending Command Registration Content
    // ========================================================================

    /**
     * Verifies the REGISTER_PENDING_COMMAND payload carries the correct
     * correlationId, agentId, sessionId, and actionName — all required for
     * `routeCommandResponseToSession` to match and route the response.
     */
    @Test
    fun `ACTION_CREATED registers pending command with correct correlation fields`() {
        val (feature, store, _) = createTestSetup()

        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agent.identity.uuid!!) to agent)
        )

        val action = actionCreatedPayload(
            actionName = "filesystem.LIST",
            correlationId = "corr-42",
            sessionId = "session-xyz"
        )

        feature.handleSideEffects(action, store, null, agentState)

        val registerAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND
        }
        assertNotNull(registerAction,
            "AGENT_REGISTER_PENDING_COMMAND should be dispatched.")

        val payload = registerAction.payload!!
        assertTrue(payload["correlationId"]?.jsonPrimitive?.contentOrNull == "corr-42",
            "correlationId must match ACTION_CREATED's correlationId.")
        assertTrue(payload["agentId"]?.jsonPrimitive?.contentOrNull == "agent-1",
            "agentId must be the agent's UUID.")
        assertTrue(payload["sessionId"]?.jsonPrimitive?.contentOrNull == "session-xyz",
            "sessionId must match ACTION_CREATED's sessionId.")
        assertTrue(payload["actionName"]?.jsonPrimitive?.contentOrNull == "filesystem.LIST",
            "actionName must match the dispatched domain action.")
    }

    // ========================================================================
    // Sandbox Rewrite + CorrelationId Injection
    // ========================================================================

    /**
     * Verifies that the domain action payload receives sandbox-rewritten paths
     * AND the injected correlationId — both required for FileSystemFeature to
     * route the response back to the correct agent with a matchable correlationId.
     */
    @Test
    fun `ACTION_CREATED injects correlationId and sandbox path into domain action payload`() {
        val (feature, store, _) = createTestSetup()

        val agentState = AgentRuntimeState(
            agents = mapOf(uid(agent.identity.uuid!!) to agent)
        )

        val action = actionCreatedPayload(
            actionName = "filesystem.LIST",
            correlationId = "corr-99",
            actionPayload = buildJsonObject { put("recursive", true) }
        )

        feature.handleSideEffects(action, store, null, agentState)

        val domainAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_LIST
        }
        assertNotNull(domainAction, "filesystem.LIST should be dispatched.")

        val payload = domainAction.payload!!

        // correlationId should be injected
        assertTrue(payload["correlationId"]?.jsonPrimitive?.contentOrNull == "corr-99",
            "correlationId must be injected into the domain action payload for response matching. " +
                    "Payload: $payload")

        // Sandbox path should be rewritten to {agentId}/workspace
        val path = payload["path"]?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(path.contains("agent-1") && path.contains("workspace"),
            "Path should be sandbox-rewritten to include agent UUID and workspace. " +
                    "Got: '$path'")
    }
}