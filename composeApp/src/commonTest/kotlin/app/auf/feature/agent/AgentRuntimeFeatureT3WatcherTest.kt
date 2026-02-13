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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 3 Peer Test for Agent Automatic Triggering Logic.
 *
 * Uses the "Manual Crank" pattern: We strictly control time and the heartbeat signal
 * to verify the logic deterministically, avoiding race conditions between
 * virtual time advancement and the Store's processing queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeFeatureT3WatcherTest {

    private lateinit var testScope: TestScope
    private lateinit var platform: FakePlatformDependencies
    private lateinit var harness: TestHarness

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

    /**
     * The Manual Crank:
     * 1. Advance the system clock (so the logic sees that time has passed).
     * 2. Dispatch the internal heartbeat action (so the logic actually runs).
     * 3. Run pending coroutines (so the Store processes the action).
     */
    private fun dispatchHeartbeat(timePassedMillis: Long) {
        platform.currentTime += timePassedMillis
        // Fix: Use "agent" as originator to pass Store security checks for agent.internal.* actions
        harness.store.dispatch("agent", Action(ActionRegistry.Names.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS))
        testScope.runCurrent()
    }

    @Test
    fun `automatic agent should trigger after autoWaitTime debounce period`() = runTest {
        val agent = AgentInstance("auto-agent-1", "Debouncer", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, autoWaitTimeSeconds = 5)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT: A user message arrives.
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
            }))
            testScope.runCurrent()

            val stateAfterPost = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertEquals(AgentStatus.WAITING, stateAfterPost.agentStatuses[agent.id]?.status)

            // ACT: Advance time by 4 seconds (Undershoot). Should NOT trigger.
            dispatchHeartbeat(4000)
            assertNull(harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Should not trigger at 4s")

            // ACT: Advance time by 2 more seconds (Total 6s). Should trigger.
            dispatchHeartbeat(2000)

            // ASSERT
            val triggerAction = harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }
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
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "First part")
            }))
            testScope.runCurrent()

            // ACT 2: Advance time by 3s.
            dispatchHeartbeat(3000)
            assertNull(harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Agent should not have triggered yet.")

            // ACT 3: A second message arrives, resetting the timer.
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Second part")
            }))
            testScope.runCurrent()

            // ACT 4: Advance time by 3s again. (Total since first msg: 6s, but only 3s since second).
            dispatchHeartbeat(3000)
            assertNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Agent should not have triggered as timer should have reset.")

            // ACT 5: Advance past the reset timer's deadline.
            dispatchHeartbeat(2001)

            // ASSERT
            assertNotNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Agent should have triggered after the second message's debounce period.")
        }
    }

    @Test
    fun `automatic agent should trigger after autoMaxWaitTime even with continuous messages`() = runTest {
        val agent = AgentInstance("auto-agent-1", "Timeout", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, autoWaitTimeSeconds = 10, autoMaxWaitTimeSeconds = 20)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT 1: First message starts the max-wait timer.
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Message 1")
            }))
            testScope.runCurrent() // T=0

            // ACT 2: Send messages every 8 seconds, preventing the 10s debounce from ever completing.
            dispatchHeartbeat(8000) // T=8s
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject { put("session", "sid-A"); put("senderId", "user"); put("message", "Message 2") }))
            testScope.runCurrent()
            assertNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Should not trigger at T=8s.")

            dispatchHeartbeat(8000) // T=16s
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject { put("session", "sid-A"); put("senderId", "user"); put("message", "Message 3") }))
            testScope.runCurrent()
            assertNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Should not trigger at T=16s.")

            // ACT 3: Advance past the 20s max-wait time.
            dispatchHeartbeat(5000) // T=21s

            // ASSERT
            assertNotNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Agent must trigger after max wait time is exceeded.")
        }
    }

    @Test
    fun `manual agent in WAITING state should NOT trigger automatically`() = runTest {
        val agent = AgentInstance("manual-agent-1", "Manual", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = false, autoWaitTimeSeconds = 3)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // ACT: A user message arrives, putting agent in WAITING.
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Hello")
            }))
            testScope.runCurrent()

            // ACT: Advance time well past any timers.
            dispatchHeartbeat(5000)

            // ASSERT
            assertNull(harness.processedActions.findLast { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }, "Manual agent should never trigger automatically.")
        }
    }

    @Test
    fun `automatic agent should NOT trigger if isAgentActive is false (Paused)`() = runTest {
        val agent = AgentInstance(
            "paused-agent", "Paused", "", "", "",
            subscribedSessionIds = listOf("sid-A"),
            automaticMode = true,
            isAgentActive = false, // Paused
            autoWaitTimeSeconds = 3
        )
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // Simulate message arrival manually to force WAITING state
            harness.store.dispatch("ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", "sid-A"); put("senderId", "user"); put("message", "Wake up!")
            }))
            testScope.runCurrent()

            // Advance time past debounce
            dispatchHeartbeat(5000)

            // ASSERT
            assertNull(
                harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN },
                "Paused agent should NOT trigger even if automatic and waiting."
            )
        }
    }

    @Test
    fun `automatic agent should NOT trigger if timestamps are missing (Safe Startup)`() = runTest {
        // Agent starts IDLE/WAITING but has no timestamps set (simulate fresh load)
        val agent = AgentInstance("fresh-agent", "Fresh", "", "", "", subscribedSessionIds = listOf("sid-A"), automaticMode = true, isAgentActive = true)
        setupHarness(agent)

        harness.runAndLogOnFailure {
            // Advance time without sending any message first
            dispatchHeartbeat(10000)

            assertNull(harness.processedActions.find { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN })
        }
    }
}