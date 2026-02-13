package app.auf.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit/Component Test for Agent Avatar Logic & UI.
 *
 * Mandate (P-TEST-001, T1):
 * 1. Verify pure logic in AgentAvatarLogic using FakeStore.
 * 2. Verify UI composition and interaction in AgentAvatarCard using ComposeTestRule.
 */
class AgentRuntimeFeatureT1AvatarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform, ActionRegistry.Names.allActionNames)
    }

    // --- 1. Logic Verification (AgentAvatarLogic) ---

    @Test
    fun `updateAgentAvatars should atomically replace the card in ALL sessions`() {
        // ARRANGE
        val agentId = "a1"
        val session1 = "s1"
        val session2 = "s2"
        val oldCard1 = "msg-old-1"

        // Agent subscribed to 2 sessions, only has card in 1 currently
        val agent = AgentInstance(agentId, "Test", null, "p", "m", subscribedSessionIds = listOf(session1, session2))

        val statusInfo = AgentStatusInfo(
            lastSeenMessageId = "msg-last",
            processingFrontierMessageId = "msg-last"
        )

        // Setup State: Map<String, Map<String, String>>
        val state = AgentRuntimeState(
            agents = mapOf(agentId to agent),
            agentStatuses = mapOf(agentId to statusInfo),
            agentAvatarCardIds = mapOf(agentId to mapOf(session1 to oldCard1))
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        // ACT
        AgentAvatarLogic.updateAgentAvatars(agentId, fakeStore, AgentStatus.PROCESSING, null)

        // ASSERT
        // 1. Delete Old (Session 1 only)
        val deleteAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction)
        assertEquals(session1, deleteAction.payload?.get("session")?.jsonPrimitive?.contentOrNull)
        assertEquals(oldCard1, deleteAction.payload?.get("messageId")?.jsonPrimitive?.contentOrNull)

        // 2. Set Status
        val statusAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_STATUS }
        assertNotNull(statusAction)
        assertEquals("PROCESSING", statusAction.payload?.get("status")?.jsonPrimitive?.contentOrNull)

        // 3. Post New (Both sessions)
        val postActions = fakeStore.dispatchedActions.filter { it.name == ActionRegistry.Names.SESSION_POST }
        assertEquals(2, postActions.size)

        val postedSessions = postActions.map { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }.toSet()
        assertTrue(postedSessions.contains(session1))
        assertTrue(postedSessions.contains(session2))
    }

    @Test
    fun `updateAgentAvatars should cleanup zombie sessions`() {
        // ARRANGE
        val agentId = "a1"
        val activeSession = "s1"
        val oldZombieSession = "s-zombie"

        // Agent ONLY subscribed to s1. But state thinks card exists in s-zombie.
        val agent = AgentInstance(agentId, "Test", null, "p", "m", subscribedSessionIds = listOf(activeSession))

        val state = AgentRuntimeState(
            agents = mapOf(agentId to agent),
            agentAvatarCardIds = mapOf(agentId to mapOf(oldZombieSession to "msg-zombie", activeSession to "msg-active"))
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        // ACT
        AgentAvatarLogic.updateAgentAvatars(agentId, fakeStore) // Just refresh

        // ASSERT
        // 1. Delete Zombie
        val zombieDelete = fakeStore.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_DELETE_MESSAGE && it.payload?.get("session")?.jsonPrimitive?.contentOrNull == oldZombieSession
        }
        assertNotNull(zombieDelete)

        // 2. Post ONLY to active session
        val postActions = fakeStore.dispatchedActions.filter { it.name == ActionRegistry.Names.SESSION_POST }
        assertEquals(1, postActions.size)
        assertEquals(activeSession, postActions[0].payload?.get("session")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `updateAgentAvatars should target private session if sovereign`() {
        // ARRANGE
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m",
            privateSessionId = "private-1",
            subscribedSessionIds = listOf("public-1")
        )
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        // ACT
        AgentAvatarLogic.updateAgentAvatars("a1", fakeStore, AgentStatus.IDLE)

        // ASSERT
        val postActions = fakeStore.dispatchedActions.filter { it.name == ActionRegistry.Names.SESSION_POST }
        val sessions = postActions.map { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }.toSet()

        assertTrue(sessions.contains("private-1"))
        assertTrue(sessions.contains("public-1"))
    }

    // --- 2. UI Verification (AgentAvatarCard) ---

    private fun renderAgentCard(agent: AgentInstance, status: AgentStatusInfo) {
        val state = AgentRuntimeState(
            agents = mapOf(agent.id to agent),
            agentStatuses = mapOf(agent.id to status)
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        composeTestRule.setContent {
            AppTheme {
                AgentAvatarCard(agent, fakeStore, fakePlatform)
            }
        }
    }

    @Test
    fun `primary button triggers INITIATE_TURN when IDLE`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1"))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE))

        // Click the Play arrow
        composeTestRule.onNodeWithContentDescription("Trigger Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_INITIATE_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
        assertEquals(false, action.payload?.get("preview")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `primary button triggers CANCEL_TURN when PROCESSING`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1"))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.PROCESSING))

        // Click the Cancel icon
        composeTestRule.onNodeWithContentDescription("Cancel Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_CANCEL_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }
}