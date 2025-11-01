package app.auf.feature.knowledgegraph

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Component Test for KnowledgeGraphView's various components.
 *
 * Mandate (P-TEST-001, T1): To test the UI component's rendering and action dispatching
 * in isolation, using a FakeStore to intercept dispatched actions.
 */
class KnowledgeGraphFeatureT1ViewComponentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeStore: FakeStore
    private val json = Json

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

    // --- Inspector View Tests ---
    @Test
    fun `clicking 'Show Summaries' switch dispatches TOGGLE_SHOW_SUMMARIES`() {
        setViewState(KnowledgeGraphState(activePersonaIdForView = "p1"))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Show Summaries").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES }
        assertNotNull(action)
    }

    @Test
    fun `clicking a filter chip dispatches SET_TYPE_FILTERS`() {
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), buildJsonObject {})
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "H1"), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1),
            personaRoots = mapOf("P1" to "p1"),
            activePersonaIdForView = "p1"
        ))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Type_A").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_SET_TYPE_FILTERS }
        assertNotNull(action)
        assertEquals("""{"types":["Type_A"]}""", action.payload.toString())
    }

    @Test
    fun `clicking Edit button dispatches SET_HOLON_TO_EDIT`() {
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "H1", summary = "summary"), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("h1" to h1),
            activeHolonIdForView = "h1"
        ))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Edit").performClick()
        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT }
        assertNotNull(action)
        assertEquals("h1", action.payload?.get("holonId")?.toString()?.trim('"'))
    }

    @Test
    fun `when holonIdToEdit is set HolonEditView is shown`() {
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "H1", summary = "summary"), buildJsonObject {}, content = "Test Content")
        setViewState(KnowledgeGraphState(
            holons = mapOf("h1" to h1),
            holonIdToEdit = "h1"
        ))
        composeTestRule.onNodeWithText("Editing: H1").assertExists()
        composeTestRule.onNodeWithText("Test Content").assertExists() // Checks if the TextField has the content
    }

    @Test
    fun `clicking Save in HolonEditView dispatches UPDATE_HOLON_CONTENT`() {
        val h1 = Holon(HolonHeader(id = "h1", type = "Type_A", name = "H1", summary = "summary"), buildJsonObject {}, content = "Old")
        setViewState(KnowledgeGraphState(
            holons = mapOf("h1" to h1),
            holonIdToEdit = "h1"
        ))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Old").performTextInput(" New")
        composeTestRule.onNodeWithText("Save Changes").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT }
        assertNotNull(action)
        assertEquals("h1", action.payload?.get("holonId")?.toString()?.trim('"'))
        assertEquals("Old New", action.payload?.get("newContent")?.toString()?.trim('"'))
    }

    @Test
    fun `Rename and Delete buttons are hidden for AI_Persona_Root`() {
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("p1" to p1),
            activeHolonIdForView = "p1"
        ))

        composeTestRule.onNodeWithText("Rename").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    // --- Deletion Workflow UI Tests ---
    @Test
    fun `clicking delete in kebab menu dispatches SET_PERSONA_TO_DELETE`() {
        // FIX: Use named arguments for HolonHeader constructor
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("p1" to p1),
            personaRoots = mapOf("P1" to "p1"),
            activePersonaIdForView = "p1"
        ))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Delete Persona").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE }
        assertNotNull(action)
        assertEquals("p1", action.payload?.get("personaId")?.toString()?.trim('"'))
    }

    @Test
    fun `when personaIdToDelete is set, AlertDialog is shown`() {
        // FIX: Use named arguments for HolonHeader constructor
        val p1 = Holon(HolonHeader(id = "p1", type = "AI_Persona_Root", name = "P1"), buildJsonObject {})
        setViewState(KnowledgeGraphState(
            holons = mapOf("p1" to p1),
            personaIdToDelete = "p1"
        ))

        composeTestRule.onNodeWithText("Delete Persona?").assertExists()
        composeTestRule.onNodeWithText("Are you sure you want to permanently delete 'P1'? This action cannot be undone.").assertExists()
    }
}