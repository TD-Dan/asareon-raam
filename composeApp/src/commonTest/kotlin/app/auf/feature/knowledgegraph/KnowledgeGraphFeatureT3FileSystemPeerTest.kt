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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 3 Peer Test for KnowledgeGraphFeature <-> FileSystemFeature interaction.
 *
 * Mandate (P-TEST-001, T3): To test the runtime contract and emergent behavior
 * of the end-to-end "Import HKG" workflow, with a focus on the new correlation ID mechanism.
 */
class KnowledgeGraphFeatureT3FileSystemPeerTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val kgFeature = KnowledgeGraphFeature(platform, testScope)
    private val fsFeature = FileSystemFeature(platform)

    // --- Ground Truth Data ---
    private val existingPersonaContent = """
        {
            "header": { "id": "p1", "type": "AI_Persona_Root", "name": "Existing Persona", "sub_holons": [{"id": "h1", "type": "T", "summary": "S"}] },
            "payload": {}
        }
    """.trimIndent()
    private val updatedHolonContent = """
        { "header": { "id": "h1", "type": "Existing_Holon", "name": "Holon One Updated" }, "payload": {} }
    """.trimIndent()
    private val newHolonContent = """
        { "header": { "id": "h2", "type": "New_Holon", "name": "Holon Two"}, "payload": {} }
    """.trimIndent()

    @Test
    fun `end-to-end import workflow correctly uses correlationId and analyzes files`() {
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(holons = mapOf(
                "p1" to json.decodeFromString<Holon>(existingPersonaContent)
            )))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // --- 2. ACT 1 (Start Analysis) ---
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))

            // --- 3. ASSERT 1 (Request with Correlation ID) ---
            val stateAfterRequest = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            val correlationId = stateAfterRequest.pendingImportCorrelationId
            assertNotNull(correlationId, "A correlation ID should be set in the state.")

            // [THE FIX]: Correctly find the relevant action in the sequence, not just the last one.
            val fsRequest = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_REQUEST_SCOPED_READ_UI }
            assertNotNull(fsRequest, "A scoped read request should have been dispatched.")
            assertEquals(correlationId, fsRequest.payload?.get("correlationId")?.jsonPrimitive?.content)

            // --- 4. ACT 2 (Simulate FileSystem Response) ---
            val fsResponse = PrivateDataEnvelope(
                type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("contents", buildJsonObject {
                        put("h1.json", updatedHolonContent)
                        put("h2.json", newHolonContent)
                    })
                }
            )
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", fsResponse)

            // --- 5. ASSERT 2 (Analysis is Correct and Correlation ID is Cleared) ---
            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            assertNull(finalState.pendingImportCorrelationId, "Correlation ID should be cleared after use.")
            assertEquals(2, finalState.importItems.size)

            val updateAction = finalState.importSelectedActions["h1.json"]
            assertIs<Update>(updateAction, "h1.json should be identified as an Update.")
            assertEquals("h1", updateAction.targetHolonId)

            val quarantineAction = finalState.importSelectedActions["h2.json"]
            assertIs<Quarantine>(quarantineAction, "h2.json should be quarantined as a new top-level holon.")
        }
    }
}