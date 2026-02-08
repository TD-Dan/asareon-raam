package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.Json
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

    @Test
    fun `SET_STATUS should update status and timestamps correctly`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(agentId to AgentInstance(agentId, "Test", "", "", "")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(status = AgentStatus.IDLE))
        )

        val action = Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "PROCESSING")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[agentId]

        assertNotNull(newStatus)
        assertEquals(AgentStatus.PROCESSING, newStatus.status)
        assertNotNull(newStatus.processingSinceTimestamp)
    }

    @Test
    fun `SET_STATUS should clear errors when transitioning from ERROR`() {
        val agentId = "agent-1"
        val initialState = AgentRuntimeState(
            agents = mapOf(agentId to AgentInstance(agentId, "Test", "", "", "")),
            agentStatuses = mapOf(agentId to AgentStatusInfo(status = AgentStatus.ERROR, errorMessage = "Bad thing"))
        )

        val action = Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "IDLE")
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val newStatus = newState.agentStatuses[agentId]

        assertEquals(AgentStatus.IDLE, newStatus?.status)
        assertNull(newStatus?.errorMessage)
    }

    @Test
    fun `MESSAGE_POSTED should update lastSeenMessageId`() {
        val agentId = "agent-1"
        val sessionId = "session-1"
        val agent = AgentInstance(agentId, "Test", "", "", "", subscribedSessionIds = listOf(sessionId))
        val initialState = AgentRuntimeState(agents = mapOf(agentId to agent))

        val action = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", sessionId)
            put("entry", buildJsonObject {
                put("id", "msg-1")
                put("senderId", "user")
                put("timestamp", 1000L)
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[agentId]

        assertNotNull(status)
        assertEquals("msg-1", status.lastSeenMessageId)
    }

    @Test
    fun `MESSAGE_POSTED from 'system' should NOT update lastSeenMessageId (Sentinel Fix)`() {
        val agentId = "agent-1"
        val sessionId = "session-1"
        val agent = AgentInstance(agentId, "Test", "", "", "", subscribedSessionIds = listOf(sessionId))
        val initialStatus = AgentStatusInfo(lastSeenMessageId = "msg-old")
        val initialState = AgentRuntimeState(
            agents = mapOf(agentId to agent),
            agentStatuses = mapOf(agentId to initialStatus)
        )

        val action = Action(ActionNames.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
            put("sessionId", sessionId)
            put("entry", buildJsonObject {
                put("id", "msg-sentinel")
                put("senderId", "system") // <--- Sentinel sender
                put("timestamp", 1000L)
            })
        })

        val newState = AgentRuntimeReducer.reduce(initialState, action, platform)
        val status = newState.agentStatuses[agentId]

        // Assert state is unchanged
        assertEquals("msg-old", status?.lastSeenMessageId)
    }

    @Test
    fun `INITIATE_TURN should set mode and clear old context`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.IDLE,
            stagedTurnContext = listOf(GatewayMessage("user", "old", "u", "U", 0L))
        )
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to initialStatus))

        val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", agentId)
            put("preview", true)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val newStatus = newState.agentStatuses[agentId]!!

        assertEquals(TurnMode.PREVIEW, newStatus.turnMode)
        assertNull(newStatus.stagedTurnContext)
    }

    @Test
    fun `INTERNAL_STAGE_TURN_CONTEXT should update staged context`() {
        val agentId = "agent-1"
        val messages = listOf(GatewayMessage("user", "Hello", "u1", "User", 100L))
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to AgentStatusInfo()))

        val action = Action(ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agentId)
            put("messages", json.encodeToJsonElement(messages))
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val updatedStatus = newState.agentStatuses[agentId]!!

        assertEquals(1, updatedStatus.stagedTurnContext?.size)
        assertEquals("Hello", updatedStatus.stagedTurnContext?.first()?.content)
    }

    @Test
    fun `SET_PREVIEW_DATA should update status and set active viewing context`() {
        val agentId = "agent-1"
        val request = GatewayRequest("model", emptyList(), agentId)
        val rawJson = "{}"
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to AgentStatusInfo()))

        val action = Action(ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agentId)
            put("agnosticRequest", json.encodeToJsonElement(request))
            put("rawRequestJson", rawJson)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertNotNull(newState.agentStatuses[agentId]?.stagedPreviewData)
        assertEquals(agentId, newState.viewingContextForAgentId)
    }

    @Test
    fun `DISCARD_PREVIEW should clear preview data and viewing context`() {
        val agentId = "agent-1"
        val previewData = StagedPreviewData(GatewayRequest("m", emptyList(), "id"), "{}")
        val state = AgentRuntimeState(
            agentStatuses = mapOf(agentId to AgentStatusInfo(stagedPreviewData = previewData)),
            viewingContextForAgentId = agentId
        )

        val action = Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        assertNull(newState.agentStatuses[agentId]?.stagedPreviewData)
        assertNull(newState.viewingContextForAgentId)
    }

    @Test
    fun `SESSION_DELETED should remove session from agent subscriptions and trigger persistence`() {
        // ARRANGE
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("s1", "s2"))
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))

        // ACT
        val action = Action(ActionNames.SESSION_PUBLISH_SESSION_DELETED, buildJsonObject {
            put("sessionId", "s1")
        })
        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // ASSERT
        val updatedAgent = newState.agents["a1"]!!
        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertEquals("s2", updatedAgent.subscribedSessionIds.first())
        assertTrue(newState.agentsToPersist?.contains("a1") == true)
    }

    // === Workspace Context Reducer Tests ===

    @Test
    fun `SET_WORKSPACE_CONTEXT should stage workspace context on agent status`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to AgentStatusInfo()))

        val action = Action(ActionNames.AGENT_INTERNAL_SET_WORKSPACE_CONTEXT, buildJsonObject {
            put("agentId", agentId)
            put("context", "Your workspace has 3 files.")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[agentId]!!

        assertEquals("Your workspace has 3 files.", status.transientWorkspaceContext)
    }

    @Test
    fun `SET_CONTEXT_GATHERING_STARTED should record timestamp on agent status`() {
        val agentId = "agent-1"
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to AgentStatusInfo()))

        val action = Action(ActionNames.AGENT_INTERNAL_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
            put("agentId", agentId)
            put("startedAt", 9999L)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val status = newState.agentStatuses[agentId]!!

        assertEquals(9999L, status.contextGatheringStartedAt)
    }

    @Test
    fun `CONTEXT_GATHERING_TIMEOUT is a no-op in reducer (side-effect only)`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(status = AgentStatus.PROCESSING, contextGatheringStartedAt = 5000L)
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to initialStatus))

        val action = Action(ActionNames.AGENT_INTERNAL_CONTEXT_GATHERING_TIMEOUT, buildJsonObject {
            put("agentId", agentId)
            put("startedAt", 5000L)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)

        // State should be completely unchanged
        assertEquals(state, newState)
    }

    @Test
    fun `INITIATE_TURN should clear workspace context and gathering timestamp`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.IDLE,
            transientWorkspaceContext = "old workspace data",
            contextGatheringStartedAt = 1234L,
            stagedTurnContext = listOf(GatewayMessage("user", "old", "u", "U", 0L))
        )
        val state = AgentRuntimeState(agentStatuses = mapOf(agentId to initialStatus))

        val action = Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
            put("agentId", agentId)
            put("preview", false)
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val newStatus = newState.agentStatuses[agentId]!!

        assertNull(newStatus.transientWorkspaceContext)
        assertNull(newStatus.contextGatheringStartedAt)
        assertNull(newStatus.stagedTurnContext)
        assertNull(newStatus.transientHkgContext)
    }

    @Test
    fun `SET_STATUS to IDLE should clear workspace context and gathering timestamp`() {
        val agentId = "agent-1"
        val initialStatus = AgentStatusInfo(
            status = AgentStatus.PROCESSING,
            transientWorkspaceContext = "workspace data",
            contextGatheringStartedAt = 5555L,
            processingSinceTimestamp = 1000L
        )
        val state = AgentRuntimeState(
            agents = mapOf(agentId to AgentInstance(agentId, "Test", "", "", "")),
            agentStatuses = mapOf(agentId to initialStatus)
        )

        val action = Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", "IDLE")
        })

        val newState = AgentRuntimeReducer.reduce(state, action, platform)
        val newStatus = newState.agentStatuses[agentId]!!

        assertEquals(AgentStatus.IDLE, newStatus.status)
        assertNull(newStatus.transientWorkspaceContext)
        assertNull(newStatus.contextGatheringStartedAt)
    }
}