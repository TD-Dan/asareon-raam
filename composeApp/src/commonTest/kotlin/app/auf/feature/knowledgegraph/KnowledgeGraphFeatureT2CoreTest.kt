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
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for KnowledgeGraphFeature.
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
    fun `full load sequence correctly populates and synchronizes holons`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "persona-1") }))
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, buildJsonObject {
                put("subpath", "persona-1")
                put("listing", buildJsonArray {
                    add(json.encodeToJsonElement(FileEntry("persona-1/persona-1.json", false)))
                    add(json.encodeToJsonElement(FileEntry("persona-1/holon-a.json", false)))
                })
            }))
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, buildJsonObject {
                put("correlationId", JsonNull)
                put("contents", buildJsonObject {
                    put("persona-1/persona-1.json", persona1Content)
                    put("persona-1/holon-a.json", holonAContent)
                })
            }))

            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState

            // Assert enrichment
            val loadedChild = finalState.holons["holon-a"]!!
            assertEquals(1, loadedChild.header.depth)
            assertEquals("persona-1", loadedChild.header.parentId)

            // Assert synchronization
            val parsedRawContent = json.parseToJsonElement(loadedChild.rawContent).jsonObject
            assertEquals("persona-1", parsedRawContent["header"]!!.jsonObject["parentId"]!!.jsonPrimitive.content)
            assertEquals(1, parsedRawContent["header"]!!.jsonObject["depth"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `RENAME_HOLON should update name, timestamp, and synchronize rawContent before writing`() {
        val holonToRename = Holon(
            header = HolonHeader(id = "h1", type="TestHolon", name = "Old Name", filePath = "p1/h1.json"),
            payload = buildJsonObject{},
            rawContent = "stale"
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(holons = mapOf("h1" to holonToRename), activePersonaIdForView = "p1"))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject {
                put("holonId", "h1")
                put("newName", "New Name")
            }))

            val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
            assertNotNull(writeAction)

            val writtenContent = writeAction.payload!!.jsonObject["content"]!!.jsonPrimitive.content
            val parsedWrittenJson = json.parseToJsonElement(writtenContent).jsonObject
            val parsedHeader = parsedWrittenJson["header"]!!.jsonObject

            assertEquals("New Name", parsedHeader["name"]!!.jsonPrimitive.content)
            assertNotNull(parsedHeader["modified_at"]?.jsonPrimitive?.content)

            // Assert a reload was triggered to update the in-memory state
            assertTrue(harness.processedActions.any { it.name == ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA })
        }
    }
}