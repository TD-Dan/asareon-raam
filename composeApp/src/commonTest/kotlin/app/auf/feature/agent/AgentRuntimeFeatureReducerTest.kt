package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentRuntimeFeature.
 * These tests focus on the reducer's state integrity and the side-effect logic of public methods
 * in complete isolation, using a high-fidelity test harness.
 */
class AgentRuntimeFeatureReducerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var harness: app.auf.test.TestHarness
    private lateinit var feature: AgentRuntimeFeature
    private lateinit var platform: FakePlatformDependencies

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies("test")
        feature = AgentRuntimeFeature(platform, scope)
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
    }

    // --- Reducer Integrity Tests ---

    @Test
    fun `reducer should log error and not change state on invalid status string in SET_STATUS`() = runTest {
        val agent = AgentInstance("agent-1", "Test", "", "", "")
        val initialState = harness.store.state.value.copy(
            featureStates = harness.store.state.value.featureStates +
                    ("agent" to AgentRuntimeState(agents = mapOf(agent.id to agent)))
        )
        val invalidAction = Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agent.id)
            put("status", "proccessing") // Deliberate typo
        })

        val newState = feature.reducer(initialState, invalidAction)

        assertEquals(initialState, newState, "State should not be modified on a parsing failure.")
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "An error should be logged for the invalid status.")
        assertTrue(log.message.contains("Received invalid agent status string 'proccessing' for agent 'agent-1'"))
    }

    @Test
    fun `reducer should log error and not change state on invalid status in MESSAGE_POSTED`() = runTest {
        val agent = AgentInstance("agent-1", "Test", "", "", "")
        val initialState = harness.store.state.value.copy(
            featureStates = harness.store.state.value.featureStates +
                    ("agent" to AgentRuntimeState(agents = mapOf(agent.id to agent)))
        )
        val invalidPayload = buildJsonObject {
            put("sessionId", "session-1")
            put("entry", buildJsonObject {
                put("senderId", "agent-1")
                put("id", "msg-1")
                put("metadata", buildJsonObject {
                    put("render_as_partial", true)
                    put("agentStatus", "proccessing") // Deliberate typo
                })
            })
        }
        val invalidAction = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, invalidPayload)

        val newState = feature.reducer(initialState, invalidAction)

        assertEquals(initialState, newState, "State should not be modified on a parsing failure.")
        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "An error should be logged for the invalid status.")
        assertTrue(log.message.contains("Received posted message with invalid agent status 'proccessing'"))
    }

    // --- Deadlock & Side-Effect Tests ---

    @Test
    fun `onPrivateData with corrupted ledger should set agent to ERROR and log fatal error`() = runTest {
        val agent = AgentInstance("agent-1", "Test", "", "", "", primarySessionId = "session-1")
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)
        val corruptedPayload = buildJsonObject {
            put("correlationId", "agent-1")
            put("messages", "this-should-be-an-array-not-a-string")
        }

        feature.onPrivateData(corruptedPayload, harness.store)

        val log = platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log, "A fatal error should be logged.")
        assertTrue(log.message.contains("FATAL: Failed to parse session ledger for agent 'agent-1'"))

        val setStatusAction = harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_SET_STATUS }
        assertNotNull(setStatusAction, "Should have dispatched an action to set the status.")
        assertEquals("ERROR", setStatusAction.payload?.get("status")?.jsonPrimitive?.content)

        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "Should have dispatched a POST to create the new ERROR avatar card.")
    }

    @Test
    fun `beginCognitiveCycle should atomically delete old cards before creating a new one`() = runTest {
        val agent = AgentInstance("agent-1", "Test", "", "", "", primarySessionId = "session-1")
        val initialAvatarCards = mapOf("agent-1" to mapOf(AgentStatus.IDLE to "msg-idle-123"))
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = initialAvatarCards))
            .build(platform = platform)

        // ACT: Trigger a state change from IDLE to PROCESSING
        val triggerAction = Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", "agent-1") })
        harness.store.dispatch("ui", triggerAction)

        // ASSERT
        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction, "A delete action for the old card must be dispatched.")
        assertEquals("msg-idle-123", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        val postAction = harness.processedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction, "A post action for the new card must be dispatched.")
        val metadata = postAction.payload?.get("metadata")?.jsonObject
        assertEquals("PROCESSING", metadata?.get("agentStatus")?.jsonPrimitive?.content)

        // Verify the order of operations is correct for atomicity
        val deleteIndex = harness.processedActions.indexOf(deleteAction)
        val setStatusIndex = harness.processedActions.indexOfFirst { it.name == ActionNames.AGENT_INTERNAL_SET_STATUS }
        val postIndex = harness.processedActions.indexOf(postAction)

        assertTrue(deleteIndex < setStatusIndex, "DELETE must happen before SET_STATUS.")
        assertTrue(setStatusIndex < postIndex, "SET_STATUS must happen before POST.")
    }
}