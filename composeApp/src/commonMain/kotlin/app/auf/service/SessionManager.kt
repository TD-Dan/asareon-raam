package app.auf.service

import app.auf.core.ChatMessage
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * ---
 * ## Mandate
 * Manages the persistence of the chat session. It is responsible for loading the chat
 * history from a dedicated session file on startup and saving it back to the file
 * whenever it changes. This ensures that the application state is resilient to crashes.
 *
 * ---
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: For all file system I/O.
 * - `kotlinx.serialization.json.Json`: For serializing the chat history.
 *
 * @version 1.0
 * @since 2025-08-27
 */
class SessionManager(
    private val platform: PlatformDependencies,
    private val jsonParser: Json
) {
    private val sessionFilePath: String

    init {
        val sessionDir = platform.getBasePathFor(BasePath.SESSIONS)
        platform.createDirectories(sessionDir)
        sessionFilePath = sessionDir + platform.pathSeparator + "active_session.json"
    }

    /**
     * Saves the provided list of ChatMessages to the active_session.json file.
     * This operation overwrites the existing file.
     */
    fun saveSession(chatHistory: List<ChatMessage>) {
        try {
            val jsonString = jsonParser.encodeToString(ListSerializer(ChatMessage.serializer()), chatHistory)
            platform.writeFileContent(sessionFilePath, jsonString)
        } catch (e: Exception) {
            println("ERROR: Could not save session file: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Loads a list of ChatMessages from the active_session.json file.
     * Returns null if the file does not exist or fails to parse, allowing the app
     * to start with a fresh session.
     */
    fun loadSession(): List<ChatMessage>? {
        if (!platform.fileExists(sessionFilePath)) return null

        return try {
            val jsonString = platform.readFileContent(sessionFilePath)
            // Avoid loading an empty file which would crash the parser
            if (jsonString.isBlank()) {
                null
            } else {
                jsonParser.decodeFromString(ListSerializer(ChatMessage.serializer()), jsonString)
            }
        } catch (e: Exception) {
            println("WARNING: Could not parse session file. Starting fresh. Error: ${e.message}")
            null
        }
    }
}