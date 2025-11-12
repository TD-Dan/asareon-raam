package app.auf.feature.knowledgegraph

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

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private val json = Json { prettyPrint = true }

    @Before
    fun setUp() {
        fakePlatform = FakePlatformDependencies("test")
        fakeStore = FakeStore(AppState(), fakePlatform, ActionNames.allActionNames)
    }

    private fun setViewState(state: KnowledgeGraphState) {
        val appState = AppState(featureStates = mapOf("knowledgegraph" to state))
        fakeStore.setState(appState)

        composeTestRule.setContent {
            AppTheme {
                KnowledgeGraphView(
                    store = fakeStore,
                    platformDependencies = fakePlatform
                )
            }
        }
    }

    @Test
    fun `selecting a holon displays its rawContent`() {
        val h1Content = "This is the unique raw holon content."
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "Holon One"), buildJsonObject {}, rawContent = h1Content)
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1", subHolons = listOf(SubHolonRef("h1", "Type_A", ""))), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1),
            activePersonaIdForView = "p1"
        ).copy(activeHolonIdForView = "h1"))

        composeTestRule.onNodeWithText(h1Content).assertExists()
    }

    @Test
    fun `HolonEditView updates payload and dispatches correct action`() {
        val initialPayload = buildJsonObject { put("key", "old") }
        val h1 = Holon(
            header = HolonHeader(id = "h1", type = "Type_A", name = "H1"),
            payload = initialPayload,
            execute = buildJsonObject {}
        )
        setViewState(KnowledgeGraphState(
            holons = mapOf("h1" to h1),
            holonIdToEdit = "h1"
        ))
        fakeStore.dispatchedActions.clear()

        val newPayloadString = """{ "key": "new" }"""
        composeTestRule.onNodeWithText("Payload").performTextInput(newPayloadString)
        composeTestRule.onNodeWithText("Save Changes").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT }
        assertNotNull(action)
        assertEquals("h1", action.payload?.get("holonId")?.toString()?.trim('"'))
        assertEquals(newPayloadString, action.payload?.get("payload")?.toString())
    }

    @Test
    fun `HolonEditView with invalid JSON does not dispatch action and shows error`() {
        val h1 = Holon(header = HolonHeader(id = "h1", type = "Type_A", name = "H1"), payload = buildJsonObject {})
        setViewState(KnowledgeGraphState(holons = mapOf("h1" to h1), holonIdToEdit = "h1"))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Payload").performTextInput("{ \"key\": ") // Invalid JSON
        composeTestRule.onNodeWithText("Save Changes").performClick()

        // Assert that NO action was dispatched
        assertTrue(fakeStore.dispatchedActions.none { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT })

        // Assert that an error message is shown
        composeTestRule.onNodeWithText("Invalid JSON format in payload.").assertExists()
    }

    @Test
    fun `ImportPane ActionSelector should only show actions from the availableActions list`() {
        // ARRANGE: Create a state where the analyzer has provided a limited set of actions.
        val importItem = ImportItem(
            sourcePath = "quarantined.json",
            initialAction = Quarantine("Test reason"),
            targetPath = null,
            // The analyzer has determined only these 3 actions are valid for this item.
            availableActions = listOf(ImportActionType.QUARANTINE, ImportActionType.ASSIGN_PARENT, ImportActionType.IGNORE)
        )
        setViewState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importItems = listOf(importItem),
            importSelectedActions = mapOf("quarantined.json" to importItem.initialAction),
            importFileContents = mapOf("quarantined.json" to "{}")
        ))

        // ACT: Find the dropdown for our item and open it.
        // The "More" icon is the only way to open the menu for a ParentSelector.
        composeTestRule.onNodeWithContentDescription("Change Action Type").performClick()

        // ASSERT:
        // Check that the valid options are present.
        composeTestRule.onNodeWithText("Quarantine (fix later)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Orphan - select parent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ignore - Do nothing").assertIsDisplayed()

        // Check that an INVALID option (which the old UI would show) is NOT present.
        // This is the assertion we expect to FAIL.
        composeTestRule.onNodeWithText("Update existing holon").assertDoesNotExist()
    }
}