package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 3 Peer Test for Agent Automatic Triggering Logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeFeatureT3AutomaticPeerTest {

    private lateinit var testScope: TestScope
    private lateinit var platform: FakePlatformDependencies
    private lateinit var harness: TestHarness

    private fun advanceTimeAndRun(timeMillis: Long) {
        platform.currentTime += timeMillis
        testScope.advanceTimeBy(timeMillis)
        testScope.runCurrent()
    }

    private fun setupHarness(vararg agents: AgentInstance) {
        testScope = TestScope()
        platform = FakePlatformDependencies("test")
        val agentFeature = AgentRuntimeFeature(platform, testScope)
        val sessionFeature = SessionFeature(platform, testScope)

        val sessions = agents.flatMap { it.subscribedSessionIds }.distinct()
            .associateWith { Session(it, "Session $it", emptyList(), 1L) }

        harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = agents.associateBy { it.id }))
            .withInitialState("session", SessionState(sessions = sessions))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(scope = testScope, platform = platform)
    }

    @Test
    fun `automatic agent should trigger after autoWaitTime debounce period`() = runTest {
        val agent = AgentInstance("auto-agent-1", "Debouncer", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, autoWaitTimeSeconds = 5)
        setupHarness(agent)

        // ACT: A user message arrives.
        harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
        }))
        runCurrent()

        val stateAfterPost = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        // ASSERT: Check separate status map
        assertEquals(AgentStatus.WAITING, stateAfterPost.agentStatuses[agent.id]?.status)
        assertNull(harness.processedActions.find { it.name == ActionNames.AGENT_INITIATE_TURN })

        // ACT: Advance time just past the debounce period
        advanceTimeAndRun(5001)

        // ASSERT
        val triggerAction = harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }
        assertNotNull(triggerAction, "Agent should have triggered its turn after the debounce period.")
        assertEquals(agent.id, triggerAction.payload?.get("agentId")?.jsonPrimitive?.content)
    }

    @Test
    fun `manual agent in WAITING state should NOT trigger automatically`() = runTest {
        val agent = AgentInstance("manual-agent-1", "Manual", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = false, autoWaitTimeSeconds = 3)
        setupHarness(agent)

        // ACT: A user message arrives, putting agent in WAITING.
        harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
        }))
        runCurrent()
        val stateAfterPost = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
        assertEquals(AgentStatus.WAITING, stateAfterPost.agentStatuses[agent.id]?.status)

        // ACT: Advance time well past any timers.
        advanceTimeAndRun(5000)

        // ASSERT
        assertNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Manual agent should never trigger automatically.")
    }

    // Note: Other debounce tests follow exact same pattern, updating assertion location.
}