package app.auf.feature.session

import app.auf.core.CodeBlock

/**
 * ## Mandate
 * A stateless service that interprets a CodeBlock to determine if it represents a
 * runnable tool command. It is responsible for parsing the command syntax.
 */
class CommandInterpreter {

    /**
     * A simple, structured data class to hold the result of a successful command parse.
     */
    data class ToolCall(val command: String, val argument: String)

    /**
     * Attempts to parse a command from a CodeBlock.
     * @return A [ToolCall] object if the block is a valid command, otherwise null.
     */
    fun interpret(block: CodeBlock): ToolCall? {
        val commandPrefix = "auf_"
        if (!block.language.startsWith(commandPrefix)) {
            return null
        }

        val command = block.language
        var argument = block.content.trim()

        // --- CORRECTED: Handle multiple argument formats robustly ---
        // 1. Handle function-call style: ("argument") or ('argument')
        if ((argument.startsWith("(\"") && argument.endsWith("\")")) ||
            (argument.startsWith("('") && argument.endsWith("')"))) {
            argument = argument.substring(2, argument.length - 2)
        }
        // 2. Handle simple parenthetical wrapping: (argument)
        else if (argument.startsWith("(") && argument.endsWith(")")) {
            argument = argument.removeSurrounding("(", ")")
        }
        // 3. Handle standard quotes: "argument" or 'argument'
        else if (argument.startsWith("\"") && argument.endsWith("\"")) {
            argument = argument.removeSurrounding("\"")
        } else if (argument.startsWith("'") && argument.endsWith("'")) {
            argument = argument.removeSurrounding("'")
        }

        return ToolCall(command, argument)
    }
}