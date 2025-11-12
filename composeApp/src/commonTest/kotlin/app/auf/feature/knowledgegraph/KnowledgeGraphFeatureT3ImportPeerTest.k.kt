package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
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
 * Tier 3 Peer Test for the KnowledgeGraphFeature Import workflow.
 */
class KnowledgeGraphFeatureT3ImportPeerTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val kgFeature = KnowledgeGraphFeature(platform, testScope)
    private val fsFeature = FileSystemFeature(platform)

    // --- Ground Truth Data ---
    private val existingPersonaContent = """
        { "header": { "id": "p1-20251112T190000Z", "type": "AI_Persona_Root", "name": "Existing Persona" }, "payload": {} }
    """.trimIndent()
    private val newHolonContent = """
        { "header": { "id": "h2-20251112T190000Z", "type": "New_Holon", "name": "Holon Two"}, "payload": {} }
    """.trimIndent()

    @Test
    fun `execute INTEGRATE should write new holon and dispatch update for parent`() {
        val existingPersonaFilePath = "p1/p1-20251112T190000Z.json"
        val newHolonFilePath = "h2-20251112T190000Z.json"
        val existingPersona = createHolonFromString(existingPersonaContent, existingPersonaFilePath, platform)

        val initialState = KnowledgeGraphState(
            holons = mapOf("p1" to existingPersona),
            importFileContents = mapOf(newHolonFilePath to newHolonContent),
            importSelectedActions = mapOf(newHolonFilePath to Integrate("p1"))
        )
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Act
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            // Assert: Find the two write actions
            val writeActions = harness.processedActions.filter { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
            assertEquals(2, writeActions.size, "Expected writes for both the new holon and the updated parent.")

            // Assert new holon was written to correct sub-directory
            val newHolonWrite = writeActions.find { it.payload?.get("subpath")?.jsonPrimitive?.content?.contains("h2") == true }
            assertNotNull(newHolonWrite)
            assertEquals("p1-20251112T190000Z/h2-20251112T190000Z/h2-20251112T190000Z.json", newHolonWrite.payload?.get("subpath")?.jsonPrimitive?.content)

            // Assert parent was updated with new sub_holon reference
            val parentUpdateWrite = writeActions.find { it.payload?.get("subpath")?.jsonPrimitive?.content == existingPersonaFilePath }
            assertNotNull(parentUpdateWrite)
            val finalParentContent = parentUpdateWrite.payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(finalParentContent)
            val finalParentHolon = createHolonFromString(finalParentContent, existingPersonaFilePath, platform)

            assertTrue(finalParentHolon.header.subHolons.any { it.id == "h2-20251112T190000Z" }, "Parent holon file should be updated to include a reference to the new child.")
        }
    }
}