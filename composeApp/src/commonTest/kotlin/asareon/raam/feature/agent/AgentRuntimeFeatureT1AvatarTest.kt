package asareon.raam.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.core.AppState
import asareon.raam.core.Identity
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.feature.agent.ui.AgentAvatarCard
import asareon.raam.feature.agent.ui.AgentAvatarLogic
import asareon.raam.ui.AppTheme
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
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
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    /**
     * Builds an identity registry with session entries so that
     * `registry.findByUUID(IdentityUUID(id))?.handle` returns `id`.
     * Required because AgentAvatarLogic resolves UUIDs to handles via the registry.
     */
    private fun sessionRegistry(vararg sessionIds: String): Map<String, Identity> =
        sessionIds.associate { id ->
            id to Identity(uuid = id, localHandle = id, handle = id, name = "Session $id", parentHandle = "session")
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
        val agent = testAgent(agentId, "Test", null, "p", "m", subscribedSessionIds = listOf(session1, session2))

        val statusInfo = AgentStatusInfo(
            lastSeenMessageId = "msg-last",
            processingFrontierMessageId = "msg-last"
        )

        // Setup State: Map<IdentityUUID, Map<IdentityUUID, String>>
        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to agent),
            agentStatuses = mapOf(uid(agentId) to statusInfo),
            agentAvatarCardIds = mapOf(uid(agentId) to mapOf(uid(session1) to oldCard1))
        )
        fakeStore.setState(AppState(
            featureStates = mapOf("agent" to state),
            identityRegistry = sessionRegistry(session1, session2)
        ))

        // ACT
        AgentAvatarLogic.updateAgentAvatars(uid(agentId), fakeStore, state, newStatus = AgentStatus.PROCESSING, newError = null)

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
        val agent = testAgent(agentId, "Test", null, "p", "m", subscribedSessionIds = listOf(activeSession))

        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to agent),
            agentAvatarCardIds = mapOf(uid(agentId) to mapOf(uid(oldZombieSession) to "msg-zombie", uid(activeSession) to "msg-active"))
        )
        fakeStore.setState(AppState(
            featureStates = mapOf("agent" to state),
            identityRegistry = sessionRegistry(activeSession, oldZombieSession)
        ))

        // ACT
        AgentAvatarLogic.updateAgentAvatars(uid(agentId), fakeStore, state) // Just refresh

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
    fun `updateAgentAvatars should target output session if sovereign`() {
        // ARRANGE
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m",
            privateSessionId = "private-1",
            subscribedSessionIds = listOf("public-1")
        )
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))
        fakeStore.setState(AppState(
            featureStates = mapOf("agent" to state),
            identityRegistry = sessionRegistry("public-1", "private-1")
        ))

        // ACT
        AgentAvatarLogic.updateAgentAvatars(uid("a1"), fakeStore, state, newStatus = AgentStatus.IDLE)

        // ASSERT
        val postActions = fakeStore.dispatchedActions.filter { it.name == ActionRegistry.Names.SESSION_POST }
        val sessions = postActions.map { it.payload?.get("session")?.jsonPrimitive?.contentOrNull }.toSet()

        assertTrue(sessions.contains("private-1"))
        assertTrue(sessions.contains("public-1"))
    }

    // --- 2. UI Verification (AgentAvatarCard) ---

    private fun renderAgentCard(agent: AgentInstance, status: AgentStatusInfo) {
        renderAgentCard(agent, status, sessionUUID = null)
    }

    private fun renderAgentCard(agent: AgentInstance, status: AgentStatusInfo, sessionUUID: String?) {
        val agentUuid = agent.identityUUID
        val state = AgentRuntimeState(
            agents = mapOf(agentUuid to agent),
            agentStatuses = mapOf(agentUuid to status)
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        composeTestRule.setContent {
            AppTheme {
                AgentAvatarCard(agent, sessionUUID, fakeStore, fakePlatform)
            }
        }
    }

    @Test
    fun `primary button triggers INITIATE_TURN when IDLE`() {
        val agent = testAgent("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1"))
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
        val agent = testAgent("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1"))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.PROCESSING))

        // Click the Cancel icon
        composeTestRule.onNodeWithContentDescription("Cancel Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_CANCEL_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 3. Remove From Session (kebab menu, avatar context) ---

    private val testSessionUUID = "a0000000-0000-0000-0000-000000000001"

    @Test
    fun `Remove from session appears in kebab menu when sessionUUID is provided`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(testSessionUUID))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE), sessionUUID = testSessionUUID)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Remove from session").assertIsDisplayed()
    }

    @Test
    fun `Remove from session is absent when sessionUUID is null`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(testSessionUUID))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE), sessionUUID = null)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Remove from session").assertDoesNotExist()
    }

    @Test
    fun `clicking Remove from session dispatches SET_SESSION_SUBSCRIPTION with subscribed=false`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(testSessionUUID))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE), sessionUUID = testSessionUUID)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Remove from session").performClick()

        val action = fakeStore.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_SET_SESSION_SUBSCRIPTION
        }
        assertNotNull(action, "AGENT_SET_SESSION_SUBSCRIPTION should be dispatched")
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
        assertEquals(testSessionUUID, action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull)
        assertEquals(false, action.payload?.get("subscribed")?.jsonPrimitive?.booleanOrNull)
    }
}