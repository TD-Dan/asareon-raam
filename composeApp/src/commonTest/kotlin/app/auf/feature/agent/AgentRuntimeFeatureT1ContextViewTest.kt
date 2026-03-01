package app.auf.feature.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
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

/**
 * Tier 1 Component Test for AgentContextView.
 *
 * Mandate (P-TEST-001, T1): To verify the rendering and interaction logic of the
 * turn preview screen, specifically handling StagedPreviewData.
 */
class AgentRuntimeFeatureT1ContextViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform)
    }

    private fun setPreviewState(
        systemPrompt: String? = null,
        rawJson: String = "{}"
    ) {
        val agentId = "a1"
        val agent = testAgent(agentId, "Test Agent", null, "p", "m")

        val messages = listOf(
            GatewayMessage("user", "Hello Agent", "u1", "User", 1000L)
        )

        val request = GatewayRequest(
            modelName = "m",
            contents = messages,
            correlationId = agentId,
            systemPrompt = systemPrompt
        )

        val previewData = StagedPreviewData(request, rawJson)
        val statusInfo = AgentStatusInfo(stagedPreviewData = previewData)

        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to agent),
            agentStatuses = mapOf(uid(agentId) to statusInfo),
            viewingContextForAgentId = uid(agentId)
        )

        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        composeTestRule.setContent {
            AppTheme {
                AgentContextView(fakeStore)
            }
        }
    }

    @Test
    fun `logical tab displays system prompt and message history`() {
        setPreviewState(systemPrompt = "You are a helpful assistant.")

        // 1. Verify System Prompt
        composeTestRule.onNodeWithText("SYSTEM PROMPT").assertIsDisplayed()
        composeTestRule.onNodeWithText("You are a helpful assistant.").assertIsDisplayed()

        // 2. Verify Message History
        composeTestRule.onNodeWithText("ROLE: USER").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hello Agent").assertIsDisplayed()
    }

    @Test
    fun `raw tab displays raw json payload`() {
        val rawJson = """{"foo": "bar"}"""
        setPreviewState(rawJson = rawJson)

        // 1. Switch to Raw Tab
        composeTestRule.onNodeWithText("Raw JSON Payload").performClick()

        // 2. Verify Content
        composeTestRule.onNodeWithText(rawJson).assertIsDisplayed()
    }

    @Test
    fun `execute button dispatches AGENT_EXECUTE_PREVIEWED_TURN`() {
        setPreviewState()

        composeTestRule.onNodeWithText("Execute Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_EXECUTE_PREVIEWED_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `discard button dispatches AGENT_DISCARD_PREVIEW`() {
        setPreviewState()

        // Note: There are two buttons that trigger this (Back Arrow and "Cancel").
        // We test the "Cancel" button here.
        composeTestRule.onNodeWithText("Cancel").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_DISCARD_PREVIEW }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `copy button dispatches CORE_COPY_TO_CLIPBOARD`() {
        setPreviewState(rawJson = "{\"test\": true}")

        // Switch to raw tab to test that specific copy button
        composeTestRule.onNodeWithText("Raw JSON Payload").performClick()

        composeTestRule.onNodeWithText("Copy All").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD }
        assertNotNull(action)
        assertEquals("{\"test\": true}", action.payload?.get("text")?.jsonPrimitive?.contentOrNull)
    }
}