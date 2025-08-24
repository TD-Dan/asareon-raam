package app.auf.fakes

import app.auf.core.ContentBlock
import app.auf.core.TextBlock
import app.auf.model.ToolDefinition
import app.auf.service.AufTextParser
import app.auf.util.JsonProvider
import kotlinx.serialization.json.Json

/**
 * A fake implementation of the AufTextParser for use in unit tests.
 * This allows us to satisfy dependencies of classes that require an AufTextParser
 * without needing a fully functional parser or a real tool registry.
 *
 * For simplicity in tests not focused on parsing logic, its `parse` method
 * will return a single TextBlock, mimicking a basic text message.
 */
class FakeAufTextParser(
    // Match the constructor of the real AufTextParser
    jsonParser: Json = JsonProvider.appJson,
    toolRegistry: List<ToolDefinition> = emptyList()
) : AufTextParser(jsonParser, toolRegistry) {

    // You can configure this if a test needs a specific parse result
    var nextParseResult: List<ContentBlock> = emptyList()

    /**
     * Overrides the parse method to return a predefined simple TextBlock,
     * or the configured `nextParseResult`.
     */
    override fun parse(rawText: String): List<ContentBlock> {
        return if (nextParseResult.isNotEmpty()) {
            nextParseResult
        } else {
            // Default behavior: wrap the raw text in a single TextBlock
            // This is a common simple case and prevents NullPointerExceptions
            // if parse is called unexpectedly in a test.
            listOf(TextBlock(rawText))
        }
    }
}