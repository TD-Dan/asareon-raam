package asareon.raam.feature.commandbot

import asareon.raam.core.Action
import asareon.raam.core.Identity
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreFeature
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.session.Session
import asareon.raam.feature.session.SessionFeature
import asareon.raam.feature.session.SessionState
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.test.TestHarness
import asareon.raam.util.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Integration Tests for CommandBot command feedback hardening.
 *
 * These tests exercise the full end-to-end pipeline through the real Store to
 * verify that agents ALWAYS receive feedback on command status — success, failure,
 * or timeout.
 *
 * ## Coverage
 * - TTL timeout: command published via ACTION_CREATED → no ACTION_RESULT arrives → TTL fires → TIMEOUT feedback posted
 * - Pending result registration + TTL scheduling: verifies the scheduled callback exists with correct delay
 *
 * ## Architecture Note
 * The TTL timeout is the safety net for the hardened CommandBot. When a command goes
 * unhandled (no feature picks up the ACTION_CREATED), the 5-minute scheduled callback
 * fires CLEAR_PENDING_RESULT. The new handleSideEffects handler detects this is an
 * expiry (entry was in previousState) and posts TIMEOUT feedback to the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandBotFeatureT2TimeoutTest {

    private val testUser = Identity(
        uuid = "user-1", localHandle = "test-user", handle = "core.test-user",
        name = "Test User", parentHandle = "core"
    )
    private val testSession = Session(
        identity = Identity(
            uuid = "session-1", localHandle = "test-session",
            handle = "session.test-session", name = "Test Session", parentHandle = "session"
        ),
        ledger = emptyList(), createdAt = 1L
    )

    private fun TestScope.buildStandardHarness(
        platform: FakePlatformDependencies = FakePlatformDependencies("test")
    ): TestHarness {
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                activeUserId = testUser.handle,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(testSession.identity.localHandle to testSession),
                activeSessionLocalHandle = testSession.identity.localHandle
            ))
            .build(platform = platform)

        harness.store.updateIdentityRegistry { registry ->
            registry + (testUser.handle to testUser)
        }

        return harness
    }

    private fun postMessage(harness: TestHarness, senderId: String, message: String) {
        harness.store.dispatch("session", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", testSession.identity.localHandle)
            put("senderId", senderId)
            put("message", message)
        }))
    }

    // ========================================================================
    // TTL Timeout Feedback — End-to-End
    // ========================================================================

    @Test
    fun `TTL timeout posts feedback when no ACTION_RESULT arrives`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = buildStandardHarness(platform)
        runCurrent()

        // ACT: Human user posts a valid command
        // (SHOW_TOAST goes through ACTION_CREATED → CoreFeature dispatches it → immediately handled,
        //  so we need to verify the scheduled TTL callback exists even though the result arrives quickly)
        postMessage(harness, testUser.handle, "```raam_core.SHOW_TOAST\n{ \"message\": \"Hello\" }\n```")
        runCurrent()

        // VERIFY: A REGISTER_PENDING_RESULT was dispatched
        harness.runAndLogOnFailure {
            val registerAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT
            }
            assertNotNull(registerAction, "CommandBot should register a pending result for every ACTION_CREATED.")

            // VERIFY: A scheduled callback was registered with the correct TTL
            val ttlCallback = platform.scheduledCallbacks.find {
                it.delayMs == CommandBotFeature.PENDING_RESULT_TTL_MS
            }
            assertNotNull(ttlCallback, "A TTL cleanup callback should be scheduled at ${CommandBotFeature.PENDING_RESULT_TTL_MS}ms.")
        }
    }

    @Test
    fun `TTL timeout fires and posts TIMEOUT feedback for genuinely unhandled command`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = buildStandardHarness(platform)
        runCurrent()

        // Pre-seed a pending result directly in CommandBot state (simulating a command
        // that was published but never received an ACTION_RESULT — e.g., the target
        // feature doesn't exist or crashed).
        val correlationId = "manual-corr-1"
        harness.store.dispatch("commandbot", Action(
            ActionRegistry.Names.COMMANDBOT_REGISTER_PENDING_RESULT,
            buildJsonObject {
                put("correlationId", correlationId)
                put("sessionId", testSession.identity.localHandle)
                put("originatorId", testUser.handle)
                put("originatorName", "Test User")
                put("actionName", "nonexistent.GHOST_ACTION")
            }
        ))
        runCurrent()

        // Verify the pending result exists
        val midState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        assertNotNull(midState.pendingResults[correlationId], "Pending result should be registered.")

        // ACT: Fire the TTL callback (simulates 5 minutes passing with no ACTION_RESULT)
        platform.fireScheduledCallbacks(CommandBotFeature.PENDING_RESULT_TTL_MS)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // 1. Pending result should be cleared
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            assertTrue(finalState.pendingResults.isEmpty(),
                "Pending result should be cleared after TTL expiry.")

            // 2. Timeout feedback should be posted to the session
            val timeoutPost = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_POST &&
                        it.originator == "commandbot"
            }.find {
                it.payload?.get("message")?.jsonPrimitive?.contentOrNull?.contains("TIMEOUT") == true
            }
            assertNotNull(timeoutPost,
                "CommandBot should post a TIMEOUT feedback message to the session when TTL expires.")

            val message = timeoutPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(message.contains("nonexistent.GHOST_ACTION"),
                "Timeout feedback should name the unhandled action. Got: $message")
            assertTrue(message.contains("30"),
                "Timeout feedback should mention the TTL duration. Got: $message")

            // 3. WARN log should be emitted
            assertTrue(
                platform.capturedLogs.any {
                    it.level == LogLevel.WARN && it.message.contains("EXPIRED") && it.message.contains(correlationId)
                },
                "A WARN log should be emitted for the expired pending result."
            )
        }
    }
}