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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


/**
 * Tier 1 Component Test for AgentManagerView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 */
class AgentRuntimeFeatureT1ViewComponentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private val agentFeature = AgentRuntimeFeature(
        platformDependencies = FakePlatformDependencies("test"),
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform, ActionNames.allActionNames)
    }

    private fun setViewState(state: AgentRuntimeState) {
        val appState = AppState(featureStates = mapOf(agentFeature.name to state))
        fakeStore.setState(appState)

        composeTestRule.setContent {
            AppTheme {
                AgentManagerView(
                    store = fakeStore,
                    platformDependencies = fakePlatform
                )
            }
        }
    }

    @Test
    fun `agent manager view displays available knowledge graphs in dropdown`() {
        // --- ARRANGE ---
        val agent = AgentInstance(
            id = "agent-1",
            name = "Test Agent",
            knowledgeGraphId = "p1",
            modelProvider = "test-provider",
            modelName = "test-model"
        )
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = "agent-1" // Set agent to edit mode to show the dropdown
        )
        setViewState(state)

        // --- ACT ---
        // The dropdown is identified by its label text. We click it to expand the menu.
        composeTestRule.onNodeWithText("Knowledge Graph").performClick()

        // --- ASSERT ---
        // CORRECTED: Assert that there are exactly two nodes with the text "Keel".
        // One is the selected value in the text field, the other is the item in the menu.
        composeTestRule.onAllNodesWithText("Keel").assertCountEquals(2)

        // The assertion for "Sage" is unambiguous and remains correct.
        composeTestRule.onNodeWithText("Sage").assertIsDisplayed()
    }

    @Test
    fun `clicking knowledge graph in dropdown dispatches UPDATE_CONFIG action`() {
        // --- ARRANGE ---
        val agent = AgentInstance(
            id = "agent-1",
            name = "Test Agent",
            knowledgeGraphId = "p1",
            modelProvider = "test-provider",
            modelName = "test-model"
        )
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = "agent-1"
        )
        setViewState(state)
        fakeStore.dispatchedActions.clear()

        // --- ACT ---
        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onNodeWithText("Sage").performClick()

        // --- ASSERT ---
        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(action, "AGENT_UPDATE_CONFIG should have been dispatched.")
        assertEquals("ui.agentManager", action.originator)

        val payload = action.payload
        assertNotNull(payload)
        assertEquals("agent-1", payload["agentId"].toString().trim('"'))
        assertEquals("p2", payload["knowledgeGraphId"].toString().trim('"'))
    }

    @Test
    fun `knowledge graph dropdown should NOT show options reserved by other agents`() {
        // --- ARRANGE ---
        val agent = AgentInstance("agent-1", "Test Agent", null, "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf(
                "kg-free" to "Free HKG",
                "kg-reserved" to "Reserved HKG"
            ),
            hkgReservedIds = setOf("kg-reserved"), // "kg-reserved" is locked
            editingAgentId = "agent-1"
        )
        setViewState(state)

        // --- ACT ---
        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.waitForIdle()

        // --- ASSERT ---
        composeTestRule.onNodeWithText("Free HKG").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reserved HKG").assertDoesNotExist()
    }

    @Test
    fun `knowledge graph dropdown SHOULD show an option that is reserved by the agent being edited`() {
        // --- ARRANGE ---
        val agent = AgentInstance("agent-1", "Test Agent", "kg-self-reserved", "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf(
                "kg-self-reserved" to "My Own HKG"
            ),
            hkgReservedIds = setOf("kg-self-reserved"), // Reserved by our agent
            editingAgentId = "agent-1"
        )
        setViewState(state)

        // --- ACT ---
        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.waitForIdle()

        // --- ASSERT ---
        // We assert count is 2 because one is the selected text and the other is in the dropdown.
        composeTestRule.onAllNodesWithText("My Own HKG").assertCountEquals(2)
    }
}