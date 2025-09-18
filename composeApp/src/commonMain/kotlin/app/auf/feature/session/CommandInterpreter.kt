package app.auf.feature.session

import app.auf.core.*

/**
 * ## Mandate
 * A stateless service that interprets a CodeBlock to determine if it represents a
 * runnable tool command. It is responsible for parsing the command syntax and
 * constructing the appropriate Command to be dispatched.
 */
class CommandInterpreter {

    /**
     * Attempts to parse an Command from a CodeBlock.
     * @param block The CodeBlock to analyze.
     * @param sessionId The ID of the current session, required for session-specific actions.
     * @return An [AppAction] object if the block is a valid and recognized command, otherwise null.
     */
    fun interpret(block: CodeBlock, sessionId: String): AppAction? {
        val commandPrefix = "auf_"
        if (!block.language.startsWith(commandPrefix)) {
            return null
        }

        val command = block.language
        var argument = block.content.trim()

        // Handle multiple argument formats robustly
        if ((argument.startsWith("(\"") && argument.endsWith("\")")) ||
            (argument.startsWith("('") && argument.endsWith("')"))) {
            argument = argument.substring(2, argument.length - 2)
        } else if (argument.startsWith("(") && argument.endsWith(")")) {
            argument = argument.removeSurrounding("(", ")")
        } else if (argument.startsWith("\"") && argument.endsWith("\"")) {
            argument = argument.removeSurrounding("\"")
        } else if (argument.startsWith("'") && argument.endsWith("'")) {
            argument = argument.removeSurrounding("'")
        }

        // TODO: Replace with actual reflection to allow calling for all Commands
        return when (command) {
            "auf_toastMessage" -> ShowToast(argument)
            "auf_clearSession" -> ClearSession(sessionId)
            else -> null
        }
    }
}