package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.session.SessionFeature
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
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
class AgentRuntimeFeatureT2AvatarCoreTest {

    private val platform = FakePlatformDependencies("test")

    // All IDs must be valid UUIDs — reducer validates with stringIsUUID(),
    // avatar system resolves session UUIDs via identity registry.
    private val SESSION_1_UUID = "a0000000-0000-0000-0000-000000000001"
    private val SESSION_2_UUID = "a0000000-0000-0000-0000-000000000002"
    private val PRIVATE_SESSION_UUID = "a0000000-0000-0000-0000-0000000000aa"
    private val PUBLIC_SESSION_UUID = "a0000000-0000-0000-0000-0000000000bb"

    // Session objects for registration and handle resolution
    private val session1 = testSession(SESSION_1_UUID, "Session One")
    private val session2 = testSession(SESSION_2_UUID, "Session Two")
    private val privateSession = testSession(PRIVATE_SESSION_UUID, "Private Cognition")
    private val publicSession = testSession(PUBLIC_SESSION_UUID, "Public Discussion")

    @Test
    fun `Rapid incoming messages should not create duplicate avatar cards`() = runTest {
        // ARRANGE
        // [FIX] Use the TestScope's backgroundScope so that delays are controllable by advanceTimeBy
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val sessionFeature = SessionFeature(platform, this.backgroundScope)
        val agent = testAgent(
            id = "b0000000-0000-0000-0000-000000000001",
            name = "Test Agent",
            modelProvider = "test",
            modelName = "test",
            subscribedSessionIds = listOf(SESSION_1_UUID)
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            // Initial state: Agent is IDLE — use IdentityUUID keys
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.identityUUID to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register session identity so avatar system can resolve UUID → handle
            harness.registerSessionIdentity(session1)

            // ACT
            // Simulate rapid-fire messages.
            // Current Logic: Debounced by 50ms.
            val msg1 = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", SESSION_1_UUID)
                put("entry", buildJsonObject {
                    put("id", "msg-1"); put("senderId", "user"); put("timestamp", 1000L)
                })
            })

            val msg2 = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", SESSION_1_UUID)
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
                it.name == ActionRegistry.Names.SESSION_POST &&
                        it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.identity.handle
            }

            // Expected: 1 (The first trigger is cancelled, only second runs)
            assertEquals(1, avatarPostActions.size, "Race condition detected! Duplicate avatar cards were posted.")
        }
    }

    @Test
    fun `Avatar should appear in all subscribed sessions`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val sessionFeature = SessionFeature(platform, this.backgroundScope)
        val agent = testAgent(
            id = "b0000000-0000-0000-0000-000000000002",
            name = "Multi Agent",
            modelProvider = "test",
            modelName = "test",
            subscribedSessionIds = listOf(SESSION_1_UUID, SESSION_2_UUID) // Subscribed to TWO sessions
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.identityUUID to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register session identities so avatar system can resolve UUIDs → handles
            harness.registerSessionIdentity(session1)
            harness.registerSessionIdentity(session2)

            // ACT
            // Trigger the agent in Session 1
            harness.store.dispatch("session", Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", SESSION_1_UUID)
                put("entry", buildJsonObject {
                    put("id", "msg-trigger"); put("senderId", "user"); put("timestamp", 1000L)
                })
            }))

            this.advanceTimeBy(100)

            // ASSERT
            // Avatar system resolves UUIDs to handles, so SESSION_POST uses handles
            val postedSessions = harness.processedActions
                .filter { it.name == ActionRegistry.Names.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.identity.handle }
                .mapNotNull { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }
                .toSet()

            assertTrue(postedSessions.contains(session1.identity.handle), "Should have posted avatar to session 1")
            assertTrue(postedSessions.contains(session2.identity.handle), "Should have posted avatar to session 2")
            assertEquals(2, postedSessions.size, "Should have posted exactly 2 avatar cards")
        }
    }

    @Test
    fun `Sovereign avatar should appear in private AND subscribed sessions`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(platform, this.backgroundScope)
        val sessionFeature = SessionFeature(platform, this.backgroundScope)

        val agent = testAgent(
            id = "b0000000-0000-0000-0000-000000000003",
            name = "Sovereign",
            knowledgeGraphId = "kg-1",
            modelProvider = "test",
            modelName = "test",
            privateSessionId = PRIVATE_SESSION_UUID,
            subscribedSessionIds = listOf(PUBLIC_SESSION_UUID)
        )

        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.identityUUID to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Register session identities
            harness.registerSessionIdentity(privateSession)
            harness.registerSessionIdentity(publicSession)

            // ACT
            // Trigger the agent in public message
            harness.store.dispatch("session", Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
                put("sessionId", PUBLIC_SESSION_UUID)
                put("entry", buildJsonObject {
                    put("id", "msg-pub")
                    put("senderId", "user")
                    put("timestamp", 1000L)
                })
            }))

            this.advanceTimeBy(100)

            // ASSERT
            // Avatar system resolves UUIDs to handles
            val postedSessions = harness.processedActions
                .filter { it.name == ActionRegistry.Names.SESSION_POST && it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == agent.identity.handle }
                .mapNotNull { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }
                .toSet()

            assertTrue(postedSessions.contains(publicSession.identity.handle), "Sovereign agent must have avatar in public session")
            assertTrue(postedSessions.contains(privateSession.identity.handle), "Sovereign agent must have avatar in private session")
            assertEquals(2, postedSessions.size, "Sovereign agent should be present in both contexts")
        }
    }
}