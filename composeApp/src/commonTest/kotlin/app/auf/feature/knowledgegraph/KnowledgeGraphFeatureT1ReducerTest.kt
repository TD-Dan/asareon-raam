package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_PERSONA_LOADED, payload)

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
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "p1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertTrue(newState.isLoading)
    }

    @Test
    fun `on INTERNAL_LOAD_FAILED should set the fatalError message and clear isLoading flag`() {
        val initialState = KnowledgeGraphState(isLoading = true)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_FAILED, buildJsonObject { put("error", "Test error") })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertFalse(newState.isLoading)
        assertEquals("Test error", newState.fatalError)
    }

    // --- Reducer - Reservation Logic ---

    @Test
    fun `RESERVE_HKG should add a persona to agent mapping to the reservations map`() {
        val initialState = KnowledgeGraphState(reservations = emptyMap())
        val action = Action(
            name = ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
            originator = "agent-alpha",
            payload = buildJsonObject { put("personaId", "persona-1") }
        )

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(1, newState.reservations.size)
        assertEquals("agent-alpha", newState.reservations["persona-1"])
    }

    @Test
    fun `RELEASE_HKG should remove a persona mapping from the reservations map`() {
        val initialState = KnowledgeGraphState(
            reservations = mapOf("persona-1" to "agent-alpha", "persona-2" to "agent-beta")
        )
        val action = Action(
            name = ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
            payload = buildJsonObject { put("personaId", "persona-1") }
        )

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(1, newState.reservations.size)
        assertFalse(newState.reservations.containsKey("persona-1"))
        assertEquals("agent-beta", newState.reservations["persona-2"])
    }


    // --- Reducer - UI State Management ---

    @Test
    fun `on SET_VIEW_MODE should correctly switch the viewMode`() {
        val initialState = KnowledgeGraphState(viewMode = KnowledgeGraphViewMode.INSPECTOR)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.IMPORT.name) })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(KnowledgeGraphViewMode.IMPORT, newState.viewMode)
    }

    @Test
    fun `on SET_ACTIVE_VIEW_HOLON should set active holon and clear edit mode`() {
        val initialState = KnowledgeGraphState(holonIdToEdit = "h1")
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON, buildJsonObject { put("holonId", "h2") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h2", newState.activeHolonIdForView)
        assertNull(newState.holonIdToEdit)
    }

    @Test
    fun `on SET_HOLON_TO_EDIT should set the holonIdToEdit`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h1", newState.holonIdToEdit)
    }

    @Test
    fun `on SET_HOLON_TO_RENAME should set the holonIdToRename`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("h1", newState.holonIdToRename)
    }

    @Test
    fun `on TOGGLE_SHOW_SUMMARIES should toggle the flag`() {
        val initialState = KnowledgeGraphState(showSummariesInTreeView = true)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES)
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertFalse(newState.showSummariesInTreeView)
    }

    @Test
    fun `on SET_TYPE_FILTERS should update the active filters`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_TYPE_FILTERS, buildJsonObject {
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
            personaRoots = mapOf("P1" to "p1", "P2" to "p2")
        )
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_PERSONA, buildJsonObject { put("personaId", "p1") })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertFalse(newState.holons.containsKey("p1"))
        assertFalse(newState.holons.containsKey("h1"))
        assertFalse(newState.holons.containsKey("h2"))
        assertTrue(newState.holons.containsKey("p2"))
        assertFalse(newState.personaRoots.containsKey("P1"))
        assertTrue(newState.personaRoots.containsKey("P2"))
    }

    // --- Reducer - Import Workflow ---
    @Test
    fun `UPDATE_IMPORT_ACTION should trigger cascading demotion of children`() {
        // [FIX] Use valid timestamped IDs to pass strict validation in runImportAnalysis
        val parentId = "parent-20251112T100000Z"
        val childId = "child-20251112T100000Z"
        val parentFile = "$parentId.json"
        val childFile = "$childId.json"

        val parentContent = """{ "header": { "id": "$parentId", "type": "T", "name": "P", "sub_holons": [{"id": "$childId", "type": "T", "summary": "S"}] }, "payload": {} }"""
        val childContent = """{ "header": { "id": "$childId", "type": "T", "name": "C" }, "payload": {} }"""

        val initialState = KnowledgeGraphState(
            importFileContents = mapOf(parentFile to parentContent, childFile to childContent),
            importSelectedActions = mapOf(
                parentFile to Integrate("root-20251112T000000Z"),
                childFile to Integrate(parentId)
            )
        )

        // User ignores the parent
        // [FIX] Explicitly serialize as ImportAction to include class discriminator for polymorphic deserialization
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION, buildJsonObject {
            put("sourcePath", parentFile)
            put("action", json.encodeToJsonElement<ImportAction>(Ignore()))
        })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        // Assert the user's override was respected
        assertIs<Ignore>(newState.importUserOverrides[parentFile])
        // Assert the child was automatically quarantined
        val childAction = newState.importSelectedActions[childFile]
        assertIs<Quarantine>(childAction)
        assertEquals("Parent holon '$parentId' is not being imported.", childAction.reason)
    }
}