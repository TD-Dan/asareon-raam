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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            "payload": {}, "execute": null
        }
    """.trimIndent()
    private val holonAContent = """
        {
            "header": { "id": "holon-a", "type": "Test_Holon", "name": "Holon A" },
            "payload": {}, "execute": null
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
    fun `on PERSONA_LOADED should broadcast AVAILABLE_PERSONAS_UPDATED`() {
        // --- ARRANGE ---
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "Persona One"), buildJsonObject {})
        val h1 = Holon(HolonHeader("h1", "T", "Holon A", parentId = "p1", depth = 1), buildJsonObject {})

        val payload = buildJsonObject {
            put("holons", json.encodeToJsonElement(mapOf("p1" to p1, "h1" to h1)))
        }
        val loadAction = Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED, payload)

        // --- ACT ---
        harness.store.dispatch(feature.name, loadAction)

        // --- ASSERT ---
        val broadcastAction = harness.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED }
        assertNotNull(broadcastAction, "The feature should have broadcasted the persona update.")
        assertEquals(feature.name, broadcastAction.originator, "The broadcast must originate from the feature itself.")

        val broadcastPayload = broadcastAction.payload?.get("names")?.jsonObject
        assertNotNull(broadcastPayload)
        assertEquals(1, broadcastPayload.size)
        assertEquals("Persona One", broadcastPayload["p1"]?.jsonPrimitive?.content)
    }


    @Test
    fun `full load sequence correctly populates holons from filesystem`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        // 1. Dispatch the action to load a persona.
        harness.store.dispatch("system", Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "persona-1") }))

        // 2. Assert that SYSTEM_LIST_RECURSIVE was requested for the persona root sandbox.
        val dirReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_LIST_RECURSIVE, dirReadRequest.name)
        assertEquals("persona-1", dirReadRequest.payload?.get("subpath")?.jsonPrimitive?.content)

        // 3. Simulate the filesystem returning the recursive file list (as subpaths).
        val dirContentsResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST_RECURSIVE, buildJsonObject {
            put("subpath", "persona-1")
            put("listing", buildJsonArray {
                add(json.encodeToJsonElement(FileEntry("persona-1/persona-1.json", false)))
                add(json.encodeToJsonElement(FileEntry("persona-1/holon-a/holon-a.json", false)))
            })
        })
        // THE FIX: Use the store's delivery mechanism to ensure the event loop runs.
        harness.store.deliverPrivateData("filesystem", "knowledgegraph", dirContentsResponse)

        // 4. Assert that READ_FILES_CONTENT was requested for all files using their relative subpaths.
        val filesReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_READ_FILES_CONTENT, filesReadRequest.name)
        val pathsToRead = filesReadRequest.payload?.get("subpaths")?.let { json.decodeFromJsonElement(serializer<List<String>>(), it) }
        assertNotNull(pathsToRead)
        assertEquals(2, pathsToRead.size)
        assertTrue(pathsToRead.contains("persona-1/persona-1.json"))
        assertTrue(pathsToRead.contains("persona-1/holon-a/holon-a.json"))


        // 5. Simulate the filesystem returning the content for all files, keyed by their relative subpaths.
        val filesContentResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, buildJsonObject {
            put("contents", buildJsonObject {
                put("persona-1/persona-1.json", persona1Content)
                put("persona-1/holon-a/holon-a.json", holonAContent)
            })
        })
        // THE FIX: Use the store's delivery mechanism here as well.
        harness.store.deliverPrivateData("filesystem", "knowledgegraph", filesContentResponse)

        // 6. Assert the final state is correct and complete
        val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        val loadedPersona = finalState.holons["persona-1"]
        assertNotNull(loadedPersona)
        assertEquals("Persona One", loadedPersona.header.name)
        assertEquals(0, loadedPersona.header.depth)
        assertNull(loadedPersona.header.parentId)

        val loadedChild = finalState.holons["holon-a"]
        assertNotNull(loadedChild)
        assertEquals("Holon A", loadedChild.header.name)
        assertEquals(1, loadedChild.header.depth)
        assertEquals("persona-1", loadedChild.header.parentId)
    }

    @Test
    fun `import analysis workflow dispatches REQUEST_SCOPED_READ_UI`() {
        // Arrange
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        // Act
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", "/import") }))

        // Assert
        val requestAction = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_REQUEST_SCOPED_READ_UI, requestAction.name)
        assertEquals(true, requestAction.payload?.get("recursive")?.jsonPrimitive?.booleanOrNull)
        // We can add assertions for file extensions later if needed.
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