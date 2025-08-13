package app.auf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.assertFalse

class ActionModelsTest {

    // As per the System Hardening Protocol, we define a single, correctly configured
    // JSON parser for all tests. This is the crucial configuration that was likely
    // missing or incorrect in the application code.
    private val jsonParser = Json {
        prettyPrint = true
        serializersModule = SerializersModule {
            // Explicitly register the sealed interface 'Action' and all of its direct subclasses.
            // This tells the serializer exactly what concrete types to expect when it
            // encounters the abstract 'Action' type.
            polymorphic(Action::class) {
                subclass(CreateHolon::class)
                subclass(UpdateHolonContent::class)
                subclass(CreateFile::class)
            }
        }
    }

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