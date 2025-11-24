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
        fakeStore = FakeStore(AppState(), fakePlatform, ActionNames.allActionNames)
    }

    // --- 1. Logic Verification (AgentAvatarLogic) ---

    @Test
    fun `updateAgentAvatarCard should atomically replace the card in the ledger`() {
        // ARRANGE
        val agentId = "a1"
        val sessionId = "s1"
        val oldCardInfo = AgentRuntimeState.AvatarCardInfo("msg-old", sessionId)

        val agent = AgentInstance(agentId, "Test", null, "p", "m", subscribedSessionIds = listOf(sessionId))

        // FIX: We must seed processingFrontierMessageId because we are testing the PROCESSING transition
        val statusInfo = AgentStatusInfo(
            lastSeenMessageId = "msg-last",
            processingFrontierMessageId = "msg-last"
        )

        val state = AgentRuntimeState(
            agents = mapOf(agentId to agent),
            agentStatuses = mapOf(agentId to statusInfo),
            agentAvatarCardIds = mapOf(agentId to oldCardInfo)
        )
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        // ACT
        AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, fakeStore)

        // ASSERT
        // 1. Delete Old
        val deleteAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction)
        assertEquals("msg-old", deleteAction.payload?.get("messageId")?.jsonPrimitive?.contentOrNull)

        // 2. Set Status
        val statusAction = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_INTERNAL_SET_STATUS }
        assertNotNull(statusAction)
        assertEquals("PROCESSING", statusAction.payload?.get("status")?.jsonPrimitive?.contentOrNull)

        // 3. Post New
        val postAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction)
        assertEquals(sessionId, postAction.payload?.get("session")?.jsonPrimitive?.contentOrNull)
        assertEquals("msg-last", postAction.payload?.get("afterMessageId")?.jsonPrimitive?.contentOrNull)

        val metadata = postAction.payload?.get("metadata") // Implicit check that metadata exists
        assertNotNull(metadata)
    }

    @Test
    fun `updateAgentAvatarCard should target private session if sovereign`() {
        // ARRANGE
        val agent = AgentInstance("a1", "Sovereign", "kg1", "p", "m",
            privateSessionId = "private-1",
            subscribedSessionIds = listOf("public-1")
        )
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))
        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        // ACT
        AgentAvatarLogic.updateAgentAvatarCard("a1", AgentStatus.IDLE, null, fakeStore)

        // ASSERT
        val postAction = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_POST }
        assertNotNull(postAction)
        assertEquals("private-1", postAction.payload?.get("session")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `touchAgentAvatarCard should dispatch SESSION_UPDATE_MESSAGE`() {
        // ARRANGE
        val agent = AgentInstance("a1", "Test", null, "p", "m")
        val cardInfo = AgentRuntimeState.AvatarCardInfo("msg-1", "s-1")
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            agentAvatarCardIds = mapOf("a1" to cardInfo)
        )

        // ACT
        AgentAvatarLogic.touchAgentAvatarCard(agent, state, fakeStore)

        // ASSERT
        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.SESSION_UPDATE_MESSAGE }
        assertNotNull(action)
        assertEquals("msg-1", action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull)
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

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_INITIATE_TURN }
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

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_CANCEL_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `menu action 'Preview Turn' triggers INITIATE_TURN with preview=true`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1"))
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE))

        // Open Menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        // Click Preview
        composeTestRule.onNodeWithText("Preview Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_INITIATE_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, action.payload?.get("preview")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `toggle buttons dispatch correct actions`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m")
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.IDLE))

        // Toggle Active
        composeTestRule.onNodeWithContentDescription("Toggle Active State").performClick()
        assertNotNull(fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_TOGGLE_ACTIVE })

        // Toggle Automatic
        composeTestRule.onNodeWithContentDescription("Toggle Automatic Mode").performClick()
        assertNotNull(fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE })
    }

    @Test
    fun `error message is displayed when status is ERROR`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m")
        renderAgentCard(agent, AgentStatusInfo(status = AgentStatus.ERROR, errorMessage = "Something broke"))

        composeTestRule.onNodeWithText("Something broke").assertIsDisplayed()
    }
}