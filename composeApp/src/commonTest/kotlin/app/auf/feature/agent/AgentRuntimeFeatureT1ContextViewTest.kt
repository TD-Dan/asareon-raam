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

/**
 * Tier 1 Component Test for ManageContextView.
 *
 * Mandate: To verify the rendering and interaction logic of the
 * Manage Context screen (§6), specifically handling ContextAssemblyResult
 * and the three-tab layout (Context Management, API Preview, Raw JSON Payload).
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

    private fun setManagedContextState(
        systemPrompt: String = "You are a test agent.",
        rawJson: String? = null,
        estimatedTokens: Int? = null,
        partitions: List<ContextCollapseLogic.ContextPartition> = emptyList()
    ) {
        val agentId = "a1"
        val agent = testAgent(agentId, "Test Agent", null, "p", "m")

        val collapseResult = ContextCollapseLogic.CollapseResult(partitions, partitions.sumOf { it.effectiveCharCount })

        val managedContext = ContextAssemblyResult(
            partitions = partitions,
            collapseResult = collapseResult,
            budgetReport = "Test budget report",
            systemPrompt = systemPrompt,
            gatewayRequest = GatewayRequest(
                modelName = "m",
                contents = emptyList(),
                correlationId = agentId,
                systemPrompt = systemPrompt
            ),
            softBudgetChars = 200_000,
            maxBudgetChars = 500_000,
            transientDataSnapshot = TransientDataSnapshot(emptyMap(), null, null, emptyMap())
        )

        val managedPartitions = PartitionAssemblyResult(
            partitions = partitions,
            collapseResult = collapseResult,
            totalChars = partitions.sumOf { it.effectiveCharCount },
            softBudgetChars = 200_000,
            maxBudgetChars = 500_000
        )

        val statusInfo = AgentStatusInfo(
            managedContext = managedContext,
            managedPartitions = managedPartitions,
            managedContextRawJson = rawJson,
            managedContextEstimatedTokens = estimatedTokens
        )

        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to agent),
            agentStatuses = mapOf(uid(agentId) to statusInfo),
            managingContextForAgentId = uid(agentId)
        )

        fakeStore.setState(AppState(featureStates = mapOf("agent" to state)))

        composeTestRule.setContent {
            AppTheme {
                ManageContextView(fakeStore)
            }
        }
    }

    // =========================================================================
    // Tab 0: Context Management
    // =========================================================================

    @Test
    fun `context management tab displays partition cards`() {
        val partitions = listOf(
            ContextCollapseLogic.ContextPartition(
                key = "YOUR IDENTITY AND ROLE",
                fullContent = "You are Test Agent.",
                collapsedContent = "You are Test Agent.",
                isAutoCollapsible = false
            ),
            ContextCollapseLogic.ContextPartition(
                key = "CONVERSATION_LOG",
                fullContent = "Hello world",
                collapsedContent = "[Collapsed]",
                isAutoCollapsible = true
            )
        )
        setManagedContextState(partitions = partitions)

        composeTestRule.onNodeWithText("YOUR IDENTITY AND ROLE").assertIsDisplayed()
        composeTestRule.onNodeWithText("CONVERSATION_LOG").assertIsDisplayed()
    }

    @Test
    fun `context management tab shows persistence notice`() {
        setManagedContextState()
        composeTestRule.onNodeWithText("Collapse settings persist across turns for this agent.")
            .assertIsDisplayed()
    }

    @Test
    fun `protected partition shows PROTECTED label instead of toggle`() {
        val partitions = listOf(
            ContextCollapseLogic.ContextPartition(
                key = "SESSION_METADATA",
                fullContent = "metadata",
                collapsedContent = "metadata",
                isAutoCollapsible = false
            )
        )
        setManagedContextState(partitions = partitions)

        composeTestRule.onNodeWithText("PROTECTED").assertIsDisplayed()
    }

    // =========================================================================
    // Tab 1: API Preview
    // =========================================================================

    @Test
    fun `api preview tab displays system prompt`() {
        setManagedContextState(systemPrompt = "You are a helpful assistant.")

        composeTestRule.onNodeWithText("API Preview").performClick()
        composeTestRule.onNodeWithText("SYSTEM PROMPT").assertIsDisplayed()
        composeTestRule.onNodeWithText("You are a helpful assistant.").assertIsDisplayed()
    }

    @Test
    fun `api preview tab shows token estimate when available`() {
        setManagedContextState(estimatedTokens = 12345)

        composeTestRule.onNodeWithText("API Preview").performClick()
        composeTestRule.onNodeWithText("Estimated input: 12,345 tokens").assertIsDisplayed()
    }

    // =========================================================================
    // Tab 2: Raw JSON Payload
    // =========================================================================

    @Test
    fun `raw tab displays raw json payload`() {
        val rawJson = """{"foo": "bar"}"""
        setManagedContextState(rawJson = rawJson)

        composeTestRule.onNodeWithText("Raw JSON Payload").performClick()
        composeTestRule.onNodeWithText(rawJson).assertIsDisplayed()
    }

    @Test
    fun `raw tab shows loading spinner when no raw json yet`() {
        setManagedContextState(rawJson = null)

        composeTestRule.onNodeWithText("Raw JSON Payload").performClick()
        composeTestRule.onNodeWithText("Waiting for gateway preview...").assertIsDisplayed()
    }

    // =========================================================================
    // Action dispatches
    // =========================================================================

    @Test
    fun `execute button dispatches AGENT_EXECUTE_MANAGED_TURN`() {
        setManagedContextState()

        composeTestRule.onNodeWithText("Execute Turn").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_EXECUTE_MANAGED_TURN }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `cancel button dispatches AGENT_DISCARD_MANAGED_CONTEXT`() {
        setManagedContextState()

        composeTestRule.onNodeWithText("Cancel").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT }
        assertNotNull(action)
        assertEquals("a1", action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `copy button on api preview tab dispatches CORE_COPY_TO_CLIPBOARD`() {
        setManagedContextState(systemPrompt = "test prompt content")

        composeTestRule.onNodeWithText("API Preview").performClick()
        composeTestRule.onNodeWithText("Copy All").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD }
        assertNotNull(action)
        assertEquals("test prompt content", action.payload?.get("text")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `collapse toggle dispatches CONTEXT_COLLAPSE`() {
        val partitions = listOf(
            ContextCollapseLogic.ContextPartition(
                key = "CONVERSATION_LOG",
                fullContent = "content",
                collapsedContent = "[collapsed]",
                state = CollapseState.EXPANDED,
                isAutoCollapsible = true
            )
        )
        setManagedContextState(partitions = partitions)

        composeTestRule.onNodeWithText("EXPANDED ▾").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE }
        assertNotNull(action)
        assertEquals("CONVERSATION_LOG", action.payload?.get("partitionKey")?.jsonPrimitive?.contentOrNull)
    }
}