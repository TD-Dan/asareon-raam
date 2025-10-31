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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    private val platform = FakePlatformDependencies("test")
    private val feature = KnowledgeGraphFeature(platform, testScope)

    private val persona1Content = """
        {
            "header": { "id": "persona-1", "type": "AI_Persona_Root", "name": "Persona One", "sub_holons": [{"id": "holon-a", "type": "T", "summary": "S"}] },
            "payload": {}, "execute": null, "content": "..."
        }
    """.trimIndent()
    private val holonAContent = """
        {
            "header": { "id": "holon-a", "type": "Test_Holon", "name": "Holon A" },
            "payload": {}, "execute": null, "content": "Holon A Content"
        }
    """.trimIndent()


    @Test
    fun `on SYSTEM_PUBLISH_STARTING should dispatch FILESYSTEM_SYSTEM_LIST`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)
        harness.store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }
        assertNotNull(listAction)
    }

    @Test
    fun `full load sequence correctly populates holons from filesystem`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" }!!

        val listResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, buildJsonObject {
            put("listing", buildJsonArray { add(json.encodeToJsonElement(FileEntry("persona-1", true))) })
        })
        feature.onPrivateData(listResponse, harness.store)

        val readRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, readRequest.name)
        assertEquals("persona-1/persona-1.json", readRequest.payload?.get("subpath")?.jsonPrimitive?.content)

        val readResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, buildJsonObject {
            put("subpath", "persona-1/persona-1.json")
            put("content", persona1Content)
        })
        feature.onPrivateData(readResponse, harness.store)

        val stateAfterPersona = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertNotNull(stateAfterPersona.holons["persona-1"])
        assertEquals("Persona One", stateAfterPersona.holons["persona-1"]?.header?.name)

        val childReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, childReadRequest.name)
        assertEquals("persona-1/holon-a/holon-a.json", childReadRequest.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `import analysis workflow dispatches correct sequence of actions`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" }!!

        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", "/import") }))
        assertEquals(ActionNames.FILESYSTEM_READ_DIRECTORY_CONTENTS, harness.processedActions.last().name)

        val dirContentsResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_DIRECTORY_CONTENTS, buildJsonObject {
            put("path", "/import")
            put("listing", buildJsonArray { add(json.encodeToJsonElement(FileEntry("/import/holon-a.json", false))) })
        })
        feature.onPrivateData(dirContentsResponse, harness.store)
        assertEquals(ActionNames.FILESYSTEM_READ_FILES_CONTENT, harness.processedActions.last().name)

        // CORRECTED: Simulate the second private data response to complete the workflow
        val filesContentResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, buildJsonObject {
            put("contents", buildJsonObject { put("/import/holon-a.json", holonAContent) })
        })
        feature.onPrivateData(filesContentResponse, harness.store)

        // NOW the assertion is valid.
        val analysisCompleteAction = harness.processedActions.last()
        assertEquals(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, analysisCompleteAction.name)
        val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertEquals(1, finalState.importItems.size)
        assertIs<Quarantine>(finalState.importItems.first().initialAction)
    }

    @Test
    fun `REQUEST_CONTEXT should build context and deliver it privately`() {
        val initialState = KnowledgeGraphState(holons = mapOf(
            "persona-1" to json.decodeFromString<Holon>(persona1Content).copy(content = persona1Content),
            "holon-a" to json.decodeFromString<Holon>(holonAContent).copy(content = holonAContent)
        ))
        val harness = TestEnvironment.create().withFeature(feature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.store.dispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT, buildJsonObject {
            put("personaId", "persona-1"); put("correlationId", "corr-123")
        }))

        assertEquals(1, harness.deliveredPrivateData.size)
        val delivery = harness.deliveredPrivateData.first()
        assertEquals("agent", delivery.recipient)
        assertEquals(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, delivery.envelope.type)
        assertEquals("corr-123", delivery.envelope.payload["correlationId"]?.jsonPrimitive?.content)
        val context = delivery.envelope.payload["context"]?.jsonObject
        assertNotNull(context)
        assertTrue(context.containsKey("persona-1"))
        assertTrue(context.containsKey("holon-a"))
    }
}