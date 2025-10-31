package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier 3 Peer Test for KnowledgeGraphFeature <-> FileSystemFeature interaction.
 *
 * Mandate (P-TEST-001, T3): To test the runtime contract and emergent behavior
 * of the end-to-end "Import HKG" workflow.
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
            "payload": {}, "content": "..."
        }
    """.trimIndent()
    private val existingHolonContent = """
        {
            "header": { "id": "h1", "type": "Existing_Holon", "name": "Holon One" }, "payload": {}, "content": "Original Content"
        }
    """.trimIndent()
    private val updatedHolonContent = """
        {
            "header": { "id": "h1", "type": "Existing_Holon", "name": "Holon One Updated" }, "payload": {}, "content": "Updated Content"
        }
    """.trimIndent()
    private val newHolonContent = """
        {
            "header": { "id": "h2", "type": "New_Holon", "name": "Holon Two", "sub_holons": [{"id": "h3", "type": "T", "summary": "S"}] }, "payload": {}, "content": "New Holon"
        }
    """.trimIndent()
    private val newChildHolonContent = """
        {
            "header": { "id": "h3", "type": "New_Child", "name": "Holon Three" }, "payload": {}, "content": "New Child"
        }
    """.trimIndent()

    @Test
    fun `end-to-end import workflow correctly analyzes, integrates, and writes files`() {
        // --- 1. Arrange ---
        // Setup existing HKG in the fake filesystem's APP_ZONE using the CANONICAL HIERARCHICAL STRUCTURE
        platform.writeFileContent("/fake/.auf/v2/knowledgegraph/p1/p1.json", existingPersonaContent)
        platform.writeFileContent("/fake/.auf/v2/knowledgegraph/p1/h1/h1.json", existingHolonContent)
        // Setup source directory for import
        platform.createDirectories("/import/source")
        platform.writeFileContent("/import/source/h1.json", updatedHolonContent) // An update
        platform.writeFileContent("/import/source/h2.json", newHolonContent)     // An integration
        platform.writeFileContent("/import/source/h3.json", newChildHolonContent)  // A child of an integration

        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .build(platform = platform)

        // Pre-load the existing HKG
        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))
        harness.store.deliveredPrivateData.toList().forEach { kgFeature.onPrivateData(it.envelope, harness.store) }
        harness.store.deliveredPrivateData.clear()

        // --- 2. Act 1 (Analysis) ---
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS, buildJsonObject { put("path", "/import/source") }))
        harness.store.deliveredPrivateData.toList().forEach { kgFeature.onPrivateData(it.envelope, harness.store) }
        harness.store.deliveredPrivateData.clear()


        // --- 3. Assert 1 (State Verification) ---
        val stateAfterAnalysis = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
        assertEquals(3, stateAfterAnalysis.importItems.size)
        val updateAction = stateAfterAnalysis.importSelectedActions["/import/source/h1.json"]
        val integrateAction = stateAfterAnalysis.importSelectedActions["/import/source/h2.json"]
        val assignParentAction = stateAfterAnalysis.importSelectedActions["/import/source/h3.json"]
        assertIs<Update>(updateAction)
        assertEquals("h1", updateAction.targetHolonId)
        assertIs<Quarantine>(integrateAction) // Correctly identified as a top-level unknown
        assertIs<Integrate>(assignParentAction) // Correctly identified as child of h2
        assertEquals("h2", assignParentAction.parentHolonId)

        // --- 4. Act 2 (User Override) ---
        // User assigns h2 to be a child of p1
        val newAction = Integrate(parentHolonId = "p1")
        // CORRECTED: The action payload must be a JsonObject, so we build one.
        val actionPayload = buildJsonObject {
            put("sourcePath", "/import/source/h2.json")
            put("action", json.encodeToJsonElement(newAction))
        }
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION, actionPayload))


        // --- 5. Act 3 (Execution) ---
        harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))
        harness.store.deliveredPrivateData.toList().forEach { kgFeature.onPrivateData(it.envelope, harness.store) }

        // --- 6. Assert 2 (Ground Truth Verification) ---
        val writtenFiles = harness.platform.writtenFiles
        // 1. Holon h1 should be updated at its original hierarchical path
        val updatedH1Path = "/fake/.auf/v2/knowledgegraph/p1/h1/h1.json"
        assertTrue(writtenFiles.containsKey(updatedH1Path))
        assertEquals(updatedHolonContent, writtenFiles[updatedH1Path])

        // 2. Holon h2 should be created as a child of p1
        val newH2Path = "/fake/.auf/v2/knowledgegraph/p1/h2/h2.json"
        assertTrue(writtenFiles.containsKey(newH2Path))
        assertEquals(newHolonContent, writtenFiles[newH2Path])

        // 3. Holon h3 should be created as a child of h2
        val newH3Path = "/fake/.auf/v2/knowledgegraph/p1/h2/h3/h3.json"
        assertTrue(writtenFiles.containsKey(newH3Path))
        assertEquals(newChildHolonContent, writtenFiles[newH3Path])

        // 4. The parent persona (p1) should be updated to include h2 as a sub_holon
        val updatedP1Path = "/fake/.auf/v2/knowledgegraph/p1/p1.json"
        assertTrue(writtenFiles.containsKey(updatedP1Path))
        val finalP1Content = writtenFiles[updatedP1Path]!!
        assertTrue(finalP1Content.contains(""""id": "h2""""), "Parent holon was not updated with new sub_holon ref.")
    }
}