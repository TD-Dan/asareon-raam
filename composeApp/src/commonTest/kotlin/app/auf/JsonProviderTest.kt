package app.auf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A dedicated test suite for the JsonProvider object.
 * As per the System Hardening Protocol, this test verifies that our single,
 * canonical Json parser is correctly configured for ALL polymorphic types
 * used in the application.
 *
 * This suite's passing is a prerequisite for injecting the provider into any
 * other part of the codebase.
 */
class JsonProviderTest {

    private val parser = JsonProvider.appJson

    @Test
    fun `JsonProvider can correctly serialize and deserialize polymorphic Actions`() {
        // Arrange
        val originalActions = listOf<Action>(
            CreateHolon("p1", "{}", "Create a test holon"),
            UpdateHolonContent("h1", "{}", "Update a test holon"),
            CreateFile("f1", "c1", "Create a test file")
        )

        // Act
        val jsonString = parser.encodeToString<List<Action>>(originalActions)
        val deserializedActions = parser.decodeFromString<List<Action>>(jsonString)

        // Assert
        assertEquals(originalActions, deserializedActions)
    }

    @Test
    fun `JsonProvider can correctly serialize and deserialize polymorphic ContentBlocks`() {
        // Arrange: Create a list of every ContentBlock type with corrected arguments.
        val originalBlocks = listOf<ContentBlock>(
            TextBlock("This is text."),
            ActionBlock(
                actions = listOf(CreateFile("f2", "c2", "s4")),
                isResolved = false
            ),
            FileContentBlock("MyFile.kt", "fun main() {}"),
            AppRequestBlock("START_DREAM_CYCLE"),
            AnchorBlock(
                "anchor-123",
                buildJsonObject { put("status", "OK") }
            )
        )

        // Act
        val jsonString = parser.encodeToString<List<ContentBlock>>(originalBlocks)
        val deserializedBlocks = parser.decodeFromString<List<ContentBlock>>(jsonString)

        // Assert
        assertEquals(originalBlocks, deserializedBlocks)
        // FIX: Access the element at index 1 BEFORE casting to ActionBlock.
        assertTrue((deserializedBlocks[1] as ActionBlock).actions.first() is CreateFile)
    }

    @Test
    fun `JsonProvider gracefully ignores unknown keys`() {
        // Arrange
        val jsonWithUnknownKey = """
            {
                "type": "TextBlock",
                "text": "Hello",
                "someFutureProperty": 123
            }
        """

        // Act
        val deserializedBlock = parser.decodeFromString<ContentBlock>(jsonWithUnknownKey)

        // Assert
        assertTrue(deserializedBlock is TextBlock)
        assertEquals("Hello", (deserializedBlock as TextBlock).text)
    }
}