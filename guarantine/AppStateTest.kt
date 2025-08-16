package app.auf

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.AssignParent
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.Ignore
import app.auf.core.ImportAction
import app.auf.core.Integrate
import app.auf.core.Quarantine
import app.auf.core.TextBlock
import app.auf.core.Update
import app.auf.model.CreateHolon
import app.auf.util.JsonProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test suite for data models defined in AppState.kt.
 * As per the System Hardening Protocol, this test verifies that our single,
 * canonical Json parser from JsonProvider can correctly handle the polymorphic
 * hierarchies defined in AppState.
 */
class AppStateTest {

    private val parser = JsonProvider.appJson

    @Test
    fun `ContentBlock polymorphic types can be serialized and deserialized successfully`() {
        // Arrange
        val originalBlocks: List<ContentBlock> = listOf(
            TextBlock("Some narrative text."),
            ActionBlock(
                actions = listOf(CreateHolon("p1", "{}", "Create action")),
                isResolved = true
            ),
            FileContentBlock("code.kt", "val x = 1", "kotlin"),
            AppRequestBlock("DO_A_THING"),
            AnchorBlock("anchor-id", buildJsonObject { put("test", "value") })
        )

        // Act
        val jsonString = parser.encodeToString<List<ContentBlock>>(originalBlocks)
        val deserializedBlocks = parser.decodeFromString<List<ContentBlock>>(jsonString)

        // Assert
        assertEquals(originalBlocks, deserializedBlocks)
    }

    @Test
    fun `ImportAction polymorphic types can be serialized and deserialized successfully`() {
        // Arrange
        val originalImportActions: List<ImportAction> = listOf(
            Update("holon-to-update-123"),
            Integrate("parent-holon-456"),
            AssignParent("new-parent-789"),
            Quarantine("File is malformed."),
            Ignore()
        )

        // Act
        val jsonString = parser.encodeToString<List<ImportAction>>(originalImportActions)
        val deserializedImportActions = parser.decodeFromString<List<ImportAction>>(jsonString)

        // Assert
        assertEquals(originalImportActions, deserializedImportActions)
    }
}