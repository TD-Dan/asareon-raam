package app.auf.feature.knowledgegraph

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.ui.AppTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Component Test for KnowledgeGraphView's ImportPane.
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

    @Test
    fun `given an empty importSourcePath it should display the 'Select a folder' prompt`() {
        setViewState(KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.IMPORT))
        composeTestRule.onNodeWithText("Select a folder to begin analysis.").assertExists()
    }

    @Test
    fun `given a list of importItems it should render an ImportItemRow for each`() {
        val items = listOf(
            ImportItem("/path/to/file1.json", Ignore(), null),
            ImportItem("/path/to/file2.json", Update("id2"), "/target/file2.json")
        )
        setViewState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importSourcePath = "/path/to",
            importItems = items,
            importSelectedActions = items.associate { it.sourcePath to it.initialAction }
        ))

        composeTestRule.onNodeWithText("file1.json").assertExists()
        composeTestRule.onNodeWithText("file2.json").assertExists()
    }

    @Test
    fun `clicking 'Select & Analyze' button should dispatch START_IMPORT_ANALYSIS`() {
        fakePlatform.selectedDirectoryPathToReturn = "/fake/import/dir" // CORRECTED
        setViewState(KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.IMPORT))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Select & Analyze...").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS }
        assertNotNull(action)
        assertEquals("/fake/import/dir", action.payload?.get("path")?.toString()?.trim('"'))
    }

    @Test
    fun `toggling the 'recursive' checkbox should dispatch SET_IMPORT_RECURSIVE`() {
        setViewState(KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.IMPORT, importSourcePath = "/path"))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Import sub-folders recursively").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE }
        assertNotNull(action)
        assertEquals("false", action.payload?.get("recursive")?.toString())
    }

    @Test
    fun `toggling the 'show only changed' checkbox should dispatch TOGGLE_SHOW_ONLY_CHANGED`() {
        setViewState(KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.IMPORT, importSourcePath = "/path"))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Show only changed files").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED }
        assertNotNull(action)
    }

    @Test
    fun `changing an item's action in a dropdown should dispatch UPDATE_IMPORT_ACTION`() {
        val item = ImportItem("/path/to/file1.json", Update("id1"), "/target/file1.json")
        setViewState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importSourcePath = "/path/to",
            importItems = listOf(item),
            importSelectedActions = mapOf(item.sourcePath to item.initialAction)
        ))
        fakeStore.dispatchedActions.clear()

        // 1. Click the text field to expand the menu
        composeTestRule.onNodeWithText("Update existing holon").performClick()
        // 2. Click the new action
        composeTestRule.onNodeWithText("Do not import").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION }
        assertNotNull(action)
        assertEquals("/path/to/file1.json", action.payload?.get("sourcePath")?.toString()?.trim('"'))
        val newAction = action.payload?.get("action")?.let { json.decodeFromJsonElement(ImportAction.serializer(), it) } // CORRECTED
        assertTrue(newAction is Ignore)
    }

    @Test
    fun `clicking 'Execute Import' button should dispatch EXECUTE_IMPORT`() {
        val item = ImportItem("/path/to/file1.json", Ignore(), null)
        setViewState(KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importSourcePath = "/path/to",
            importItems = listOf(item)
        ))
        fakeStore.dispatchedActions.clear()

        composeTestRule.onNodeWithText("Execute Import").performClick()

        val action = fakeStore.dispatchedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT }
        assertNotNull(action)
    }
}