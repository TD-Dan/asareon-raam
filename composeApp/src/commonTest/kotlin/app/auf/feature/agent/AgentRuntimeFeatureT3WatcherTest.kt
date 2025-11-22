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
class AgentRuntimeFeatureT3WatcherTest {

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

        harness.runAndLogOnFailure {
            // ACT: A user message arrives.
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
            }))
            runCurrent()

            val stateAfterPost = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            // ASSERT: Check the new status map
            assertEquals(AgentStatus.WAITING, stateAfterPost.agentStatuses[agent.id]?.status)
            assertNull(harness.processedActions.find { it.name == ActionNames.AGENT_INITIATE_TURN })

            // ACT: Advance time just past the debounce period
            advanceTimeAndRun(5001)

            // ASSERT
            val triggerAction = harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }
            assertNotNull(triggerAction, "Agent should have triggered its turn after the debounce period.")
            assertEquals(agent.id, triggerAction.payload?.get("agentId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `debounce timer should reset when a new message arrives`() = runTest {
        val agent = AgentInstance("auto-agent-1", "Debouncer", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, autoWaitTimeSeconds = 5)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT 1: First user message arrives.
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "First part")
            }))
            runCurrent()

            // ACT 2: Advance time, but not enough to trigger.
            advanceTimeAndRun(3000)
            assertNull(harness.processedActions.find { it.name == ActionNames.AGENT_INITIATE_TURN }, "Agent should not have triggered yet.")

            // ACT 3: A second message arrives, resetting the timer (handled by handleMessagePosted logic).
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Second part")
            }))
            runCurrent()

            // ACT 4: Advance time again. If timer didn't reset, it would have triggered by now (3000+3000 > 5000).
            advanceTimeAndRun(3000)
            assertNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Agent should not have triggered as timer should have reset.")

            // ACT 5: Advance past the reset timer's deadline.
            advanceTimeAndRun(2001)

            // ASSERT
            assertNotNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Agent should have triggered after the second message's debounce period.")
        }
    }

    @Test
    fun `automatic agent should trigger after autoMaxWaitTime even with continuous messages`() = runTest {
        val agent = AgentInstance("auto-agent-1", "Timeout", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, autoWaitTimeSeconds = 10, autoMaxWaitTimeSeconds = 20)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT 1: First message starts the max-wait timer.
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Message 1")
            }))
            runCurrent() // T=0

            // ACT 2: Send messages every 8 seconds, preventing the 10s debounce from ever completing.
            advanceTimeAndRun(8000) // T=8s
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject { put("session", "sid-A"); put("senderId", "user"); put("message", "Message 2") }))
            runCurrent()
            assertNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Should not trigger at T=8s.")

            advanceTimeAndRun(8000) // T=16s
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject { put("session", "sid-A"); put("senderId", "user"); put("message", "Message 3") }))
            runCurrent()
            assertNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Should not trigger at T=16s.")

            // ACT 3: Advance past the 20s max-wait time.
            advanceTimeAndRun(5000) // T=21s

            // ASSERT
            assertNotNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Agent must trigger after max wait time is exceeded.")
        }
    }

    @Test
    fun `manual agent in WAITING state should NOT trigger automatically`() = runTest {
        val agent = AgentInstance("manual-agent-1", "Manual", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = false, autoWaitTimeSeconds = 3)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT: A user message arrives, putting agent in WAITING.
            harness.store.dispatch("ui", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
            }))
            runCurrent()
            val stateAfterPost = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            // ASSERT: Check the new status map
            assertEquals(AgentStatus.WAITING, stateAfterPost.agentStatuses[agent.id]?.status)

            // ACT: Advance time well past any timers.
            advanceTimeAndRun(5000)

            // ASSERT
            assertNull(harness.processedActions.findLast { it.name == ActionNames.AGENT_INITIATE_TURN }, "Manual agent should never trigger automatically.")
        }
    }
}