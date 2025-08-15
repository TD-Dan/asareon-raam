package app.auf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ActionModelsTest {

    // UPDATE: Replaced the local parser instance with the canonical one from JsonProvider.
    // This aligns the test with our "Single Source of Truth" principle for serialization.
    private val jsonParser = JsonProvider.appJson

    @Test
    fun `CreateHolon action can be serialized and deserialized successfully`() {
        // 1. Arrange: Create a pristine test object.
        val originalAction = CreateHolon(
            parentId = "parent-id-123",
            content = """{"header": {"id": "new-holon-456"}}""",
            summary = "Create a new child holon"
        )

        // 2. Act: Perform the serialization/deserialization round-trip.
        val jsonString = jsonParser.encodeToString<Action>(originalAction)
        val deserializedAction = jsonParser.decodeFromString<Action>(jsonString)

        // 3. Assert: The object that comes back must be identical to the one we started with.
        assertEquals(originalAction, deserializedAction)
    }

    @Test
    fun `UpdateHolonContent action can be serialized and deserialized successfully`() {
        // 1. Arrange
        val originalAction = UpdateHolonContent(
            holonId = "existing-holon-789",
            newContent = """{"header": {"id": "existing-holon-789", "version": "1.1"}}""",
            summary = "Update an existing holon"
        )

        // 2. Act
        val jsonString = jsonParser.encodeToString<Action>(originalAction)
        val deserializedAction = jsonParser.decodeFromString<Action>(jsonString)

        // 3. Assert
        assertEquals(originalAction, deserializedAction)
    }

    @Test
    fun `CreateFile action can be serialized and deserialized successfully`() {
        // 1. Arrange
        val originalAction = CreateFile(
            filePath = "./reports/report-abc.md",
            content = "This is the content of the report.",
            summary = "Create a new markdown report"
        )

        // 2. Act
        val jsonString = jsonParser.encodeToString<Action>(originalAction)
        val deserializedAction = jsonParser.decodeFromString<Action>(jsonString)

        // 3. Assert
        assertEquals(originalAction, deserializedAction)
    }

    @Test
    fun `A list of mixed Actions can be serialized and deserialized successfully`() {
        // 1. Arrange: Create a list containing all types of Actions.
        // This simulates the real-world use case inside an ActionManifest.
        val originalActionList = listOf<Action>(
            CreateFile(
                filePath = "./dreams/dream.txt",
                content = "A dream about serialization.",
                summary = "Log a dream"
            ),
            CreateHolon(
                parentId = "parent-id-123",
                content = "{}",
                summary = "Create a test holon"
            ),
            UpdateHolonContent(
                holonId = "existing-holon-789",
                newContent = "{}",
                summary = "Update the test holon"
            )
        )

        // 2. Act
        val jsonString = jsonParser.encodeToString<List<Action>>(originalActionList)
        val deserializedActionList = jsonParser.decodeFromString<List<Action>>(jsonString)

        // 3. Assert
        assertEquals(originalActionList, deserializedActionList)
    }
}