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
import kotlin.test.assertFalse
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
    fun `full load sequence correctly populates holons from filesystem`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val feature = harness.store.features.find { it.name == "knowledgegraph" }!!

        // 1. Simulate the filesystem listing personas
        val listResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, buildJsonObject {
            put("listing", buildJsonArray { add(json.encodeToJsonElement(FileEntry("persona-1", true))) })
        })
        feature.onPrivateData(listResponse, harness.store)

        // 2. Assert that a read was requested for the persona root
        val rootReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, rootReadRequest.name)
        assertEquals("persona-1/persona-1.json", rootReadRequest.payload?.get("subpath")?.jsonPrimitive?.content)

        // 3. Simulate the filesystem returning the content for the persona root
        val rootReadResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, buildJsonObject {
            put("subpath", "persona-1/persona-1.json")
            put("content", persona1Content)
        })
        feature.onPrivateData(rootReadResponse, harness.store)

        // 4. Assert the persona is loaded in the state and its content is preserved
        val stateAfterPersona = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        val loadedPersona = stateAfterPersona.holons["persona-1"]
        assertNotNull(loadedPersona)
        assertEquals("Persona One", loadedPersona.header.name)
        assertEquals(persona1Content, loadedPersona.content, "Content of root holon should be preserved.")

        // 5. Assert that a read was requested for the child holon
        val childReadRequest = harness.processedActions.last()
        assertEquals(ActionNames.FILESYSTEM_SYSTEM_READ, childReadRequest.name)
        assertEquals("persona-1/holon-a/holon-a.json", childReadRequest.payload?.get("subpath")?.jsonPrimitive?.content)

        // 6. Simulate the filesystem returning the content for the child
        val childReadResponse = PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ, buildJsonObject {
            put("subpath", "persona-1/holon-a/holon-a.json")
            put("content", holonAContent)
        })
        feature.onPrivateData(childReadResponse, harness.store)

        // 7. Assert the final state is correct and complete
        val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        val loadedChild = finalState.holons["holon-a"]
        assertNotNull(loadedChild)
        assertEquals("Holon A", loadedChild.header.name)
        assertEquals(holonAContent, loadedChild.content, "Content of child holon should be preserved.")
        assertEquals("persona-1", loadedChild.header.parentId)
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

    @Test
    fun `delete persona workflow dispatches correct sequence`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.store.dispatch("ui.dialog", Action(ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject {
            put("personaId", "persona-to-delete")
        }))

        val deleteDirAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY }
        assertNotNull(deleteDirAction, "Should dispatch an action to delete the directory.")
        assertEquals("persona-to-delete", deleteDirAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val confirmAction = harness.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA }
        assertNotNull(confirmAction, "Should dispatch an internal action to update the state.")
        assertEquals("persona-to-delete", confirmAction.payload?.get("personaId")?.jsonPrimitive?.content)
    }

    @Test
    fun `create persona workflow dispatches correct sequence`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.store.dispatch("ui.dialog", Action(ActionNames.KNOWLEDGEGRAPH_CREATE_PERSONA, buildJsonObject {
            put("name", "My Test Persona")
        }))

        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "Should dispatch a write action to the filesystem.")
        assertTrue(writeAction.payload?.get("subpath")?.jsonPrimitive?.content?.startsWith("my-test-persona-") == true)

        val toastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
        assertNotNull(toastAction, "Should dispatch a toast to confirm creation.")

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }
        assertNotNull(listAction, "Should dispatch a list action to reload the graph.")
    }

    @Test
    fun `delete holon workflow dispatches correct sequence`() {
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "P1", filePath = "p1/p1.json", subHolons = listOf(SubHolonRef("h1", "T", "S"))), buildJsonObject {})
        val h1 = Holon(HolonHeader("h1", "T", "H1", filePath = "p1/h1/h1.json", parentId = "p1"), buildJsonObject {})
        val initialState = KnowledgeGraphState(holons = mapOf("p1" to p1, "h1" to h1))
        val harness = TestEnvironment.create().withFeature(feature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.store.dispatch("ui.menu", Action(ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject { put("holonId", "h1") }))

        val deleteDirAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY }
        assertNotNull(deleteDirAction, "Should delete the holon's directory.")
        assertEquals("p1/h1", deleteDirAction.payload?.get("subpath")?.jsonPrimitive?.content)

        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "Should write the updated parent.")
        assertEquals("p1/p1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        val updatedParentContent = writeAction.payload?.get("content")?.jsonPrimitive?.content ?: ""
        assertFalse(updatedParentContent.contains("h1"), "Updated parent content should not contain the deleted holon ID.")

        val confirmAction = harness.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON }
        assertNotNull(confirmAction, "Should confirm the state change.")
        assertEquals("h1", confirmAction.payload?.get("holonId")?.jsonPrimitive?.content)
    }
}