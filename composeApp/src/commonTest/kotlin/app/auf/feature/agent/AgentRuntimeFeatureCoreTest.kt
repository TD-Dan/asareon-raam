package app.auf.feature.agent

import app.auf.core.Action
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
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
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)
    private val sessionFeature = SessionFeature(platform, scope)

    @Test
    fun `create() adds new agent to state and dispatches SYSTEM_WRITE`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(fileSystemFeature)
            .build(platform = platform)
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
    fun `delete() orchestrates cleanup and then confirms deletion`() = runTest {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m", primarySessionId = "sid-1")
        val session = Session("sid-1", "Test Session", emptyList(), 1L)
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.IDLE to "msg-123", AgentStatus.PROCESSING to "msg-456"))
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(fileSystemFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            // THE FIX: Provide the session state that the agent is subscribed to.
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .build(platform = platform)
        val deleteAction = Action("agent.DELETE", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        harness.store.dispatch("ui", deleteAction)

        // ASSERT (State)
        val finalAgentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalAgentState)
        assertTrue(finalAgentState.agents.isEmpty(), "Agent should be removed from the final state.")
        assertFalse(finalAgentState.agentAvatarCardIds.containsKey("aid-1"), "Avatar cards should be removed from the final state.")

        // ASSERT (Side Effects - using the new Command/Event pattern)
        val dispatched = harness.processedActions
        assertNotNull(dispatched.find { it.name == "filesystem.SYSTEM_DELETE_DIRECTORY" }, "Should delete agent directory.")
        assertNotNull(dispatched.find { it.name == "agent.internal.CONFIRM_DELETE" }, "Should dispatch internal confirmation.")
        assertNotNull(dispatched.find { it.name == "agent.publish.AGENT_DELETED" }, "Should publish deletion event.")
        val deleteMsgActions = dispatched.filter { it.name == "session.DELETE_MESSAGE" }
        assertEquals(2, deleteMsgActions.size, "Should dispatch a delete action for each tracked card.")
    }

    @Test
    fun `onSystemStarting() dispatches requests to load agents and models`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

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
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
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
    fun `onPrivateData() with valid agent config loads agent into state`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
        val validJsonContent = """{"id":"agent-good","name":"Good Agent","personaId":"","modelProvider":"","modelName":""}"""
        val fileContentPayload = buildJsonObject { put("content", validJsonContent); put("subpath", "agent-good/agent.json") }

        // ACT
        agentFeature.onPrivateData(fileContentPayload, harness.store)

        // ASSERT (State)
        val finalState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalState)
        assertTrue(finalState.agents.containsKey("agent-good"))
    }

    @Test
    fun `onPrivateData() with corrupted agent config logs error and does not load`() = runTest {
        // ARRANGE
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
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
            .withFeature(agentFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1, agent2.id to agent2)))
            .build(platform = platform)
        // THE FIX: Dispatch the canonical event, not the command.
        val deleteEvent = Action("session.publish.DELETED", buildJsonObject { put("sessionId", "session-to-delete") })

        // ACT
        harness.store.dispatch("session", deleteEvent)

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
    fun `triggerManualTurn() orchestrates request for ledger content`() = runTest {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .build(platform = platform)
        val triggerAction = Action("agent.TRIGGER_MANUAL_TURN", buildJsonObject { put("agentId", "aid-1") })

        // ACT
        harness.store.dispatch("ui", triggerAction)

        // ASSERT
        assertNotNull(harness.processedActions.find { it.name == "agent.internal.SET_STATUS" })
        assertNotNull(harness.processedActions.find { it.name == "session.POST" && (it.payload?.get("metadata")?.jsonObject?.get("is_transient")?.jsonPrimitive?.boolean == true) })
        // THE FIX: Assert that the new decoupled request action was dispatched.
        val requestAction = harness.processedActions.find { it.name == "session.REQUEST_LEDGER_CONTENT" }
        assertNotNull(requestAction)
        assertEquals("sid-1", requestAction.payload?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals("aid-1", requestAction.payload?.get("correlationId")?.jsonPrimitive?.content)
    }

    @Test
    fun `onContentGenerated() with success orchestrates the full response sequence`() = runTest {
        // ARRANGE
        val agent = AgentInstance("aid-1", "Test", "", "", "", "sid-1", false, AgentStatus.PROCESSING)
        val avatarCards = mapOf("aid-1" to mapOf(AgentStatus.PROCESSING to "msg-processing-123"))
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            .build(platform = platform)
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