package app.auf.feature.commandbot

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.session.BlockSeparatingParser
import app.auf.feature.session.ContentBlock
import app.auf.feature.session.LedgerEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * A headless, stateless agent that observes all session transcripts for command directives
 * (`auf_` code blocks) and dispatches them as universal Actions, making the application
 * universally scriptable.
 */
class CommandBotFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "commandbot"
    override val composableProvider: Feature.ComposableProvider? = null

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable
    private data class MessagePostedPayload(val sessionId: String, val entry: LedgerEntry)

    // --- Utilities ---
    private val blockParser = BlockSeparatingParser()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val payload = action.payload?.let {
                    try {
                        json.decodeFromJsonElement<MessagePostedPayload>(it)
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.ERROR, name, "Failed to decode MessagePostedPayload", e)
                        null
                    }
                } ?: return

                val entry = payload.entry

                // Guardrail (CAG-001): Self-Reaction Prevention. This is the most critical check.
                if (entry.senderId == this.name) {
                    return
                }

                // Find and process all command blocks in the message.
                entry.rawContent?.let { content ->
                    blockParser.parse(content)
                        .filterIsInstance<ContentBlock.CodeBlock>()
                        .filter { it.language.startsWith("auf_") }
                        .forEach { commandBlock ->
                            // *** THE FIX: Pass the original senderId for correct originator stamping ***
                            processCommandBlock(commandBlock, payload.sessionId, entry.senderId, store)
                        }
                }
            }
        }
    }

    private fun processCommandBlock(block: ContentBlock.CodeBlock, sessionId: String, originalSenderId: String, store: Store) {
        val actionName = block.language.removePrefix("auf_")
        val payloadString = block.code

        try {
            val payloadJson = if (payloadString.isNotBlank()) {
                json.parseToJsonElement(payloadString) as JsonObject
            } else {
                buildJsonObject {}
            }

            val commandAction = Action(name = actionName, payload = payloadJson)

            // Guardrail (CAG-002): Causality Tracking. The action is dispatched on BEHALF of the original sender.
            store.deferredDispatch(originalSenderId, commandAction)

        } catch (e: Exception) {
            // Guardrail (CAG-003): Robust Error Handling with feedback loop.
            platformDependencies.log(
                LogLevel.ERROR,
                name,
                "Failed to parse command '$actionName' due to invalid JSON payload.",
                e
            )

            val errorMessage = """
                ```text
                [COMMAND BOT ERROR]
                Action Name: $actionName
                Error: Failed to parse command JSON payload. Please check for syntax errors.
                Details: ${e.message}
                ```
            """.trimIndent()

            val feedbackAction = Action(
                name = ActionNames.SESSION_POST,
                payload = buildJsonObject {
                    put("session", sessionId)
                    put("senderId", this@CommandBotFeature.name)
                    put("message", errorMessage)
                }
            )
            store.deferredDispatch(this.name, feedbackAction)
        }
    }
}