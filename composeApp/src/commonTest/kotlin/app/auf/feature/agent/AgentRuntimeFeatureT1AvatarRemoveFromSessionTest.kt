package app.auf.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.AppState
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tier 1 UI Component Tests for the "Remove from this session"
 * menu item on the AgentAvatarCard.
 *
 * Verifies:
 * 1. "Remove from this session" appears when sessionUUID is provided
 * 2. "Remove from this session" is absent when sessionUUID is null (e.g., Manager View)
 * 3. Clicking it dispatches AGENT_REMOVE_SESSION_SUBSCRIPTION with correct payload
 */
class AgentRuntimeFeatureT1AvatarRemoveFromSessionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    private val sessionUUID = "a0000000-0000-0000-0000-000000000001"

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    private fun renderAgentCard(
        agent: AgentInstance,
        status: AgentStatusInfo = AgentStatusInfo(status = AgentStatus.IDLE),
        sessionUUID: String? = null
    ) {
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
    fun `Remove from this session appears in menu when sessionUUID is provided`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(sessionUUID))
        renderAgentCard(agent, sessionUUID = sessionUUID)

        // Open the kebab menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        // Verify the menu item is present
        composeTestRule.onNodeWithText("Remove from this session").assertIsDisplayed()
    }

    @Test
    fun `Remove from this session is absent when sessionUUID is null`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(sessionUUID))
        renderAgentCard(agent, sessionUUID = null)

        // Open the kebab menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        // Verify the menu item is NOT present
        composeTestRule.onNodeWithText("Remove from this session").assertDoesNotExist()
    }

    @Test
    fun `clicking Remove from this session dispatches REMOVE_SESSION_SUBSCRIPTION`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(sessionUUID))
        renderAgentCard(agent, sessionUUID = sessionUUID)

        // Open the kebab menu and click the item
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Remove from this session").performClick()

        // Verify the dispatched action
        val action = fakeStore.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION
        }
        assertNotNull(action, "AGENT_REMOVE_SESSION_SUBSCRIPTION should be dispatched")
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull,
            "agentId should match the agent's UUID")
        assertEquals(sessionUUID, action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull,
            "sessionId should match the provided session UUID")
    }

    @Test
    fun `Edit Agent and Preview Turn still appear regardless of sessionUUID`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", subscribedSessionIds = listOf(sessionUUID))
        renderAgentCard(agent, sessionUUID = sessionUUID)

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Edit Agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Preview Turn").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove from this session").assertIsDisplayed()
    }
}