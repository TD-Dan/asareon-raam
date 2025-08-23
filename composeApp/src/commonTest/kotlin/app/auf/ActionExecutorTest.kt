// --- FILE: composeApp/src/commonTest/kotlin/app/auf/service/ActionExecutorTest.kt ---
package app.auf.service

import app.auf.core.Holon
import app.auf.core.HolonHeader
import app.auf.core.SubHolonRef
import app.auf.fakes.FakePlatformDependencies
import app.auf.model.CreateHolon
import app.auf.model.UpdateHolonContent
import app.auf.util.JsonProvider
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ActionExecutorTest {

    private fun setupTestEnvironment(): Pair<ActionExecutor, FakePlatformDependencies> {
        val platform = FakePlatformDependencies()
        val jsonParser = JsonProvider.appJson
        val actionExecutor = ActionExecutor(platform, jsonParser)
        return actionExecutor to platform
    }

    private val sampleHolonHeader = HolonHeader(
        id = "test-holon-1",
        type = "Project",
        name = "Test Holon",
        summary = "A sample holon for testing.",
        filePath = "holons/test-holon-1/test-holon-1.json"
    )

    private val sampleHolon = Holon(
        header = sampleHolonHeader.copy(filePath = ""),
        payload = kotlinx.serialization.json.buildJsonObject {}
    )

    @Test
    fun `execute UpdateHolonContent success path`() {
        // ARRANGE
        val (actionExecutor, platform) = setupTestEnvironment()
        val originalContent = JsonProvider.appJson.encodeToString(sampleHolon)
        val updatedContent = JsonProvider.appJson.encodeToString(sampleHolon.copy(header = sampleHolon.header.copy(name = "Updated Name")))

        platform.writeFileContent(sampleHolonHeader.filePath, originalContent)
        val manifest = listOf(
            UpdateHolonContent(
                holonId = "test-holon-1",
                newContent = updatedContent,
                summary = "Update name"
            )
        )
        val currentGraph = listOf(sampleHolonHeader)

        // ACT
        val result = actionExecutor.execute(manifest, "persona-id", currentGraph)

        // ASSERT
        assertIs<ActionExecutorResult.Success>(result, "Execution should be successful")
        val finalFileContent = platform.files[sampleHolonHeader.filePath]
        assertEquals(updatedContent, finalFileContent, "The file content should be updated")
    }

    @Test
    fun `execute CreateHolon success path`() {
        // ARRANGE
        val (actionExecutor, platform) = setupTestEnvironment()

        val parentHeader = HolonHeader(id = "parent-1", type = "Project", name = "Parent", summary = "", filePath = "holons/parent-1/parent-1.json")
        val parentHolon = Holon(parentHeader.copy(filePath = ""), kotlinx.serialization.json.buildJsonObject {})
        val parentContent = JsonProvider.appJson.encodeToString(parentHolon)
        platform.writeFileContent(parentHeader.filePath, parentContent)

        val newHolonHeader = HolonHeader(id = "new-child-1", type = "Task", name = "New Task", summary = "")
        val newHolon = Holon(newHolonHeader, kotlinx.serialization.json.buildJsonObject {})
        val newHolonContent = JsonProvider.appJson.encodeToString(newHolon)
        val expectedNewHolonPath = "holons/parent-1/new-child-1/new-child-1.json"

        val manifest = listOf(
            CreateHolon(parentId = "parent-1", content = newHolonContent, summary = "Create new task")
        )
        val currentGraph = listOf(parentHeader)

        // ACT
        val result = actionExecutor.execute(manifest, "persona-id", currentGraph)

        // ASSERT
        assertIs<ActionExecutorResult.Success>(result)
        assertTrue(platform.files.containsKey(expectedNewHolonPath), "New holon file should be created.")
        assertEquals(newHolonContent, platform.files[expectedNewHolonPath])

        val updatedParentContent = platform.files[parentHeader.filePath]
        val updatedParentHolon = JsonProvider.appJson.decodeFromString<Holon>(updatedParentContent!!)
        assertEquals(1, updatedParentHolon.header.subHolons.size, "Parent should have one sub_holon.")
        val subRef = updatedParentHolon.header.subHolons.first()
        assertEquals("new-child-1", subRef.id)
        assertEquals("Task", subRef.type)
    }

    @Test
    fun `execute CreateHolon should fail if parentId is not in the graph`() {
        // ARRANGE
        val (actionExecutor, platform) = setupTestEnvironment()
        val manifest = listOf(CreateHolon(parentId = "non-existent-parent", content = "{}", summary = "Fail"))
        val currentGraph = listOf(sampleHolonHeader) // Graph does not contain the parent

        // ACT
        val result = actionExecutor.execute(manifest, "persona-id", currentGraph)

        // ASSERT
        assertIs<ActionExecutorResult.Failure>(result)
        assertTrue(result.error.contains("Parent holon with ID 'non-existent-parent' not found"))
        assertTrue(platform.files.isEmpty(), "No files should be written on failure.")
    }

    @Test
    fun `execute CreateHolon should fail if parent file does not exist on disk`() {
        // ARRANGE
        val (actionExecutor, platform) = setupTestEnvironment()
        val parentHeader = HolonHeader(id = "parent-1", type = "Project", name = "Parent", summary = "", filePath = "holons/parent-1/parent-1.json")
        val currentGraph = listOf(parentHeader)
        val manifest = listOf(CreateHolon(parentId = "parent-1", content = "{}", summary = "Fail"))


        // ACT
        val result = actionExecutor.execute(manifest, "persona-id", currentGraph)

        // ASSERT
        assertIs<ActionExecutorResult.Failure>(result)
        assertTrue(result.error.contains("Parent holon file does not exist"))
        assertTrue(platform.files.isEmpty(), "No files should be created if the parent file is missing.")
    }

    @Test
    fun `execute CreateHolon should roll back by deleting child if parent update fails`() {
        // ARRANGE
        val (actionExecutor, platform) = setupTestEnvironment()

        val parentHeader = HolonHeader(id = "parent-1", type = "Project", name = "Parent", summary = "", filePath = "holons/parent-1/parent-1.json")
        val parentHolon = Holon(parentHeader.copy(filePath = ""), kotlinx.serialization.json.buildJsonObject {})
        val originalParentContent = JsonProvider.appJson.encodeToString(parentHolon)
        platform.writeFileContent(parentHeader.filePath, originalParentContent)

        val newHolonHeader = HolonHeader(id = "new-child-1", type = "Task", name = "New Task", summary = "")
        val newHolon = Holon(newHolonHeader, kotlinx.serialization.json.buildJsonObject {})
        val newHolonContent = JsonProvider.appJson.encodeToString(newHolon)
        val expectedNewHolonPath = "holons/parent-1/new-child-1/new-child-1.json"

        val manifest = listOf(
            CreateHolon(parentId = "parent-1", content = newHolonContent, summary = "Create new task")
        )
        val currentGraph = listOf(parentHeader)

        // Configure the fake platform to fail specifically on the parent file update
        platform.setFailOnWriteForPath(parentHeader.filePath)

        // ACT
        val result = actionExecutor.execute(manifest, "persona-id", currentGraph)

        // ASSERT
        assertIs<ActionExecutorResult.Failure>(result)
        assertTrue(result.error.contains("Failed to update parent holon"), "Error message should indicate parent update failure.")

        // CRITICAL: Assert that the rollback occurred
        assertFalse(platform.files.containsKey(expectedNewHolonPath), "Orphaned child file should have been deleted.")

        // Assert that the parent file was not corrupted
        assertEquals(originalParentContent, platform.files[parentHeader.filePath], "Parent file content should be unchanged.")
    }
}