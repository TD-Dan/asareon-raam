package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.FeatureState
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

    private val samplePersonaContent = """
        {
            "header": { "id": "persona-1", "type": "AI_Persona_Root", "name": "Persona One" }, "payload": {}
        }
    """.trimIndent()
    private val sampleHolonContent = """
        {
            "header": { "id": "holon-a", "type": "Test_Holon", "name": "Holon A" }, "payload": {}
        }
    """.trimIndent()


    // --- Reducer - Loading & State Hydration (Hardened Protocol) ---

    @Test
    fun `on PERSONA_LOADED should atomically merge holons and update roots`() {
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "P1"), buildJsonObject {})
        val h1 = Holon(HolonHeader("h1", "T", "H1", parentId = "p1", depth = 1), buildJsonObject {})
        val existingP2 = Holon(HolonHeader("p2", "AI_Persona_Root", "P2"), buildJsonObject {})

        val initialState = KnowledgeGraphState(
            holons = mapOf("p2" to existingP2),
            personaRoots = mapOf("P2" to "p2")
        )
        val payload = buildJsonObject {
            put("holons", json.encodeToJsonElement(mapOf("p1" to p1, "h1" to h1)))
        }
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED, payload)

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(3, newState.holons.size)
        assertTrue(newState.holons.containsKey("p1"))
        assertTrue(newState.holons.containsKey("h1"))
        assertTrue(newState.holons.containsKey("p2"))
        assertEquals(2, newState.personaRoots.size)
        assertEquals("p1", newState.personaRoots["P1"])
        assertFalse(newState.isLoading)
    }

    @Test
    fun `on LOAD_PERSONA should set isLoading flag`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "p1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertTrue(newState.isLoading)
    }

    @Test
    fun `on INTERNAL_LOAD_FAILED should set the fatalError message and clear isLoading flag`() {
        val initialState = KnowledgeGraphState(isLoading = true)
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED, buildJsonObject { put("error", "Test error") })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertFalse(newState.isLoading)
        assertEquals("Test error", newState.fatalError)
    }

    // --- Reducer - UI State Management ---

    @Test
    fun `on SET_VIEW_MODE should correctly switch the viewMode`() {
        val initialState = KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.INSPECTOR)
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(KnowledgeGraphViewMode.IMPORT, newState.viewMode)
    }

    @Test
    fun `on SET_ACTIVE_VIEW_PERSONA should set active persona, clear active holon, and clear filters`() {
        val initialState = KnowledgeGraphState(
            activePersonaIdForView = "old-persona",
            activeHolonIdForView = "old-holon",
            activeTypeFilters = setOf("Test")
        )
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA, buildJsonObject { put("personaId", "new-persona") })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals("new-persona", newState.activePersonaIdForView)
        assertNull(newState.activeHolonIdForView, "Active holon should be cleared when persona changes.")
        assertTrue(newState.activeTypeFilters.isEmpty(), "Filters should be reset on persona change.")
    }

    @Test
    fun `on SET_ACTIVE_VIEW_HOLON should set active holon and clear edit mode`() {
        val initialState = KnowledgeGraphState(holonIdToEdit = "h1")
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", "h2") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h2", newState.activeHolonIdForView)
        assertNull(newState.holonIdToEdit)
    }

    @Test
    fun `on SET_HOLON_TO_EDIT should set the holonIdToEdit`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h1", newState.holonIdToEdit)
    }

    @Test
    fun `on SET_HOLON_TO_RENAME should set the holonIdToRename`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h1", newState.holonIdToRename)
    }

    @Test
    fun `on TOGGLE_SHOW_SUMMARIES should toggle the flag`() {
        val initialState = KnowledgeGraphState(showSummariesInTreeView = true)
        val action = Action(ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES)
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertFalse(newState.showSummariesInTreeView)
    }

    @Test
    fun `on SET_TYPE_FILTERS should update the active filters`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SET_TYPE_FILTERS, buildJsonObject {
            put("types", json.encodeToJsonElement(setOf("Type1", "Type2")))
        })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals(setOf("Type1", "Type2"), newState.activeTypeFilters)
    }

    // --- Reducer - Deletion Workflow ---
    @Test
    fun `on CONFIRM_DELETE_PERSONA should remove the persona and all its descendant holons`() {
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "P1", subHolons = listOf(SubHolonRef("h1", "T", "S"))), buildJsonObject {})
        val h1 = Holon(HolonHeader("h1", "T", "H1", subHolons = listOf(SubHolonRef("h2", "T", "S"))), buildJsonObject {})
        val h2 = Holon(HolonHeader("h2", "T", "H2"), buildJsonObject {})
        val p2 = Holon(HolonHeader("p2", "AI_Persona_Root", "P2"), buildJsonObject {})

        val initialState = KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1, "h2" to h2, "p2" to p2),
            personaRoots = mapOf("P1" to "p1", "P2" to "p2"),
            activePersonaIdForView = "p1"
        )
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA, buildJsonObject { put("personaId", "p1") })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertFalse(newState.holons.containsKey("p1"))
        assertFalse(newState.holons.containsKey("h1"))
        assertFalse(newState.holons.containsKey("h2"))
        assertTrue(newState.holons.containsKey("p2"))
        assertFalse(newState.personaRoots.containsKey("P1"))
        assertTrue(newState.personaRoots.containsKey("P2"))
        assertNull(newState.activePersonaIdForView, "Active view should be cleared if the deleted persona was active.")
    }
}