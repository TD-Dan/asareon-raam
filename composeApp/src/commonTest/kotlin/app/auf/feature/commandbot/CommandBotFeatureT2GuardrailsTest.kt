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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Guardrail & Edge-Case Tests for CommandBotFeature.
 *
 * Complements [CommandBotFeatureT2CoreTest] with coverage of:
 * - Agent identity tracking lifecycle (Proactive Broadcast pattern)
 * - CAG-004: Agent action restriction (ExposedActions allowlist)
 * - CAG-007: Auto-fill injection (senderId for session.POST)
 * - Command processing edge cases (empty payload, no blocks, non-auf blocks, multi-block)
 * - Approval card PartialView metadata verification
 * - DENY unknown-approvalId no-op
 *
 * All assertions wrapped in `runAndLogOnFailure` per P-TEST-005.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandBotFeatureT2GuardrailsTest {

    private val testUser = Identity("user-1", "Test User")
    private val testSession = Session("session-1", "Test Session", emptyList(), 1L)

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Builds a standard harness with Core, Session, and CommandBot.
     * No agents registered — sender IDs not in knownAgentIds are treated as human.
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
     * Builds a harness and registers one agent via Proactive Broadcast.
     * This makes the agent visible to CAG-004/006/007 enforcement.
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

        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.AGENT_PUBLISH_AGENT_NAMES_UPDATED,
            buildJsonObject { put("names", buildJsonObject { put(agentId, agentName) }) }
        ))
        // Note: caller is responsible for runCurrent() after setup.

        return harness
    }

    /**
     * Posts a message containing a raw string to the test session.
     * The SessionFeature's BlockSeparatingParser will process the markdown into ContentBlocks,
     * and publish MESSAGE_POSTED, which CommandBot observes.
     */
    private fun postRawMessage(harness: TestHarness, senderId: String, message: String) {
        harness.store.dispatch("test", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", testSession.id)
            put("senderId", senderId)
            put("message", message)
        }))
    }

    // ========================================================================
    // 1. Agent Identity Tracking Lifecycle
    // ========================================================================

    @Test
    fun `agent tracking - unregistered sender is treated as human and unrestricted`() = runTest {
        val harness = buildStandardHarness()

        // "agent-1" is NOT in knownAgentIds → human path → unrestricted
        postRawMessage(harness, "agent-1", "```auf_core.SHOW_TOAST\n{ \"message\": \"Hi\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toast,
                "An unregistered sender should be treated as human and bypass agent restrictions.")
            assertEquals("agent-1", toast.originator,
                "CAG-002: originator should be the original senderId.")
        }
    }

    @Test
    fun `agent tracking - AGENT_NAMES_UPDATED makes sender subject to agent enforcement`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // After registration, agent-1 is in knownAgentIds.
        // core.SHOW_TOAST is not in ExposedActions → should be blocked (CAG-004).
        postRawMessage(harness, "agent-1", "```auf_core.SHOW_TOAST\n{ \"message\": \"Blocked\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNull(toast,
                "After registration, the agent should be subject to CAG-004 enforcement.")
        }
    }

    @Test
    fun `agent tracking - AGENT_DELETED reverts sender to human treatment`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // Delete the agent
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.AGENT_PUBLISH_AGENT_DELETED,
            buildJsonObject { put("agentId", "agent-1") }
        ))
        runCurrent()

        // Now agent-1 is no longer tracked → human path → unrestricted
        postRawMessage(harness, "agent-1", "```auf_core.SHOW_TOAST\n{ \"message\": \"Allowed\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_SHOW_TOAST &&
                        it.payload?.get("message")?.jsonPrimitive?.contentOrNull == "Allowed"
            }
            assertNotNull(toast,
                "After AGENT_DELETED, the sender is no longer tracked and should be unrestricted.")
        }
    }

    @Test
    fun `agent tracking - second NAMES_UPDATED replaces entire agent set`() = runTest {
        val harness = buildStandardHarness()

        // Register two agents
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.AGENT_PUBLISH_AGENT_NAMES_UPDATED,
            buildJsonObject {
                put("names", buildJsonObject {
                    put("agent-1", "Agent One")
                    put("agent-2", "Agent Two")
                })
            }
        ))
        runCurrent()

        // Second broadcast replaces with only agent-2
        harness.store.dispatch("agent", Action(
            ActionRegistry.Names.AGENT_PUBLISH_AGENT_NAMES_UPDATED,
            buildJsonObject {
                put("names", buildJsonObject { put("agent-2", "Agent Two") })
            }
        ))
        runCurrent()

        // agent-1 should now be treated as human (unrestricted)
        postRawMessage(harness, "agent-1", "```auf_core.SHOW_TOAST\n{ \"message\": \"From ex-agent\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_SHOW_TOAST &&
                        it.payload?.get("message")?.jsonPrimitive?.contentOrNull == "From ex-agent"
            }
            assertNotNull(toast,
                "agent-1 was removed from the set by the second broadcast and should be unrestricted.")
        }
    }

    // ========================================================================
    // 2. CAG-004: Agent Action Restriction
    // ========================================================================

    @Test
    fun `CAG-004 - agent blocked from non-exposed action receives feedback message`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postRawMessage(harness, "agent-1", "```auf_core.SHOW_TOAST\n{ \"message\": \"Blocked\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            // The target action must NOT be dispatched
            val toast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNull(toast, "Non-exposed action must be blocked for known agents.")

            // A feedback SESSION_POST should be dispatched by CommandBot
            val feedbackPost = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_POST && it.originator == "commandbot"
            }.lastOrNull()
            assertNotNull(feedbackPost, "CommandBot should post feedback about the blocked action.")

            val msg = feedbackPost.payload?.get("message")?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(msg.contains("not available to agents"),
                "Feedback should explain the action is restricted. Got: $msg")
            assertTrue(msg.contains("core.SHOW_TOAST"),
                "Feedback should name the blocked action. Got: $msg")
        }
    }

    @Test
    fun `CAG-004 - human user is unrestricted for same non-exposed action`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // Human user sends the exact same command that would be blocked for an agent
        postRawMessage(harness, testUser.id, "```auf_core.SHOW_TOAST\n{ \"message\": \"Human OK\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toast, "Human users bypass CAG-004 and can dispatch any action.")
            assertEquals(testUser.id, toast.originator, "CAG-002: originator should be the human user.")
        }
    }

    // ========================================================================
    // 3. CAG-007: Auto-Fill
    // ========================================================================

    @Test
    fun `CAG-007 - session POST auto-fills senderId with agentId`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // Agent sends a session.POST command — the auto-fill rule should inject senderId.
        // Note: the payload deliberately omits senderId.
        postRawMessage(
            harness, "agent-1",
            "```auf_session.POST\n{ \"session\": \"${testSession.id}\", \"message\": \"Agent message\" }\n```"
        )
        runCurrent()

        harness.runAndLogOnFailure {
            // Agent commands now go through ACTION_CREATED, not direct dispatch
            val actionCreated = harness.processedActions.find {
                it.name == ActionRegistry.Names.COMMANDBOT_PUBLISH_ACTION_CREATED &&
                        it.payload?.get("actionName")?.jsonPrimitive?.contentOrNull == "session.POST"
            }

            assertNotNull(actionCreated, "CommandBot should publish ACTION_CREATED for the agent's session.POST.")

            val actionPayload = actionCreated.payload?.get("actionPayload")?.jsonObject
            assertNotNull(actionPayload, "ACTION_CREATED must include an actionPayload.")

            val senderId = actionPayload["senderId"]?.jsonPrimitive?.contentOrNull
            assertEquals("agent-1", senderId,
                "CAG-007 should auto-fill senderId with the requesting agent's ID in the actionPayload.")
        }
    }

    // ========================================================================
    // 4. Command Processing Edge Cases
    // ========================================================================

    @Test
    fun `edge case - empty payload code block dispatches with empty JsonObject`() = runTest {
        val harness = buildStandardHarness()

        // Code block with auf_ prefix but empty body
        postRawMessage(harness, testUser.id, "```auf_core.SHOW_TOAST\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val toast = harness.processedActions.find { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertNotNull(toast, "A command with an empty code block body should still be dispatched.")

            // Payload should be an empty JsonObject, not null
            assertNotNull(toast.payload, "Payload should be a non-null empty JsonObject.")
            assertTrue(toast.payload!!.isEmpty(), "Payload should be empty when the code block body is empty.")
        }
    }

    @Test
    fun `edge case - message with no code blocks produces no commands`() = runTest {
        val harness = buildStandardHarness()

        val actionCountBefore = harness.processedActions.size

        postRawMessage(harness, testUser.id, "This is just a regular chat message with no commands.")
        runCurrent()

        harness.runAndLogOnFailure {
            // Only the SESSION_POST and its side-effects should be present — no command dispatches.
            // SessionFeature also emits filesystem.SYSTEM_WRITE for persistence, so we exclude those.
            val commandActions = harness.processedActions.drop(actionCountBefore).filter {
                it.name != ActionRegistry.Names.SESSION_POST &&
                        !it.name.startsWith("session.publish.") &&
                        !it.name.startsWith("session.internal.") &&
                        !it.name.startsWith("filesystem.")
            }
            assertTrue(commandActions.isEmpty(),
                "A plain text message should not cause CommandBot to dispatch any actions. " +
                        "Found: ${commandActions.map { it.name }}")
        }
    }

    @Test
    fun `edge case - non-auf code block is ignored`() = runTest {
        val harness = buildStandardHarness()

        val actionCountBefore = harness.processedActions.size

        // A standard code block with language "json" — should not trigger CommandBot
        postRawMessage(harness, testUser.id, "```json\n{ \"not\": \"a command\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val commandActions = harness.processedActions.drop(actionCountBefore).filter {
                it.name != ActionRegistry.Names.SESSION_POST &&
                        !it.name.startsWith("session.publish.") &&
                        !it.name.startsWith("session.internal.") &&
                        !it.name.startsWith("filesystem.")
            }
            assertTrue(commandActions.isEmpty(),
                "A code block with a non-auf_ language tag should be ignored. " +
                        "Found: ${commandActions.map { it.name }}")
        }
    }

    @Test
    fun `edge case - multiple auf blocks in one message all processed`() = runTest {
        val harness = buildStandardHarness()

        val multiBlockMessage = """
            First command:
            ```auf_core.SHOW_TOAST
            { "message": "Toast One" }
            ```
            Second command:
            ```auf_core.SHOW_TOAST
            { "message": "Toast Two" }
            ```
        """.trimIndent()

        postRawMessage(harness, testUser.id, multiBlockMessage)
        runCurrent()

        harness.runAndLogOnFailure {
            val toasts = harness.processedActions.filter { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertEquals(2, toasts.size,
                "Both auf_ code blocks in a single message should be processed.")

            val messages = toasts.mapNotNull { it.payload?.get("message")?.jsonPrimitive?.contentOrNull }
            assertTrue("Toast One" in messages, "First command payload should be dispatched.")
            assertTrue("Toast Two" in messages, "Second command payload should be dispatched.")
        }
    }

    @Test
    fun `edge case - mixed auf and non-auf blocks processes only auf blocks`() = runTest {
        val harness = buildStandardHarness()

        val mixedMessage = """
            Here is some context:
            ```json
            { "this is": "not a command" }
            ```
            Now the actual command:
            ```auf_core.SHOW_TOAST
            { "message": "Only this one" }
            ```
        """.trimIndent()

        postRawMessage(harness, testUser.id, mixedMessage)
        runCurrent()

        harness.runAndLogOnFailure {
            val toasts = harness.processedActions.filter { it.name == ActionRegistry.Names.CORE_SHOW_TOAST }
            assertEquals(1, toasts.size,
                "Only the auf_ block should be processed; the json block should be ignored.")
            assertEquals("Only this one",
                toasts.first().payload?.get("message")?.jsonPrimitive?.contentOrNull)
        }
    }

    // ========================================================================
    // 5. Approval Card Metadata Verification
    // ========================================================================

    @Test
    fun `approval card is posted with correct PartialView metadata`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        // session.CREATE requires approval → stages approval + posts card entry
        postRawMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"New Session\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            // Find the SESSION_POST dispatched by CommandBot to post the approval card
            val cardPost = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_POST && it.originator == "commandbot"
            }.find {
                it.payload?.get("metadata") != null
            }
            assertNotNull(cardPost, "CommandBot should dispatch a SESSION_POST to post the approval card.")

            val metadata = cardPost.payload?.get("metadata")
            assertNotNull(metadata, "Card post payload must include metadata.")
            val metaObj = metadata.jsonObject

            // Verify PartialView routing fields
            val renderAsPartial = metaObj["render_as_partial"]?.jsonPrimitive?.booleanOrNull
            assertEquals(true, renderAsPartial,
                "metadata.render_as_partial must be true for PartialView rendering.")

            val featureName = metaObj["partial_view_feature"]?.jsonPrimitive?.contentOrNull
            assertEquals("commandbot", featureName,
                "metadata.partial_view_feature must route to 'commandbot'.")

            val viewKey = metaObj["partial_view_key"]?.jsonPrimitive?.contentOrNull
            assertEquals("commandbot.approval", viewKey,
                "metadata.partial_view_key must be 'commandbot.approval'.")

            // Card should survive SESSION_CLEAR while pending
            val doNotClear = cardPost.payload?.get("doNotClear")?.jsonPrimitive?.booleanOrNull
            assertEquals(true, doNotClear,
                "Approval card must be posted with doNotClear=true to survive SESSION_CLEAR.")
        }
    }

    @Test
    fun `approval card uses generated approvalId as senderId for PartialView context`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postRawMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Test\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            val approvalId = commandBotState.pendingApprovals.keys.firstOrNull()
            assertNotNull(approvalId, "A pending approval should exist.")

            // The card's senderId must match the approvalId so that PartialView
            // receives it as the context parameter.
            val cardPost = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_POST && it.originator == "commandbot"
            }.find { it.payload?.get("metadata") != null }
            assertNotNull(cardPost)

            val cardSenderId = cardPost.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
            assertEquals(approvalId, cardSenderId,
                "The card's senderId must equal the approvalId for PartialView context routing.")
        }
    }

    @Test
    fun `approval card messageId matches the staged PendingApproval cardMessageId`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        postRawMessage(harness, "agent-1", "```auf_session.CREATE\n{ \"name\": \"Test\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            val pending = commandBotState.pendingApprovals.values.firstOrNull()
            assertNotNull(pending, "A pending approval should exist.")

            // Verify the card post's messageId matches what was staged
            val cardPost = harness.processedActions.filter {
                it.name == ActionRegistry.Names.SESSION_POST && it.originator == "commandbot"
            }.find { it.payload?.get("metadata") != null }
            assertNotNull(cardPost)

            val postedMessageId = cardPost.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
            assertEquals(pending.cardMessageId, postedMessageId,
                "The card's messageId must match the PendingApproval.cardMessageId for cleanup routing.")
        }
    }

    // ========================================================================
    // 6. DENY Edge Case
    // ========================================================================

    @Test
    fun `DENY for unknown approvalId is a safe no-op`() = runTest {
        val harness = buildHarnessWithKnownAgent()
        runCurrent()

        val actionCountBefore = harness.processedActions.size

        harness.store.dispatch("commandbot.ui", Action(ActionRegistry.Names.COMMANDBOT_DENY, buildJsonObject {
            put("approvalId", "approval-does-not-exist")
        }))
        runCurrent()

        harness.runAndLogOnFailure {
            val finalState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            assertTrue(finalState.pendingApprovals.isEmpty())
            assertTrue(finalState.resolvedApprovals.isEmpty())

            // No RESOLVE_APPROVAL should have been dispatched
            val resolveActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL
            }
            assertTrue(resolveActions.isEmpty(),
                "No RESOLVE_APPROVAL should be dispatched for a non-existent approval.")
        }
    }

    // ========================================================================
    // 7. Agent Name Display in Approvals
    // ========================================================================

    @Test
    fun `staged approval uses display name from agent tracking`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = buildHarnessWithKnownAgent(platform, agentId = "agent-x", agentName = "Research Bot")
        runCurrent()

        postRawMessage(harness, "agent-x", "```auf_session.CREATE\n{ \"name\": \"Test\" }\n```")
        runCurrent()

        harness.runAndLogOnFailure {
            val commandBotState = harness.store.state.value.featureStates["commandbot"] as CommandBotState
            val pending = commandBotState.pendingApprovals.values.firstOrNull()
            assertNotNull(pending)
            assertEquals("Research Bot", pending.requestingAgentName,
                "The staged approval should use the display name from AGENT_NAMES_UPDATED.")
            assertEquals("agent-x", pending.requestingAgentId)
        }
    }
}