package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Rate Limiting Tests for the Agent Runtime Feature.
 *
 * Tests are organized by component:
 *   - Section 1: AgentRuntimeReducer — INITIATE_TURN guard, SET_STATUS transitions
 *   - Section 2: AgentRuntimeReducer — MESSAGE_POSTED does not override RATE_LIMITED
 *   - Section 3: AgentAutoTriggerLogic — Auto-retry after rate limit expiry
 *
 * Pipeline-level tests (handleGatewayResponse detecting rate limits) require the full
 * agent feature with strategies registered and are covered at the T3 integration level.
 *
 * Reducer tests (Sections 1-2) are pure T1 tests — direct function calls, no Store.
 * Auto-trigger tests (Section 3) are T2 tests — use TestEnvironment with a real Store.
 */
class AgentRuntimeFeatureT3RateLimitTest {

    // =========================================================================
    // Test fixtures — all UUIDs must pass stringIsUUID() validation
    // =========================================================================

    private val platform = FakePlatformDependencies("test")

    // Valid UUID v4 format strings for test fixtures
    private val testAgentUuid = IdentityUUID("a0000000-0000-4000-a000-000000000001")
    private val testSessionUuid = IdentityUUID("b0000000-0000-4000-b000-000000000001")

    /** Creates a minimal AgentInstance for testing. */
    private fun testAgent(
        uuid: String = testAgentUuid.uuid,
        automaticMode: Boolean = false,
        isActive: Boolean = true
    ): AgentInstance = AgentInstance(
        identity = Identity(
            uuid = uuid,
            handle = "agent.test-agent",
            localHandle = "test-agent",
            name = "Test Agent"
        ),
        modelProvider = "anthropic",
        modelName = "claude-3-5-sonnet-20241022",
        subscribedSessionIds = listOf(testSessionUuid),
        automaticMode = automaticMode,
        isAgentActive = isActive
    )

    /** Creates a state with one agent at a given status. */
    private fun stateWith(
        agent: AgentInstance = testAgent(),
        status: AgentStatus = AgentStatus.IDLE,
        rateLimitedUntilMs: Long? = null,
        waitingSinceTimestamp: Long? = null,
        lastMessageReceivedTimestamp: Long? = null,
        lastSeenMessageId: String? = null
    ): AgentRuntimeState {
        val uuid = agent.identityUUID
        return AgentRuntimeState(
            agents = mapOf(uuid to agent),
            agentStatuses = mapOf(uuid to AgentStatusInfo(
                status = status,
                rateLimitedUntilMs = rateLimitedUntilMs,
                waitingSinceTimestamp = waitingSinceTimestamp,
                lastMessageReceivedTimestamp = lastMessageReceivedTimestamp,
                lastSeenMessageId = lastSeenMessageId
            ))
        )
    }

    // =========================================================================
    // Section 1: Reducer — INITIATE_TURN guard
    // =========================================================================

    @Test
    fun `INITIATE_TURN is rejected when RATE_LIMITED and window has not expired`() {
        // ARRANGE: Agent is rate limited until T+60s. Current time is T+30s.
        val rateLimitExpiry = platform.currentTimeMillis() + 60_000
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = rateLimitExpiry
        )
        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("preview", false)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: State should be unchanged — the turn was rejected.
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertEquals(AgentStatus.RATE_LIMITED, statusInfo.status,
            "Agent should remain RATE_LIMITED when turn is initiated before window expires.")
        assertEquals(rateLimitExpiry, statusInfo.rateLimitedUntilMs,
            "rateLimitedUntilMs should be preserved.")
    }

    @Test
    fun `INITIATE_TURN is allowed when RATE_LIMITED but window has expired`() {
        // ARRANGE: Rate limit expired 5 seconds ago.
        val rateLimitExpiry = platform.currentTimeMillis() - 5_000
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = rateLimitExpiry
        )
        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("preview", false)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: The turn should proceed — rateLimitedUntilMs cleared, context reset.
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertNull(statusInfo.rateLimitedUntilMs,
            "rateLimitedUntilMs should be cleared when turn proceeds after expiry.")
        assertNull(statusInfo.stagedTurnContext,
            "Transient context should be cleared for the new turn.")
    }

    @Test
    fun `INITIATE_TURN is still rejected when PROCESSING regardless of rate limit`() {
        // ARRANGE: Agent is PROCESSING (not rate limited).
        val state = stateWith(status = AgentStatus.PROCESSING)
        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("preview", false)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: State unchanged — PROCESSING guard takes precedence.
        assertEquals(AgentStatus.PROCESSING, newState.agentStatuses[testAgentUuid]!!.status)
    }

    @Test
    fun `INITIATE_TURN allowed from IDLE, WAITING, and ERROR statuses`() {
        val allowedStatuses = listOf(AgentStatus.IDLE, AgentStatus.WAITING, AgentStatus.ERROR)

        allowedStatuses.forEach { startStatus ->
            val state = stateWith(status = startStatus)
            val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", testAgentUuid.uuid)
                put("preview", false)
            })

            val newState = AgentRuntimeReducer.reduce(state, action, platform)
            val statusInfo = newState.agentStatuses[testAgentUuid]!!

            // The reducer doesn't set PROCESSING — that happens in side effects.
            // But it DOES reset transient context, confirming the turn was accepted.
            assertNull(statusInfo.stagedTurnContext,
                "Turn should be accepted from $startStatus — context should be reset.")
            assertEquals(TurnMode.DIRECT, statusInfo.turnMode)
        }
    }

    // =========================================================================
    // Section 2: Reducer — SET_STATUS with RATE_LIMITED
    // =========================================================================

    @Test
    fun `SET_STATUS to RATE_LIMITED stores rateLimitedUntilMs and error message`() {
        // ARRANGE
        val state = stateWith(status = AgentStatus.PROCESSING)
        val retryAfter = platform.currentTimeMillis() + 30_000
        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("status", AgentStatus.RATE_LIMITED.name)
            put("error", "Rate limited by Anthropic API. Please wait before retrying.")
            put("rateLimitedUntilMs", retryAfter)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertEquals(AgentStatus.RATE_LIMITED, statusInfo.status)
        assertEquals(retryAfter, statusInfo.rateLimitedUntilMs)
        assertEquals("Rate limited by Anthropic API. Please wait before retrying.", statusInfo.errorMessage)
    }

    @Test
    fun `SET_STATUS transitioning away from RATE_LIMITED clears rateLimitedUntilMs`() {
        // ARRANGE: Agent is rate limited.
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = platform.currentTimeMillis() + 30_000
        )
        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("status", AgentStatus.IDLE.name)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertEquals(AgentStatus.IDLE, statusInfo.status)
        assertNull(statusInfo.rateLimitedUntilMs,
            "rateLimitedUntilMs should be cleared when leaving RATE_LIMITED.")
        assertNull(statusInfo.errorMessage)
    }

    @Test
    fun `SET_STATUS to RATE_LIMITED clears transient processing context`() {
        // ARRANGE: Agent has transient context from a turn.
        val uuid = testAgentUuid
        val state = AgentRuntimeState(
            agents = mapOf(uuid to testAgent()),
            agentStatuses = mapOf(uuid to AgentStatusInfo(
                status = AgentStatus.PROCESSING,
                processingSinceTimestamp = platform.currentTimeMillis() - 5000,
                processingStep = "Generating Content",
                stagedTurnContext = listOf(GatewayMessage("user", "Hello", "u1", "User", 1L)),
                transientWorkspaceListing = JsonArray(emptyList()),
                contextGatheringStartedAt = platform.currentTimeMillis() - 3000
            ))
        )
        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", uuid.uuid)
            put("status", AgentStatus.RATE_LIMITED.name)
            put("error", "Rate limited")
            put("rateLimitedUntilMs", platform.currentTimeMillis() + 30_000)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        val statusInfo = newState.agentStatuses[uuid]!!
        assertEquals(AgentStatus.RATE_LIMITED, statusInfo.status)
        assertNull(statusInfo.stagedTurnContext, "Staged context should be cleared on RATE_LIMITED.")
        assertNull(statusInfo.transientWorkspaceListing, "Workspace listing should be cleared.")
        assertNull(statusInfo.contextGatheringStartedAt, "Context gathering timestamp should be cleared.")
        assertNull(statusInfo.processingSinceTimestamp, "Processing timestamp should be cleared.")
        assertNull(statusInfo.processingStep, "Processing step should be cleared.")
    }

    @Test
    fun `SET_STATUS preserves token usage when transitioning to RATE_LIMITED`() {
        // ARRANGE: Agent had token usage from a previous successful turn.
        val uuid = testAgentUuid
        val state = AgentRuntimeState(
            agents = mapOf(uuid to testAgent()),
            agentStatuses = mapOf(uuid to AgentStatusInfo(
                status = AgentStatus.PROCESSING,
                lastInputTokens = 500,
                lastOutputTokens = 150
            ))
        )
        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", uuid.uuid)
            put("status", AgentStatus.RATE_LIMITED.name)
            put("error", "Rate limited")
            put("rateLimitedUntilMs", platform.currentTimeMillis() + 30_000)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: Token usage from previous turn should be preserved.
        val statusInfo = newState.agentStatuses[uuid]!!
        assertEquals(500, statusInfo.lastInputTokens)
        assertEquals(150, statusInfo.lastOutputTokens)
    }

    // =========================================================================
    // Section 3: Reducer — MESSAGE_POSTED does not override RATE_LIMITED
    // =========================================================================

    @Test
    fun `MESSAGE_POSTED does not override RATE_LIMITED status`() {
        // ARRANGE: Agent is rate limited and subscribed to a session.
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = platform.currentTimeMillis() + 30_000
        )

        // Simulate a message posted in the subscribed session (uses valid UUID format)
        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", testSessionUuid.uuid)
            put("sessionUUID", testSessionUuid.uuid)
            put("entry", buildJsonObject {
                put("id", "msg-123")
                put("senderId", "some-user")
                put("rawContent", "Hello agent!")
                put("timestamp", platform.currentTimeMillis())
            })
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: Status should remain RATE_LIMITED, not transition to WAITING.
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertEquals(AgentStatus.RATE_LIMITED, statusInfo.status,
            "RATE_LIMITED status must not be overridden by MESSAGE_POSTED. " +
                    "Only IDLE should transition to WAITING.")
        assertNotNull(statusInfo.rateLimitedUntilMs,
            "rateLimitedUntilMs should still be set.")
        // lastSeenMessageId SHOULD be updated (message tracking is independent of status).
        assertEquals("msg-123", statusInfo.lastSeenMessageId,
            "lastSeenMessageId should be updated even when RATE_LIMITED.")
    }

    @Test
    fun `MESSAGE_POSTED still transitions IDLE to WAITING`() {
        // ARRANGE: Sanity check — normal IDLE → WAITING path is not broken.
        val state = stateWith(status = AgentStatus.IDLE)
        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", testSessionUuid.uuid)
            put("sessionUUID", testSessionUuid.uuid)
            put("entry", buildJsonObject {
                put("id", "msg-456")
                put("senderId", "some-user")
                put("rawContent", "Hello!")
                put("timestamp", platform.currentTimeMillis())
            })
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        assertEquals(AgentStatus.WAITING, newState.agentStatuses[testAgentUuid]!!.status)
    }

    // =========================================================================
    // Section 4: AgentAutoTriggerLogic — Rate limit auto-retry
    // =========================================================================

    @Test
    fun `auto-trigger dispatches INITIATE_TURN when rate limit has expired`() {
        val testScope = TestScope()
        testScope.runTest {
            // ARRANGE
            val harnessPlatform = FakePlatformDependencies("test")
            val expiredRetryTime = harnessPlatform.currentTimeMillis() - 5_000 // Expired 5s ago

            val agent = testAgent(isActive = true)
            val agentState = stateWith(
                agent = agent,
                status = AgentStatus.RATE_LIMITED,
                rateLimitedUntilMs = expiredRetryTime
            )

            val harness = TestEnvironment.create()
                .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
                .withInitialState("agent", agentState)
                .build(scope = testScope, platform = harnessPlatform)

            // ACT
            AgentAutoTriggerLogic.checkAndDispatchTriggers(
                harness.store, agentState, harnessPlatform, "agent"
            )
            runCurrent()

            // ASSERT
            harness.runAndLogOnFailure {
                val initiateTurn = harness.processedActions.find {
                    it.name == ActionRegistry.Names.AGENT_INITIATE_TURN
                }
                assertNotNull(initiateTurn,
                    "INITIATE_TURN should be dispatched when rate limit has expired.")
                assertEquals(testAgentUuid.uuid,
                    initiateTurn.payload?.get("agentId")?.toString()?.trim('"'))
            }
        }
    }

    @Test
    fun `auto-trigger does NOT dispatch when rate limit is still active`() {
        val testScope = TestScope()
        testScope.runTest {
            // ARRANGE
            val harnessPlatform = FakePlatformDependencies("test")
            val futureRetryTime = harnessPlatform.currentTimeMillis() + 30_000 // 30s from now

            val agent = testAgent(isActive = true)
            val agentState = stateWith(
                agent = agent,
                status = AgentStatus.RATE_LIMITED,
                rateLimitedUntilMs = futureRetryTime
            )

            val harness = TestEnvironment.create()
                .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
                .withInitialState("agent", agentState)
                .build(scope = testScope, platform = harnessPlatform)

            // ACT
            AgentAutoTriggerLogic.checkAndDispatchTriggers(
                harness.store, agentState, harnessPlatform, "agent"
            )
            runCurrent()

            // ASSERT
            harness.runAndLogOnFailure {
                val initiateTurn = harness.processedActions.find {
                    it.name == ActionRegistry.Names.AGENT_INITIATE_TURN
                }
                assertNull(initiateTurn,
                    "INITIATE_TURN should NOT be dispatched when rate limit is still active.")
            }
        }
    }

    @Test
    fun `auto-trigger does NOT dispatch for inactive agents even if rate limit expired`() {
        val testScope = TestScope()
        testScope.runTest {
            // ARRANGE
            val harnessPlatform = FakePlatformDependencies("test")
            val expiredRetryTime = harnessPlatform.currentTimeMillis() - 5_000

            val agent = testAgent(isActive = false) // Inactive!
            val agentState = stateWith(
                agent = agent,
                status = AgentStatus.RATE_LIMITED,
                rateLimitedUntilMs = expiredRetryTime
            )

            val harness = TestEnvironment.create()
                .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
                .withInitialState("agent", agentState)
                .build(scope = testScope, platform = harnessPlatform)

            // ACT
            AgentAutoTriggerLogic.checkAndDispatchTriggers(
                harness.store, agentState, harnessPlatform, "agent"
            )
            runCurrent()

            // ASSERT
            harness.runAndLogOnFailure {
                val initiateTurn = harness.processedActions.find {
                    it.name == ActionRegistry.Names.AGENT_INITIATE_TURN
                }
                assertNull(initiateTurn,
                    "INITIATE_TURN should NOT be dispatched for inactive agents.")
            }
        }
    }

    @Test
    fun `auto-trigger rate limit retry applies regardless of automaticMode setting`() {
        val testScope = TestScope()
        testScope.runTest {
            // ARRANGE: Agent is NOT in automatic mode but IS rate limited with expired window.
            // Rate limit retry should fire for ALL agents, not just automatic ones.
            val harnessPlatform = FakePlatformDependencies("test")
            val expiredRetryTime = harnessPlatform.currentTimeMillis() - 1_000

            val agent = testAgent(automaticMode = false, isActive = true)
            val agentState = stateWith(
                agent = agent,
                status = AgentStatus.RATE_LIMITED,
                rateLimitedUntilMs = expiredRetryTime
            )

            val harness = TestEnvironment.create()
                .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
                .withInitialState("agent", agentState)
                .build(scope = testScope, platform = harnessPlatform)

            // ACT
            AgentAutoTriggerLogic.checkAndDispatchTriggers(
                harness.store, agentState, harnessPlatform, "agent"
            )
            runCurrent()

            // ASSERT
            harness.runAndLogOnFailure {
                val initiateTurn = harness.processedActions.find {
                    it.name == ActionRegistry.Names.AGENT_INITIATE_TURN
                }
                assertNotNull(initiateTurn,
                    "Rate limit retry should fire for non-automatic agents too — " +
                            "any agent can hit a rate limit, manual or automatic.")
            }
        }
    }

    @Test
    fun `auto-trigger does NOT fire automatic debounce for RATE_LIMITED agents`() {
        val testScope = TestScope()
        testScope.runTest {
            // ARRANGE: Agent is automatic AND rate limited (window not expired).
            // The WAITING auto-trigger logic should NOT fire because status is RATE_LIMITED, not WAITING.
            val harnessPlatform = FakePlatformDependencies("test")
            val futureRetryTime = harnessPlatform.currentTimeMillis() + 60_000

            val agent = testAgent(automaticMode = true, isActive = true)
            val agentState = stateWith(
                agent = agent,
                status = AgentStatus.RATE_LIMITED,
                rateLimitedUntilMs = futureRetryTime,
                // Set timestamps that WOULD trigger debounce if status were WAITING
                waitingSinceTimestamp = harnessPlatform.currentTimeMillis() - 60_000,
                lastMessageReceivedTimestamp = harnessPlatform.currentTimeMillis() - 60_000
            )

            val harness = TestEnvironment.create()
                .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
                .withInitialState("agent", agentState)
                .build(scope = testScope, platform = harnessPlatform)

            // ACT
            AgentAutoTriggerLogic.checkAndDispatchTriggers(
                harness.store, agentState, harnessPlatform, "agent"
            )
            runCurrent()

            // ASSERT
            harness.runAndLogOnFailure {
                val initiateTurn = harness.processedActions.find {
                    it.name == ActionRegistry.Names.AGENT_INITIATE_TURN
                }
                assertNull(initiateTurn,
                    "Neither rate limit retry (window not expired) nor debounce trigger " +
                            "(status is RATE_LIMITED, not WAITING) should fire.")
            }
        }
    }

    // =========================================================================
    // Section 5: Reducer — Edge cases and state integrity
    // =========================================================================

    @Test
    fun `SET_STATUS to RATE_LIMITED without rateLimitedUntilMs preserves existing value`() {
        // ARRANGE: Simulate a SET_STATUS dispatch that doesn't include the timestamp
        // (e.g., from avatar update logic that only sets status + error).
        val existingExpiry = platform.currentTimeMillis() + 30_000
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = existingExpiry
        )
        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("status", AgentStatus.RATE_LIMITED.name)
            put("error", "Updated error message")
            // NOTE: rateLimitedUntilMs is NOT in this payload
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: Existing rateLimitedUntilMs should be preserved.
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertEquals(AgentStatus.RATE_LIMITED, statusInfo.status)
        assertEquals(existingExpiry, statusInfo.rateLimitedUntilMs,
            "Existing rateLimitedUntilMs should be preserved when not provided in payload.")
        assertEquals("Updated error message", statusInfo.errorMessage)
    }

    @Test
    fun `INITIATE_TURN with RATE_LIMITED and null rateLimitedUntilMs is allowed`() {
        // ARRANGE: Edge case — RATE_LIMITED but no timestamp (shouldn't happen normally,
        // but the reducer should handle it gracefully by allowing the turn).
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = null
        )
        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("preview", false)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT: Should be allowed through (no timestamp to check against).
        val statusInfo = newState.agentStatuses[testAgentUuid]!!
        assertNull(statusInfo.rateLimitedUntilMs)
        assertEquals(TurnMode.DIRECT, statusInfo.turnMode,
            "Turn should proceed when RATE_LIMITED has no timestamp.")
    }

    @Test
    fun `INITIATE_TURN in preview mode is also blocked by rate limit`() {
        // ARRANGE: Even preview turns should respect rate limits — they still hit the API.
        val rateLimitExpiry = platform.currentTimeMillis() + 60_000
        val state = stateWith(
            status = AgentStatus.RATE_LIMITED,
            rateLimitedUntilMs = rateLimitExpiry
        )
        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", testAgentUuid.uuid)
            put("preview", true)
        })

        // ACT
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        assertEquals(AgentStatus.RATE_LIMITED, newState.agentStatuses[testAgentUuid]!!.status,
            "Preview turns should also be blocked by rate limiting.")
    }
}