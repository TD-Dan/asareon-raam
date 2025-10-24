package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
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
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Unit Tests for AgentRuntimeFeature.
 * These tests focus on features interaction with the Core
 */
class AgentRuntimeFeatureT2CoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    private val fileSystemFeature = FileSystemFeature(platform)
    private val sessionFeature = SessionFeature(platform, scope)

    @Test
    fun `create() adds new agent to state and dispatches SYSTEM_WRITE`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(fileSystemFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val createAction = Action(ActionNames.AGENT_CREATE, buildJsonObject { put("name", "Test Agent") })

        harness.store.dispatch("ui", createAction)

        val agentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(agentState)
        assertEquals(1, agentState.agents.size)
        val newAgent = agentState.agents.values.first()
        assertEquals("Test Agent", newAgent.name)
        assertEquals(newAgent.id, agentState.editingAgentId)

        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction)
        assertEquals("agent", writeAction.originator)
        assertEquals("${newAgent.id}/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `delete() orchestrates cleanup and then confirms deletion`() = runTest {
        val agent = AgentInstance("aid-1", "Test", "p", "m", "m", primarySessionId = "sid-1")
        val session = Session("sid-1", "Test Session", emptyList(), 1L)
        val avatarCards = mapOf("aid-1" to "msg-123")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(fileSystemFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            .withInitialState("session", SessionState(sessions = mapOf(session.id to session)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val deleteAction = Action(ActionNames.AGENT_DELETE, buildJsonObject { put("agentId", "aid-1") })

        harness.store.dispatch("ui", deleteAction)

        val finalAgentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalAgentState)
        assertTrue(finalAgentState.agents.isEmpty(), "Agent should be removed from the final state.")
        assertFalse(finalAgentState.agentAvatarCardIds.containsKey("aid-1"), "Avatar card should be removed from the final state.")

        val dispatched = harness.processedActions
        assertNotNull(dispatched.find { it.name == ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY }, "Should delete agent directory.")
        assertNotNull(dispatched.find { it.name == ActionNames.AGENT_INTERNAL_CONFIRM_DELETE }, "Should dispatch internal confirmation.")
        assertNotNull(dispatched.find { it.name == ActionNames.AGENT_PUBLISH_AGENT_DELETED }, "Should publish deletion event.")
        val deleteMsgActions = dispatched.filter { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertEquals(1, deleteMsgActions.size, "Should dispatch a delete action for the tracked card.")
    }

    @Test
    fun `onSystemStarting() dispatches requests to load agents and models`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

        val listAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_LIST && it.originator == "agent" }
        assertNotNull(listAction, "AgentFeature should request its file list on start.")
        val modelsAction = harness.processedActions.find { it.name == ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS }
        assertNotNull(modelsAction, "AgentFeature should request available models on start.")
    }

    @Test
    fun `onPrivateData() with directory list dispatches SYSTEM_READ for each agent config`() = runTest {
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
        val dirList = listOf(FileEntry("/fake/path/agent-1", true), FileEntry("/fake/path/agent-2", true))

        val payload = buildJsonObject { put("listing", Json.encodeToJsonElement(dirList)) }
        val envelope = PrivateDataEnvelope("filesystem.response.list", payload)
        agentFeature.onPrivateData(envelope, harness.store)


        val readActions = harness.processedActions.filter { it.name == ActionNames.FILESYSTEM_SYSTEM_READ }
        assertEquals(2, readActions.size)
        assertEquals("agent-1/agent.json", readActions[0].payload?.get("subpath")?.jsonPrimitive?.content)
        assertEquals("agent-2/agent.json", readActions[1].payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData() with valid agent config loads agent into state`() = runTest {
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
        val validJsonContent = """{"id":"agent-good","name":"Good Agent","personaId":"","modelProvider":"","modelName":""}"""
        val fileContentPayload = buildJsonObject { put("content", validJsonContent); put("subpath", "agent-good/agent.json") }

        val envelope = PrivateDataEnvelope("filesystem.response.read", fileContentPayload)
        agentFeature.onPrivateData(envelope, harness.store)


        val finalState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalState)
        assertTrue(finalState.agents.containsKey("agent-good"))
    }

    @Test
    fun `onPrivateData() with corrupted agent config logs error and does not load`() = runTest {
        val harness = TestEnvironment.create().withFeature(agentFeature).build(platform = platform)
        val corruptedJsonContent = """{"id":"bad-agent","name":"Bad Agent",}"""
        val fileContentPayload = buildJsonObject { put("content", corruptedJsonContent); put("subpath", "bad-agent/agent.json") }

        val envelope = PrivateDataEnvelope("filesystem.response.read", fileContentPayload)
        agentFeature.onPrivateData(envelope, harness.store)


        val loadedAction = harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_AGENT_LOADED }
        assertNull(loadedAction)
        val log = harness.platform.capturedLogs.find { it.level == LogLevel.ERROR }
        assertNotNull(log)
        assertTrue(log.message.contains("Failed to parse agent config"))
    }

    @Test
    fun `onSessionDeleted() nullifies primarySessionId for subscribed agents and persists the change`() = runTest {
        val agent1 = AgentInstance("agent-1", "A1", "p", "m", "m", primarySessionId = "session-to-delete")
        val agent2 = AgentInstance("agent-2", "A2", "p", "m", "m", primarySessionId = "session-safe")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent1.id to agent1, agent2.id to agent2)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val deleteEvent = Action(ActionNames.SESSION_PUBLISH_SESSION_DELETED, buildJsonObject { put("sessionId", "session-to-delete") })

        harness.store.dispatch("session", deleteEvent)

        val finalAgentState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalAgentState)
        assertNull(finalAgentState.agents["agent-1"]?.primarySessionId)
        assertEquals("session-safe", finalAgentState.agents["agent-2"]?.primarySessionId)

        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction)
        assertEquals("agent-1/agent.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `triggerManualTurn() orchestrates setting status and requesting ledger content`() = runTest {
        val agent = AgentInstance("aid-1", "Test Agent", "", "gemini", "gemini-pro", "sid-1")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val triggerAction = Action(ActionNames.AGENT_TRIGGER_MANUAL_TURN, buildJsonObject { put("agentId", "aid-1") })

        harness.store.dispatch("ui", triggerAction)

        assertNotNull(harness.processedActions.find { it.name == ActionNames.AGENT_INTERNAL_SET_STATUS })
        assertNotNull(harness.processedActions.find { it.name == ActionNames.SESSION_POST && (it.payload?.get("metadata")?.jsonObject?.get("is_transient")?.jsonPrimitive?.boolean == true) })
        val requestAction = harness.processedActions.find { it.name == ActionNames.SESSION_REQUEST_LEDGER_CONTENT }
        assertNotNull(requestAction)
        assertEquals("sid-1", requestAction.payload?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals("aid-1", requestAction.payload?.get("correlationId")?.jsonPrimitive?.content)
    }

    @Test
    fun `onContentGenerated() with success posts response first then posts new IDLE card`() = runTest {
        val agent = AgentInstance("aid-1", "Test", "", "", "", "sid-1", false, AgentStatus.PROCESSING)
        val avatarCards = mapOf("aid-1" to "msg-processing-123")
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(sessionFeature)
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(agent.id to agent), agentAvatarCardIds = avatarCards))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
        val gatewayResponsePayload = buildJsonObject {
            put("correlationId", "aid-1"); put("rawContent", "Hello back")
        }

        val envelope = PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE, gatewayResponsePayload)
        agentFeature.onPrivateData(envelope, harness.store)

        val deleteAction = harness.processedActions.find { it.name == ActionNames.SESSION_DELETE_MESSAGE }
        assertNotNull(deleteAction)
        assertEquals("msg-processing-123", deleteAction.payload?.get("messageId")?.jsonPrimitive?.content)

        val postActions = harness.processedActions.filter { it.name == ActionNames.SESSION_POST }
        assertEquals(2, postActions.size, "Should be 2 POST actions: the response and the new IDLE card.")
        val responsePost = postActions.find { it.payload?.get("message")?.jsonPrimitive?.content == "Hello back" }
        val idleCardPost = postActions.find { it.payload?.get("metadata")?.jsonObject?.get("agentStatus")?.jsonPrimitive?.content == "IDLE" }
        assertNotNull(responsePost)
        assertNotNull(idleCardPost)

        // Assert the correct sequence: content first, then the new status card.
        assertTrue(harness.processedActions.indexOf(responsePost) < harness.processedActions.indexOf(idleCardPost))
    }
}