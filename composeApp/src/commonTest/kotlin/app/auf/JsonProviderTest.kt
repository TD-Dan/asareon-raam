package app.auf

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Canonization test suite for the JsonProvider singleton.
 *
 * ---
 * ## Mandate
 * This test suite's sole responsibility is to enforce the "Single Source of Truth" mandate
 * of the `JsonProvider`. It programmatically verifies that the `JsonProvider.appJson`
 * instance is correctly configured to handle **every single subclass** of every polymorphic
 * interface used within the application.
 *
 * If a new polymorphic subclass is added to the app and this test is not updated, the build
 * should fail. If this test passes, we have a high degree of confidence that any serialization
 * errors encountered elsewhere in the app are due to malformed input strings, not a faulty
 * or incomplete parser configuration.
 *
 * ---
 * ## Test Strategy
 * - For each sealed interface, a list containing one instance of **every** concrete
 *   subclass is created.
 * - This list is serialized to a JSON string.
 * - The JSON string is deserialized back into a list of the interface type.
 * - The test passes if the deserialized list is identical to the original, proving that
 *   the parser correctly handled all subtypes.
 *
 * @version 1.0
 * @since 2025-08-16
 */
class JsonProviderTest {

    private val parser = JsonProvider.appJson

    @Test
    fun `all Action subtypes are registered and can be serialized and deserialized`() {
        // Arrange: A list containing one instance of every known Action subclass.
        val originalActions: List<Action> = listOf(
            CreateHolon("p1", "{}", "Create action"),
            UpdateHolonContent("h1", "{}", "Update action"),
            CreateFile("path/f1", "content", "Create file")
        )

        try {
            // Act
            val jsonString = parser.encodeToString<List<Action>>(originalActions)
            val deserializedActions = parser.decodeFromString<List<Action>>(jsonString)

            // Assert
            assertEquals(originalActions, deserializedActions, "The deserialized list of Actions must match the original.")

        } catch (e: Exception) {
            fail("Serialization failed for the 'Action' hierarchy. Is a new subclass missing from the JsonProvider configuration? Error: ${e.message}")
        }
    }

    @Test
    fun `all ContentBlock subtypes are registered and can be serialized and deserialized`() {
        // Arrange: A list containing one instance of every known ContentBlock subclass.
        val originalBlocks: List<ContentBlock> = listOf(
            TextBlock("Some narrative text."),
            ActionBlock(actions = listOf(CreateHolon("p1", "{}", "Create action"))),
            FileContentBlock("code.kt", "val x = 1", "kotlin"),
            AppRequestBlock("DO_A_THING"),
            AnchorBlock("anchor-id", buildJsonObject { put("test", "value") })
        )

        try {
            // Act
            val jsonString = parser.encodeToString<List<ContentBlock>>(originalBlocks)
            val deserializedBlocks = parser.decodeFromString<List<ContentBlock>>(jsonString)

            // Assert
            assertEquals(originalBlocks, deserializedBlocks, "The deserialized list of ContentBlocks must match the original.")
        } catch (e: Exception) {
            fail("Serialization failed for the 'ContentBlock' hierarchy. Is a new subclass missing from the JsonProvider configuration? Error: ${e.message}")
        }
    }

    @Test
    fun `all ImportAction subtypes are registered and can be serialized and deserialized`() {
        // Arrange: A list containing one instance of every known ImportAction subclass.
        val originalImportActions: List<ImportAction> = listOf(
            Update(targetHolonId = "holon-to-update-123"),
            Integrate(parentHolonId = "parent-holon-456"),
            AssignParent(assignedParentId = "new-parent-789"),
            Quarantine(reason = "File is malformed."),
            Ignore()
        )

        try {
            // Act
            val jsonString = parser.encodeToString<List<ImportAction>>(originalImportActions)
            val deserializedImportActions = parser.decodeFromString<List<ImportAction>>(jsonString)

            // Assert
            assertEquals(originalImportActions, deserializedImportActions, "The deserialized list of ImportActions must match the original.")
        } catch (e: Exception) {
            fail("Serialization failed for the 'ImportAction' hierarchy. Is a new subclass missing from the JsonProvider configuration? Error: ${e.message}")
        }
    }
}