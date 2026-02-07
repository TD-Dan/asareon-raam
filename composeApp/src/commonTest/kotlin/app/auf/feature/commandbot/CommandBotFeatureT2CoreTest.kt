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
import app.auf.test.TestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
 * Mandate (P-TEST-001, T2): To test the feature's core logic and side effects
 * in response to events from its peers within a realistic TestEnvironment.
 *
 * ## Coverage
 * - Baseline command processing: human user dispatch, originator attribution (CAG-002)
 * - CAG-001: Self-reaction prevention
 * - CAG-003: Robust error handling (JSON parse failure → feedback message)
 * - Cross-feature dispatch: CommandBot → SessionFeature (session.CREATE)
 * - Approval workflow: stage → approve/deny → state transitions → clearability → cleanup
 * - Auto-fill: LIST_SESSIONS responseSession injection for agent vs. human pass-through
 *
 * ## Related Test Files
 * - [CommandBotFeatureT1ReducerTest]: Pure reducer unit tests (no Store or coroutines)
 * - [CommandBotFeatureT2GuardrailsTest]: Agent tracking, CAG-004, CAG-007, edge cases
 *
 * All assertions wrapped in `runAndLogOnFailure` per P-TEST-005.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandBotFeatureT2CoreTest {

    private val testUser = Identity("user-1", "Test User")
    private val testSession = Session("session-1", "Test Session", emptyList(), 1L)

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Builds a standard harness with Core, Session, and CommandBot.
     * No agents registered — all senders treated as human.
     */
    private fun TestScope.buildStandardHarness(
        platform: FakePlatformDependencies = FakePlatformDependencies("test")
    ): TestHarness {
        return TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(testSession.id to testSession),
                activeSessionId = testSession.id
            ))
            .build(platform = platform)
    }

    /**
     * Builds a harness with a known agent registered via Proactive Broadcast.
     * This makes the agent visible to CommandBot's approval gate (CAG-006).
     * Caller is responsible for [runCurrent] after setup.
     */
    private fun TestScope.buildHarnessWithKnownAgent(
        platform: FakePlatformDependencies = FakePlatformDependencies("test"),
        agentId: String = "agent-1",
        agentName: String = "Test Agent"
    ): TestHarness {
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(SessionFeature(platform, this))
            .withFeature(CommandBotFeature(platform))
            .withInitialState("core", CoreState(
                userIdentities = listOf(testUser),
                activeUserId = testUser.id,
                lifecycle = AppLifecycle.RUNNING
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(testSession.id to testSession),
                activeSessionId = testSession.id
            ))
            .withInitialState("commandbot", CommandBotState())
            .build(platform = platform)

        // Simulate agent registration via Proactive Broadcast
        harness.store.dispatch("agent", Action(
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED,
            buildJsonObject { put("names", buildJsonObject { put(agentId, agentName) }) }
        ))

        return harness
    }

    /**
     * Posts a message to the test session. The SessionFeature pipeline parses it
     * into ContentBlocks and publishes MESSAGE_POSTED, which CommandBot observes.
     */
    private fun postMessage(harness: TestHarness, senderId: String, message: String) {
        harness.store.dispatch(senderId, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", senderId)
            put("message", message)
        }))
    }

    // ========================================================================
    // 1. Baseline Command Processing
    // ========================================================================

    @Test
    fun `parses and dispatches a valid command from a session message`() = runTest {
        val harness = buildStandardHarness()

        val commandMessage = """
            Here is a command:
            ```auf_core.SHOW_TOAST
            { "message": "Hello from the bot!" }
            ```
        """.trimIndent()

        // ACT
        postMessage(harness, testUser.id, commandMessage)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedToastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
            assertNotNull(dispatchedToastAction, "The command bot should have dispatched a CORE_SHOW_TOAST action.")
            assertEquals(testUser.id, dispatchedToastAction.originator,
                "CAG-002: The originator of the dispatched action must be the original message sender.")
            val payloadMessage = dispatchedToastAction.payload?.get("message")?.jsonPrimitive?.content
            assertEquals("Hello from the bot!", payloadMessage)
        }
    }

    @Test
    fun `can dispatch a command to a different feature (SessionFeature)`() = runTest {
        val harness = buildStandardHarness()

        val commandMessage = """
            ```auf_session.CREATE
            { "name": "Created By Bot" }
            ```
        """.trimIndent()

        // ACT
        postMessage(harness, testUser.id, commandMessage)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedCreateAction = harness.processedActions.find { it.name == ActionNames.SESSION_CREATE }
            assertNotNull(dispatchedCreateAction, "The command bot should have dispatched a SESSION_CREATE action.")
            assertEquals(testUser.id, dispatchedCreateAction.originator, "Originator must be the original user.")

            val finalSessionState = harness.store.state.value.featureStates["session"] as SessionState
            assertEquals(2, finalSessionState.sessions.size, "There should now be two sessions.")
            assertNotNull(
                finalSessionState.sessions.values.find { it.name == "Created By Bot" },
                "The new session should exist with the correct name."
            )
        }
    }

    // ========================================================================
    // 2. CAG-001: Self-Reaction Prevention
    // ========================================================================

    @Test
    fun `ignores commands originating from itself to prevent loops`() = runTest {
        val harness = buildStandardHarness()

        val selfOriginatedCommand = "```auf_core.SHOW_TOAST\n{ \"message\": \"This should not be sent\" }\n```"

        // ACT: Post with senderId = "commandbot" (the feature's own name)
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

    // ========================================================================
    // 3. CAG-003: Robust Error Handling
    // ========================================================================

    @Test
    fun `dispatches a feedback message on JSON parsing failure`() = runTest {
        val harness = buildStandardHarness()

        val malformedCommand = "```auf_core.SHOW_TOAST\n{ this is not valid json }\n```"

        // ACT
        postMessage(harness, testUser.id, malformedCommand)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val dispatchedToastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
            assertNull(dispatchedToastAction, "No toast action should be dispatched for a malformed command.")

            val feedbackAction = harness.processedActions.findLast { it.name == ActionNames.SESSION_POST }
            assertNotNull(feedbackAction, "A new session.POST action should be dispatched as feedback.")
            assertEquals("commandbot", feedbackAction.originator)
            assertEquals("commandbot", feedbackAction.payload?.get("senderId")?.jsonPrimitive?.content)
            assertEquals(testSession.id, feedbackAction.payload?.get("session")?.jsonPrimitive?.content)

            val feedbackMessage = feedbackAction.payload?.get("message")?.jsonPrimitive?.content ?: ""
            assertTrue(feedbackMessage.contains("[COMMAND BOT ERROR]"), "The feedback message should contain an error.")
            assertTrue(feedbackMessage.contains("core.SHOW_TOAST"), "The feedback message should contain the attempted command.")
        }
    }

    // ========================================================================
    // 4. Approval Workflow
    // ========================================================================

    @Test
    fun `approval gate - agent action requiring approval stages a PendingApproval`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // session.CREATE requires approval per session_actions.json
        val commandMessage = "```auf_session.CREATE\n{ \"name\": \"Agent Session\" }\n```"

        // ACT: Agent posts the command
        postMessage(harness, "agent-1", commandMessage)
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState

            // 1. A PendingApproval should be staged
            assertEquals(1, commandBotState.pendingApprovals.size, "Exactly one pending approval should be staged.")
            val approval = commandBotState.pendingApprovals.values.first()
            assertEquals("session.CREATE", approval.actionName)
            assertEquals("agent-1", approval.requestingAgentId)
            assertEquals("Test Agent", approval.requestingAgentName)

            // 2. The actual SESSION_CREATE should NOT have been dispatched yet
            val createActions = harness.processedActions.filter { it.name == ActionNames.SESSION_CREATE }
            assertTrue(createActions.isEmpty(), "SESSION_CREATE must NOT be dispatched before approval.")

            // 3. An approval card should be posted to the session
            val stageAction = harness.processedActions.find { it.name == ActionNames.COMMANDBOT_INTERNAL_STAGE_APPROVAL }
            assertNotNull(stageAction, "STAGE_APPROVAL internal action should have been dispatched.")
        }
    }

    @Test
    fun `approval gate - APPROVE dispatches staged action and resolves state`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // Stage an approval
        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Agent Session\" }\n```")
        runCurrent()

        val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val approvalId = commandBotState.pendingApprovals.keys.first()

        // ACT: User approves
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_APPROVE, buildJsonObject {
            put("approvalId", approvalId)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState

            // 1. Pending approval should be removed
            assertTrue(finalState.pendingApprovals.isEmpty(), "Pending approvals should be empty after approval.")

            // 2. Resolved approval should exist
            assertEquals(1, finalState.resolvedApprovals.size, "There should be exactly one resolved approval.")
            val resolved = finalState.resolvedApprovals.values.first()
            assertEquals(Resolution.APPROVED, resolved.resolution)
            assertEquals("session.CREATE", resolved.actionName)
            assertEquals("Test Agent", resolved.requestingAgentName)

            // 3. The staged action should now be dispatched
            val createAction = harness.processedActions.find { it.name == ActionNames.SESSION_CREATE }
            assertNotNull(createAction, "SESSION_CREATE should be dispatched after approval.")

            // 4. Verify ground-truth: the session was actually created
            val sessionState = harness.store.state.value.featureStates["session"] as SessionState
            assertTrue(sessionState.sessions.size >= 2, "A new session should have been created.")
        }
    }

    @Test
    fun `approval gate - DENY resolves state without dispatching the action`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Agent Session\" }\n```")
        runCurrent()

        val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val approvalId = commandBotState.pendingApprovals.keys.first()

        // ACT: User denies
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_DENY, buildJsonObject {
            put("approvalId", approvalId)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState

            // 1. Pending approval should be removed
            assertTrue(finalState.pendingApprovals.isEmpty(), "Pending approvals should be empty after denial.")

            // 2. Resolved approval should exist with DENIED resolution
            assertEquals(1, finalState.resolvedApprovals.size, "There should be exactly one resolved approval.")
            val resolved = finalState.resolvedApprovals.values.first()
            assertEquals(Resolution.DENIED, resolved.resolution)

            // 3. The staged action should NOT have been dispatched
            val createActions = harness.processedActions.filter { it.name == ActionNames.SESSION_CREATE }
            assertTrue(createActions.isEmpty(), "SESSION_CREATE must NOT be dispatched after denial.")
        }
    }

    @Test
    fun `approval gate - resolved card becomes clearable after resolution`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Agent Session\" }\n```")
        runCurrent()

        val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val approvalId = commandBotState.pendingApprovals.keys.first()
        val cardMessageId = commandBotState.pendingApprovals.values.first().cardMessageId

        // Verify pre-condition: card has doNotClear = true
        val preClearSession = (harness.store.state.value.featureStates["session"] as SessionState)
            .sessions[testSession.id]!!
        val cardEntryBefore = preClearSession.ledger.find { it.id == cardMessageId }
        assertNotNull(cardEntryBefore, "Card entry should exist in ledger.")
        assertTrue(cardEntryBefore.doNotClear, "Card should have doNotClear=true before resolution.")

        // ACT: Approve (triggers doNotClear flip)
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_APPROVE, buildJsonObject {
            put("approvalId", approvalId)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            // Verify the UPDATE_MESSAGE was dispatched to flip doNotClear
            val updateAction = harness.processedActions.find {
                it.name == ActionNames.SESSION_UPDATE_MESSAGE &&
                        it.payload?.get("messageId")?.jsonPrimitive?.content == cardMessageId
            }
            assertNotNull(updateAction, "An UPDATE_MESSAGE should be dispatched to make the card clearable.")

            // Verify ground truth: the entry's doNotClear is now false
            val sessionState = harness.store.state.value.featureStates["session"] as SessionState
            val session = sessionState.sessions[testSession.id]!!
            val cardEntryAfter = session.ledger.find { it.id == cardMessageId }
            assertNotNull(cardEntryAfter, "Card entry should still exist.")
            assertTrue(!cardEntryAfter.doNotClear,
                "Card should have doNotClear=false after resolution, making it clearable by SESSION_CLEAR.")
        }
    }

    @Test
    fun `approval gate - SESSION_CLEAR removes resolved card but not pending card`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // First command (will be approved → clearable)
        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Session A\" }\n```")
        runCurrent()

        val stateAfterFirst = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val firstApprovalId = stateAfterFirst.pendingApprovals.keys.first()
        val firstCardId = stateAfterFirst.pendingApprovals.values.first().cardMessageId

        // Approve the first
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_APPROVE, buildJsonObject {
            put("approvalId", firstApprovalId)
        }))
        runCurrent()

        // Second command (will stay pending → doNotClear=true → survives CLEAR)
        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Session B\" }\n```")
        runCurrent()

        val stateAfterSecond = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val secondCardId = stateAfterSecond.pendingApprovals.values.first().cardMessageId

        // ACT: Clear the session
        harness.store.dispatch(testUser.id, Action(ActionNames.SESSION_CLEAR, buildJsonObject {
            put("session", testSession.id)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val sessionState = harness.store.state.value.featureStates["session"] as SessionState
            val session = sessionState.sessions[testSession.id]!!

            // Resolved card (doNotClear=false) should be removed by CLEAR
            val resolvedCard = session.ledger.find { it.id == firstCardId }
            assertNull(resolvedCard, "Resolved approval card (doNotClear=false) should be removed by SESSION_CLEAR.")

            // Pending card (doNotClear=true) should survive CLEAR
            val pendingCard = session.ledger.find { it.id == secondCardId }
            assertNotNull(pendingCard, "Pending approval card (doNotClear=true) should survive SESSION_CLEAR.")
        }
    }

    @Test
    fun `approval gate - resolved approval state cleaned up when card is deleted`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Agent Session\" }\n```")
        runCurrent()

        val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        val approvalId = commandBotState.pendingApprovals.keys.first()
        val cardMessageId = commandBotState.pendingApprovals.values.first().cardMessageId

        // Approve it
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_APPROVE, buildJsonObject {
            put("approvalId", approvalId)
        }))
        runCurrent()

        // Verify resolved state exists
        val midState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
        assertEquals(1, midState.resolvedApprovals.size, "Should have one resolved approval.")

        // ACT: Delete the card message (simulates Dismiss button)
        harness.store.dispatch("commandbot.ui", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
            put("session", testSession.id)
            put("messageId", cardMessageId)
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            assertTrue(finalState.resolvedApprovals.isEmpty(),
                "Resolved approval should be cleaned up when the card's ledger entry is deleted.")
        }
    }

    @Test
    fun `approval gate - APPROVE for unknown approvalId is a no-op`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // ACT: Approve a non-existent approval
        harness.store.dispatch("commandbot.ui", Action(ActionNames.COMMANDBOT_APPROVE, buildJsonObject {
            put("approvalId", "approval-does-not-exist")
        }))
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            assertTrue(finalState.pendingApprovals.isEmpty())
            assertTrue(finalState.resolvedApprovals.isEmpty())

            // No RESOLVE_APPROVAL should have been dispatched
            val resolveActions = harness.processedActions.filter {
                it.name == ActionNames.COMMANDBOT_INTERNAL_RESOLVE_APPROVAL
            }
            assertTrue(resolveActions.isEmpty(),
                "No RESOLVE_APPROVAL should be dispatched for a non-existent approval.")
        }
    }

    // ========================================================================
    // 5. Auto-Fill: LIST_SESSIONS
    // ========================================================================

    @Test
    fun `LIST_SESSIONS from agent is dispatched with responseSession auto-filled`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // Agent sends LIST_SESSIONS with no payload
        postMessage(harness, "agent-1", "```auf_session.LIST_SESSIONS\n```")
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val listAction = harness.processedActions.find { it.name == ActionNames.SESSION_LIST_SESSIONS }
            assertNotNull(listAction, "CommandBot should dispatch SESSION_LIST_SESSIONS.")

            // The auto-fill should have injected the originating sessionId as responseSession
            val responseSession = listAction.payload?.get("responseSession")?.jsonPrimitive?.content
            assertEquals(testSession.id, responseSession,
                "The responseSession should be auto-filled with the originating session ID.")
        }
    }

    @Test
    fun `LIST_SESSIONS from human user passes through without modification`() = runTest {
        val harness = buildStandardHarness()

        // Human user sends LIST_SESSIONS with explicit responseSession
        postMessage(
            harness, testUser.id,
            "```auf_session.LIST_SESSIONS\n{ \"responseSession\": \"${testSession.id}\" }\n```"
        )
        runCurrent()

        // ASSERT
        harness.runAndLogOnFailure {
            val listAction = harness.processedActions.find { it.name == ActionNames.SESSION_LIST_SESSIONS }
            assertNotNull(listAction, "CommandBot should dispatch SESSION_LIST_SESSIONS for human users.")

            // CAG-002: originator is the human user
            assertEquals(testUser.id, listAction.originator)

            // The explicit responseSession should be preserved
            val responseSession = listAction.payload?.get("responseSession")?.jsonPrimitive?.content
            assertEquals(testSession.id, responseSession)
        }
    }
}