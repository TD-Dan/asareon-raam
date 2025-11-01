package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for KnowledgeGraphFeature's reducer.
 *
 * Mandate (P-TEST-001, T1): To test the reducer as a pure function in complete isolation.
 * These tests focus on pure state transformations and do not involve side effects.
 */
class KnowledgeGraphFeatureT1ReducerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val feature = KnowledgeGraphFeature(
        platformDependencies = app.auf.fakes.FakePlatformDependencies("test"),
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )
    private val featureName = feature.name

    private fun createAppState(kgState: KnowledgeGraphState = KnowledgeGraphState()) = AppState(
        featureStates = mapOf(featureName to kgState)
    )

    private val samplePersonaHeader = HolonHeader(id = "persona-1", type = "AI_Persona_Root", name = "Persona One")
    private val sampleHolon = Holon(
        header = HolonHeader(id = "holon-a", type = "Test_Holon", name = "Holon A"),
        payload = buildJsonObject {},
        content = "{}"
    )

    // --- Reducer - Loading & State Hydration ---

    @Test
    fun `on INTERNAL_PERSONA_DISCOVERED should add a new persona root`() {
        val initialState = createAppState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_DISCOVERED, json.encodeToJsonElement(samplePersonaHeader) as JsonObject)

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals(1, newKgState.personaRoots.size)
        assertEquals("persona-1", newKgState.personaRoots["Persona One"])
    }

    @Test
    fun `on INTERNAL_HOLON_LOADED should add a new holon to the state map`() {
        val initialState = createAppState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(sampleHolon) as JsonObject)

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals(1, newKgState.holons.size)
        assertNotNull(newKgState.holons["holon-a"])
        assertEquals("Holon A", newKgState.holons["holon-a"]?.header?.name)
        assertFalse(newKgState.isLoading, "isLoading should be cleared after a holon is loaded.")
    }

    @Test
    fun `on INTERNAL_LOAD_FAILED should set the fatalError message and clear isLoading flag`() {
        val initialState = createAppState(KnowledgeGraphState(isLoading = true))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED, buildJsonObject { put("error", "Test error") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertFalse(newKgState.isLoading)
        assertEquals("Test error", newKgState.fatalError)
    }

    // --- Reducer - UI State Management ---

    @Test
    fun `on SET_VIEW_MODE should correctly switch the viewMode`() {
        val initialState = createAppState(KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.INSPECTOR))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals(KnowledgeGraphViewMode.IMPORT, newKgState.viewMode)
    }

    @Test
    fun `on SET_ACTIVE_VIEW_PERSONA should set active persona and clear active holon`() {
        val initialState = createAppState(KnowledgeGraphState(activePersonaIdForView = "old-persona", activeHolonIdForView = "old-holon"))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA, buildJsonObject { put("personaId", "new-persona") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals("new-persona", newKgState.activePersonaIdForView)
        assertNull(newKgState.activeHolonIdForView, "Active holon should be cleared when persona changes.")
    }

    @Test
    fun `on SET_ACTIVE_VIEW_HOLON should set the active holon ID`() {
        val initialState = createAppState(KnowledgeGraphState(activeHolonIdForView = "old-holon"))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", "new-holon") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals("new-holon", newKgState.activeHolonIdForView)
    }

    // --- Reducer - Import Workflow State ---

    @Test
    fun `on START_IMPORT_ANALYSIS should set isLoading, set the source path, and clear old import items`() {
        val oldItem = ImportItem("/old/path", Ignore(), null)
        val initialState = createAppState(KnowledgeGraphState(importItems = listOf(oldItem)))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", "/new/path") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertTrue(newKgState.isLoading)
        assertEquals("/new/path", newKgState.importSourcePath)
        assertTrue(newKgState.importItems.isEmpty())
        assertTrue(newKgState.importSelectedActions.isEmpty())
    }

    @Test
    fun `on INTERNAL_ANALYSIS_COMPLETE should populate importItems and clear isLoading`() {
        val newItem = ImportItem("/new/path", Update("target-id"), "/target/path")
        val initialState = createAppState(KnowledgeGraphState(isLoading = true))
        val payload = buildJsonObject {
            put("items", json.encodeToJsonElement(listOf(newItem)))
            put("contents", buildJsonObject { put("/new/path", "{}") })
        }
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, payload)

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertFalse(newKgState.isLoading)
        assertEquals(1, newKgState.importItems.size)
        assertEquals(1, newKgState.importSelectedActions.size)
        assertEquals(Update("target-id"), newKgState.importSelectedActions["/new/path"])
        assertEquals("{}", newKgState.importFileContents["/new/path"])
    }

    @Test
    fun `on UPDATE_IMPORT_ACTION should update the action for a single item in importSelectedActions`() {
        val initialState = createAppState(KnowledgeGraphState(
            importSelectedActions = mapOf("/path/1" to Update("id1"), "/path/2" to Ignore())
        ))
        val payload = buildJsonObject {
            put("sourcePath", "/path/1")
            put("action", json.encodeToJsonElement(Quarantine("test reason") as ImportAction))
        }
        val action = Action(ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION, payload)

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals(2, newKgState.importSelectedActions.size)
        assertEquals(Quarantine("test reason"), newKgState.importSelectedActions["/path/1"])
        assertEquals(Ignore(), newKgState.importSelectedActions["/path/2"])
    }

    @Test
    fun `on SET_IMPORT_RECURSIVE should correctly toggle the isImportRecursive flag`() {
        val initialState = createAppState(KnowledgeGraphState(isImportRecursive = true))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE, buildJsonObject { put("recursive", false) })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertFalse(newKgState.isImportRecursive)
        assertTrue(newKgState.isLoading, "Changing recursive flag should trigger a new analysis.")
    }

    @Test
    fun `on TOGGLE_SHOW_ONLY_CHANGED should correctly toggle the showOnlyChangedImportItems flag`() {
        val initialState = createAppState(KnowledgeGraphState(showOnlyChangedImportItems = false))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED)

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertTrue(newKgState.showOnlyChangedImportItems)
    }

    // --- Reducer - Deletion Workflow ---
    @Test
    fun `on SET_PERSONA_TO_DELETE should set the personaIdToDelete`() {
        val initialState = createAppState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE, buildJsonObject { put("personaId", "p1") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertEquals("p1", newKgState.personaIdToDelete)
    }

    @Test
    fun `on CONFIRM_DELETE_PERSONA should remove the persona and all its descendant holons`() {
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "P1", subHolons = listOf(SubHolonRef("h1", "T", "S"))), buildJsonObject {})
        val h1 = Holon(HolonHeader("h1", "T", "H1", subHolons = listOf(SubHolonRef("h2", "T", "S"))), buildJsonObject {})
        val h2 = Holon(HolonHeader("h2", "T", "H2"), buildJsonObject {})
        val p2 = Holon(HolonHeader("p2", "AI_Persona_Root", "P2"), buildJsonObject {})

        val initialState = createAppState(KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1, "h2" to h2, "p2" to p2),
            personaRoots = mapOf("P1" to "p1", "P2" to "p2"),
            activePersonaIdForView = "p1"
        ))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA, buildJsonObject { put("personaId", "p1") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertFalse(newKgState.holons.containsKey("p1"))
        assertFalse(newKgState.holons.containsKey("h1"))
        assertFalse(newKgState.holons.containsKey("h2"))
        assertTrue(newKgState.holons.containsKey("p2"))
        assertFalse(newKgState.personaRoots.containsKey("P1"))
        assertTrue(newKgState.personaRoots.containsKey("P2"))
        assertNull(newKgState.activePersonaIdForView, "Active view should be cleared if the deleted persona was active.")
    }

    @Test
    fun `on SET_HOLON_TO_DELETE should set the holonIdToDelete`() {
        val initialState = createAppState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState
        assertEquals("h1", newKgState.holonIdToDelete)
    }

    @Test
    fun `on CONFIRM_DELETE_HOLON should remove holon and update parent`() {
        val h1 = Holon(HolonHeader("h1", "T", "H1", subHolons = listOf(SubHolonRef("h2", "T", "S"))), buildJsonObject {})
        val h2 = Holon(HolonHeader("h2", "T", "H2", parentId = "h1"), buildJsonObject {})
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "P1", subHolons = listOf(SubHolonRef("h1", "T", "S"))), buildJsonObject {})

        val initialState = createAppState(KnowledgeGraphState(holons = mapOf("p1" to p1, "h1" to h1, "h2" to h2)))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON, buildJsonObject { put("holonId", "h1") })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertFalse(newKgState.holons.containsKey("h1"), "Deleted holon should be removed.")
        assertFalse(newKgState.holons.containsKey("h2"), "Descendant holon should be removed.")
        assertTrue(newKgState.holons.containsKey("p1"), "Parent should still exist.")
        val updatedParent = newKgState.holons["p1"]!!
        assertTrue(updatedParent.header.subHolons.isEmpty(), "Parent's sub_holons list should be updated.")
    }

    // --- Reducer - Creation Workflow ---
    @Test
    fun `on SET_CREATING_PERSONA should toggle isCreatingPersona flag`() {
        val initialState = createAppState(KnowledgeGraphState(isCreatingPersona = false))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA, buildJsonObject { put("isCreating", true) })

        val newState = feature.reducer(initialState, action)
        val newKgState = newState.featureStates[featureName] as KnowledgeGraphState

        assertTrue(newKgState.isCreatingPersona)
    }
}