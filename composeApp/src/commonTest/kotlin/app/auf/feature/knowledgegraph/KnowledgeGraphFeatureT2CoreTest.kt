package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for KnowledgeGraphFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction/onPrivateData handlers
 * working together within a realistic TestEnvironment that includes the real Store and a
 * fake platform layer.
 */
class KnowledgeGraphFeatureT2CoreTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private val sampleManifestContent = """
        {
            "id": "graph-1",
            "name": "Sample Graph"
        }
    """.trimIndent()

    private val sampleHolonContent = """
        {
            "header": {
                "id": "holon-a",
                "type": "Test_Holon",
                "name": "Test Holon A",
                "summary": "A sample holon for testing."
            },
            "payload": {}
        }
    """.trimIndent()


    @Test
    fun `on STARTING action feature requests root file system listing`() {
        // FIX: Set the initial lifecycle to INITIALIZING to allow the STARTING action to pass the store's guard.
        val harness = TestEnvironment.create()
            .withFeature(KnowledgeGraphFeature(FakePlatformDependencies("test"), testScope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build()

        harness.store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }
        assertNotNull(listAction, "Feature should dispatch a file system list action on startup.")
        // Subpath should be null for root listing
        assertTrue(listAction.payload?.get("subpath")?.jsonPrimitive?.contentOrNull == null)
    }

    @Test
    fun `CREATE_GRAPH dispatches write to filesystem and updates state optimistically`() {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(KnowledgeGraphFeature(platform, testScope))
            .build(platform = platform)
        platform.uuidCounter = 0 // for predictable ID

        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_CREATE_GRAPH, buildJsonObject { put("name", "New Test Graph") }))

        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "A filesystem write action should be dispatched.")
        assertEquals("fake-uuid-1/graph.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        assertTrue(writeAction.payload?.get("content")?.jsonPrimitive?.content?.contains("New Test Graph") == true)

        val loadAction = harness.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED }
        assertNotNull(loadAction, "An internal graph loaded action should be dispatched optimistically.")

        val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertEquals(1, finalState.graphs.size)
        assertEquals("New Test Graph", finalState.graphs.first().name)
    }

    @Test
    fun `full load sequence correctly populates graphs and holons from filesystem`() {
        val platform = FakePlatformDependencies("test")
        // FIX: Set the initial lifecycle to INITIALIZING to allow the STARTING action to pass the store's guard.
        val harness = TestEnvironment.create()
            .withFeature(KnowledgeGraphFeature(platform, testScope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" } as KnowledgeGraphFeature

        // Arrange: Setup fake file system
        platform.createDirectories("/fake/.auf/v2/knowledgegraph/graph-1")
        platform.writeFileContent("/fake/.auf/v2/knowledgegraph/graph-1/graph.json", sampleManifestContent)
        platform.writeFileContent("/fake/.auf/v2/knowledgegraph/graph-1/holon-a.json", sampleHolonContent)

        // 1. App starts, KG feature requests root listing
        harness.store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        assertNotNull(harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST })

        // 2. Simulate FileSystem response with the graph directory
        val rootListResponse = PrivateDataEnvelope(
            type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST,
            payload = buildJsonObject {
                put("listing", buildJsonArray {
                    add(json.encodeToJsonElement(FileEntry("graph-1", isDirectory = true)))
                })
            }
        )
        feature.onPrivateData(rootListResponse, harness.store)

        // 3. Assert KG feature now requests the manifest file
        val readManifestAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_READ && it.payload?.get("subpath")?.jsonPrimitive?.content == "graph-1/graph.json" }
        assertNotNull(readManifestAction, "Should request to read the graph manifest.")

        // 4. Simulate FileSystem response with manifest content
        val manifestReadResponse = PrivateDataEnvelope(
            type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "graph-1/graph.json")
                put("content", sampleManifestContent)
            }
        )
        feature.onPrivateData(manifestReadResponse, harness.store)

        // 5. Assert state now contains the graph shell
        val stateAfterManifest = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertEquals(1, stateAfterManifest.graphs.size)
        assertEquals("Sample Graph", stateAfterManifest.graphs.first().name)

        // 6. User selects the graph, triggering a listing of its holons
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_SELECT_GRAPH_SCOPE, buildJsonObject { put("graphId", "graph-1") }))
        val listHolonsAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST && it.payload?.get("subpath")?.jsonPrimitive?.content == "graph-1" }
        assertNotNull(listHolonsAction, "Should request to list holons in the graph directory.")

        // 7. Simulate FileSystem response with the holon file
        val holonListResponse = PrivateDataEnvelope(
            type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST,
            payload = buildJsonObject {
                put("subpath", "graph-1")
                put("listing", buildJsonArray {
                    add(json.encodeToJsonElement(FileEntry("holon-a.json", isDirectory = false)))
                })
            }
        )
        feature.onPrivateData(holonListResponse, harness.store)

        // 8. Assert KG feature now requests the holon content
        val readHolonAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_READ && it.payload?.get("subpath")?.jsonPrimitive?.content == "graph-1/holon-a.json" }
        assertNotNull(readHolonAction, "Should request to read the holon file.")
    }

    @Test
    fun `on AGENT_REQUEST_CONTEXT delivers context envelope with all holon content`() {
        val holonWithContent = Holon("holon-a", "Test", "Holon A", content = "This is holon A content.")
        val graphWithContent = HolonKnowledgeGraph("graph-1", "Test Graph", holons = listOf(holonWithContent))
        val initialState = KnowledgeGraphState(graphs = listOf(graphWithContent))

        val harness = TestEnvironment.create()
            .withFeature(KnowledgeGraphFeature(FakePlatformDependencies("test"), testScope))
            .withInitialState("knowledgegraph", initialState)
            .build()
        val feature = harness.store.features.find { it.name == "knowledgegraph" } as KnowledgeGraphFeature

        val agentRequest = PrivateDataEnvelope(
            type = ActionNames.Envelopes.AGENT_REQUEST_CONTEXT,
            payload = buildJsonObject {
                put("correlationId", "agent-turn-123")
                put("knowledgeGraphId", "graph-1")
            }
        )
        feature.onPrivateData(agentRequest, harness.store)

        assertEquals(1, harness.deliveredPrivateData.size)
        val delivered = harness.deliveredPrivateData.first()
        assertEquals("knowledgegraph", delivered.originator)
        assertEquals("agent", delivered.recipient)
        assertEquals(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, delivered.envelope.type)

        val responsePayload = delivered.envelope.payload
        assertEquals("agent-turn-123", responsePayload["correlationId"]?.jsonPrimitive?.content)
        val context = responsePayload["context"]?.toString()
        assertNotNull(context)
        assertTrue(context.contains("\"holon-a\":\"This is holon A content.\""))
    }
}