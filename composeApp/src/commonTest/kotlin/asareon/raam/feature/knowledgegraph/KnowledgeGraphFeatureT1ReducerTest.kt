package asareon.raam.feature.knowledgegraph

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
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
        platformDependencies = asareon.raam.fakes.FakePlatformDependencies("test"),
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

    // ========================================================================================
    // NEW TESTS — Reducer coverage gaps identified in Task 3
    // ========================================================================================

    @Test
    fun `SET_IMPORT_EXECUTION_STATUS should set the isExecutingImport flag`() {
        val initialState = KnowledgeGraphState(isExecutingImport = false)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_EXECUTION_STATUS, buildJsonObject {
            put("isExecuting", true)
        })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertTrue(newState.isExecutingImport)

        // And toggle back
        val action2 = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_EXECUTION_STATUS, buildJsonObject {
            put("isExecuting", false)
        })
        val newState2 = feature.reducer(newState, action2) as KnowledgeGraphState
        assertFalse(newState2.isExecutingImport)
    }

    @Test
    fun `SET_PENDING_IMPORT_ID should set the pendingImportCorrelationId`() {
        val initialState = KnowledgeGraphState()
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PENDING_IMPORT_ID, buildJsonObject {
            put("id", "corr-12345")
        })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertEquals("corr-12345", newState.pendingImportCorrelationId)
    }

    @Test
    fun `TOGGLE_HOLON_EXPANDED should collapse then expand a holon`() {
        val initialState = KnowledgeGraphState(collapsedHolonIds = emptySet())

        // First toggle: collapse
        val collapse = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_HOLON_EXPANDED, buildJsonObject { put("holonId", "h1") })
        val collapsed = feature.reducer(initialState, collapse) as KnowledgeGraphState
        assertTrue(collapsed.collapsedHolonIds.contains("h1"), "Holon should be collapsed after first toggle.")

        // Second toggle: expand
        val expanded = feature.reducer(collapsed, collapse) as KnowledgeGraphState
        assertFalse(expanded.collapsedHolonIds.contains("h1"), "Holon should be expanded after second toggle.")
    }

    @Test
    fun `SET_VIEW_MODE to INSPECTOR should reset all import state`() {
        val initialState = KnowledgeGraphState(
            viewMode = KnowledgeGraphViewMode.IMPORT,
            importItems = listOf(ImportItem("file.json", Ignore(), null, null)),
            importSelectedActions = mapOf("file.json" to Ignore()),
            importUserOverrides = mapOf("file.json" to Ignore()),
            importFileContents = mapOf("file.json" to "{}"),
            pendingImportCorrelationId = "corr-1",
            isExecutingImport = true
        )

        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject {
            put("mode", KnowledgeGraphViewMode.INSPECTOR.name)
        })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertEquals(KnowledgeGraphViewMode.INSPECTOR, newState.viewMode)
        assertTrue(newState.importItems.isEmpty(), "Import items should be cleared on switch to INSPECTOR.")
        assertTrue(newState.importSelectedActions.isEmpty(), "Import selected actions should be cleared.")
        assertTrue(newState.importUserOverrides.isEmpty(), "Import user overrides should be cleared.")
        assertTrue(newState.importFileContents.isEmpty(), "Import file contents should be cleared.")
        assertNull(newState.pendingImportCorrelationId, "Pending import ID should be cleared.")
        assertFalse(newState.isExecutingImport, "Import execution flag should be cleared.")
    }

    @Test
    fun `START_IMPORT_ANALYSIS should reset import state and set loading`() {
        val initialState = KnowledgeGraphState(
            isLoading = false,
            importItems = listOf(ImportItem("old.json", Ignore(), null, null)),
            importSelectedActions = mapOf("old.json" to Ignore()),
            importUserOverrides = mapOf("old.json" to Ignore()),
            importFileContents = mapOf("old.json" to "{}")
        )

        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS)

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertTrue(newState.isLoading, "Should set isLoading.")
        assertTrue(newState.importItems.isEmpty(), "Import items should be cleared.")
        assertTrue(newState.importSelectedActions.isEmpty())
        assertTrue(newState.importUserOverrides.isEmpty())
        assertTrue(newState.importFileContents.isEmpty())
    }

    @Test
    fun `SET_IMPORT_RECURSIVE should update flag and set loading`() {
        val initialState = KnowledgeGraphState(isImportRecursive = true, isLoading = false)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE, buildJsonObject {
            put("recursive", false)
        })

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        assertFalse(newState.isImportRecursive, "Recursive flag should be updated.")
        assertTrue(newState.isLoading, "Should set isLoading for re-analysis.")
    }

    @Test
    fun `TOGGLE_SHOW_ONLY_CHANGED should toggle the filter flag`() {
        val initialState = KnowledgeGraphState(showOnlyChangedImportItems = false)
        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED)

        val newState = feature.reducer(initialState, action) as KnowledgeGraphState
        assertTrue(newState.showOnlyChangedImportItems)

        val newState2 = feature.reducer(newState, action) as KnowledgeGraphState
        assertFalse(newState2.showOnlyChangedImportItems)
    }

    @Test
    fun `CONFIRM_DELETE_HOLON should remove holon and descendants and update parent sub_holons`() {
        val p1 = Holon(
            HolonHeader("p1", "AI_Persona_Root", "Root",
                subHolons = listOf(SubHolonRef("h1", "T", "S"), SubHolonRef("h3", "T", "Other"))),
            buildJsonObject {}
        )
        val h1 = Holon(
            HolonHeader("h1", "T", "H1", parentId = "p1",
                subHolons = listOf(SubHolonRef("h2", "T", "S"))),
            buildJsonObject {}
        )
        val h2 = Holon(HolonHeader("h2", "T", "H2", parentId = "h1"), buildJsonObject {})
        val h3 = Holon(HolonHeader("h3", "T", "Other", parentId = "p1"), buildJsonObject {})

        val initialState = KnowledgeGraphState(
            holons = mapOf("p1" to p1, "h1" to h1, "h2" to h2, "h3" to h3),
            activeHolonIdForView = "h1",
            holonIdToDelete = "h1"
        )

        val action = Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_HOLON, buildJsonObject { put("holonId", "h1") })
        val newState = feature.reducer(initialState, action) as KnowledgeGraphState

        // h1 and h2 (descendant) should be removed
        assertFalse(newState.holons.containsKey("h1"), "Deleted holon should be removed.")
        assertFalse(newState.holons.containsKey("h2"), "Descendant of deleted holon should be removed.")

        // p1 and h3 should survive
        assertTrue(newState.holons.containsKey("p1"), "Parent should survive.")
        assertTrue(newState.holons.containsKey("h3"), "Sibling should survive.")

        // Parent's sub_holons should be updated to only contain h3
        val updatedParent = newState.holons["p1"]!!
        assertEquals(1, updatedParent.header.subHolons.size)
        assertEquals("h3", updatedParent.header.subHolons.first().id)

        // Active holon was h1 (deleted) — should be cleared
        assertNull(newState.activeHolonIdForView, "Active view should be cleared when the active holon is deleted.")

        // holonIdToDelete should be cleared
        assertNull(newState.holonIdToDelete, "holonIdToDelete should be cleared after confirmation.")
    }
}