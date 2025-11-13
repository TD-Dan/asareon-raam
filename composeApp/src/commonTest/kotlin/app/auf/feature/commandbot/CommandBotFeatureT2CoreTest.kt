package app.auf.feature.commandbot

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreFeature
import app.auf.feature.core.CoreState
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Tests for CommandBotFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's logic and side effects
 * in response to events from its peers within a realistic TestEnvironment.
 */
class CommandBotFeatureT2CoreTest {

    private val testUser = Identity("user-1", "Test User")
    private val testSession = Session("session-1", "Test Session", emptyList(), 1L)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `parses and dispatches a valid command from a session message`() = runTest {
        // ARRANGE
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(sessions = mapOf(testSession.id to testSession), activeSessionId = testSession.id))
            .build(platform = platform)

        val commandMessage = """
            Here is a command:
            ```auf_core.SHOW_TOAST
            { "message": "Hello from the bot!" }
            ```
        """.trimIndent()

        // ACT
        harness.store.dispatch(testUser.id, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", testUser.id)
            put("message", commandMessage)
        }))
        runCurrent() // Allow all deferred actions (like the .publish event) to process

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedToastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
            assertNotNull(dispatchedToastAction, "The command bot should have dispatched a CORE_SHOW_TOAST action.")

            // Verify Originator Integrity (CAG-002)
            assertEquals(testUser.id, dispatchedToastAction.originator, "The originator of the dispatched action must be the original message sender.")

            val payloadMessage = dispatchedToastAction.payload?.get("message")?.jsonPrimitive?.content
            assertEquals("Hello from the bot!", payloadMessage)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatches a feedback message on JSON parsing failure`() = runTest {
        // ARRANGE
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(sessions = mapOf(testSession.id to testSession), activeSessionId = testSession.id))
            .build(platform = platform)

        val malformedCommand = "```auf_core.SHOW_TOAST\n{ this is not valid json }\n```"

        // ACT
        harness.store.dispatch(testUser.id, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", testUser.id)
            put("message", malformedCommand)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedToastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
            assertNull(dispatchedToastAction, "No toast action should be dispatched for a malformed command.")

            val feedbackAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
            assertNotNull(feedbackAction, "A new session.POST action should be dispatched as feedback.")

            // Verify the feedback came from the bot
            assertEquals("commandbot", feedbackAction.originator)
            assertEquals("commandbot", feedbackAction.payload?.get("senderId")?.jsonPrimitive?.content)
            assertEquals(testSession.id, feedbackAction.payload?.get("session")?.jsonPrimitive?.content)

            // Verify the feedback contains a helpful error message
            val feedbackMessage = feedbackAction.payload?.get("message")?.jsonPrimitive?.content ?: ""
            // *** THE FIX: Assert for the correct error string ***
            assertTrue(feedbackMessage.contains("[COMMAND BOT ERROR]"), "The feedback message should contain an error.")
            assertTrue(feedbackMessage.contains("core.SHOW_TOAST"), "The feedback message should contain the attempted command.")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ignores commands originating from itself to prevent loops`() = runTest {
        // ARRANGE: (CAG-001 Self-Reaction Prevention)
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(sessions = mapOf(testSession.id to testSession), activeSessionId = testSession.id))
            .build(platform = platform)

        val selfOriginatedCommand = "```auf_core.SHOW_TOAST\n{ \"message\": \"This should not be sent\" }\n```"

        // ACT
        // Note: The senderId is the critical part of this test.
        harness.store.dispatch("some-other-feature", Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", "commandbot")
            put("message", selfOriginatedCommand)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedToastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
            assertNull(dispatchedToastAction, "The bot must ignore commands where the senderId matches its own name.")
        }
    }
    // (Add this new test function inside the existing CommandBotFeatureT2CoreTest class)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `can dispatch a command to a different feature (SessionFeature)`() = runTest {
        // ARRANGE
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(sessions = mapOf(testSession.id to testSession), activeSessionId = testSession.id))
            .build(platform = platform)

        val commandMessage = """
        ```auf_session.CREATE
        { "name": "Created By Bot" }
        ```
    """.trimIndent()

        // ACT
        harness.store.dispatch(testUser.id, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", testUser.id)
            put("message", commandMessage)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // Assert that the command was dispatched correctly
            val dispatchedCreateAction = harness.processedActions.find { it.name == ActionNames.SESSION_CREATE }
            assertNotNull(dispatchedCreateAction, "The command bot should have dispatched a SESSION_CREATE action.")
            assertEquals(testUser.id, dispatchedCreateAction.originator, "Originator must be the original user.")

            // Assert the ground-truth state change
            val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals(2, finalSessionState.sessions.size, "There should now be two sessions.")
            assertNotNull(finalSessionState.sessions.values.find { it.name == "Created By Bot" }, "The new session should exist with the correct name.")
        }
    }
}