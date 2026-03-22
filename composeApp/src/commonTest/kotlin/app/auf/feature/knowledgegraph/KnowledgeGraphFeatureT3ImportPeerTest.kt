package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.BasePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 3 Peer Test for the KnowledgeGraphFeature Import workflow.
 *
 * Verifies the interaction between KnowledgeGraphFeature and FileSystemFeature
 * during the complex Import Execution phase.
 */
class KnowledgeGraphFeatureT3ImportPeerTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val kgFeature = KnowledgeGraphFeature(platform, testScope)
    private val fsFeature = FileSystemFeature(platform)

    // --- Ground Truth Data ---
    private val existingPersonaContent = """
        { "header": { "id": "pl1-20251112T190000Z", "type": "AI_Persona_Root", "name": "Existing Persona" }, "payload": {} }
    """.trimIndent()
    private val newHolonContent = """
        { "header": { "id": "hl1-20251112T190000Z", "type": "New_Holon", "name": "Holon Two"}, "payload": {} }
    """.trimIndent()

    @Test
    fun `execute INTEGRATE should write new holon and dispatch update for parent`() {
        val existingPersonaFilePath = "pl1-20251112T190000Z/pl1-20251112T190000Z.json"
        val newHolonFilePath = "hl1-20251112T190000Z.json"

        // [FIX] Write the existing parent to the fake disk.
        // FileSystemFeature reads from the sandbox root, so we must populate it.
        // We ensure the directory structure exists and the file content is present.
        val sandboxRoot = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph"
        platform.createDirectories("$sandboxRoot/pl1-20251112T190000Z")
        platform.writeFileContent("$sandboxRoot/$existingPersonaFilePath", existingPersonaContent)

        // We simulate the persona already existing in memory (loaded)
        val existingPersona = createHolonFromString(existingPersonaContent, existingPersonaFilePath, platform)

        val initialState = KnowledgeGraphState(
            holons = mapOf("pl1" to existingPersona),
            importFileContents = mapOf(newHolonFilePath to newHolonContent),
            importSelectedActions = mapOf(newHolonFilePath to Integrate("pl1"))
        )
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Act
            // [FIX] EXECUTE_IMPORT is non-public; only the owning feature can dispatch it.
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            // Assert: Find the two write actions
            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertEquals(2, writeActions.size, "Expected writes for both the new holon and the updated parent.")

            // Assert new holon was written to correct subdirectory
            val newHolonWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content?.contains("hl1") == true }
            assertNotNull(newHolonWrite)
            assertEquals("pl1-20251112T190000Z/hl1-20251112T190000Z/hl1-20251112T190000Z.json", newHolonWrite.payload?.get("path")?.jsonPrimitive?.content)

            // Assert parent was updated with new sub_holon reference
            val parentUpdateWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content == existingPersonaFilePath }
            assertNotNull(parentUpdateWrite)
            val finalParentContent = parentUpdateWrite.payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(finalParentContent)
            // Use canonical gateway for verification, assuming file path matches expectation
            val finalParentHolon = createHolonFromString(finalParentContent, existingPersonaFilePath, platform)

            assertTrue(finalParentHolon.header.subHolons.any { it.id == "hl1-20251112T190000Z" }, "Parent holon file should be updated to include a reference to the new child.")
        }
    }

    @Test
    fun `execute DEEP IMPORT should recursively resolve paths for new hierarchy`() {
        // Scenario: Importing a tree of 3 NEW files (Root -> Mid -> Leaf)
        // [FIX] Update file map keys to be the full ID + .json.
        // This is critical because createHolonFromString validates that the filename matches the ID in the header.

        val rootId = "root-20250101T000000Z"
        val midId = "mid-20250101T000000Z"
        val leafId = "leaf-20250101T000000Z"

        val rootFile = "$rootId.json"
        val midFile = "$midId.json"
        val leafFile = "$leafId.json"

        val rootContent = """{ "header": { "id": "$rootId", "type": "AI_Persona_Root", "name": "Root" }, "payload": {} }"""
        val midContent = """{ "header": { "id": "$midId", "type": "T", "name": "Mid" }, "payload": {} }"""
        val leafContent = """{ "header": { "id": "$leafId", "type": "T", "name": "Leaf" }, "payload": {} }"""

        val initialState = KnowledgeGraphState(
            importFileContents = mapOf(
                rootFile to rootContent,
                midFile to midContent,
                leafFile to leafContent
            ),
            importSelectedActions = mapOf(
                rootFile to CreateRoot(),
                midFile to Integrate(rootId),
                leafFile to Integrate(midId)
            )
        )

        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            // [FIX] EXECUTE_IMPORT is non-public; only the owning feature can dispatch it.
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            // ASSERT
            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertEquals(3, writeActions.size, "Expected 3 writes (Root, Mid, Leaf).")

            // Verify Root Path
            val rootWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content?.contains(rootId) == true && it.payload?.get("path")?.jsonPrimitive?.content?.endsWith(rootId + ".json") == true }
            assertNotNull(rootWrite)
            assertEquals("$rootId/$rootId.json", rootWrite.payload?.get("path")?.jsonPrimitive?.content)

            // Verify Mid Path (Should be inside Root)
            val midWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content?.contains(midId) == true }
            assertNotNull(midWrite)
            assertEquals("$rootId/$midId/$midId.json", midWrite.payload?.get("path")?.jsonPrimitive?.content)

            // Verify Leaf Path (Should be inside Mid)
            val leafWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content?.contains(leafId) == true }
            assertNotNull(leafWrite)
            assertEquals("$rootId/$midId/$leafId/$leafId.json", leafWrite.payload?.get("path")?.jsonPrimitive?.content)

            // Verify Content Linkage (Mid should contain Leaf ref)
            val writtenMidContent = midWrite.payload?.get("content")?.jsonPrimitive?.content!!
            val parsedMid = createHolonFromString(writtenMidContent, "$rootId/$midId/$midId.json", platform)
            assertTrue(parsedMid.header.subHolons.any { it.id == leafId }, "Mid holon must contain reference to Leaf.")

            // Verify Content Linkage (Root should contain Mid ref)
            val writtenRootContent = rootWrite.payload?.get("content")?.jsonPrimitive?.content!!
            val parsedRoot = createHolonFromString(writtenRootContent, "$rootId/$rootId.json", platform)
            assertTrue(parsedRoot.header.subHolons.any { it.id == midId }, "Root holon must contain reference to Mid.")
        }
    }
}