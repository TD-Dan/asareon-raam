package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.util.BasePath
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
 * Tier 3 Peer Test for the KnowledgeGraphFeature Import workflow.
 *
 * Mandate (P-TEST--001, T3): To test the runtime contract and emergent behavior
 * of the end-to-end "Import HKG" workflow, verifying the peer-to-peer interaction
 * between the KnowledgeGraphFeature and the FileSystemFeature.
 */
class KnowledgeGraphFeatureT3ImportPeerTest {

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
    private val existingHolonContent = """
        { "header": { "id": "h1", "type": "Existing_Holon", "name": "Holon One" }, "payload": {} }
    """.trimIndent()
    private val updatedHolonContent = """
        { "header": { "id": "h1", "type": "Existing_Holon", "name": "Holon One Updated" }, "payload": {} }
    """.trimIndent()
    private val newHolonContent = """
        { "header": { "id": "h2", "type": "New_Holon", "name": "Holon Two"}, "payload": {} }
    """.trimIndent()
    private val newRootContent = """
        { "header": { "id": "p2", "type": "AI_Persona_Root", "name": "New Root"}, "payload": {} }
    """.trimIndent()
    private val newChildOfNewParentContent = """
        { "header": { "id": "h3", "type": "New_Child", "name": "Holon Three", "sub_holons": [] }, "payload": {} }
    """.trimIndent()
    private val newParentContent = """
        { "header": { "id": "h2", "type": "New_Parent", "name": "Holon Two", "sub_holons": [{"id": "h3", "type": "NC", "summary": "S"}] }, "payload": {} }
    """.trimIndent()


    /**
     * Helper function to run the analysis part of the import workflow and return the final state.
     */
    private fun runAnalysisAndGetState(initialState: KnowledgeGraphState, importContents: Map<String, String>): KnowledgeGraphState {
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))
            val stateAfterRequest = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            val correlationId = stateAfterRequest.pendingImportCorrelationId
            assertNotNull(correlationId)

            val fsResponse = PrivateDataEnvelope(
                type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("contents", json.encodeToJsonElement(importContents))
                }
            )
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", fsResponse)
        }
        return harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
    }

    // --- ANALYSIS PHASE TESTS ---

    @Test
    fun `analysis should identify an UPDATE action for an existing holon`() {
        val initialState = KnowledgeGraphState(holons = mapOf(
            "h1" to json.decodeFromString<Holon>(existingHolonContent)
        ))
        val importContents = mapOf("h1.json" to updatedHolonContent)

        val finalState = runAnalysisAndGetState(initialState, importContents)

        val action = finalState.importSelectedActions["h1.json"]
        assertIs<Update>(action)
        assertEquals("h1", action.targetHolonId)
    }

    @Test
    fun `analysis should identify a CREATEROOT action for a new AI_Persona_Root`() {
        val finalState = runAnalysisAndGetState(KnowledgeGraphState(), mapOf("p2.json" to newRootContent))
        val action = finalState.importSelectedActions["p2.json"]
        assertIs<CreateRoot>(action)
    }

    @Test
    fun `analysis should identify an INTEGRATE action for a new holon with a parent in the import set`() {
        val importContents = mapOf(
            "h2.json" to newParentContent,
            "h3.json" to newChildOfNewParentContent
        )
        val finalState = runAnalysisAndGetState(KnowledgeGraphState(), importContents)
        val action = finalState.importSelectedActions["h3.json"]
        assertIs<Integrate>(action)
        assertEquals("h2", action.parentHolonId)
    }

    @Test
    fun `analysis should QUARANTINE an orphan holon with no known parent`() {
        val finalState = runAnalysisAndGetState(KnowledgeGraphState(), mapOf("h2.json" to newHolonContent))
        val action = finalState.importSelectedActions["h2.json"]
        assertIs<Quarantine>(action)
        assertEquals("Unknown top-level holon.", action.reason)
    }

    @Test
    fun `analysis should QUARANTINE a file with malformed JSON`() {
        val malformedContent = "{ \"header\": { \"id\": \"bad-json\" " // Missing closing brace
        val finalState = runAnalysisAndGetState(KnowledgeGraphState(), mapOf("bad.json" to malformedContent))

        val action = finalState.importSelectedActions["bad.json"]
        assertIs<Quarantine>(action)
        assertEquals("Malformed JSON", action.reason)
    }

    @Test
    fun `analysis should IGNORE files in subdirectories when recursive is false`() {
        // We can't use the helper here as we need to modify the state before the final step
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))
            val stateAfterRequest = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            val correlationId = stateAfterRequest.pendingImportCorrelationId
            assertNotNull(correlationId)

            // User toggles recursive OFF
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE, buildJsonObject { put("recursive", false) }))

            val importContents = mapOf(
                "root.json" to newRootContent,
                "sub/child.json" to newHolonContent
            )
            val fsResponse = PrivateDataEnvelope(
                type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("contents", json.encodeToJsonElement(importContents))
                }
            )
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", fsResponse)

            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            assertEquals(1, finalState.importItems.size, "Only the root file should be analyzed.")
            assertNotNull(finalState.importSelectedActions["root.json"])
            assertNull(finalState.importSelectedActions["sub/child.json"])
        }
    }


    // --- EXECUTION PHASE TESTS ---
    @Test
    fun `execute should perform an UPDATE by writing to the existing file path`() {
        val existingHolon = json.decodeFromString<Holon>(existingHolonContent)
            .copy(header = json.decodeFromString<Holon>(existingHolonContent).header.copy(filePath = "p1/h1/h1.json"))
        val initialState = KnowledgeGraphState(
            holons = mapOf("h1" to existingHolon),
            importFileContents = mapOf("h1_updated.json" to updatedHolonContent),
            importSelectedActions = mapOf("h1_updated.json" to Update("h1"))
        )
        val harness = TestEnvironment.create().withFeature(kgFeature).withFeature(fsFeature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
            assertNotNull(writeAction)
            assertEquals("p1/h1/h1.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
            assertEquals(updatedHolonContent, writeAction.payload?.get("content")?.jsonPrimitive?.content)
            assertTrue(harness.processedActions.any { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }, "A refresh action should be dispatched after import.")
        }
    }

    @Test
    fun `execute should perform a CREATEROOT by writing to a new sandbox directory`() {
        val initialState = KnowledgeGraphState(
            importFileContents = mapOf("p2.json" to newRootContent),
            importSelectedActions = mapOf("p2.json" to CreateRoot())
        )
        val harness = TestEnvironment.create().withFeature(kgFeature).withFeature(fsFeature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))
            val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
            assertNotNull(writeAction)
            assertEquals("p2/p2.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute should perform an INTEGRATE by writing new holon and updating parent`() {
        val fullParentPath = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph/p1/p1.json"
        val existingPersona = json.decodeFromString<Holon>(existingPersonaContent)
            .copy(header = json.decodeFromString<Holon>(existingPersonaContent).header.copy(filePath = "p1/p1.json"))
        val initialState = KnowledgeGraphState(
            holons = mapOf("p1" to existingPersona),
            importFileContents = mapOf("h2.json" to newHolonContent),
            importSelectedActions = mapOf("h2.json" to Integrate("p1"))
        )
        // [FIX] Pre-populate the parent file content in the fake file system at its full path.
        platform.writeFileContent(fullParentPath, existingPersonaContent)
        val harness = TestEnvironment.create().withFeature(kgFeature).withFeature(fsFeature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            // Assert new holon was written to correct sub-directory
            val expectedNewHolonPath = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph/p1/h2/h2.json"
            val writtenNewHolonContent = harness.platform.writtenFiles[expectedNewHolonPath]
            assertNotNull(writtenNewHolonContent)
            assertEquals(newHolonContent, writtenNewHolonContent)

            // Assert parent was updated with new sub_holon reference
            val finalParentContent = harness.platform.writtenFiles[fullParentPath]
            assertNotNull(finalParentContent)
            val finalParentHolon = json.decodeFromString<Holon>(finalParentContent)
            assertTrue(finalParentHolon.header.subHolons.any { it.id == "h2" })
        }
    }

    @Test
    fun `execute should perform multi-pass import for new parent and child`() {
        val initialState = KnowledgeGraphState(
            importFileContents = mapOf(
                "h3.json" to newChildOfNewParentContent, // child is listed first
                "h2.json" to newParentContent
            ),
            importSelectedActions = mapOf(
                "h2.json" to CreateRoot(), // Treat the parent as a new root for this test
                "h3.json" to Integrate("h2")
            )
        )
        val harness = TestEnvironment.create().withFeature(kgFeature).withFeature(fsFeature).withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            // Assert parent was written
            val expectedParentPath = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph/h2/h2.json"
            assertNotNull(harness.platform.writtenFiles[expectedParentPath])

            // Assert child was written
            val expectedChildPath = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph/h2/h3/h3.json"
            assertNotNull(harness.platform.writtenFiles[expectedChildPath])

            // Assert parent was UPDATED with child reference
            val finalParentContent = harness.platform.writtenFiles[expectedParentPath]
            assertNotNull(finalParentContent)
            val finalParentHolon = json.decodeFromString<Holon>(finalParentContent)
            assertTrue(finalParentHolon.header.subHolons.any { it.id == "h3" }, "Parent holon file should be updated to include a reference to the new child.")
            assertTrue(harness.processedActions.any { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST }, "A refresh should be triggered after import.")
        }
    }


    // --- LEGACY TEST ---

    @Test
    fun `end-to-end import workflow correctly uses correlationId and analyzes files`() {
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(holons = mapOf(
                "p1" to json.decodeFromString<Holon>(existingPersonaContent).copy(header = json.decodeFromString<Holon>(existingPersonaContent).header.copy(filePath = "p1/p1.json")),
                "h1" to json.decodeFromString<Holon>(existingHolonContent).copy(header = json.decodeFromString<Holon>(existingHolonContent).header.copy(filePath = "p1/h1/h1.json")),
            )))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // --- 2. ACT 1 (Start Analysis) ---
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))

            // --- 3. ASSERT 1 (Request with Correlation ID) ---
            val stateAfterRequest = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            val correlationId = stateAfterRequest.pendingImportCorrelationId
            assertNotNull(correlationId, "A correlation ID should be set in the state.")

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