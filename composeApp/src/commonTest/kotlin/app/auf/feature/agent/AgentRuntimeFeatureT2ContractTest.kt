package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Contract Tests for AgentRuntimeFeature's domain actions.
 *
 * These tests verify cross-cutting obligations that every command-dispatchable
 * domain action must satisfy:
 *   1. Publish a success ACTION_RESULT on the happy path.
 *   2. Publish a failure ACTION_RESULT when an error occurs.
 *   3. Log at ERROR level when an error occurs.
 *
 * Action-specific behaviour (NVRAM merge semantics, strategy validation,
 * cognitive pipeline flow, avatar lifecycle, etc.) is tested in the
 * corresponding T2CoreTest files.
 *
 * ## Design Note: ACTION_RESULT is ALWAYS published
 *
 * ACTION_RESULT is not just for CommandBot correlation — monitoring features,
 * logging plugins, and usage trackers also observe it. Every command-dispatchable
 * action must publish ACTION_RESULT regardless of whether a correlationId is
 * present in the payload.
 */
class AgentRuntimeFeatureT2ContractTest {

    private val featureHandle = "agent"
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Valid UUID hex format — CoreFeature validates these.
    private val AGENT_UUID = "a0000001-0000-0000-0000-000000000001"
    private val SESSION_UUID = "b0000001-0000-0000-0000-000000000001"
    private val SESSION_2_UUID = "b0000002-0000-0000-0000-000000000002"
    private val RESOURCE_UUID = "c0000001-0000-0000-0000-000000000001"

    private val session = testSession(SESSION_UUID, "Chat")
    private val session2 = testSession(SESSION_2_UUID, "Chat Two")

    /** A vanilla agent with all infrastructure satisfied. */
    private fun happyAgent() = testAgent(
        id = AGENT_UUID,
        name = "Test Agent",
        modelProvider = "test",
        modelName = "test-model",
        subscribedSessionIds = listOf(SESSION_UUID),
        cognitiveStrategyId = "vanilla_v1"
    )

    /** A user-defined resource for resource CRUD tests. */
    private fun userResource() = AgentResource(
        id = RESOURCE_UUID,
        type = AgentResourceType.SYSTEM_INSTRUCTION,
        name = "Test Resource",
        content = "test content",
        isBuiltIn = false,
        path = "resources/$RESOURCE_UUID.json"
    )

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    /**
     * A happy-path test case.
     * [buildState] creates the initial AgentRuntimeState for this case.
     * [setup] runs after the harness is built (register identities, etc.).
     */
    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildState: () -> AgentRuntimeState = { AgentRuntimeState() },
        val setup: (TestHarness) -> Unit = {}
    )

    /**
     * A failure-path test case.
     * The test environment is configured so the action fails (missing agent, etc.).
     * [buildState] creates the initial AgentRuntimeState (often empty or broken).
     * [buildPlatform] returns the FakePlatformDependencies (possibly subclassed).
     * [setup] runs after the harness is built.
     */
    private data class FailureCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildState: () -> AgentRuntimeState = { AgentRuntimeState() },
        val buildPlatform: () -> FakePlatformDependencies = { FakePlatformDependencies("test") },
        val setup: (TestHarness) -> Unit = {}
    )

    // ====================================================================
    // Harness builder
    // ====================================================================

    /**
     * Builds a test harness with the agent feature, session feature, and
     * filesystem feature. Ensures lifecycle is RUNNING and built-in resources
     * are loaded.
     */
    private fun buildHarness(
        platform: FakePlatformDependencies,
        agentState: AgentRuntimeState
    ): TestHarness {
        val agentFeature = AgentRuntimeFeature(platform, scope)
        val sessionFeature = SessionFeature(platform, scope)
        val filesystemFeature = FileSystemFeature(platform)

        return TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withFeature(filesystemFeature)
            .withInitialState("agent", agentState)
            .withInitialState("session", SessionState(
                sessions = mapOf(SESSION_UUID to session, SESSION_2_UUID to session2)
            ))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
    }

    /**
     * Registers agent and session identities so that resolveAgentId and
     * session handle resolution work in side effects.
     */
    private fun registerIdentities(harness: TestHarness) {
        harness.registerAgentIdentity(happyAgent())
        harness.registerSessionIdentity(session)
        harness.registerSessionIdentity(session2)
    }

    // -- Happy cases -------------------------------------------------------

    private val happyCases: List<HappyCase> by lazy {
        listOf(
            HappyCase(
                label = "CREATE",
                actionName = ActionRegistry.Names.AGENT_CREATE,
                originator = "core",
                payload = buildJsonObject {
                    put("name", "New Agent")
                },
                buildState = {
                    AgentRuntimeState(
                        resources = testBuiltInResources(),
                        subscribableSessionNames = mapOf(IdentityUUID(SESSION_UUID) to "Chat")
                    )
                }
            ),
            HappyCase(
                label = "CLONE",
                actionName = ActionRegistry.Names.AGENT_CLONE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources(),
                        subscribableSessionNames = mapOf(IdentityUUID(SESSION_UUID) to "Chat")
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "UPDATE_CONFIG",
                actionName = ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("name", "Renamed Agent")
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "DELETE",
                actionName = ActionRegistry.Names.AGENT_DELETE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "TOGGLE_AUTOMATIC_MODE",
                actionName = ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "TOGGLE_ACTIVE",
                actionName = ActionRegistry.Names.AGENT_TOGGLE_ACTIVE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "ADD_SESSION_SUBSCRIPTION",
                actionName = ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("sessionId", SESSION_2_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources(),
                        subscribableSessionNames = mapOf(
                            IdentityUUID(SESSION_UUID) to "Chat",
                            IdentityUUID(SESSION_2_UUID) to "Chat Two"
                        )
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "REMOVE_SESSION_SUBSCRIPTION",
                actionName = ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("sessionId", SESSION_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "UPDATE_NVRAM",
                actionName = ActionRegistry.Names.AGENT_UPDATE_NVRAM,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("updates", buildJsonObject { put("mood", "focused") })
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "INITIATE_TURN",
                actionName = ActionRegistry.Names.AGENT_INITIATE_TURN,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "CANCEL_TURN",
                actionName = ActionRegistry.Names.AGENT_CANCEL_TURN,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "DISCARD_MANAGED_CONTEXT",
                actionName = ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "CREATE_RESOURCE",
                actionName = ActionRegistry.Names.AGENT_CREATE_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("name", "New Resource")
                    put("type", "SYSTEM_INSTRUCTION")
                },
                buildState = {
                    AgentRuntimeState(resources = testBuiltInResources())
                }
            ),
            HappyCase(
                label = "SAVE_RESOURCE",
                actionName = ActionRegistry.Names.AGENT_SAVE_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", RESOURCE_UUID)
                    put("content", "updated content")
                },
                buildState = {
                    AgentRuntimeState(
                        resources = testBuiltInResources() + userResource()
                    )
                }
            ),
            HappyCase(
                label = "RENAME_RESOURCE",
                actionName = ActionRegistry.Names.AGENT_RENAME_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", RESOURCE_UUID)
                    put("newName", "Renamed Resource")
                },
                buildState = {
                    AgentRuntimeState(
                        resources = testBuiltInResources() + userResource()
                    )
                }
            ),
            HappyCase(
                label = "DELETE_RESOURCE",
                actionName = ActionRegistry.Names.AGENT_DELETE_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", RESOURCE_UUID)
                },
                buildState = {
                    AgentRuntimeState(
                        resources = testBuiltInResources() + userResource()
                    )
                }
            ),
            HappyCase(
                label = "CONTEXT_UNCOLLAPSE",
                actionName = ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("partitionKey", "AVAILABLE_ACTIONS")
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            HappyCase(
                label = "CONTEXT_COLLAPSE",
                actionName = ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("partitionKey", "AVAILABLE_ACTIONS")
                },
                buildState = {
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            )
        )
    }

    // -- Failure cases -----------------------------------------------------

    private val failureCases: List<FailureCase> by lazy {
        listOf(
            FailureCase(
                label = "CLONE (agent not found)",
                actionName = ActionRegistry.Names.AGENT_CLONE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                // Empty state — agent does not exist
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "UPDATE_CONFIG (agent not found)",
                actionName = ActionRegistry.Names.AGENT_UPDATE_CONFIG,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("name", "Should Fail")
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "DELETE (agent not found)",
                actionName = ActionRegistry.Names.AGENT_DELETE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", "nonexistent-0000-0000-0000-000000000099")
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) }
                // No setup — the agent ID won't resolve in the identity registry
            ),
            FailureCase(
                label = "TOGGLE_AUTOMATIC_MODE (agent not found)",
                actionName = ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "TOGGLE_ACTIVE (agent not found)",
                actionName = ActionRegistry.Names.AGENT_TOGGLE_ACTIVE,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "ADD_SESSION_SUBSCRIPTION (agent not found)",
                actionName = ActionRegistry.Names.AGENT_ADD_SESSION_SUBSCRIPTION,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("sessionId", SESSION_2_UUID)
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "REMOVE_SESSION_SUBSCRIPTION (agent not found)",
                actionName = ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("sessionId", SESSION_UUID)
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "UPDATE_NVRAM (agent not found)",
                actionName = ActionRegistry.Names.AGENT_UPDATE_NVRAM,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                    put("updates", buildJsonObject { put("mood", "broken") })
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "EXECUTE_MANAGED_TURN (no managed context)",
                actionName = ActionRegistry.Names.AGENT_EXECUTE_MANAGED_TURN,
                originator = "core",
                payload = buildJsonObject {
                    put("agentId", AGENT_UUID)
                },
                buildState = {
                    // Agent exists but has no managed context staged
                    AgentRuntimeState(
                        agents = mapOf(uid(AGENT_UUID) to happyAgent()),
                        resources = testBuiltInResources()
                    )
                },
                setup = ::registerIdentities
            ),
            FailureCase(
                label = "SAVE_RESOURCE (resource not found)",
                actionName = ActionRegistry.Names.AGENT_SAVE_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", "nonexistent-resource-id")
                    put("content", "should fail")
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) }
            ),
            FailureCase(
                label = "RENAME_RESOURCE (resource not found)",
                actionName = ActionRegistry.Names.AGENT_RENAME_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", "nonexistent-resource-id")
                    put("newName", "should fail")
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) }
            ),
            FailureCase(
                label = "DELETE_RESOURCE (resource not found)",
                actionName = ActionRegistry.Names.AGENT_DELETE_RESOURCE,
                originator = "core",
                payload = buildJsonObject {
                    put("resourceId", "nonexistent-resource-id")
                },
                buildState = { AgentRuntimeState(resources = testBuiltInResources()) }
            )
        )
    }

    // ====================================================================
    // Failure-collecting helper
    //
    // Runs every case in a batch, collects all failures, and reports them
    // together at the end. This ensures a single test run surfaces ALL
    // broken contracts, not just the first one encountered.
    // ====================================================================

    /**
     * Runs [block] for each item in the list, catching assertion failures.
     * After all items have been evaluated, throws a single [AssertionError]
     * listing every failure — or passes silently if all succeeded.
     */
    private fun <T> assertAllCases(cases: List<T>, block: (T) -> Unit) {
        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                block(case)
            } catch (e: Throwable) {
                failures.add(e.message ?: e.toString())
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${cases.size} cases failed:\n\n" +
                        failures.joinToString("\n\n") { "  • $it" }
            )
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on success
    // ====================================================================

    @Test
    fun `all command-dispatchable actions publish success ACTION_RESULT on happy path`() {
        assertAllCases(happyCases) { case ->
            val platform = FakePlatformDependencies("test")
            val harness = buildHarness(platform, case.buildState())

            case.setup(harness)
            // Clear actions from setup phase so we only see the action under test
            harness.store.processedActions.clear()

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish AGENT_ACTION_RESULT on success.")
            assertEquals(true, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=true.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on failure
    // ====================================================================

    @Test
    fun `all command-dispatchable actions publish failure ACTION_RESULT on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val harness = buildHarness(platform, case.buildState())

            case.setup(harness)
            harness.store.processedActions.clear()

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish AGENT_ACTION_RESULT on failure.")
            assertEquals(false, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=false.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
            assertNotNull(result.payload?.get("error")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT should contain a non-null error field.")
        }
    }

    // ====================================================================
    // Contract: ERROR-level log on failure
    // ====================================================================

    @Test
    fun `all command-dispatchable actions log at ERROR or WARN level on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val harness = buildHarness(platform, case.buildState())

            case.setup(harness)
            platform.capturedLogs.clear()

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            assertTrue(
                platform.capturedLogs.any { it.level == LogLevel.ERROR || it.level == LogLevel.WARN },
                "[${case.label}] should log at ERROR or WARN level on failure. " +
                        "Captured logs: ${platform.capturedLogs.map { "[${it.level}] ${it.message}" }}"
            )
        }
    }
}