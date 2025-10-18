package app.auf.feature.agent

import app.auf.core.Action
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.test.TestEnvironment
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 2 Core Integration Test for AgentRuntimeFeature.
 *
 * Mandate (P-TEST-001, T2): To test the AgentRuntimeFeature's complete internal logic
 * against its foundational contract with the system (the Store). This verifies that the
 * feature correctly processes actions, updates its own state, and orchestrates side effects.
 *
 * This file replaces the previous Reducer/OnAction split, per P-TEST-002.
 */
class AgentRuntimeFeatureCoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `create() adds new agent to state and dispatches SYSTEM_WRITE`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), scope))
            .withFeature(FileSystemFeature(FakePlatformDependencies("test")))
            .build()
        val createAction = Action("agent.CREATE", buildJsonObject { put("name", "Test Agent") })

        // ACT
        harness.store.dispatch("ui", createAction)

        // ASSERT (State)
        val agentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size)
        val newAgent = agentState.agents.values.first()
        assertEquals("Test Agent", newAgent.name)
        assertEquals(newAgent.id, agentState.editingAgentId)

        // ASSERT (Side Effect)
        val writeAction = harness.processedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction)
        assertEquals("agent", writeAction.originator)
        assertEquals("${newAgent.id}/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `delete() removes agent, dispatches DELETE_DIRECTORY, publishes AGENT_DELETED, and cleans up avatar cards`() = runTest {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m", primarySessionId = "sid-1")
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.IDLE to "msg-123", AgentStatus.PROCESSING to "msg-456"))
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), scope))
            .withFeature(FileSystemFeature(FakePlatformDependencies("test")))
            .withFeature(SessionFeature(FakePlatformDependencies("test"), scope))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            .build()
        val deleteAction = Action("agent.DELETE", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        harness.store.dispatch("ui", deleteAction)

        // ASSERT (State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalAgentState)
        assertTrue(finalAgentState.agents.isEmpty())
        assertFalse(finalAgentState.agentAvatarCardIds.containsKey("aid-1"))

        // ASSERT (Side Effects)
        assertNotNull(harness.processedActions.find { it.name == "filesystem.SYSTEM_DELETE_DIRECTORY" })
        assertNotNull(harness.processedActions.find { it.name == "agent.publish.AGENT_DELETED" })
        val deleteMsgActions = harness.processedActions.filter { it.name == "session.DELETE_MESSAGE" }
        assertEquals(2, deleteMsgActions.size, "Should dispatch a delete action for each tracked card.")
    }

    @Test
    fun `onSystemStarting() dispatches requests to load agents and models`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), scope))
            .build()

        // ACT
        harness.store.dispatch("system", Action("system.STARTING"))

        // ASSERT
        val listAction = harness.processedActions.find { it.name == "filesystem.SYSTEM_LIST" && it.originator == "agent" }
        assertNotNull(listAction, "AgentFeature should request its file list on start.")
        val modelsAction = harness.processedActions.find { it.name == "gateway.REQUEST_AVAILABLE_MODELS" }
        assertNotNull(modelsAction, "AgentFeature should request available models on start.")
    }

    @Test
    fun `onPrivateData() with directory list dispatches SYSTEM_READ for each agent config`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(FakePlatformDependencies("test"), scope)
        val harness = TestEnvironment.create().withFeature(agentFeature).build()
        val dirList = listOf(FileEntry("/fake/path/agent-1", true), FileEntry("/fake/path/agent-2", true))

        // ACT
        agentFeature.onPrivateData(dirList, harness.store)

        // ASSERT
        val readActions = harness.processedActions.filter { it.name == "filesystem.SYSTEM_READ" }
        assertEquals(2, readActions.size)
        assertEquals("agent-1/agent.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("agent-2/agent.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData() with corrupted agent config logs error and does not load`() = runTest {
        // ARRANGE
        val agentFeature = AgentRuntimeFeature(FakePlatformDependencies("test"), scope)
        val harness = TestEnvironment.create().withFeature(agentFeature).build()
        val corruptedJsonContent = """{"id":"bad-agent","name":"Bad Agent",}"""
        val fileContentPayload = buildJsonObject { put("content", corruptedJsonContent); put("subpath", "bad-agent/agent.json") }

        // ACT
        agentFeature.onPrivateData(fileContentPayload, harness.store)

        // ASSERT
        val loadedAction = harness.processedActions.find { it.name == "agent.internal.AGENT_LOADED" }
        assertNull(loadedAction)
        val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log)
        assertTrue(log.message.contains("Failed to parse agent config"))
    }

    @Test
    fun `onSessionDeleted() nullifies primarySessionId for subscribed agents and persists the change`() = runTest {
        // ARRANGE
        val agent1 = AgentInstance("agent-1", "A1", "p", "m", "m", primarySessionId = "session-to-delete")
        val agent2 = AgentInstance("agent-2", "A2", "p", "m", "m", primarySessionId = "session-safe")
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), scope))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1, agent2.id to agent2)))
            .build()
        val deleteAction = Action("session.DELETE", buildJsonObject { put("sessionId", "session-to-delete") })

        // ACT
        harness.store.dispatch("session.ui", deleteAction)

        // ASSERT (State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalAgentState)
        assertNull(finalAgentState.agents["agent-1"]?.primarySessionId)
        assertEquals("session-safe", finalAgentState.agents["agent-2"]?.primarySessionId)

        // ASSERT (Side Effect)
        val writeAction = harness.processedActions.find { it.name == "filesystem.SYSTEM_WRITE" }
        assertNotNull(writeAction)
        assertEquals("agent-1/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `triggerManualTurn() orchestrates full sequence of actions`() = runTest {
        // ARRANGE
        val session = Session("sid-1", "Test", emptyList(), 1L)
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1")
        val harness = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(FakePlatformDependencies("test"), scope))
            .withFeature(SessionFeature(FakePlatformDependencies("test"), scope))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .build()
        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        harness.store.dispatch("ui", triggerAction)

        // ASSERT
        assertNotNull(harness.processedActions.find { it.name == "agent.internal.SET_STATUS" })
        assertNotNull(harness.processedActions.find { it.name == "session.POST" && (it.payload?.get("metadata")?.jsonObject?.get("is_transient")?.jsonPrimitive?.boolean == true) })
        assertNotNull(harness.processedActions.find { it.name == "gateway.GENERATE_CONTENT" })
    }

    @Test
    fun `onContentGenerated() with success orchestrates the full response sequence`() = runTest {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "", "", "", "sid-1", false, AgentStatus.PROCESSING)
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.PROCESSING to "msg-processing-123"))
        val agentFeature = AgentRuntimeFeature(FakePlatformDependencies("test"), scope)
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(SessionFeature(FakePlatformDependencies("test"), scope))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            .build()
        val gatewayResponsePayload = buildJsonObject {
            put("correlationId", "aid-1"); put("rawContent", "Hello back")
        }

        // ACT
        agentFeature.onPrivateData(gatewayResponsePayload, harness.store)

        // ASSERT
        val deleteAction = harness.processedActions.find { it.name == "session.DELETE_MESSAGE" }
        assertNotNull(deleteAction)
        assertEquals("msg-processing-123", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)
        val postActions = harness.processedActions.filter { it.name == "session.POST" }
        assertEquals(2, postActions.size, "Should be 2 POST actions: the response and the new IDLE card.")
        assertNotNull(postActions.find { it.payload?.get("message")?.jsonPrimitive?.content == "Hello back" })
        assertNotNull(postActions.find { it.payload?.get("metadata")?.jsonObject?.get("agentStatus")?.jsonPrimitive?.content == "IDLE" })
    }
}