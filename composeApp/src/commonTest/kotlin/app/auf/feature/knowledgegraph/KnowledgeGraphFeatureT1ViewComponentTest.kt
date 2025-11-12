package app.auf.feature.knowledgegraph

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Before
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
    fun `selecting a holon displays its rawContent`() = runTest {
        val h1Content = "This is the unique raw holon content."
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "Holon One"), buildJsonObject {}, rawContent = h1Content)
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1", subHolons = listOf(SubHolonRef("h1", "Type_A", ""))), buildJsonObject {})
        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1),
            activePersonaIdForView = "p1",
            activeHolonIdForView = "h1"
        ))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(h1Content).assertExists()
    }

    @Test
    fun `HolonEditView updates payload and dispatches correct action`() = runTest {
        val initialPayload = buildJsonObject { put("key", "old") }
        val h1 = Holon(header = HolonHeader(id = "h1", type = "Type_A", name = "H1"), payload = initialPayload, execute = buildJsonObject {})
        val p1 = Holon(header = HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), payload = buildJsonObject {})

        // [THE FIX] Provide a COMPLETE and logically valid state, including the active persona.
        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("h1" to h1, "p1" to p1),
            holonIdToEdit = "h1",
            activePersonaIdForView = "p1"
        ))
        composeTestRule.waitForIdle()
        harness.store.processedActions.clear()

        val newPayloadString = """{"key":"new"}"""
        composeTestRule.onNodeWithText("Payload").performTextReplacement(newPayloadString)

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save Changes").performClick()

        composeTestRule.waitForIdle()

        val action = harness.store.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT }
        assertNotNull(action)
        assertEquals("h1", action.payload?.get("holonId")?.toString()?.trim('"'))
        assertEquals(newPayloadString, action.payload?.get("payload")?.toString())
    }

    @Test
    fun `HolonEditView with invalid JSON does not dispatch action and shows error`() = runTest {
        val h1 = Holon(header = HolonHeader(id = "h1", type = "Type_A", name = "H1"), payload = buildJsonObject {})
        val p1 = Holon(header = HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), payload = buildJsonObject {})

        // [THE FIX] Provide a COMPLETE and logically valid state, including the active persona.
        setupTestWithState(KnowledgeGraphState(
            holons = mapOf("h1" to h1, "p1" to p1),
            holonIdToEdit = "h1",
            activePersonaIdForView = "p1"
        ))
        composeTestRule.waitForIdle()
        harness.store.processedActions.clear()

        composeTestRule.onNodeWithText("Payload").performTextInput("{ \"key\": ")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()

        assertTrue(harness.store.processedActions.none { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT })
        composeTestRule.onNodeWithText("Invalid JSON format in payload.").assertExists()
    }

    @Test
    fun `ImportPane ActionSelector should only show actions from the availableActions list`() = runTest {
        val importItem = ImportItem(
            sourcePath = "quarantined.json",
            initialAction = Quarantine("Test reason"),
            targetPath = null,
            availableActions = listOf(ImportActionType.QUARANTINE, ImportActionType.ASSIGN_PARENT, ImportActionType.IGNORE)
        )
        setupTestWithState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importItems = listOf(importItem),
            importSelectedActions = mapOf("quarantined.json" to importItem.initialAction),
            importFileContents = mapOf("quarantined.json" to "{}")
        ))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Change Action Type").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Quarantine (fix later)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Orphan - select parent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ignore - Do nothing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update existing holon").assertDoesNotExist()
    }
}