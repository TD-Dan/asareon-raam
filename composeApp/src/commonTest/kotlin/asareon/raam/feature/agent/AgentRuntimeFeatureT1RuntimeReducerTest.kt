package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.IdentityUUID
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentRuntimeReducer.
 * Verifies pure state transitions for ephemeral runtime logic.
 */
class AgentRuntimeFeatureT1RuntimeReducerTest {

    private val platform = FakePlatformDependencies("test")
    private val json = Json { ignoreUnknownKeys = true }

    // UUID constants for IDs that pass through UUID validation in the reducer.
    // SESSION_MESSAGE_POSTED validates sessionId with stringIsUUID().
    // SESSION_SESSION_DELETED validates sessionUUID with stringIsUUID().
    // MessageDeletedPayload now requires a sessionId field.
    private val AGENT_1 = "b0000000-0000-0000-0000-000000000001"
    private val SESSION_1 = "a0000000-0000-0000-0000-000000000001"
    private val SESSION_2 = "a0000000-0000-0000-0000-000000000002"
    private val SESSION_OUTPUT = "a0000000-0000-0000-0000-0000000000aa"

    @BeforeTest
    fun setUp() {
        // SESSION_DELETED calls strategy.validateConfig() which requires registered strategies.
        CognitiveStrategyRegistry.clearForTesting()
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.VanillaStrategy, legacyId = "vanilla_v1")
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.SovereignStrategy, legacyId = "sovereign_v1")
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.StateMachineStrategy)
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.PrivateSessionStrategy)
        CognitiveStrategyRegistry.register(asareon.raam.feature.agent.strategies.HKGStrategy)
    }

    @AfterTest
    fun tearDown() {
        CognitiveStrategyRegistry.clearForTesting()
    }

    // =========================================================================
    // SET_STATUS
    // =========================================================================

    @Test
    fun `SET_STATUS should update status and timestamps correctly`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "PROCESSING")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]

        assertNotNull(newStatus)
        assertEquals(AgentStatus.PROCESSING, newStatus.status)
        assertNotNull(newStatus.processingSinceTimestamp)
    }

    @Test
    fun `SET_STATUS should clear errors when transitioning from ERROR`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(status = AgentStatus.ERROR, errorMessage = "Bad thing"))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "IDLE")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]

        assertEquals(AgentStatus.IDLE, newStatus?.status)
        assertNull(newStatus?.errorMessage)
    }

    @Test
    fun `SET_STATUS should pass through token usage when provided`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(status = AgentStatus.PROCESSING, processingSinceTimestamp = 1000L))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "IDLE")
            put("lastInputTokens", 512); put("lastOutputTokens", 128)
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]!!

        assertEquals(512, newStatus.lastInputTokens)
        assertEquals(128, newStatus.lastOutputTokens)
    }

    @Test
    fun `SET_STATUS should preserve existing token usage when not provided`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(
                status = AgentStatus.IDLE, lastInputTokens = 100, lastOutputTokens = 50
            ))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "PROCESSING")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]!!
        assertEquals(100, newStatus.lastInputTokens)
        assertEquals(50, newStatus.lastOutputTokens)
    }

    @Test
    fun `SET_STATUS should clear WAITING timers when transitioning from WAITING`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(
                status = AgentStatus.WAITING, waitingSinceTimestamp = 1000L, lastMessageReceivedTimestamp = 2000L
            ))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "PROCESSING")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]!!

        assertEquals(AgentStatus.PROCESSING, newStatus.status)
        assertNull(newStatus.waitingSinceTimestamp)
        assertNull(newStatus.lastMessageReceivedTimestamp)
    }

    @Test
    fun `SET_STATUS to ERROR should set error message and clear context`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(
                status = AgentStatus.PROCESSING, processingSinceTimestamp = 1000L,
                transientWorkspaceListing = JsonArray(emptyList()), contextGatheringStartedAt = 500L
            ))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "ERROR"); put("error", "Gateway timeout")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]!!

        assertEquals(AgentStatus.ERROR, newStatus.status)
        assertEquals("Gateway timeout", newStatus.errorMessage)
        assertNull(newStatus.transientWorkspaceListing)
        assertNull(newStatus.contextGatheringStartedAt)
    }

    @Test
    fun `SET_STATUS should reset agentsToPersist flag`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(status = AgentStatus.IDLE)),
            agentsToPersist = setOf(uid(agentId))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "PROCESSING")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertNull(newState.agentsToPersist)
    }

    @Test
    fun `SET_STATUS with invalid status string should be a no-op`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "BOGUS")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals(AgentStatus.IDLE, newState.agentStatuses[uid(agentId)]?.status)
    }

    // =========================================================================
    // MESSAGE_POSTED — Auto-Waiting
    // =========================================================================

    @Test
    fun `MESSAGE_POSTED should update lastSeenMessageId`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(agents = mapOf(uid(AGENT_1) to agent))

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-1"); put("senderId", "user"); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals("msg-1", newState.agentStatuses[uid(AGENT_1)]?.lastSeenMessageId)
    }

    @Test
    fun `MESSAGE_POSTED should transition IDLE agent to WAITING`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-1"); put("senderId", "user"); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[uid(AGENT_1)]!!

        assertEquals(AgentStatus.WAITING, status.status)
        assertNotNull(status.waitingSinceTimestamp)
        assertNotNull(status.lastMessageReceivedTimestamp)
    }

    @Test
    fun `MESSAGE_POSTED should NOT change status if agent is PROCESSING`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.PROCESSING, processingSinceTimestamp = 1000L))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-1"); put("senderId", "user"); put("timestamp", 2000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[uid(AGENT_1)]!!

        assertEquals(AgentStatus.PROCESSING, status.status)
        assertEquals("msg-1", status.lastSeenMessageId)
    }

    @Test
    fun `MESSAGE_POSTED should not re-set waitingSinceTimestamp if already waiting`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.WAITING, waitingSinceTimestamp = 1000L))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-2"); put("senderId", "user"); put("timestamp", 5000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals(1000L, newState.agentStatuses[uid(AGENT_1)]!!.waitingSinceTimestamp)
    }

    // =========================================================================
    // MESSAGE_POSTED — Self-Message & Irrelevant
    // =========================================================================

    @Test
    fun `MESSAGE_POSTED from agent itself should only update lastSeenMessageId (isSelf by UUID)`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-self"); put("senderId", AGENT_1); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[uid(AGENT_1)]!!

        assertEquals("msg-self", status.lastSeenMessageId)
        assertEquals(AgentStatus.IDLE, status.status)
        assertNull(status.waitingSinceTimestamp)
    }

    @Test
    fun `MESSAGE_POSTED from agent itself should detect isSelf by handle`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-self-h"); put("senderId", agent.identityHandle.handle); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[uid(AGENT_1)]!!

        assertEquals("msg-self-h", status.lastSeenMessageId)
        assertEquals(AgentStatus.IDLE, status.status)
    }

    @Test
    fun `MESSAGE_POSTED for unsubscribed session should be ignored`() {
        val otherSession = "a0000000-0000-0000-0000-000000000099"
        val unrelatedSession = "a0000000-0000-0000-0000-0000000000ff"
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(otherSession))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", unrelatedSession); put("sessionUUID", unrelatedSession)
            put("entry", buildJsonObject { put("id", "msg-1"); put("senderId", "user"); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertNull(newState.agentStatuses[uid(AGENT_1)]?.lastSeenMessageId)
    }

    @Test
    fun `MESSAGE_POSTED from 'system' should NOT update lastSeenMessageId (Sentinel Fix)`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(lastSeenMessageId = "msg-old"))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject { put("id", "msg-sentinel"); put("senderId", "system"); put("timestamp", 1000L) })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals("msg-old", newState.agentStatuses[uid(AGENT_1)]?.lastSeenMessageId)
    }

    // =========================================================================
    // MESSAGE_POSTED — Avatar Card Filtering
    //
    // Avatar card state is managed exclusively by AGENT_AVATAR_MOVED.
    // MESSAGE_POSTED must NOT update agentAvatarCardIds — doing so creates
    // a race condition where late-arriving broadcasts overwrite newer entries.
    // Avatar messages are filtered out early to prevent agent WAITING transitions.
    // =========================================================================

    @Test
    fun `MESSAGE_POSTED with render_as_partial metadata should NOT update avatar card state`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE)),
            agentAvatarCardIds = emptyMap() // No existing avatar cards
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject {
                put("id", "avatar-msg-1"); put("senderId", AGENT_1); put("timestamp", 1000L)
                put("metadata", buildJsonObject { put("render_as_partial", true) })
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)

        // Avatar card state must NOT be updated — AGENT_AVATAR_MOVED is the sole authority
        assertTrue(newState.agentAvatarCardIds.isEmpty(),
            "MESSAGE_POSTED must not track avatar cards — AGENT_AVATAR_MOVED is the sole authority")
    }

    @Test
    fun `MESSAGE_POSTED with avatar metadata should not trigger WAITING transition`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject {
                put("id", "avatar-msg-2"); put("senderId", agent.identityHandle.handle); put("timestamp", 1000L)
                put("metadata", buildJsonObject { put("render_as_partial", true) })
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)

        assertEquals(AgentStatus.IDLE, newState.agentStatuses[uid(AGENT_1)]?.status,
            "Avatar messages must not trigger WAITING transition")
    }

    @Test
    fun `MESSAGE_POSTED with avatar metadata should not overwrite existing AGENT_AVATAR_MOVED entries`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE)),
            // AGENT_AVATAR_MOVED has already set this card — it is the authoritative entry
            agentAvatarCardIds = mapOf(uid(AGENT_1) to mapOf(uid(SESSION_1) to "current-card-from-avatar-moved"))
        )

        // A late-arriving MESSAGE_POSTED broadcast for an OLD card
        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject {
                put("id", "old-stale-card"); put("senderId", AGENT_1); put("timestamp", 1000L)
                put("metadata", buildJsonObject { put("render_as_partial", true) })
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)

        // The authoritative entry from AGENT_AVATAR_MOVED must be preserved
        assertEquals("current-card-from-avatar-moved",
            newState.agentAvatarCardIds[uid(AGENT_1)]?.get(uid(SESSION_1)),
            "MESSAGE_POSTED must not overwrite entries set by AGENT_AVATAR_MOVED")
    }

    // =========================================================================
    // MESSAGE_DELETED
    // =========================================================================

    @Test
    fun `MESSAGE_DELETED should remove avatar card entry`() {
        val initialState = AgentRuntimeState(
            agentAvatarCardIds = mapOf(uid("a1") to mapOf(uid("s1") to "msg-avatar"))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_DELETED, buildJsonObject {
            put("messageId", "msg-avatar"); put("sessionId", "s1")
        })
        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)

        assertFalse(newState.agentAvatarCardIds[uid("a1")]!!.containsKey(uid("s1")))
    }

    @Test
    fun `MESSAGE_DELETED for non-avatar message should be a no-op`() {
        val initialState = AgentRuntimeState(
            agentAvatarCardIds = mapOf(uid("a1") to mapOf(uid("s1") to "msg-avatar"))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_DELETED, buildJsonObject {
            put("messageId", "msg-unrelated"); put("sessionId", "s1")
        })
        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)

        assertEquals("msg-avatar", newState.agentAvatarCardIds[uid("a1")]!![uid("s1")])
    }

    // =========================================================================
    // SESSION_SESSION_DELETED
    // =========================================================================

    @Test
    fun `SESSION_DELETED should remove session from agent subscriptions and trigger persistence`() {
        val agent = testAgent(AGENT_1, "Test", null, "p", "m", subscribedSessionIds = listOf(SESSION_1, SESSION_2))
        val state = AgentRuntimeState(agents = mapOf(uid(AGENT_1) to agent))

        val action = Action(ActionRegistry.Names.SESSION_SESSION_DELETED, buildJsonObject { put("sessionUUID", SESSION_1) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        val updatedAgent = newState.agents[uid(AGENT_1)]!!
        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertEquals(IdentityUUID(SESSION_2), updatedAgent.subscribedSessionIds.first())
        assertTrue(newState.agentsToPersist?.contains(uid(AGENT_1)) == true)
    }

    @Test
    fun `SESSION_DELETED should clear outputSessionId if it matches deleted session`() {
        // subscribedSessionIds is empty so Vanilla's validateConfig cannot auto-assign
        // outputSessionId to a remaining subscription after it's cleared.
        val agent = testAgent(AGENT_1, "Test", null, "p", "m",
            subscribedSessionIds = emptyList(), privateSessionId = SESSION_OUTPUT
        )
        val state = AgentRuntimeState(agents = mapOf(uid(AGENT_1) to agent))

        val action = Action(ActionRegistry.Names.SESSION_SESSION_DELETED, buildJsonObject { put("sessionUUID", SESSION_OUTPUT) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertNull(newState.agents[uid(AGENT_1)]!!.outputSessionId)
    }

    @Test
    fun `SESSION_DELETED should clean up avatar cards for deleted session`() {
        val agent = testAgent(AGENT_1, "Test", null, "p", "m", subscribedSessionIds = listOf(SESSION_1, SESSION_2))
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentAvatarCardIds = mapOf(uid(AGENT_1) to mapOf(uid(SESSION_1) to "msg-1", uid(SESSION_2) to "msg-2"))
        )

        val action = Action(ActionRegistry.Names.SESSION_SESSION_DELETED, buildJsonObject { put("sessionUUID", SESSION_1) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertFalse(newState.agentAvatarCardIds[uid(AGENT_1)]!!.containsKey(uid(SESSION_1)))
        assertEquals("msg-2", newState.agentAvatarCardIds[uid(AGENT_1)]!![uid(SESSION_2)])
    }

    // =========================================================================
    // SESSION_NAMES_UPDATED
    // =========================================================================

    @Test
    fun `SESSION_NAMES_UPDATED should populate subscribableSessionNames from sessions array`() {
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED, buildJsonObject {
            put("sessions", buildJsonArray {
                add(buildJsonObject { put("uuid", SESSION_1); put("handle", "session.chat"); put("localHandle", "chat"); put("name", "Chat Room") })
                add(buildJsonObject { put("uuid", SESSION_2); put("handle", "session.general"); put("localHandle", "general"); put("name", "General") })
            })
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals(2, newState.subscribableSessionNames.size)
        assertEquals("Chat Room", newState.subscribableSessionNames[uid(SESSION_1)])
        assertEquals("General", newState.subscribableSessionNames[uid(SESSION_2)])
    }

    @Test
    fun `SESSION_NAMES_UPDATED without sessions array should be a no-op`() {
        val existingNames = mapOf(uid("s1") to "Existing")
        val state = AgentRuntimeState(subscribableSessionNames = existingNames)

        val action = Action(ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED, buildJsonObject {
            put("names", buildJsonObject { put("s1", "Existing") })
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals(existingNames, newState.subscribableSessionNames)
    }

    // =========================================================================
    // GATEWAY_AVAILABLE_MODELS_UPDATED
    // =========================================================================

    @Test
    fun `AVAILABLE_MODELS_UPDATED should populate availableModels map`() {
        val state = AgentRuntimeState()
        val modelsPayload = buildJsonObject {
            put("openai", buildJsonArray { add("gpt-4"); add("gpt-3.5-turbo") })
            put("gemini", buildJsonArray { add("gemini-pro") })
        }

        val action = Action(ActionRegistry.Names.GATEWAY_AVAILABLE_MODELS_UPDATED, modelsPayload)
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertEquals(2, newState.availableModels.size)
        assertEquals(listOf("gpt-4", "gpt-3.5-turbo"), newState.availableModels["openai"])
        assertEquals(listOf("gemini-pro"), newState.availableModels["gemini"])
    }

    // =========================================================================
    // RETURN_REGISTER_IDENTITY / RETURN_UPDATE_IDENTITY
    // =========================================================================

    @Test
    fun `RETURN_REGISTER_IDENTITY should update agent identity on success`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to testAgent(agentId, "Placeholder")))

        val action = Action(ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY, buildJsonObject {
            put("success", true); put("uuid", agentId)
            put("approvedLocalHandle", "my-agent"); put("handle", "agent.my-agent")
            put("name", "My Agent"); put("parentHandle", "agent")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val updated = newState.agents[uid(agentId)]!!
        assertEquals("my-agent", updated.identity.localHandle)
        assertEquals("agent.my-agent", updated.identity.handle)
        assertEquals("My Agent", updated.identity.name)
    }

    @Test
    fun `RETURN_REGISTER_IDENTITY should be a no-op on failure`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to testAgent(agentId, "Original")))

        val action = Action(ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY, buildJsonObject {
            put("success", false); put("uuid", agentId)
            put("approvedLocalHandle", "rejected"); put("handle", "agent.rejected"); put("name", "Rejected")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals("Original", newState.agents[uid(agentId)]!!.identity.name)
    }

    @Test
    fun `RETURN_UPDATE_IDENTITY should update agent identity fields`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to testAgent(agentId, "Old Name")))

        val action = Action(ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY, buildJsonObject {
            put("success", true); put("uuid", agentId)
            put("newLocalHandle", "new-name"); put("newHandle", "agent.new-name"); put("name", "New Name")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val updated = newState.agents[uid(agentId)]!!
        assertEquals("new-name", updated.identity.localHandle)
        assertEquals("agent.new-name", updated.identity.handle)
        assertEquals("New Name", updated.identity.name)
    }

    @Test
    fun `RETURN_UPDATE_IDENTITY should be a no-op on failure`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agents = mapOf(uid(agentId) to testAgent(agentId, "Keep Me")))

        val action = Action(ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY, buildJsonObject {
            put("success", false); put("uuid", agentId)
            put("newLocalHandle", "x"); put("newHandle", "agent.x"); put("name", "X")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals("Keep Me", newState.agents[uid(agentId)]!!.identity.name)
    }

    // =========================================================================
    // Pending Command Tracking
    // =========================================================================

    @Test
    fun `REGISTER_PENDING_COMMAND should add entry to pendingCommands`() {
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.AGENT_REGISTER_PENDING_COMMAND, buildJsonObject {
            put("correlationId", "corr-1"); put("agentId", "a1")
            put("agentName", "TestBot"); put("sessionId", "s1"); put("actionName", "knowledgegraph.SEARCH")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val pending = newState.pendingCommands["corr-1"]
        assertNotNull(pending)
        assertEquals(uid("a1"), pending.agentId)
        assertEquals("TestBot", pending.agentName)
        assertEquals(uid("s1"), pending.sessionId)
        assertEquals("knowledgegraph.SEARCH", pending.actionName)
    }

    @Test
    fun `CLEAR_PENDING_COMMAND should remove entry from pendingCommands`() {
        val existing = AgentPendingCommand("corr-1", uid("a1"), "TestBot", uid("s1"), "search", 1000L)
        val state = AgentRuntimeState(pendingCommands = mapOf("corr-1" to existing))

        val action = Action(ActionRegistry.Names.AGENT_CLEAR_PENDING_COMMAND, buildJsonObject { put("correlationId", "corr-1") })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertTrue(newState.pendingCommands.isEmpty())
    }

    // =========================================================================
    // INITIATE_TURN
    // =========================================================================

    @Test
    fun `INITIATE_TURN should set mode and clear old context`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(status = AgentStatus.IDLE, stagedTurnContext = listOf(GatewayMessage("user", "old", "u", "U", 0L)))
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentId); put("preview", true) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertEquals(TurnMode.PREVIEW, newState.agentStatuses[uid(agentId)]!!.turnMode)
        assertNull(newState.agentStatuses[uid(agentId)]!!.stagedTurnContext)
    }

    @Test
    fun `INITIATE_TURN should be a no-op if agent is PROCESSING`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(status = AgentStatus.PROCESSING, processingSinceTimestamp = 1000L)
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentId); put("preview", false) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertEquals(AgentStatus.PROCESSING, newState.agentStatuses[uid(agentId)]!!.status)
    }

    @Test
    fun `INITIATE_TURN should clear workspace context and gathering timestamp`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.IDLE, transientWorkspaceListing = JsonArray(emptyList()), contextGatheringStartedAt = 1234L,
            stagedTurnContext = listOf(GatewayMessage("user", "old", "u", "U", 0L))
        )
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentId); put("preview", false) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val s = newState.agentStatuses[uid(agentId)]!!

        assertNull(s.transientWorkspaceListing); assertNull(s.contextGatheringStartedAt)
        assertNull(s.stagedTurnContext); assertNull(s.transientHkgContext)
    }

    // =========================================================================
    // STAGE_TURN_CONTEXT / PREVIEW / DISCARD
    // =========================================================================

    @Test
    fun `INTERNAL_STAGE_TURN_CONTEXT should update staged context`() {
        val agentId = "agent-1"
        val messages = listOf(GatewayMessage("user", "Hello", "u1", "User", 100L))
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        val action = Action(ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agentId); put("messages", json.encodeToJsonElement(messages))
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals("Hello", newState.agentStatuses[uid(agentId)]!!.stagedTurnContext?.first()?.content)
    }

    @Test
    fun `SET_MANAGED_CONTEXT should store managed context and set active managing agent`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        // Set up the transient stash (same pattern the pipeline uses)
        val fakeResult = ContextAssemblyResult(
            partitions = emptyList(),
            collapseResult = ContextCollapseLogic.CollapseResult(emptyList(), 0),
            budgetReport = "",
            systemPrompt = "test prompt",
            gatewayRequest = GatewayRequest("model", emptyList(), agentId, "test prompt"),
            softBudgetChars = 200_000,
            maxBudgetChars = 500_000,
            transientDataSnapshot = TransientDataSnapshot(emptyMap(), null, null, emptyMap(), emptyMap(), emptyMap())
        )
        CognitivePipeline.pendingManagedContext = fakeResult

        val action = Action(ActionRegistry.Names.AGENT_SET_MANAGED_CONTEXT, buildJsonObject {
            put("agentId", agentId)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertNotNull(newState.agentStatuses[uid(agentId)]?.managedContext)
        assertNotNull(newState.agentStatuses[uid(agentId)]?.managedPartitions)
        assertEquals(uid(agentId), newState.managingContextForAgentId)
        // Stash should be cleared after consumption
        assertNull(CognitivePipeline.pendingManagedContext)
    }

    @Test
    fun `DISCARD_MANAGED_CONTEXT should clear managed context and managing agent`() {
        val agentId = "agent-1"
        val fakeResult = ContextAssemblyResult(
            partitions = emptyList(),
            collapseResult = ContextCollapseLogic.CollapseResult(emptyList(), 0),
            budgetReport = "",
            systemPrompt = "test prompt",
            gatewayRequest = GatewayRequest("model", emptyList(), agentId, "test prompt"),
            softBudgetChars = 200_000,
            maxBudgetChars = 500_000,
            transientDataSnapshot = TransientDataSnapshot(emptyMap(), null, null, emptyMap(), emptyMap(), emptyMap())
        )
        val state = AgentRuntimeState(
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(managedContext = fakeResult)),
            managingContextForAgentId = uid(agentId)
        )

        val action = Action(ActionRegistry.Names.AGENT_DISCARD_MANAGED_CONTEXT, buildJsonObject { put("agentId", agentId) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertNull(newState.agentStatuses[uid(agentId)]?.managedContext)
        assertNull(newState.agentStatuses[uid(agentId)]?.managedPartitions)
        assertNull(newState.agentStatuses[uid(agentId)]?.managedContextRawJson)
        assertNull(newState.agentStatuses[uid(agentId)]?.managedContextEstimatedTokens)
        assertNull(newState.managingContextForAgentId)
    }

    // =========================================================================
    // Workspace Context
    // =========================================================================

    @Test
    fun `SET_WORKSPACE_LISTING should stage workspace listing on agent status`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        val listing = buildJsonArray {
            add(buildJsonObject { put("path", "file.txt"); put("isDirectory", false) })
        }
        val action = Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING, buildJsonObject {
            put("agentId", agentId); put("listing", listing)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals(listing, newState.agentStatuses[uid(agentId)]!!.transientWorkspaceListing)
    }

    @Test
    fun `SET_CONTEXT_GATHERING_STARTED should record timestamp on agent status`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        val action = Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
            put("agentId", agentId); put("startedAt", 9999L)
        })

        assertEquals(9999L, AgentRuntimeReducer.reduce(state, action, platform).agentStatuses[uid(agentId)]!!.contextGatheringStartedAt)
    }

    @Test
    fun `CONTEXT_GATHERING_TIMEOUT is a no-op in reducer (side-effect only)`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(status = AgentStatus.PROCESSING, contextGatheringStartedAt = 5000L)
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT, buildJsonObject { put("agentId", agentId); put("startedAt", 5000L) })
        assertEquals(state, AgentRuntimeReducer.reduce(state, action, platform))
    }

    @Test
    fun `SET_STATUS to IDLE should clear workspace context and gathering timestamp`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.PROCESSING, transientWorkspaceListing = JsonArray(emptyList()),
            contextGatheringStartedAt = 5555L, processingSinceTimestamp = 1000L
        )
        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to initialStatus)
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject { put("agentId", agentId); put("status", "IDLE") })
        val newStatus = AgentRuntimeReducer.reduce(state, action, platform).agentStatuses[uid(agentId)]!!

        assertEquals(AgentStatus.IDLE, newStatus.status)
        assertNull(newStatus.transientWorkspaceListing)
        assertNull(newStatus.contextGatheringStartedAt)
    }

    // =========================================================================
    // Context Collapse Actions (Phase A — §3.6)
    // =========================================================================

    @Test
    fun `CONTEXT_UNCOLLAPSE with scope single sets override to EXPANDED`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject {
                put("agentId", "a1"); put("partitionKey", "hkg:some-holon-id"); put("scope", "single")
            }
        ), platform)

        assertEquals(CollapseState.EXPANDED, result.agentStatuses[agentId]?.contextCollapseOverrides?.get("hkg:some-holon-id"))
    }

    @Test
    fun `CONTEXT_UNCOLLAPSE with scope subtree sets override to EXPANDED`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject {
                put("agentId", "a1"); put("partitionKey", "hkg:parent-holon"); put("scope", "subtree")
            }
        ), platform)

        assertEquals(CollapseState.EXPANDED, result.agentStatuses[agentId]?.contextCollapseOverrides?.get("hkg:parent-holon"))
    }

    @Test
    fun `CONTEXT_UNCOLLAPSE preserves existing overrides`() {
        val agentId = uid("a1")
        val existing = mapOf("hkg:existing" to CollapseState.COLLAPSED)
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(contextCollapseOverrides = existing))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject {
                put("agentId", "a1"); put("partitionKey", "hkg:new-holon"); put("scope", "single")
            }
        ), platform)

        val overrides = result.agentStatuses[agentId]!!.contextCollapseOverrides
        assertEquals(2, overrides.size)
        assertEquals(CollapseState.COLLAPSED, overrides["hkg:existing"])
        assertEquals(CollapseState.EXPANDED, overrides["hkg:new-holon"])
    }

    @Test
    fun `CONTEXT_COLLAPSE sets override to COLLAPSED`() {
        val agentId = uid("a1")
        val existing = mapOf("hkg:some-holon" to CollapseState.EXPANDED)
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(contextCollapseOverrides = existing))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE,
            buildJsonObject {
                put("agentId", "a1"); put("partitionKey", "hkg:some-holon")
            }
        ), platform)

        assertEquals(CollapseState.COLLAPSED, result.agentStatuses[agentId]?.contextCollapseOverrides?.get("hkg:some-holon"))
    }

    @Test
    fun `CONTEXT_STATE_LOADED populates overrides from loaded data`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED,
            buildJsonObject {
                put("agentId", "a1")
                put("overrides", buildJsonObject {
                    put("hkg:meridian-root", "EXPANDED")
                    put("AVAILABLE_ACTIONS", "COLLAPSED")
                })
            }
        ), platform)

        val overrides = result.agentStatuses[agentId]!!.contextCollapseOverrides
        assertEquals(CollapseState.EXPANDED, overrides["hkg:meridian-root"])
        assertEquals(CollapseState.COLLAPSED, overrides["AVAILABLE_ACTIONS"])
    }

    @Test
    fun `CONTEXT_STATE_LOADED with empty overrides results in empty map`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED,
            buildJsonObject {
                put("agentId", "a1"); put("overrides", buildJsonObject {})
            }
        ), platform)

        assertTrue(result.agentStatuses[agentId]!!.contextCollapseOverrides.isEmpty())
    }

    @Test
    fun `context json round trip — save then load via reducer produces matching overrides`() {
        val agentId = uid("a1")
        var state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        // Build overrides via reducer
        state = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject { put("agentId", "a1"); put("partitionKey", "hkg:root"); put("scope", "single") }
        ), platform)
        state = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE,
            buildJsonObject { put("agentId", "a1"); put("partitionKey", "AVAILABLE_ACTIONS") }
        ), platform)

        val saved = state.agentStatuses[agentId]!!.contextCollapseOverrides

        // Simulate context.json serialization round-trip
        val serializedOverrides = buildJsonObject {
            saved.forEach { (k, v) -> put(k, v.name) }
        }

        val freshState = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )
        val reloaded = AgentRuntimeReducer.reduce(freshState, Action(
            ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED,
            buildJsonObject { put("agentId", "a1"); put("overrides", serializedOverrides) }
        ), platform)

        assertEquals(saved, reloaded.agentStatuses[agentId]!!.contextCollapseOverrides)
    }

    // =========================================================================
    // Context Collapse — Silent failure diagnostics (Phase C)
    // =========================================================================

    @Test
    fun `CONTEXT_UNCOLLAPSE without agentId should be no-op`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        // Payload has partitionKey but NO agentId — this was the live Meridian bug
        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject {
                put("partitionKey", "hkg:some-holon"); put("scope", "single")
            }
        ), platform)

        // State should be completely unchanged
        assertEquals(state, result)
        assertTrue(result.agentStatuses[agentId]!!.contextCollapseOverrides.isEmpty())
    }

    @Test
    fun `CONTEXT_COLLAPSE without agentId should be no-op`() {
        val agentId = uid("a1")
        val existing = mapOf("hkg:some-holon" to CollapseState.EXPANDED)
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(contextCollapseOverrides = existing))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_COLLAPSE,
            buildJsonObject { put("partitionKey", "hkg:some-holon") }
        ), platform)

        // EXPANDED override should remain — action was silently dropped
        assertEquals(CollapseState.EXPANDED, result.agentStatuses[agentId]!!.contextCollapseOverrides["hkg:some-holon"])
    }

    @Test
    fun `CONTEXT_UNCOLLAPSE without partitionKey should be no-op`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_UNCOLLAPSE,
            buildJsonObject { put("agentId", "a1"); put("scope", "single") }
        ), platform)

        assertEquals(state, result)
    }

    @Test
    fun `CONTEXT_STATE_LOADED with invalid CollapseState value should default to COLLAPSED`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_CONTEXT_STATE_LOADED,
            buildJsonObject {
                put("agentId", "a1")
                put("overrides", buildJsonObject {
                    put("hkg:good-key", "EXPANDED")
                    put("hkg:bad-key", "FOOBAR_INVALID")
                })
            }
        ), platform)

        val overrides = result.agentStatuses[agentId]!!.contextCollapseOverrides
        assertEquals(CollapseState.EXPANDED, overrides["hkg:good-key"])
        assertEquals(CollapseState.COLLAPSED, overrides["hkg:bad-key"])
    }

    // =========================================================================
    // Pending Private Session Guard (Phase A — §5.2)
    // =========================================================================

    @Test
    fun `SET_PENDING_PRIVATE_SESSION toggles flag to true`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(pendingPrivateSessionCreation = false))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION,
            buildJsonObject { put("agentId", "a1"); put("pending", true) }
        ), platform)

        assertTrue(result.agentStatuses[agentId]!!.pendingPrivateSessionCreation)
    }

    @Test
    fun `SET_PENDING_PRIVATE_SESSION toggles flag to false`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(
            agents = mapOf(agentId to testAgent("a1", "Test")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(pendingPrivateSessionCreation = true))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_PENDING_PRIVATE_SESSION,
            buildJsonObject { put("agentId", "a1"); put("pending", false) }
        ), platform)

        assertFalse(result.agentStatuses[agentId]!!.pendingPrivateSessionCreation)
    }

    // =========================================================================
    // Boundary / Malformed Payload Tests
    // =========================================================================

    @Test
    fun `SET_STATUS with null payload should be a no-op`() {
        val state = AgentRuntimeState()
        assertEquals(state, AgentRuntimeReducer.reduce(state, Action(ActionRegistry.Names.AGENT_SET_STATUS, null), platform))
    }

    @Test
    fun `unrecognized action name should be a no-op`() {
        val state = AgentRuntimeState()
        assertEquals(state, AgentRuntimeReducer.reduce(state, Action("completely.unknown.action", buildJsonObject { put("foo", "bar") }), platform))
    }

    // =========================================================================
    // Multi-Session Ledger Accumulation (Phase B — §6.2)
    // =========================================================================

    @Test
    fun `SET_PENDING_LEDGER_SESSIONS initializes pending set from payload`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_PENDING_LEDGER_SESSIONS,
            buildJsonObject {
                put("agentId", AGENT_1)
                put("sessionIds", buildJsonArray { add(SESSION_1); add(SESSION_2) })
            }
        ), platform)

        assertEquals(
            setOf(IdentityUUID(SESSION_1), IdentityUUID(SESSION_2)),
            result.agentStatuses[uid(AGENT_1)]!!.pendingLedgerSessionIds
        )
    }

    @Test
    fun `SET_PENDING_LEDGER_SESSIONS clears accumulated ledgers from previous turn`() {
        val staleAccumulated = mapOf(
            IdentityUUID(SESSION_1) to listOf(GatewayMessage("user", "old", "u1", "User", 1000L))
        )
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                accumulatedSessionLedgers = staleAccumulated
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_PENDING_LEDGER_SESSIONS,
            buildJsonObject {
                put("agentId", AGENT_1)
                put("sessionIds", buildJsonArray { add(SESSION_2) })
            }
        ), platform)

        assertTrue(result.agentStatuses[uid(AGENT_1)]!!.accumulatedSessionLedgers.isEmpty(),
            "Should clear stale accumulated data from previous turn")
    }

    @Test
    fun `SET_PENDING_LEDGER_SESSIONS with missing sessionIds returns state unchanged`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo())
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_PENDING_LEDGER_SESSIONS,
            buildJsonObject { put("agentId", AGENT_1) }
        ), platform)

        assertTrue(result === state)
    }

    @Test
    fun `ACCUMULATE_SESSION_LEDGER stores messages and removes session from pending`() {
        val messages = listOf(
            GatewayMessage("user", "Hello", "u1", "Alice", 1000L),
            GatewayMessage("model", "Hi!", "a1", "Bot", 2000L)
        )

        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1), IdentityUUID(SESSION_2)),
                accumulatedSessionLedgers = emptyMap()
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER,
            buildJsonObject {
                put("agentId", AGENT_1)
                put("sessionId", SESSION_1)
                put("messages", json.encodeToJsonElement(messages))
            }
        ), platform)

        val status = result.agentStatuses[uid(AGENT_1)]!!
        assertEquals(setOf(IdentityUUID(SESSION_2)), status.pendingLedgerSessionIds,
            "Should remove the arrived session from pending set")
        assertEquals(2, status.accumulatedSessionLedgers[IdentityUUID(SESSION_1)]!!.size)
        assertEquals("Hello", status.accumulatedSessionLedgers[IdentityUUID(SESSION_1)]!![0].content)
    }

    @Test
    fun `ACCUMULATE_SESSION_LEDGER accumulates multiple sessions independently`() {
        val msg1 = listOf(GatewayMessage("user", "From S1", "u1", "Alice", 1000L))
        val msg2 = listOf(GatewayMessage("user", "From S2", "u2", "Bob", 2000L))

        var state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1), IdentityUUID(SESSION_2))
            ))
        )

        state = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER,
            buildJsonObject {
                put("agentId", AGENT_1); put("sessionId", SESSION_1)
                put("messages", json.encodeToJsonElement(msg1))
            }
        ), platform)

        state = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER,
            buildJsonObject {
                put("agentId", AGENT_1); put("sessionId", SESSION_2)
                put("messages", json.encodeToJsonElement(msg2))
            }
        ), platform)

        val status = state.agentStatuses[uid(AGENT_1)]!!
        assertTrue(status.pendingLedgerSessionIds.isEmpty(), "All sessions should have arrived")
        assertEquals("From S1", status.accumulatedSessionLedgers[IdentityUUID(SESSION_1)]!!.first().content)
        assertEquals("From S2", status.accumulatedSessionLedgers[IdentityUUID(SESSION_2)]!!.first().content)
    }

    @Test
    fun `ACCUMULATE_SESSION_LEDGER with last pending session empties the pending set`() {
        val messages = listOf(GatewayMessage("user", "Done", "u1", "Alice", 1000L))

        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1))
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER,
            buildJsonObject {
                put("agentId", AGENT_1); put("sessionId", SESSION_1)
                put("messages", json.encodeToJsonElement(messages))
            }
        ), platform)

        assertTrue(result.agentStatuses[uid(AGENT_1)]!!.pendingLedgerSessionIds.isEmpty())
    }

    @Test
    fun `ACCUMULATE_SESSION_LEDGER with empty messages stores empty list`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1))
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER,
            buildJsonObject {
                put("agentId", AGENT_1); put("sessionId", SESSION_1)
                put("messages", json.encodeToJsonElement(emptyList<GatewayMessage>()))
            }
        ), platform)

        val stored = result.agentStatuses[uid(AGENT_1)]!!.accumulatedSessionLedgers[IdentityUUID(SESSION_1)]
        assertNotNull(stored)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `INITIATE_TURN clears pendingLedgerSessionIds and accumulatedSessionLedgers`() {
        val state = AgentRuntimeState(
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                status = AgentStatus.IDLE,
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1)),
                accumulatedSessionLedgers = mapOf(
                    IdentityUUID(SESSION_2) to listOf(GatewayMessage("user", "stale", "u1", "Alice", 1000L))
                )
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_INITIATE_TURN,
            buildJsonObject { put("agentId", AGENT_1); put("preview", false) }
        ), platform)

        val status = result.agentStatuses[uid(AGENT_1)]!!
        assertTrue(status.pendingLedgerSessionIds.isEmpty())
        assertTrue(status.accumulatedSessionLedgers.isEmpty())
    }

    @Test
    fun `SET_STATUS to IDLE clears multi-session ledger fields`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                status = AgentStatus.PROCESSING,
                processingSinceTimestamp = 1000L,
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1)),
                accumulatedSessionLedgers = mapOf(
                    IdentityUUID(SESSION_2) to listOf(GatewayMessage("user", "x", "u1", "U", 1L))
                )
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_STATUS,
            buildJsonObject { put("agentId", AGENT_1); put("status", "IDLE") }
        ), platform)

        val status = result.agentStatuses[uid(AGENT_1)]!!
        assertTrue(status.pendingLedgerSessionIds.isEmpty())
        assertTrue(status.accumulatedSessionLedgers.isEmpty())
    }

    @Test
    fun `SET_STATUS to ERROR clears multi-session ledger fields`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                status = AgentStatus.PROCESSING,
                processingSinceTimestamp = 1000L,
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1)),
                accumulatedSessionLedgers = mapOf(
                    IdentityUUID(SESSION_1) to listOf(GatewayMessage("user", "x", "u1", "U", 1L))
                )
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_STATUS,
            buildJsonObject { put("agentId", AGENT_1); put("status", "ERROR"); put("error", "fail") }
        ), platform)

        val status = result.agentStatuses[uid(AGENT_1)]!!
        assertTrue(status.pendingLedgerSessionIds.isEmpty())
        assertTrue(status.accumulatedSessionLedgers.isEmpty())
    }

    @Test
    fun `SET_STATUS to RATE_LIMITED clears multi-session ledger fields`() {
        val state = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to testAgent(AGENT_1, "Test")),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(
                status = AgentStatus.PROCESSING,
                processingSinceTimestamp = 1000L,
                pendingLedgerSessionIds = setOf(IdentityUUID(SESSION_1))
            ))
        )

        val result = AgentRuntimeReducer.reduce(state, Action(
            ActionRegistry.Names.AGENT_SET_STATUS,
            buildJsonObject {
                put("agentId", AGENT_1); put("status", "RATE_LIMITED")
                put("error", "429"); put("rateLimitedUntilMs", 9999L)
            }
        ), platform)

        assertTrue(result.agentStatuses[uid(AGENT_1)]!!.pendingLedgerSessionIds.isEmpty())
        assertTrue(result.agentStatuses[uid(AGENT_1)]!!.accumulatedSessionLedgers.isEmpty())
    }
}