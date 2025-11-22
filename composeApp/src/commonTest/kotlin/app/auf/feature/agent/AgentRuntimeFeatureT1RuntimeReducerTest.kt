package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentRuntimeReducer.
 * Verifies pure state transitions for ephemeral runtime logic.
 */
class AgentRuntimeFeatureT1RuntimeReducerTest {

    private val platform = FakePlatformDependencies("test")

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
}