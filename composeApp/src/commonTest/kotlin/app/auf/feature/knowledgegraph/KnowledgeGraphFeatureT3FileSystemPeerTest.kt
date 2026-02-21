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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Tier 3 Peer Test for KnowledgeGraphFeature <-> FileSystemFeature interaction.
 */
class KnowledgeGraphFeatureT3FileSystemPeerTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val kgFeature = KnowledgeGraphFeature(platform, testScope)
    private val fsFeature = FileSystemFeature(platform)

    @Test
    fun `holon modification round-trip should persist enriched header data correctly`() {
        // ARRANGE
        val initialTimestamp = "2025-01-01T12:00:00Z"
        val holonId = "hl1-20251112T190000Z"
        val personaId = "pl1-20251112T190000Z"
        val holonFilePath = "$personaId/$holonId/$holonId.json" // This is a path relative to the sandbox

        val initialInMemoryHolon = Holon(
            header = HolonHeader(
                id = holonId, name = "Old Name", type = "Test",
                filePath = holonFilePath, parentId = personaId, depth = 1,
                modifiedAt = initialTimestamp
            ),
            payload = buildJsonObject { put("data", "value") },
            rawContent = """{"header":{"id":"h1","name":"Old Name"},"payload":{}}"""
        )

        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(
                holons = mapOf(holonId to initialInMemoryHolon)
            ))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("ui", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject {
                put("holonId", holonId)
                put("newName", "New Name")
            }))

            // ASSERT
            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE && it.payload?.get("path")?.jsonPrimitive?.content == holonFilePath }
            assertNotNull(writeAction, "A SYSTEM_WRITE action for the correct file path should have been dispatched.")

            val writtenContent = writeAction.payload!!.jsonObject["content"]!!.jsonPrimitive.content
            // [REFACTOR] Use the canonical gateway to validate the written content,
            // which also ensures the test is using the same validation logic as production.
            val persistedHolon = createHolonFromString(writtenContent, holonFilePath, platform)

            assertEquals("New Name", persistedHolon.header.name)
            assertNotEquals(initialTimestamp, persistedHolon.header.modifiedAt)
            assertEquals(holonFilePath, persistedHolon.header.filePath)
            assertEquals(personaId, persistedHolon.header.parentId)
            assertEquals(1, persistedHolon.header.depth)
            assertEquals(buildJsonObject { put("data", "value") }, persistedHolon.payload)

            val rawParsedJson = json.parseToJsonElement(writtenContent).jsonObject
            assertFalse(rawParsedJson.containsKey("rawContent"))
        }
    }

    @Test
    fun `full load-modify-save roundtrip should preserve payload and execute sections`() {
        // ARRANGE
        val holonId = "hl1-20251112T190000Z"
        val personaId = "pl1-20251112T190000Z"

        // [THE FIX] Construct the full, absolute paths that the FileSystemFeature will use.
        val sandboxRoot = "${platform.getBasePathFor(BasePath.APP_ZONE)}/knowledgegraph"
        val holonFilePath = "$sandboxRoot/$personaId/$holonId.json"
        val personaFilePath = "$sandboxRoot/$personaId/$personaId.json"
        val personaDirPath = "$sandboxRoot/$personaId"

        val initialFileContent = """
            {
                "header": { "id": "$holonId", "type": "Test", "name": "Old Name" },
                "payload": { "data": "This is the payload" },
                "execute": { "action": "DO_SOMETHING" }
            }
        """.trimIndent()

        val initialPersonaContent = """
             {
                "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "sub_holons": [ { "id": "$holonId", "type": "Test", "summary": "A test holon" } ] },
                "payload": {}
            }
        """.trimIndent()

        // [THE FIX] Place the files and directories on the fake "disk" using their absolute paths.
        platform.writeFileContent(holonFilePath, initialFileContent)
        platform.writeFileContent(personaFilePath, initialPersonaContent)
        platform.directories.add(personaDirPath)
        platform.directories.add(sandboxRoot)


        val harness = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(fsFeature)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT 1: Load the data from the fake disk.
            harness.store.dispatch("system", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject {
                put("personaId", personaId)
            }))

            // ACT 2: Dispatch a managed command to modify the holon's header.
            harness.store.dispatch("ui", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject {
                put("holonId", holonId)
                put("newName", "New Name")
            }))

            // ASSERT
            // 1. Retrieve the final content written back to the fake disk using the absolute path.
            val finalFileContent = platform.writtenFiles[holonFilePath]
            assertNotNull(finalFileContent, "The holon file should have been written back to disk.")

            val initialJson = json.parseToJsonElement(initialFileContent).jsonObject
            val finalJson = json.parseToJsonElement(finalFileContent).jsonObject

            assertEquals("New Name", finalJson["header"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertEquals(initialJson["payload"], finalJson["payload"], "The payload section must be identical after the round-trip.")
            assertEquals(initialJson["execute"], finalJson["execute"], "The execute section must be identical after the round-trip.")
        }
    }
}