package app.auf.fakes

import app.auf.core.ChatMessage
import app.auf.service.SessionManager
import app.auf.util.PlatformDependencies
import app.auf.util.JsonProvider
import kotlinx.serialization.json.Json

/**
 * A fake implementation of the SessionManager for use in unit tests.
 * It allows tests to run without performing any actual file I/O for session state.
 */
class FakeSessionManager(
    platform: PlatformDependencies = FakePlatformDependencies(),
    jsonParser: Json = JsonProvider.appJson
) : SessionManager(platform, jsonParser) {

    private var savedHistory: List<ChatMessage>? = null

    override fun saveSession(chatHistory: List<ChatMessage>) {
        savedHistory = chatHistory
    }

    override fun loadSession(): List<ChatMessage>? {
        return savedHistory
    }
}