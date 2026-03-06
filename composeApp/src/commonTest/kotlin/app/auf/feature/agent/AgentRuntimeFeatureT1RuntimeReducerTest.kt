package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
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
                transientWorkspaceContext = "some context", contextGatheringStartedAt = 500L
            ))
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "ERROR"); put("error", "Gateway timeout")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[uid(agentId)]!!

        assertEquals(AgentStatus.ERROR, newStatus.status)
        assertEquals("Gateway timeout", newStatus.errorMessage)
        assertNull(newStatus.transientWorkspaceContext)
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
    // MESSAGE_POSTED — Avatar Card Tracking
    // =========================================================================

    @Test
    fun `MESSAGE_POSTED with render_as_partial metadata should track avatar card`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(
            agents = mapOf(uid(AGENT_1) to agent),
            agentStatuses = mapOf(uid(AGENT_1) to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject {
                put("id", "avatar-msg-1"); put("senderId", AGENT_1); put("timestamp", 1000L)
                put("metadata", buildJsonObject { put("render_as_partial", true) })
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals("avatar-msg-1", newState.agentAvatarCardIds[uid(AGENT_1)]?.get(uid(SESSION_1)))
    }

    @Test
    fun `MESSAGE_POSTED with avatar metadata resolves agent by handle`() {
        val agent = testAgent(AGENT_1, "Test", subscribedSessionIds = listOf(SESSION_1))
        val initialState = AgentRuntimeState(agents = mapOf(uid(AGENT_1) to agent))

        val action = Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", SESSION_1); put("sessionUUID", SESSION_1)
            put("entry", buildJsonObject {
                put("id", "avatar-msg-2"); put("senderId", agent.identityHandle.handle); put("timestamp", 1000L)
                put("metadata", buildJsonObject { put("render_as_partial", true) })
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        assertEquals("avatar-msg-2", newState.agentAvatarCardIds[uid(AGENT_1)]?.get(uid(SESSION_1)))
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
        val agent = testAgent(AGENT_1, "Test", null, "p", "m",
            subscribedSessionIds = listOf(SESSION_1), privateSessionId = SESSION_OUTPUT
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
            status = AgentStatus.IDLE, transientWorkspaceContext = "old", contextGatheringStartedAt = 1234L,
            stagedTurnContext = listOf(GatewayMessage("user", "old", "u", "U", 0L))
        )
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to initialStatus))

        val action = Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject { put("agentId", agentId); put("preview", false) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val s = newState.agentStatuses[uid(agentId)]!!

        assertNull(s.transientWorkspaceContext); assertNull(s.contextGatheringStartedAt)
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
    fun `SET_PREVIEW_DATA should update status and set active viewing context`() {
        val agentId = "agent-1"
        val request = GatewayRequest("model", emptyList(), agentId)
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        val action = Action(ActionRegistry.Names.AGENT_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agentId); put("agnosticRequest", json.encodeToJsonElement(request)); put("rawRequestJson", "{}")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertNotNull(newState.agentStatuses[uid(agentId)]?.stagedPreviewData)
        assertEquals(uid(agentId), newState.viewingContextForAgentId)
    }

    @Test
    fun `DISCARD_PREVIEW should clear preview data and viewing context`() {
        val agentId = "agent-1"
        val previewData = StagedPreviewData(GatewayRequest("m", emptyList(), "id"), "{}")
        val state = AgentRuntimeState(
            agentStatuses = mapOf(uid(agentId) to AgentStatusInfo(stagedPreviewData = previewData)),
            viewingContextForAgentId = uid(agentId)
        )

        val action = Action(ActionRegistry.Names.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertNull(newState.agentStatuses[uid(agentId)]?.stagedPreviewData)
        assertNull(newState.viewingContextForAgentId)
    }

    // =========================================================================
    // Workspace Context
    // =========================================================================

    @Test
    fun `SET_WORKSPACE_CONTEXT should stage workspace context on agent status`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(uid(agentId) to AgentStatusInfo()))

        val action = Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_CONTEXT, buildJsonObject {
            put("agentId", agentId); put("context", "Your workspace has 3 files.")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        assertEquals("Your workspace has 3 files.", newState.agentStatuses[uid(agentId)]!!.transientWorkspaceContext)
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
            status = AgentStatus.PROCESSING, transientWorkspaceContext = "workspace data",
            contextGatheringStartedAt = 5555L, processingSinceTimestamp = 1000L
        )
        val state = AgentRuntimeState(
            agents = mapOf(uid(agentId) to testAgent(agentId, "Test")),
            agentStatuses = mapOf(uid(agentId) to initialStatus)
        )

        val action = Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject { put("agentId", agentId); put("status", "IDLE") })
        val newStatus = AgentRuntimeReducer.reduce(state, action, platform).agentStatuses[uid(agentId)]!!

        assertEquals(AgentStatus.IDLE, newStatus.status)
        assertNull(newStatus.transientWorkspaceContext)
        assertNull(newStatus.contextGatheringStartedAt)
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
}