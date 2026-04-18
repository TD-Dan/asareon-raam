package asareon.raam.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import asareon.raam.core.AppState
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.feature.agent.ui.AgentManagerView
import asareon.raam.ui.AppTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.booleanOrNull

/**
 * Tier 1 Component Test for AgentManagerView.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 *
 * [UPDATED] Tests reflect the Draft Pattern: all editor selectors now mutate a local draft.
 * Only the Save button dispatches AGENT_UPDATE_CONFIG with the full draft state.
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
        fakeStore = FakeStore(AppState(), fakePlatform)

        // Register cognitive strategies — normally done in AgentRuntimeFeature.init(),
        // which T1 tests intentionally skip (testing only the View layer).
        CognitiveStrategyRegistry.clearForTesting()
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.VanillaStrategy,
            legacyId = "vanilla_v1"
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.SovereignStrategy,
            legacyId = "sovereign_v1"
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.StateMachineStrategy
        )
        CognitiveStrategyRegistry.register(
            asareon.raam.feature.agent.strategies.PrivateSessionStrategy
        )
    }

    @After
    fun tearDown() {
        CognitiveStrategyRegistry.clearForTesting()
    }

    private fun setViewState(state: AgentRuntimeState) {
        val appState = AppState(featureStates = mapOf(agentFeature.identity.handle to state))
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
    fun `clicking Create Agent button dispatches AGENT_CREATE action`() {
        setViewState(AgentRuntimeState())

        composeTestRule.onNodeWithText("Create Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_CREATE }
        assertNotNull(action)
        assertEquals("New Agent", action.payload?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `clicking 'Delete' in kebab menu shows dialog and confirming dispatches AGENT_DELETE`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf(uid("a1") to agent)))

        // Delete is now in the kebab dropdown menu (showManagementActions = true)
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete Agent").performClick()

        composeTestRule.onNodeWithText("Delete Agent?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_DELETE }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `clicking 'Clone' in kebab menu dispatches AGENT_CLONE`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf(uid("a1") to agent)))

        // Clone is now in the kebab dropdown menu (showManagementActions = true)
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Clone Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_CLONE }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 2. Editing Mode (Transitions) ---

    @Test
    fun `clicking 'Edit' in kebab menu dispatches AGENT_SET_EDITING`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(agents = mapOf(uid("a1") to agent)))

        // Edit is now in the kebab dropdown menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit Agent").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_EDITING }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `canceling edit dispatches AGENT_SET_EDITING with null id`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1")
        ))

        composeTestRule.onNodeWithContentDescription("Cancel Edit").performScrollTo().performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_EDITING }
        assertNotNull(action)
        assertNull(action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    // --- 3. Draft Pattern: Save collects all fields ---

    @Test
    fun `saving agent dispatches AGENT_UPDATE_CONFIG with full draft payload`() {
        val agent = testAgent("a1", "Old Name", null, "p", "m", autoWaitTimeSeconds = 5, autoMaxWaitTimeSeconds = 30)
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1")
        ))

        // 1. Update Name
        composeTestRule.onNodeWithText("Agent Name").performTextReplacement("New Name")

        // 2. Update Timers
        composeTestRule.onNodeWithText("Auto Wait (s)").performScrollTo().performTextReplacement("10")
        composeTestRule.onNodeWithText("Max Wait (s)").performScrollTo().performTextReplacement("60")

        // 3. Save
        composeTestRule.onNodeWithContentDescription("Save").performScrollTo().performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        val payload = action.payload!!
        assertEquals("a1", payload["agentId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("New Name", payload["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(10, payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull)
        assertEquals(60, payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull)
        // Draft fields should also be present
        assertNotNull(payload["cognitiveStrategyId"])
        assertNotNull(payload["modelProvider"])
        assertNotNull(payload["modelName"])
        assertNotNull(payload["subscribedSessionIds"])
        assertNotNull(payload["resources"])
    }

    @Test
    fun `toggling automatic mode is captured in draft and dispatched on save`() {
        val agent = testAgent("a1", "Test Agent", null, "p", "m", automaticMode = false)
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1")
        ))
        fakeStore.dispatchedActions.clear()

        // 1. Toggle the switch (updates draft, no dispatch)
        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()

        // Verify NO action dispatched yet
        val prematureAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNull(prematureAction, "No UPDATE_CONFIG should fire before Save")

        // 2. Save
        composeTestRule.onNodeWithContentDescription("Save").performScrollTo().performClick()

        // 3. Verify the saved payload includes automaticMode = true
        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        assertEquals(true, action.payload?.get("automaticMode")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `cancel discards draft changes and dispatches no UPDATE_CONFIG`() {
        val agent = testAgent("a1", "Original", null, "p", "m")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1")
        ))
        fakeStore.dispatchedActions.clear()

        // 1. Modify the name
        composeTestRule.onNodeWithText("Agent Name").performTextReplacement("Changed")

        // 2. Cancel
        composeTestRule.onNodeWithContentDescription("Cancel Edit").performScrollTo().performClick()

        // 3. Verify: only SET_EDITING dispatched, no UPDATE_CONFIG
        val updateAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNull(updateAction, "Cancel should not dispatch UPDATE_CONFIG")

        val setEditingAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_EDITING }
        assertNotNull(setEditingAction)
    }

    // --- 4. Dropdown Logic (Draft-Based) ---

    @Test
    fun `provider selector shows no-providers message and hint when availableModels is empty`() {
        // ARRANGE: No API keys configured → availableModels is empty → ProviderSelector
        // replaces the dropdown entirely with an explanatory message.
        val agent = testAgent("a1", "Test Agent", null, "p", "m")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1"),
            availableModels = emptyMap() // no configured providers
        ))

        // ASSERT: The informational message is shown in place of the dropdown.
        composeTestRule.onNodeWithText("No providers configured").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Use settings to configure API keys to enable models."
        ).assertIsDisplayed()

        // ASSERT: The dropdown field itself should not exist — not just disabled.
        composeTestRule.onNodeWithText("Provider").assertDoesNotExist()
    }

    @Test
    fun `provider selector shows dropdown when providers have models available`() {
        // ARRANGE: At least one provider has models — normal dropdown should be shown.
        val agent = testAgent("a1", "Test Agent", null, "gemini", "gemini-pro")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            editingAgentId = uid("a1"),
            availableModels = mapOf("gemini" to listOf("gemini-pro", "gemini-flash"))
        ))

        // ASSERT: Dropdown is rendered; the hint message is absent.
        composeTestRule.onNodeWithText("Provider").assertIsDisplayed()
        composeTestRule.onNodeWithText("No providers configured").assertDoesNotExist()
        composeTestRule.onNodeWithText(
            "Use settings to configure API keys to enable models."
        ).assertDoesNotExist()
    }

    @Test
    fun `agent editor displays available knowledge graphs for sovereign agent`() {
        val agent = testAgent("agent-1", "Test Agent", knowledgeGraphId = "p1", modelProvider = "p", modelName = "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(
            agents = mapOf(uid("agent-1") to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = uid("agent-1")
        )
        setViewState(state)

        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onAllNodesWithText("Keel").assertCountEquals(2)
        composeTestRule.onNodeWithText("Sage").assertIsDisplayed()
    }

    @Test
    fun `selecting knowledge graph updates draft and save includes it in payload`() {
        val agent = testAgent("agent-1", "Test Agent", knowledgeGraphId = "p1", modelProvider = "p", modelName = "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(
            agents = mapOf(uid("agent-1") to agent),
            knowledgeGraphNames = mapOf("p1" to "Keel", "p2" to "Sage"),
            editingAgentId = uid("agent-1")
        )
        setViewState(state)
        fakeStore.dispatchedActions.clear()

        // 1. Select KG (updates draft only)
        composeTestRule.onNodeWithText("Knowledge Graph").performClick()
        composeTestRule.onNodeWithText("Sage").performClick()

        // Verify: no action dispatched yet
        val prematureAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNull(prematureAction, "Dropdown selection should not dispatch directly")

        // 2. Save
        composeTestRule.onNodeWithContentDescription("Save").performScrollTo().performClick()

        // 3. Verify payload — knowledgeGraphId lives inside strategyConfig
        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        val strategyConfig = action.payload?.get("strategyConfig")?.jsonObject
        assertNotNull(strategyConfig, "Save payload must include strategyConfig")
        assertEquals("p2", strategyConfig["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `clicking 'Inspect NVRAM' in kebab menu displays formatted cognitive state JSON`() {
        val stateJson = buildJsonObject {
            put("phase", "BOOTING")
            put("sentinel_check", "PENDING")
        }
        val agent = testAgent(
            id = "a1",
            name = "Sovereign Agent",
            modelProvider = "p",
            modelName = "m",
            cognitiveStrategyId = "sovereign_v1"
        ).copy(cognitiveState = stateJson)
        setViewState(AgentRuntimeState(agents = mapOf(uid("a1") to agent)))

        composeTestRule.onNodeWithText("phase", substring = true).assertDoesNotExist()

        // Inspect NVRAM is in the kebab dropdown menu
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Inspect NVRAM").performClick()

        composeTestRule.onNodeWithText("BOOTING", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("sentinel_check", substring = true).assertIsDisplayed()
    }

    // --- 5. System Resources Tab ---

    @Test
    fun `clicking Resources tab switches view`() {
        setViewState(AgentRuntimeState(activeManagerTab = 0))

        composeTestRule.onNodeWithText("System Resources").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SET_MANAGER_TAB }
        assertNotNull(action)
        assertEquals(1, action.payload?.get("tabIndex")?.jsonPrimitive?.int)
    }

    @Test
    fun `selecting a resource displays its content in CodeEditor`() {
        val resource = AgentResource(
            id = "const_v1",
            type = AgentResourceType.CONSTITUTION,
            name = "Constitution v1",
            content = "<xml>Law</xml>",
            isBuiltIn = false
        )
        setViewState(AgentRuntimeState(
            activeManagerTab = 1,
            resources = listOf(resource),
            editingResourceId = null
        ))

        composeTestRule.onNodeWithText("Constitution v1").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SELECT_RESOURCE }
        assertNotNull(action)
        assertEquals("const_v1", action.payload?.get("resourceId")?.jsonPrimitive?.content)
    }

    @Test
    fun `editing a user resource dispatches SAVE action`() {
        val resource = AgentResource(
            id = "const_v1",
            type = AgentResourceType.CONSTITUTION,
            name = "Constitution v1",
            content = "Old Content",
            isBuiltIn = false
        )
        setViewState(AgentRuntimeState(
            activeManagerTab = 1,
            resources = listOf(resource),
            editingResourceId = "const_v1"
        ))

        composeTestRule.onNodeWithTag("code_editor_input").performTextClearance()
        composeTestRule.onNodeWithTag("code_editor_input").performTextInput("New Content")
        composeTestRule.onNodeWithContentDescription("Save").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_SAVE_RESOURCE }
        assertNotNull(action)
        assertEquals("New Content", action.payload?.get("content")?.jsonPrimitive?.content)
    }

    // --- 6. Resource Slot Selectors ---

    @Test
    fun `vanilla agent editor shows system instruction selector`() {
        val resource = AgentResource(
            id = "si-1",
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            name = "My Instruction",
            content = "Be helpful.",
            isBuiltIn = false
        )
        val agent = testAgent("a1", "Vanilla Bot", null, "p", "m", cognitiveStrategyId = "vanilla_v1")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            resources = listOf(resource),
            editingAgentId = uid("a1")
        ))

        // Resource slot selectors are at the bottom of the editor — scroll into view
        composeTestRule.onNodeWithText("System Instructions").performScrollTo().assertIsDisplayed()

        // Verify sovereign selectors are NOT visible
        composeTestRule.onNodeWithText("Constitution").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bootloader").assertDoesNotExist()
    }

    @Test
    fun `sovereign agent editor shows constitution and bootloader selectors`() {
        val constitution = AgentResource("c1", AgentResourceType.CONSTITUTION, "Const v1", "<xml/>", isBuiltIn = false)
        val bootloader = AgentResource("b1", AgentResourceType.BOOTLOADER, "Boot v1", "<boot/>", isBuiltIn = false)
        val agent = testAgent("a1", "Sovereign Bot", null, "p", "m", cognitiveStrategyId = "sovereign_v1")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            resources = listOf(constitution, bootloader),
            editingAgentId = uid("a1")
        ))

        // Resource slot selectors are at the bottom of the editor — scroll into view
        composeTestRule.onNodeWithText("Constitution").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Bootloader (Sentinel)").performScrollTo().assertIsDisplayed()

        // Verify vanilla selector is NOT visible
        composeTestRule.onNodeWithText("System Instructions").assertDoesNotExist()
    }

    @Test
    fun `selecting a resource slot updates draft and save includes resources in payload`() {
        val resource = AgentResource("si-1", AgentResourceType.SYSTEM_INSTRUCTION, "My Instruction", "content", isBuiltIn = false)
        val agent = testAgent("a1", "Vanilla Bot", null, "p", "m", cognitiveStrategyId = "vanilla_v1")
        setViewState(AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            resources = listOf(resource),
            editingAgentId = uid("a1")
        ))
        fakeStore.dispatchedActions.clear()

        // 1. Open the System Instruction dropdown and select a resource
        composeTestRule.onNodeWithText("System Instructions").performScrollTo().performClick()
        composeTestRule.onNodeWithText("My Instruction").performClick()

        // 2. Verify: no action dispatched yet
        val prematureAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNull(prematureAction)

        // 3. Save
        composeTestRule.onNodeWithContentDescription("Save").performScrollTo().performClick()

        // 4. Verify resources map in payload
        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(action)
        val resourcesPayload = action.payload?.get("resources")?.jsonObject
        assertNotNull(resourcesPayload)
        assertEquals("si-1", resourcesPayload["system_instruction"]?.jsonPrimitive?.contentOrNull)
    }
}