package asareon.raam.feature.commandbot

import asareon.raam.core.Action
import asareon.raam.core.AppState
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Side-Effect Tests for CommandBotFeature — Feedback Loop Hardening.
 *
 * Mandate (P-TEST-001, T1): Direct calls to [CommandBotFeature.handleSideEffects]
 * using a [FakeStore] to capture dispatched actions. No TestEnvironment or coroutines.
 *
 * ## Coverage Map
 *
 * ### TTL Timeout Feedback (COMMANDBOT_CLEAR_PENDING_RESULT)
 * - Expired pending result → posts TIMEOUT ⏱ feedback to session + logs WARN
 * - Already-consumed result (absent in previousState) → no feedback posted
 *
 * ### ACTION_RESULT Interception
 * - Matching correlationId → posts success feedback to session + clears pending
 * - Matching correlationId with failure → posts ERROR ✗ feedback
 * - Non-matching correlationId → no side effects
 * - Missing payload → logs WARN
 *
 * ### DELIVER_TO_SESSION
 * - Happy path → posts raw message to session
 * - Missing sessionId → logs WARN, no dispatch
 * - Missing message → logs WARN, no dispatch
 *
 * ### PERMISSION_DENIED Feedback
 * - Matching pending result → posts denial feedback + clears pending
 * - No matching pending result → logs DEBUG, no feedback
 */
class CommandBotFeatureT1PendingResultTest {

    private val platform = FakePlatformDependencies("test")

    private fun createFeature(): CommandBotFeature {
        return CommandBotFeature(platform)
    }

    private fun createFakeStore(): FakeStore {
        return FakeStore(AppState(), platform)
    }

    /** Creates a PendingResult for test state. */
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
    // TTL Timeout Feedback (COMMANDBOT_CLEAR_PENDING_RESULT)
    // ========================================================================

    @Test
    fun `TTL expiry posts timeout feedback when pending result was not consumed`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(correlationId = "corr-1", sessionId = "session-1", actionName = "session.CREATE")

        // previousState has the entry (it existed before the reducer cleared it)
        val previousState = CommandBotState(pendingResults = mapOf("corr-1" to pending))
        // newState has the entry removed (reducer just cleared it)
        val newState = CommandBotState(pendingResults = emptyMap())

        val clearAction = Action(
            name = ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
            payload = buildJsonObject { put("correlationId", "corr-1") }
        )

        // ACT
        feature.handleSideEffects(clearAction, store, previousState, newState)

        // ASSERT: feedback message posted to session
        val sessionPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(sessionPost, "A timeout feedback message should be posted to the session.")
        val message = sessionPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("TIMEOUT"), "Feedback should contain TIMEOUT indicator. Got: $message")
        assertTrue(message.contains("session.CREATE"), "Feedback should name the timed-out action. Got: $message")

        // ASSERT: WARN logged
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.WARN && it.message.contains("EXPIRED") && it.message.contains("corr-1")
        }, "Should log WARN for expired pending result.")
    }

    @Test
    fun `TTL expiry does NOT post feedback when pending result was already consumed`() {
        val feature = createFeature()
        val store = createFakeStore()

        // previousState does NOT have the entry (it was already consumed by ACTION_RESULT match)
        val previousState = CommandBotState(pendingResults = emptyMap())
        val newState = CommandBotState(pendingResults = emptyMap())

        val clearAction = Action(
            name = ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT,
            payload = buildJsonObject { put("correlationId", "corr-already-consumed") }
        )

        // ACT
        feature.handleSideEffects(clearAction, store, previousState, newState)

        // ASSERT: No feedback message posted
        val sessionPosts = store.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertTrue(sessionPosts.isEmpty(),
            "No feedback should be posted when the pending result was already consumed (absent from previousState).")
    }

    // ========================================================================
    // ACTION_RESULT Interception
    // ========================================================================

    @Test
    fun `ACTION_RESULT success posts OK feedback and clears pending result`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(correlationId = "corr-1", actionName = "session.CREATE")
        val currentState = CommandBotState(pendingResults = mapOf("corr-1" to pending))

        val actionResult = Action(
            name = "session.ACTION_RESULT",
            payload = buildJsonObject {
                put("correlationId", "corr-1")
                put("requestAction", "session.CREATE")
                put("success", true)
                put("summary", "Session created.")
            }
        )

        // ACT
        feature.handleSideEffects(actionResult, store, currentState, currentState)

        // ASSERT: feedback posted
        val sessionPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(sessionPost, "Success feedback should be posted to session.")
        val message = sessionPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("OK ✓"), "Success feedback should contain OK ✓. Got: $message")
        assertTrue(message.contains("Session created."), "Success feedback should include summary. Got: $message")

        // ASSERT: pending result cleared
        val clearAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT
        }
        assertNotNull(clearAction, "CLEAR_PENDING_RESULT should be dispatched.")
        assertEquals("corr-1", clearAction.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `ACTION_RESULT failure posts ERROR feedback`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(correlationId = "corr-2", actionName = "filesystem.WRITE")
        val currentState = CommandBotState(pendingResults = mapOf("corr-2" to pending))

        val actionResult = Action(
            name = "filesystem.ACTION_RESULT",
            payload = buildJsonObject {
                put("correlationId", "corr-2")
                put("requestAction", "filesystem.WRITE")
                put("success", false)
                put("error", "Permission denied: path outside sandbox.")
            }
        )

        // ACT
        feature.handleSideEffects(actionResult, store, currentState, currentState)

        // ASSERT
        val sessionPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(sessionPost, "Failure feedback should be posted to session.")
        val message = sessionPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("ERROR ✗"), "Failure feedback should contain ERROR ✗. Got: $message")
        assertTrue(message.contains("Permission denied"), "Failure feedback should include error detail. Got: $message")
    }

    @Test
    fun `ACTION_RESULT with non-matching correlationId is ignored`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(correlationId = "corr-1")
        val currentState = CommandBotState(pendingResults = mapOf("corr-1" to pending))

        val actionResult = Action(
            name = "session.ACTION_RESULT",
            payload = buildJsonObject {
                put("correlationId", "corr-unrelated")
                put("success", true)
            }
        )

        // ACT
        feature.handleSideEffects(actionResult, store, currentState, currentState)

        // ASSERT: no side effects
        assertTrue(store.dispatchedActions.isEmpty(),
            "ACTION_RESULT with non-matching correlationId should not trigger any side effects.")
    }

    @Test
    fun `ACTION_RESULT with missing payload logs WARN`() {
        val feature = createFeature()
        val store = createFakeStore()

        val currentState = CommandBotState(
            pendingResults = mapOf("corr-1" to pendingResult())
        )

        val actionResult = Action(name = "session.ACTION_RESULT", payload = null)

        // ACT
        feature.handleSideEffects(actionResult, store, currentState, currentState)

        // ASSERT
        assertTrue(store.dispatchedActions.isEmpty(), "No side effects for null payload.")
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.WARN && it.message.contains("Missing payload")
        }, "Should log WARN for missing payload on ACTION_RESULT.")
    }

    @Test
    fun `ACTION_RESULT from wrong source feature is rejected`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(correlationId = "corr-1", actionName = "session.CREATE")
        val currentState = CommandBotState(pendingResults = mapOf("corr-1" to pending))

        // Source feature is "agent" but pending action belongs to "session"
        val actionResult = Action(
            name = "agent.ACTION_RESULT",
            payload = buildJsonObject {
                put("correlationId", "corr-1")
                put("requestAction", "session.CREATE")
                put("success", true)
            }
        )

        // ACT
        feature.handleSideEffects(actionResult, store, currentState, currentState)

        // ASSERT: rejected — no feedback posted
        val sessionPosts = store.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertTrue(sessionPosts.isEmpty(), "ACTION_RESULT from wrong source feature should be ignored.")
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.WARN && it.message.contains("expected 'session'")
        }, "Should log WARN for source feature mismatch.")
    }

    // ========================================================================
    // DELIVER_TO_SESSION
    // ========================================================================

    @Test
    fun `DELIVER_TO_SESSION posts raw message to specified session`() {
        val feature = createFeature()
        val store = createFakeStore()

        val deliverAction = Action(
            name = ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
            payload = buildJsonObject {
                put("sessionId", "session-1")
                put("message", "```json\n{\"files\": [\"a.txt\"]}\n```")
            }
        )

        // ACT
        feature.handleSideEffects(deliverAction, store, null, CommandBotState())

        // ASSERT
        val sessionPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(sessionPost, "DELIVER_TO_SESSION should dispatch a SESSION_POST.")
        assertEquals("session-1", sessionPost.payload?.get("session")?.jsonPrimitive?.contentOrNull)
        val message = sessionPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("a.txt"), "Message should be passed through without modification.")
    }

    @Test
    fun `DELIVER_TO_SESSION with missing sessionId logs WARN and does nothing`() {
        val feature = createFeature()
        val store = createFakeStore()

        val deliverAction = Action(
            name = ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
            payload = buildJsonObject {
                put("message", "some data")
            }
        )

        // ACT
        feature.handleSideEffects(deliverAction, store, null, CommandBotState())

        // ASSERT
        assertTrue(store.dispatchedActions.isEmpty(), "Nothing should be dispatched for missing sessionId.")
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.WARN && it.message.contains("sessionId")
        })
    }

    @Test
    fun `DELIVER_TO_SESSION with missing message logs WARN and does nothing`() {
        val feature = createFeature()
        val store = createFakeStore()

        val deliverAction = Action(
            name = ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
            payload = buildJsonObject {
                put("sessionId", "session-1")
            }
        )

        // ACT
        feature.handleSideEffects(deliverAction, store, null, CommandBotState())

        // ASSERT
        assertTrue(store.dispatchedActions.isEmpty(), "Nothing should be dispatched for missing message.")
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.WARN && it.message.contains("message")
        })
    }

    // ========================================================================
    // PERMISSION_DENIED Feedback
    // ========================================================================

    @Test
    fun `PERMISSION_DENIED with matching pending result posts denial feedback and clears entry`() {
        val feature = createFeature()
        val store = createFakeStore()

        val pending = pendingResult(
            correlationId = "corr-1",
            originatorId = "agent.coder",
            originatorName = "Coder Agent",
            actionName = "filesystem.WRITE"
        )
        val currentState = CommandBotState(pendingResults = mapOf("corr-1" to pending))

        val deniedAction = Action(
            name = ActionRegistry.Names.CORE_PERMISSION_DENIED,
            payload = buildJsonObject {
                put("blockedAction", "filesystem.WRITE")
                put("originatorHandle", "agent.coder")
                put("missingPermissions", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("filesystem:workspace"))
                })
            }
        )

        // ACT
        feature.handleSideEffects(deniedAction, store, null, currentState)

        // ASSERT: feedback posted
        val sessionPost = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertNotNull(sessionPost, "Permission denial feedback should be posted to session.")
        val message = sessionPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(message.contains("Permission denied"), "Should mention permission denied. Got: $message")
        assertTrue(message.contains("Coder Agent"), "Should name the originator. Got: $message")
        assertTrue(message.contains("filesystem:workspace"), "Should name the missing permission. Got: $message")

        // ASSERT: pending result cleared
        val clearAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.COMMANDBOT_CLEAR_PENDING_RESULT
        }
        assertNotNull(clearAction, "CLEAR_PENDING_RESULT should be dispatched.")
        assertEquals("corr-1", clearAction.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `PERMISSION_DENIED with no matching pending result logs DEBUG and does nothing`() {
        val feature = createFeature()
        val store = createFakeStore()

        // No matching pending result in state
        val currentState = CommandBotState(pendingResults = emptyMap())

        val deniedAction = Action(
            name = ActionRegistry.Names.CORE_PERMISSION_DENIED,
            payload = buildJsonObject {
                put("blockedAction", "filesystem.WRITE")
                put("originatorHandle", "agent.unknown")
                put("missingPermissions", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("filesystem:workspace"))
                })
            }
        )

        // ACT
        feature.handleSideEffects(deniedAction, store, null, currentState)

        // ASSERT
        val sessionPosts = store.dispatchedActions.filter {
            it.name == ActionRegistry.Names.SESSION_POST
        }
        assertTrue(sessionPosts.isEmpty(), "No feedback should be posted when there's no matching pending result.")
        assertTrue(platform.capturedLogs.any {
            it.level == LogLevel.DEBUG && it.message.contains("No matching pending result")
        })
    }
}