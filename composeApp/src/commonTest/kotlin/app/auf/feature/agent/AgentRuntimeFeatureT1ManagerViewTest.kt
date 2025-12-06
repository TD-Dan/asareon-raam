package app.auf.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Tier 1 Component Test for AgentManagerView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 *
 * REPLACES: AgentRuntimeFeatureT1ViewComponentTest.kt
 */
class AgentRuntimeFeatureT1ManagerViewTest {

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

    // --- 1. Creation & Deletion (Lifecycle) ---

    @Test
    fun `clicking 'New Agent' button dispatches AGENT_CREATE action`() {
        setViewState(AgentRuntimeState())

        // Look for the button with the text "New Agent" (it's in the TopAppBar actions)
        composeTestRule.onNodeWithText("New Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_CREATE }
        assertNotNull(action)
        assertEquals("New Agent", action.payload?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `clicking 'Delete' icon shows dialog and confirming dispatches AGENT_DELETE`() {
        val agent = AgentInstance("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf("a1" to agent)))

        // Click the delete icon card action
        composeTestRule.onNodeWithContentDescription("Delete Agent").performClick()

        // Verify Dialog Appears
        composeTestRule.onNodeWithText("Delete Agent?").assertIsDisplayed()

        // Click Confirm
        composeTestRule.onNodeWithText("Delete").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_DELETE }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `clicking 'Clone' icon dispatches AGENT_CLONE`() {
        val agent = AgentInstance("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf("a1" to agent)))

        composeTestRule.onNodeWithContentDescription("Clone Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_CLONE }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 2. Editing Mode (Transitions) ---

    @Test
    fun `clicking 'Edit' icon dispatches AGENT_SET_EDITING`() {
        val agent = AgentInstance("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf("a1" to agent)))

        composeTestRule.onNodeWithContentDescription("Edit Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_SET_EDITING }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `canceling edit dispatches AGENT_SET_EDITING with null id`() {
        val agent = AgentInstance("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(
            agents = mapOf("a1" to agent),
            editingAgentId = "a1" // Already editing
        ))

        composeTestRule.onNodeWithContentDescription("Cancel Edit").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_SET_EDITING }
        assertNotNull(action)
        // The payload might contain "null" string or actual null, check carefully.
        // Logic: put("agentId", null as String?) -> results in "agentId": null
        assertNull(action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 3. Form Interaction (Inputs) ---

    @Test
    fun `saving agent name and timing inputs dispatches AGENT_UPDATE_CONFIG`() {
        val agent = AgentInstance("a1", "Old Name", null, "p", "m", autoWaitTimeSeconds = 5, autoMaxWaitTimeSeconds = 30)
        setViewState(AgentRuntimeState(
            agents = mapOf("a1" to agent),
            editingAgentId = "a1"
        ))

        // 1. Update Name
        composeTestRule.onNodeWithText("Agent Name").performTextReplacement("New Name")

        // 2. Update Timers (clear and type)
        composeTestRule.onNodeWithText("Auto Wait (s)").performTextReplacement("10")
        composeTestRule.onNodeWithText("Max Wait (s)").performTextReplacement("60")

        // 3. Save
        composeTestRule.onNodeWithContentDescription("Save Name").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        val payload = action.payload!!
        assertEquals("a1", payload["agentId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("New Name", payload["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(10, payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull)
        assertEquals(60, payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `toggling 'Automatic Mode' switch dispatches AGENT_TOGGLE_AUTOMATIC_MODE`() {
        val agent = AgentInstance("a1", "Test Agent", null, "p", "m", automaticMode = false)
        setViewState(AgentRuntimeState(
            agents = mapOf("a1" to agent),
            editingAgentId = "a1"
        ))

        // Find switch. It doesn't have text directly on it, but there is text "Automatic Mode" nearby.
        // The switch is a toggleable node.
        composeTestRule.onNode(isToggleable()).performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 4. Dropdown Logic (Existing Retained) ---

    @Test
    fun `agent manager view displays available knowledge graphs in dropdown`() {
        val agent = AgentInstance("agent-1", "Test Agent", "p1", "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = "agent-1"
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onAllNodesWithText("Keel").assertCountEquals(2)
        composeTestRule.onNodeWithText("Sage").assertIsDisplayed()
    }

    @Test
    fun `clicking knowledge graph in dropdown dispatches AGENT_UPDATE_CONFIG`() {
        val agent = AgentInstance("agent-1", "Test Agent", "p1", "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = "agent-1"
        )
        setViewState(state)
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onNodeWithText("Sage").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        assertEquals("p2", action.payload?.get("knowledgeGraphId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `knowledge graph dropdown should NOT show options reserved by other agents`() {
        val agent = AgentInstance("agent-1", "Test Agent", null, "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("kg-free" to "Free HKG", "kg-reserved" to "Reserved HKG"),
            hkgReservedIds = setOf("kg-reserved"),
            editingAgentId = "agent-1"
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onNodeWithText("Free HKG").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reserved HKG").assertDoesNotExist()
    }

    @Test
    fun `knowledge graph dropdown SHOULD show an option that is reserved by the agent being edited`() {
        val agent = AgentInstance("agent-1", "Test Agent", "kg-self", "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("agent-1" to agent),
            knowledgeGraphNames = mapOf("kg-self" to "My Own HKG"),
            hkgReservedIds = setOf("kg-self"),
            editingAgentId = "agent-1"
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onAllNodesWithText("My Own HKG").assertCountEquals(2)
    }
    @Test
    fun `clicking 'Inspect State' displays formatted cognitive state JSON`() {
        val stateJson = buildJsonObject {
            put("phase", "BOOTING")
            put("sentinel_check", "PENDING")
        }
        val agent = AgentInstance(
            id = "a1",
            name = "Sovereign Agent",
            modelProvider = "p",
            modelName = "m",
            cognitiveStrategyId = "sovereign_v1",
            cognitiveState = stateJson
        )
        setViewState(AgentRuntimeState(agents = mapOf("a1" to agent)))

        // 1. Initially, the internals should be hidden to reduce clutter
        composeTestRule.onNodeWithText("phase", substring = true).assertDoesNotExist()

        // 2. Click the expand/inspect button (Icon: Info or Text: Inspect State)
        composeTestRule.onNodeWithContentDescription("Inspect State").performClick()

        // 3. Verify the JSON content is rendered (likely in the CodeEditor)
        composeTestRule.onNodeWithText("BOOTING", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("sentinel_check", substring = true).assertIsDisplayed()
    }
}