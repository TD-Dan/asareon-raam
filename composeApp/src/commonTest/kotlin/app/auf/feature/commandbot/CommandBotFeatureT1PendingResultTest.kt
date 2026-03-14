package app.auf.feature.commandbot

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import app.auf.util.LogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Side-Effect Tests for CommandBotFeature — specifically the hardened
 * feedback paths that ensure agents always get command status reports.
 *
 * These tests call `handleSideEffects` directly with controlled previousState
 * and newState, using FakeStore to capture dispatched actions.
 *
 * ## Coverage Map
 *
 * ### TTL Timeout Feedback (NEW — hardening critical path)
 * - CLEAR_PENDING_RESULT with expired entry → posts TIMEOUT feedback to session
 * - CLEAR_PENDING_RESULT with already-consumed entry → no feedback (normal cleanup)
 * - Timeout feedback message contains action name and TTL duration
 * - WARN log emitted for expired entries
 *
 * ### ACTION_RESULT Interception
 * - Matching correlationId → posts feedback + clears pending result
 * - Success=true → OK ✓ feedback
 * - Success=false → ERROR ✗ feedback
 * - Source feature mismatch → logged and ignored
 * - Missing payload → WARN log
 *
 * ### DELIVER_TO_SESSION
 * - Happy path → posts raw message to session
 * - Missing sessionId → WARN log, no dispatch
 * - Missing message → WARN log, no dispatch
 *
 * ### PERMISSION_DENIED Feedback
 * - Matching pending result → posts denial feedback + clears entry
 * - No matching pending result → DEBUG log (non-CommandBot path)
 */
class CommandBotFeatureT1SideEffectsTest {

    private fun createTestSetup(): Triple<CommandBotFeature, FakeStore, FakePlatformDependencies> {
        val platform = FakePlatformDependencies("test")
        val feature = CommandBotFeature(platform)
        val store = FakeStore(
            AppState(
                actionDescriptors = ActionRegistry.byActionName,
                identityRegistry = mapOf(
                    "commandbot" to feature.identity,
                    "agent.test-agent" to Identity(
                        uuid = "agent-uuid-1", localHandle = "test-agent",
                        handle = "agent.test-agent", name = "Test Agent", parentHandle = "agent"
                    )
                )
            ),
            platform
        )
        return Triple(feature, store, platform)
    }

    /** A PendingResult for use in state setup. */
    private fun pendingResult(
        correlationId: String = "corr-1",
        sessionId: String = "session-1",
        originatorId: String = "agent.test-agent",
        originatorName: String = "Test Agent",
        actionName: String = "session.CREATE"
    ): PendingResult {
        return PendingResult(
            correlationId = correlationId,
            sessionId = sessionId,
            originatorId = originatorId,
            originatorName = originatorName,
            actionName = actionName,
            createdAt = 500L
        )
    }

    // ========================================================================
    // TTL Timeout Feedback
    // ========================================================================

    @Test
    fun `CLEAR_PENDING_RESULT - expired entry posts timeout feedback to session`() {
        val (feature, store, platform) = createTestSetup()

        // previousState has the entry (not yet consumed); newState has it removed (reducer ran)
        val previousState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult())
        )
        val newState = CommandBotState(
            pendingResults = emptyMap() // reducer removed it
        )

        val action = Action(ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT, buildJsonObject {
            put("correlationId", "corr-1")
        })

        feature.handleSideEffects(action, store, previousState, newState)

        // Should dispatch a SESSION_POST with timeout feedback
        val feedbackPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST &&
                    it.payload?.get("senderId")?.jsonPrimitive?.contentOrNull == "commandbot"
        }
        assertNotNull(feedbackPost, "Timeout should trigger a feedback SESSION_POST to the originating session.")

        val message = feedbackPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("TIMEOUT"), "Feedback should contain 'TIMEOUT'. Got: $message")
        assertTrue(message.contains("session.CREATE"), "Feedback should contain the action name. Got: $message")
        assertTrue(message.contains("300"), "Feedback should mention the TTL duration (300s). Got: $message")

        assertEquals("session-1", feedbackPost.payload?.get("session")?.jsonPrimitive?.contentOrNull,
            "Feedback should be posted to the session where the command originated.")
    }

    @Test
    fun `CLEAR_PENDING_RESULT - expired entry logs WARN`() {
        val (feature, store, platform) = createTestSetup()

        val previousState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult())
        )
        val newState = CommandBotState(pendingResults = emptyMap())

        val action = Action(ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT, buildJsonObject {
            put("correlationId", "corr-1")
        })

        feature.handleSideEffects(action, store, previousState, newState)

        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("EXPIRED") },
            "Expired entry should produce a WARN log."
        )
    }

    @Test
    fun `CLEAR_PENDING_RESULT - already consumed entry does NOT post timeout feedback`() {
        val (feature, store, _) = createTestSetup()

        // previousState does NOT have the entry (was already consumed by ACTION_RESULT match)
        val previousState = CommandBotState(pendingResults = emptyMap())
        val newState = CommandBotState(pendingResults = emptyMap())

        val action = Action(ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT, buildJsonObject {
            put("correlationId", "corr-1")
        })

        feature.handleSideEffects(action, store, previousState, newState)

        val feedbackPosts = store.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_POST &&
                    it.payload?.get("message")?.jsonPrimitive?.contentOrNull?.contains("TIMEOUT") == true
        }
        assertTrue(feedbackPosts.isEmpty(),
            "No timeout feedback should be posted when the entry was already consumed (not in previousState).")
    }

    // ========================================================================
    // ACTION_RESULT Interception
    // ========================================================================

    @Test
    fun `ACTION_RESULT - matching correlationId posts success feedback and clears entry`() {
        val (feature, store, _) = createTestSetup()

        val newState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult(actionName = "filesystem.WRITE"))
        )

        val action = Action("filesystem.ACTION_RESULT", buildJsonObject {
            put("correlationId", "corr-1")
            put("requestAction", "filesystem.WRITE")
            put("success", true)
            put("summary", "File written successfully.")
        })

        feature.handleSideEffects(action, store, null, newState)

        // Should post OK feedback
        val feedbackPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(feedbackPost, "Matching ACTION_RESULT should post feedback to the session.")
        val message = feedbackPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("OK ✓"), "Success feedback should contain 'OK ✓'. Got: $message")
        assertTrue(message.contains("File written successfully"), "Should include summary. Got: $message")

        // Should dispatch CLEAR_PENDING_RESULT
        val clearAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT
        }
        assertNotNull(clearAction, "Should dispatch CLEAR_PENDING_RESULT after matching ACTION_RESULT.")
        assertEquals("corr-1", clearAction.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `ACTION_RESULT - matching correlationId with failure posts error feedback`() {
        val (feature, store, _) = createTestSetup()

        val newState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult(actionName = "filesystem.WRITE"))
        )

        val action = Action("filesystem.ACTION_RESULT", buildJsonObject {
            put("correlationId", "corr-1")
            put("requestAction", "filesystem.WRITE")
            put("success", false)
            put("error", "Permission denied.")
        })

        feature.handleSideEffects(action, store, null, newState)

        val feedbackPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(feedbackPost)
        val message = feedbackPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("ERROR ✗"), "Failure feedback should contain 'ERROR ✗'. Got: $message")
        assertTrue(message.contains("Permission denied"), "Should include error message. Got: $message")
    }

    @Test
    fun `ACTION_RESULT - source feature mismatch is logged and ignored`() {
        val (feature, store, platform) = createTestSetup()

        // PendingResult expects filesystem.WRITE, but ACTION_RESULT comes from agent.*
        val newState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult(actionName = "filesystem.WRITE"))
        )

        val action = Action("agent.ACTION_RESULT", buildJsonObject {
            put("correlationId", "corr-1")
            put("success", true)
        })

        feature.handleSideEffects(action, store, null, newState)

        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("expected 'filesystem'") },
            "Source feature mismatch should produce a WARN log."
        )

        val feedbackPosts = store.dispatchedActions.filter { it.name == ActionRegistry.Names.SESSION_POST }
        assertTrue(feedbackPosts.isEmpty(), "Mismatched source feature should not produce feedback.")
    }

    @Test
    fun `ACTION_RESULT - non-CommandBot correlationId silently ignored`() {
        val (feature, store, platform) = createTestSetup()

        // No pending result for this correlationId
        val newState = CommandBotState(pendingResults = emptyMap())

        val action = Action("agent.ACTION_RESULT", buildJsonObject {
            put("correlationId", "some-other-corr")
            put("success", true)
        })

        feature.handleSideEffects(action, store, null, newState)

        // No feedback, no logs (this is normal — other consumers' results)
        assertTrue(store.dispatchedActions.isEmpty())
    }

    @Test
    fun `ACTION_RESULT - missing payload logs WARN`() {
        val (feature, store, platform) = createTestSetup()
        val newState = CommandBotState()

        val action = Action("agent.ACTION_RESULT", null)
        feature.handleSideEffects(action, store, null, newState)

        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("Missing payload") },
            "ACTION_RESULT with no payload should produce a WARN log."
        )
    }

    // ========================================================================
    // DELIVER_TO_SESSION
    // ========================================================================

    @Test
    fun `DELIVER_TO_SESSION - happy path posts raw message to session`() {
        val (feature, store, _) = createTestSetup()

        val action = Action(ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION, buildJsonObject {
            put("sessionId", "session-1")
            put("message", "```json\n{\"files\": [\"a.txt\"]}\n```")
        })

        feature.handleSideEffects(action, store, null, CommandBotState())

        val post = store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(post, "DELIVER_TO_SESSION should dispatch a SESSION_POST.")
        assertEquals("session-1", post.payload?.get("session")?.jsonPrimitive?.contentOrNull)
        assertTrue(
            post.payload?.get("message")?.jsonPrimitive?.contentOrNull?.contains("files") == true,
            "Message content should be forwarded."
        )
    }

    @Test
    fun `DELIVER_TO_SESSION - missing sessionId logs WARN and does not dispatch`() {
        val (feature, store, platform) = createTestSetup()

        val action = Action(ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION, buildJsonObject {
            put("message", "some content")
            // sessionId omitted
        })

        feature.handleSideEffects(action, store, null, CommandBotState())

        assertTrue(store.dispatchedActions.isEmpty(), "Missing sessionId should prevent dispatch.")
        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("sessionId") },
            "Should log WARN for missing sessionId."
        )
    }

    @Test
    fun `DELIVER_TO_SESSION - missing message logs WARN and does not dispatch`() {
        val (feature, store, platform) = createTestSetup()

        val action = Action(ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION, buildJsonObject {
            put("sessionId", "session-1")
            // message omitted
        })

        feature.handleSideEffects(action, store, null, CommandBotState())

        assertTrue(store.dispatchedActions.isEmpty(), "Missing message should prevent dispatch.")
        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("message") },
            "Should log WARN for missing message."
        )
    }

    // ========================================================================
    // PERMISSION_DENIED Feedback
    // ========================================================================

    @Test
    fun `PERMISSION_DENIED - matching pending result posts denial feedback and clears entry`() {
        val (feature, store, _) = createTestSetup()

        val newState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult(
                actionName = "filesystem.WRITE",
                originatorId = "agent.test-agent",
                originatorName = "Test Agent"
            ))
        )

        val action = Action(ActionRegistry.Names.CORE_PERMISSION_DENIED, buildJsonObject {
            put("blockedAction", "filesystem.WRITE")
            put("originatorHandle", "agent.test-agent")
            put("missingPermissions", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("filesystem:workspace"))
            })
        })

        feature.handleSideEffects(action, store, null, newState)

        val feedbackPost = store.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_POST }
        assertNotNull(feedbackPost, "Should post denial feedback to the session.")
        val message = feedbackPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("Permission denied"), "Feedback should indicate permission denial. Got: $message")
        assertTrue(message.contains("filesystem:workspace"), "Feedback should list missing permissions. Got: $message")

        val clearAction = store.dispatchedActions.find { it.name == ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT }
        assertNotNull(clearAction, "Should clear the matched pending result.")
    }

    @Test
    fun `PERMISSION_DENIED - no matching pending result logs DEBUG`() {
        val (feature, store, platform) = createTestSetup()

        // Empty pending results — no match possible
        val newState = CommandBotState(pendingResults = emptyMap())

        val action = Action(ActionRegistry.Names.CORE_PERMISSION_DENIED, buildJsonObject {
            put("blockedAction", "filesystem.WRITE")
            put("originatorHandle", "agent.test-agent")
            put("missingPermissions", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("filesystem:workspace"))
            })
        })

        feature.handleSideEffects(action, store, null, newState)

        assertTrue(store.dispatchedActions.isEmpty(), "No feedback should be posted when there's no matching pending result.")
        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.DEBUG && it.message.contains("No matching pending result") },
            "Should log DEBUG for unmatched PERMISSION_DENIED."
        )
    }
}