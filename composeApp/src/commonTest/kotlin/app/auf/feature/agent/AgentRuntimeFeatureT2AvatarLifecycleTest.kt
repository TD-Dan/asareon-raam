package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for Agent Avatar Lifecycle.
 *
 * Verifies the Feature's runtime behavior regarding Avatar Card management,
 * specifically targeting:
 * 1. Race conditions during rapid message influx (Double Avatar Bug).
 * 2. Multisession presence (Multisession Limitation).
 * 3. Sovereign presence (Avatar in Public + Private).
 */
class AgentRuntimeFeatureT2AvatarLifecycleTest {

    private val platform = FakePlatformDependencies("test")

    @Test
    fun `Rapid incoming messages should not create duplicate avatar cards`() = runTest {
        // ARRANGE
        // [FIX] Use the TestScope's backgroundScope so that delays are controllable by advanceTimeBy
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val session1 = "session-1"
        val agent = AgentInstance(
            id = "agent-1",
            name = "Test Agent",
            modelProvider = "test",
            modelName = "test",
            subscribedSessionIds = listOf(session1)
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            // Initial state: Agent is IDLE
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            // Simulate rapid-fire messages.
            // Current Logic: Debounced by 50ms.
            val msg1 = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", session1)
                put("entry", buildJsonObject {
                    put("id", "msg-1"); put("senderId", "user"); put("timestamp", 1000L)
                })
            })

            val msg2 = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", session1)
                put("entry", buildJsonObject {
                    put("id", "msg-2"); put("senderId", "user"); put("timestamp", 1001L)
                })
            })

            // Dispatch synchronously
            harness.store.dispatch("session", msg1)
            harness.store.dispatch("session", msg2)

            // [FIX] Advance time to allow the debounce job to run
            this.advanceTimeBy(100)

            // ASSERT
            val avatarPostActions = harness.processedActions.filter {
                it.name == ActionNames.SESSION_POST &&
                        it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.id
            }

            // Expected: 1 (The first trigger is cancelled, only second runs)
            assertEquals(1, avatarPostActions.size, "Race condition detected! Duplicate avatar cards were posted.")
        }
    }

    @Test
    fun `Avatar should appear in all subscribed sessions`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val session1 = "session-1"
        val session2 = "session-2"
        val agent = AgentInstance(
            id = "agent-multi",
            name = "Multi Agent",
            modelProvider = "test",
            modelName = "test",
            subscribedSessionIds = listOf(session1, session2) // Subscribed to TWO sessions
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            // Trigger the agent in Session 1
            harness.store.dispatch("session", Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", session1)
                put("entry", buildJsonObject {
                    put("id", "msg-trigger"); put("senderId", "user"); put("timestamp", 1000L)
                })
            }))

            this.advanceTimeBy(100)

            // ASSERT
            // We expect avatar cards posted to BOTH session-1 and session-2
            val postedSessions = harness.processedActions
                .filter { it.name == ActionNames.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.id }
                .mapNotNull { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }
                .toSet()

            assertTrue(postedSessions.contains(session1), "Should have posted avatar to session 1")
            assertTrue(postedSessions.contains(session2), "Should have posted avatar to session 2")
            assertEquals(2, postedSessions.size, "Should have posted exactly 2 avatar cards")
        }
    }

    @Test
    fun `Sovereign avatar should appear in private AND subscribed sessions`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val privateSession = "p-cognition: Sovereign (sov-1)"
        val publicSession = "public-session"

        val agent = AgentInstance(
            id = "sov-1",
            name = "Sovereign",
            knowledgeGraphId = "kg-1",
            modelProvider = "test",
            modelName = "test",
            privateSessionId = privateSession,
            subscribedSessionIds = listOf(publicSession)
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            // Trigger the agent in public message
            harness.store.dispatch("session", Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", publicSession)
                put("entry", buildJsonObject {
                    put("id", "msg-pub")
                    put("senderId", "user")
                    put("timestamp", 1000L)
                })
            }))

            this.advanceTimeBy(100)

            // ASSERT
            val postedSessions = harness.processedActions
                .filter { it.name == ActionNames.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.id }
                .mapNotNull { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }
                .toSet()

            assertTrue(postedSessions.contains(publicSession), "Sovereign agent must have avatar in public session")
            assertTrue(postedSessions.contains(privateSession), "Sovereign agent must have avatar in private session")
            assertEquals(2, postedSessions.size, "Sovereign agent should be present in both contexts")
        }
    }
}