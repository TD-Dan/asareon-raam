package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for KnowledgeGraphFeature.
 *
 * Mandate (P-TEST-001, T1): To test the reducer's state integrity in complete isolation.
 * These tests focus on pure state transformations and do not involve side effects.
 */
class KnowledgeGraphFeatureT1ReducerTest {
    private lateinit var harness: TestHarness
    private lateinit var feature: KnowledgeGraphFeature
    private val json = Json
    // FIX: A self-contained scope for the test class, as the reducer doesn't use it anyway.
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private val sampleGraph1 = HolonKnowledgeGraph(id = "graph-1", name = "Graph One")
    private val sampleGraph2 = HolonKnowledgeGraph(id = "graph-2", name = "Graph Two")
    private val sampleHolon1 = Holon(id = "holon-a", type = "Test", name = "Holon A")
    private val sampleHolon2 = Holon(id = "holon-b", type = "Test", name = "Holon B")

    @BeforeTest
    fun setup() {
        val platform = FakePlatformDependencies("test")
        // FIX: Instantiate the feature first with its own scope.
        feature = KnowledgeGraphFeature(platform, testScope)
        // FIX: The harness can now be built with the feature instance.
        harness = TestEnvironment.create()
            .withFeature(feature)
            .build(platform = platform)
    }

    private fun createInitialState(
        graphs: List<HolonKnowledgeGraph> = emptyList(),
        activeGraphId: String? = null,
        activeHolonId: String? = null
    ): AppState {
        return AppState(
            featureStates = mapOf("knowledgegraph" to KnowledgeGraphState(
                graphs = graphs,
                activeGraphId = activeGraphId,
                activeHolonId = activeHolonId
            ))
        )
    }

    @Test
    fun `reducer on SELECT_GRAPH_SCOPE should set active graph and clear active holon`() {
        val initialState = createInitialState(
            graphs = listOf(sampleGraph1, sampleGraph2),
            activeGraphId = "graph-1",
            activeHolonId = "some-holon"
        )
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SELECT_GRAPH_SCOPE, buildJsonObject { put("graphId", "graph-2") })

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        assertEquals("graph-2", newFeatureState.activeGraphId)
        assertNull(newFeatureState.activeHolonId, "Selecting a new graph scope must clear the active holon.")
    }

    @Test
    fun `reducer on SELECT_HOLON should set active holon`() {
        val initialState = createInitialState(activeHolonId = "holon-a")
        val action = Action(ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON, buildJsonObject { put("holonId", "holon-b") })

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        assertEquals("holon-b", newFeatureState.activeHolonId)
    }

    @Test
    fun `reducer on INTERNAL_GRAPH_LOADED should add a new graph`() {
        val initialState = createInitialState(graphs = listOf(sampleGraph1))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED, json.encodeToJsonElement(sampleGraph2) as JsonObject)

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        assertEquals(2, newFeatureState.graphs.size)
        assertTrue(newFeatureState.graphs.any { it.id == "graph-2" })
    }

    @Test
    fun `reducer on INTERNAL_GRAPH_LOADED should update an existing graph`() {
        val updatedGraph1 = sampleGraph1.copy(name = "Updated Name")
        val initialState = createInitialState(graphs = listOf(sampleGraph1, sampleGraph2))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED, json.encodeToJsonElement(updatedGraph1) as JsonObject)

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        assertEquals(2, newFeatureState.graphs.size)
        assertEquals("Updated Name", newFeatureState.graphs.find { it.id == "graph-1" }?.name)
    }

    @Test
    fun `reducer on DELETE_GRAPH should remove the specified graph`() {
        val initialState = createInitialState(graphs = listOf(sampleGraph1, sampleGraph2))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_DELETE_GRAPH, buildJsonObject { put("graphId", "graph-1") })

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        assertEquals(1, newFeatureState.graphs.size)
        assertEquals("graph-2", newFeatureState.graphs.first().id)
    }

    @Test
    fun `reducer on INTERNAL_HOLON_LOADED should add a holon to the active graph`() {
        val graphWithOneHolon = sampleGraph1.copy(holons = listOf(sampleHolon1))
        val initialState = createInitialState(graphs = listOf(graphWithOneHolon), activeGraphId = "graph-1")
        val action = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(sampleHolon2) as JsonObject)

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        val updatedGraph = newFeatureState.graphs.find { it.id == "graph-1" }
        assertNotNull(updatedGraph)
        assertEquals(2, updatedGraph.holons.size)
        assertTrue(updatedGraph.holons.any { it.id == "holon-b" })
    }

    @Test
    fun `reducer on DELETE_HOLON should remove a holon from the specified graph`() {
        val graphWithHolons = sampleGraph1.copy(holons = listOf(sampleHolon1, sampleHolon2))
        val initialState = createInitialState(graphs = listOf(graphWithHolons))
        val action = Action(ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject {
            put("graphId", "graph-1")
            put("holonId", "holon-a")
        })

        val newState = feature.reducer(initialState, action)
        val newFeatureState = newState.featureStates["knowledgegraph"] as? KnowledgeGraphState

        assertNotNull(newFeatureState)
        val updatedGraph = newFeatureState.graphs.find { it.id == "graph-1" }
        assertNotNull(updatedGraph)
        assertEquals(1, updatedGraph.holons.size)
        assertEquals("holon-b", updatedGraph.holons.first().id)
    }
}