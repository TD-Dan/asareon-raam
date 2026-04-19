package asareon.raam.feature.knowledgegraph

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.util.BasePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `execute writes a child whose filename needed id-repair to match its header id`() {
        // Regression for the observed "says will create N but only M land" behavior
        // with legacy HHMM-without-seconds filenames. The filename is
        // `dream-record-20250731T0925Z.json`, but id-repair rewrites the header id
        // (and thus the holon's canonical id in memory) to
        // `dream-record-20250731T092500Z`. Previously the execute phase looked up
        // each holon's action by matching filename-to-id, which misses here, so the
        // write was silently dropped. Fix: index by normalized header id.
        val parentId = "pl1-20251112T190000Z"
        val childCanonicalId = "dream-record-20250731T092500Z"
        val childFilenameId = "dream-record-20250731T0925Z"   // legacy HHMM form
        val parentFilePath = "$parentId/$parentId.json"
        val childImportPath = "$parentId/$childFilenameId.json"

        val parentContent = """
            { "header": { "id": "$parentId", "type": "AI_Persona_Root", "name": "P" }, "payload": {} }
        """.trimIndent()
        // Header id is the repair-equivalent of the filename — repair rewrites both
        // to the canonical form and they match, so the file loads fine.
        val childContent = """
            { "header": { "id": "$childFilenameId", "type": "T", "name": "C" }, "payload": {} }
        """.trimIndent()

        val sandboxRoot = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph"
        platform.createDirectories("$sandboxRoot/$parentId")
        platform.writeFileContent("$sandboxRoot/$parentFilePath", parentContent)

        val existingParent = createHolonFromString(parentContent, parentFilePath, platform)
        val initialState = KnowledgeGraphState(
            holons = mapOf(parentId to existingParent),
            importFileContents = mapOf(childImportPath to childContent),
            importSelectedActions = mapOf(childImportPath to Integrate(parentId))
        )
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            val childWrite = writeActions.find {
                it.payload?.get("path")?.jsonPrimitive?.content?.contains(childCanonicalId) == true
            }
            assertNotNull(childWrite, "Id-repaired child must still be written; the filename→id lookup used to miss it.")
            assertEquals(
                "$parentId/$childCanonicalId/$childCanonicalId.json",
                childWrite.payload?.get("path")?.jsonPrimitive?.content
            )
        }
    }

    // --- Test Group: Recursive import flag plumbing ---
    // The UI checkbox "Import sub-folders recursively" must reach the filesystem walker.
    // Previously, KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS hardcoded `recursive: true` in the
    // scoped-read request, so subfolders were always imported regardless of the flag.

    @Test
    fun `START_IMPORT_ANALYSIS propagates isImportRecursive=false to the filesystem request`() {
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(isImportRecursive = false))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))

            val readRequest = harness.processedActions
                .find { it.name == ActionRegistry.Names.FILESYSTEM_REQUEST_SCOPED_READ_UI }
            assertNotNull(readRequest, "Expected a FILESYSTEM_REQUEST_SCOPED_READ_UI to be dispatched.")
            val recursive = readRequest.payload?.get("recursive")?.jsonPrimitive?.boolean
            assertFalse(recursive == true, "recursive flag must reflect state.isImportRecursive=false, got: $recursive")
        }
    }

    // --- Test Group: Execute under an Ignore'd parent ---
    // When the user re-imports a folder whose parent holon is unchanged (auto-Ignored)
    // alongside new children that Integrate under it, the child must still be written
    // at a path derived from the kgState parent. Previously, `determinePath` fell
    // through to `else → null` for Ignore actions, poisoning the whole subtree:
    // every planned write silently dropped because no finalPath could be resolved.

    @Test
    fun `execute INTEGRATE under an Ignore'd parent in import set writes child with kgState-derived path`() {
        val parentId = "pl1-20251112T190000Z"
        val childId = "hl1-20251112T190000Z"
        val parentFilePath = "$parentId/$parentId.json"
        val childImportPath = "$parentId/$childId.json"

        // Parent content is identical in import and on disk → auto-Ignore.
        val parentContent = """
            { "header": { "id": "$parentId", "type": "AI_Persona_Root", "name": "P" }, "payload": {} }
        """.trimIndent()
        val childContent = """
            { "header": { "id": "$childId", "type": "T", "name": "C" }, "payload": {} }
        """.trimIndent()

        val sandboxRoot = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph"
        platform.createDirectories("$sandboxRoot/$parentId")
        platform.writeFileContent("$sandboxRoot/$parentFilePath", parentContent)

        val existingParent = createHolonFromString(parentContent, parentFilePath, platform)

        val initialState = KnowledgeGraphState(
            holons = mapOf(parentId to existingParent),
            // Both parent and child in the import set — parent is Ignore (identical), child is Integrate.
            importFileContents = mapOf(
                childImportPath to childContent
            ),
            importSelectedActions = mapOf(
                childImportPath to Integrate(parentId)
            )
        )
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            val childWrite = writeActions.find { it.payload?.get("path")?.jsonPrimitive?.content?.contains(childId) == true }
            assertNotNull(childWrite, "Child must be written; previously the Ignore'd parent null'd the whole subtree.")
            assertEquals("$parentId/$childId/$childId.json", childWrite.payload?.get("path")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `START_IMPORT_ANALYSIS propagates isImportRecursive=true to the filesystem request`() {
        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(isImportRecursive = true))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS))

            val readRequest = harness.processedActions
                .find { it.name == ActionRegistry.Names.FILESYSTEM_REQUEST_SCOPED_READ_UI }
            assertNotNull(readRequest)
            val recursive = readRequest.payload?.get("recursive")?.jsonPrimitive?.boolean
            assertTrue(recursive == true, "recursive flag must reflect state.isImportRecursive=true, got: $recursive")
        }
    }
}