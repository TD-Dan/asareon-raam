package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.store.dispatch("system.main", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }
        assertNotNull(listAction)
    }

    @Test
    fun `full load sequence correctly populates holons from filesystem`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" }!!

        // 1. Simulate FileSystem response with a persona directory
        val listResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, buildJsonObject {
            put("listing", buildJsonArray { add(json.encodeToJsonElement(FileEntry("persona-1", true))) })
        })
        feature.onPrivateData(listResponse, harness.store)

        // 2. Assert KG feature now requests the persona's root json
        val readRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, readRequest.name)
        assertEquals("persona-1/persona-1.json", readRequest.payload?.get("subpath")?.jsonPrimitive?.content)

        // 3. Simulate FileSystem response with persona content
        val readResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, buildJsonObject {
            put("subpath", "persona-1/persona-1.json")
            put("content", persona1Content)
        })
        feature.onPrivateData(readResponse, harness.store)

        // 4. Assert state now contains the persona root and a request for its child was sent
        val stateAfterPersona = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertNotNull(stateAfterPersona.holons["persona-1"])
        assertEquals("Persona One", stateAfterPersona.holons["persona-1"]?.header?.name)

        val childReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, childReadRequest.name)
        assertTrue(childReadRequest.payload?.get("subpath")?.jsonPrimitive?.content?.endsWith("holon-a/holon-a.json") == true)
    }

    @Test
    fun `import analysis workflow dispatches correct sequence of actions`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" }!!

        // 1. User triggers analysis
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", "/import") }))
        val readDirAction = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_READ_DIRECTORY_CONTENTS, readDirAction.name)

        // 2. Simulate FileSystem response with directory contents
        val dirContentsResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_DIRECTORY_CONTENTS, buildJsonObject {
            put("path", "/import")
            put("listing", buildJsonArray { add(json.encodeToJsonElement(FileEntry("/import/holon-a.json", false))) })
        })
        feature.onPrivateData(dirContentsResponse, harness.store)

        // 3. Assert KG feature now requests the content of those files
        val readFilesAction = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_READ_FILES_CONTENT, readFilesAction.name)
        assertTrue(readFilesAction.payload.toString().contains("/import/holon-a.json"))

        // 4. Simulate FileSystem response with file contents
        val filesContentResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, buildJsonObject {
            put("contents", buildJsonObject { put("/import/holon-a.json", holonAContent) })
        })
        feature.onPrivateData(filesContentResponse, harness.store)

        // 5. Assert a final internal action was dispatched to update the state with the analysis
        val analysisCompleteAction = harness.processedActions.last()
        assertEquals(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, analysisCompleteAction.name)
        val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertEquals(1, finalState.importItems.size)
        assertIs<AssignParent>(finalState.importItems.first().initialAction)
    }

    @Test
    fun `REQUEST_CONTEXT should build context and deliver it privately`() {
        val initialState = KnowledgeGraphState(holons = mapOf(
            "persona-1" to json.decodeFromString(persona1Content),
            "holon-a" to json.decodeFromString(holonAContent)
        ))
        val harness = TestEnvironment.create().withFeature(feature).withInitialState("knowledgegraph", initialState).build(platform = platform) // CORRECTED

        harness.store.dispatch("agent", Action(ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT, buildJsonObject {
            put("personaId", "persona-1"); put("correlationId", "corr-123")
        }))

        assertEquals(1, harness.deliveredPrivateData.size)
        val delivery = harness.deliveredPrivateData.first()
        assertEquals("agent", delivery.recipient)
        assertEquals(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, delivery.envelope.type)
        assertEquals("corr-123", delivery.envelope.payload["correlationId"]?.jsonPrimitive?.content)
        val context = delivery.envelope.payload["context"].toString()
        assertTrue(context.contains("\"persona-1\":\"...\""))
        assertTrue(context.contains("\"holon-a\":\"Holon A Content\""))
    }
}