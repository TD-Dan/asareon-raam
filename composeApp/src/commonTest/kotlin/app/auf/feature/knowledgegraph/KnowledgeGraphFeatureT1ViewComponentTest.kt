package app.auf.feature.knowledgegraph

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Component Test for KnowledgeGraphView's various components.
 */
class KnowledgeGraphFeatureT1ViewComponentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var harness: TestHarness
    private val platform = FakePlatformDependencies("test")
    private val feature = KnowledgeGraphFeature(platform, CoroutineScope(Dispatchers.Unconfined))

    private fun setupTestWithState(state: KnowledgeGraphState) {
        harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", state)
            .build(platform = platform)

        composeTestRule.setContent {
            AppTheme {
                KnowledgeGraphView(
                    store = harness.store,
                    platformDependencies = platform
                )
            }
        }
    }

    @Test
    fun `selecting a holon displays its rawContent`() {
        val h1Content = "This is the unique raw holon content."
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "Holon One"), buildJsonObject {}, rawContent = h1Content)
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1", subHolons = listOf(SubHolonRef("h1", "Type_A", ""))), buildJsonObject {})
        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1),
            personaRoots = mapOf("P1" to "p1"),
            activeHolonIdForView = "h1"
        ))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(h1Content).assertExists()
    }

    @Test
    fun `persona root item should display reservation status when reserved`() {
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), buildJsonObject {})
        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("p1" to p1),
            personaRoots = mapOf("P1" to "p1"),
            reservations = mapOf("p1" to "agent-alpha")
        ))
        composeTestRule.waitForIdle()

        // Assert that the UI clearly indicates the reservation status and owner.
        composeTestRule.onNodeWithText("Reserved by: agent-alpha", substring = true).assertIsDisplayed()
    }

    @Test
    fun `HolonEditView updates payload and dispatches correct action`() {
        val initialPayload = buildJsonObject { put("key", "old") }
        val h1 = Holon(header = HolonHeader(id = "h1", type = "Type_A", name = "H1"), payload = initialPayload, execute = buildJsonObject {})
        val p1 = Holon(header = HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), payload = buildJsonObject {})

        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("h1" to h1, "p1" to p1),
            personaRoots = mapOf("P1" to "p1"),
            holonIdToEdit = "h1"
        ))
        composeTestRule.waitForIdle()
        harness.store.processedActions.clear()

        val newPayloadString = """{"key":"new"}"""
        composeTestRule.onNodeWithText("Payload").performTextReplacement(newPayloadString)

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save Changes").performClick()

        composeTestRule.waitForIdle()

        val action = harness.store.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT }
        assertNotNull(action)
        assertEquals("h1", action.payload?.get("holonId")?.toString()?.trim('"'))
        assertEquals(newPayloadString, action.payload?.get("payload")?.toString())
    }

    @Test
    fun `HolonEditView with invalid JSON does not dispatch action and shows error`() {
        val h1 = Holon(header = HolonHeader(id = "h1", type = "Type_A", name = "H1"), payload = buildJsonObject {})
        val p1 = Holon(header = HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), payload = buildJsonObject {})

        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("h1" to h1, "p1" to p1),
            personaRoots = mapOf("P1" to "p1"),
            holonIdToEdit = "h1"
        ))
        composeTestRule.waitForIdle()
        harness.store.processedActions.clear()

        composeTestRule.onNodeWithText("Payload").performTextInput("{ \"key\": ")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()

        assertTrue(harness.store.processedActions.none { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT })
        composeTestRule.onNodeWithText("Invalid JSON format in payload.").assertExists()
    }

    @Test
    fun `ImportPane ActionSelector should only show actions from the availableActions list`() {
        // [CORRECTED] Changed 'initialAction' to 'proposedAction' and added 'statusReason'.
        val proposedAction = Quarantine("Test reason")
        val importItem = ImportItem(
            sourcePath = "quarantined.json",
            proposedAction = proposedAction,
            targetPath = null,
            statusReason = proposedAction.reason,
            availableActions = listOf(ImportActionType.QUARANTINE, ImportActionType.ASSIGN_PARENT, ImportActionType.IGNORE)
        )
        setupTestWithState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importItems = listOf(importItem),
            importSelectedActions = mapOf("quarantined.json" to importItem.proposedAction),
            importFileContents = mapOf("quarantined.json" to "{}")
        ))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Change Action Type").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Quarantine (fix later)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Orphan - select parent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ignore - Do nothing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update existing holon").assertDoesNotExist()

        // [CORRECTED] Test that the correct action instance is used to generate the menu item text.
        // In this case, clicking an item in the dropdown should use the item's *proposed* action, not the
        // currently selected one. This is a subtle but important detail for UI correctness.
        // Find the "Ignore" option and click it.
        composeTestRule.onNodeWithText("Ignore - Do nothing").performClick()
        composeTestRule.waitForIdle()

        // Verify that the correct action was dispatched
        val action = harness.store.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION }
        assertNotNull(action)
        val actionPayload = action.payload!!.jsonObject["action"]!!.jsonObject
        assertEquals("Ignore", actionPayload["type"]!!.jsonPrimitive.content)
    }
}