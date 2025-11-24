package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Feature
import app.auf.core.PrivateDataEnvelope
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.feature.gateway.GatewayFeature
import app.auf.feature.gateway.GatewayState
import app.auf.feature.gateway.UniversalGatewayProvider
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.Session
import app.auf.feature.session.SessionFeature
import app.auf.feature.session.SessionState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 3 Peer Test for Sovereign Agent Cognition Workflow.
 *
 * Mandate (P-TEST-001, T3): To test the full, multi-feature runtime contract between
 * AgentRuntime, KnowledgeGraph, Session, and a faked Gateway. This test verifies that
 * a sovereign agent correctly uses its HKG as context and routes its output to its
 * designated private session, ignoring the globally active session.
 */
class AgentRuntimeFeatureT3SovereignCognitionPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { prettyPrint = true }

    // --- Faked Peer Feature ---
    // A fake GatewayFeature that immediately responds with a simple message.
    private class FakeGatewayFeature(
        platformDependencies: FakePlatformDependencies,
        coroutineScope: CoroutineScope
    ) : GatewayFeature(platformDependencies, coroutineScope, emptyList<UniversalGatewayProvider>()) {
        override fun onAction(action: Action, store: Store, previousState: app.auf.core.FeatureState?, newState: app.auf.core.FeatureState?) {
            if (action.name == ActionNames.GATEWAY_GENERATE_CONTENT) {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.content ?: return
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("rawContent", "Response from the Gateway.")
                }
                store.deliverPrivateData(
                    this.name,
                    "agent",
                    PrivateDataEnvelope(ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE, responsePayload)
                )
            }
        }
    }

    private fun setupTestEnvironment(): TestHarness {
        // --- ARRANGE: File System State ---
        val personaId = "philosopher-20251116T000000Z"
        val hkgContent = """
            {
                "header": {
                    "id": "$personaId",
                    "type": "AI_Persona_Root",
                    "name": "Philosopher Agent",
                    "summary": "Axiom: The unexamined life is not worth programming."
                },
                "payload": {}
            }
        """.trimIndent()
        platform.createDirectories("/fake/.auf/test/$personaId")
        platform.writeFileContent("/fake/.auf/test/$personaId/$personaId.json", hkgContent)

        // --- ARRANGE: Feature States ---
        val privateSession = Session("private-session-1", "p-cognition: Philosopher", emptyList(), 1L)
        val publicSession = Session("public-session-1", "Public Discussion", emptyList(), 2L)

        val philosopherAgent = AgentInstance(
            id = "philosopher-1",
            name = "Philosopher",
            knowledgeGraphId = personaId,
            modelProvider = "fake",
            modelName = "fake",
            privateSessionId = privateSession.id,
            subscribedSessionIds = listOf(publicSession.id)
        )

        return TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(platform, scope))
            .withFeature(KnowledgeGraphFeature(platform, scope))
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withFeature(FakeGatewayFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(agents = mapOf(philosopherAgent.id to philosopherAgent)))
            .withInitialState("session", SessionState(
                sessions = mapOf(privateSession.id to privateSession, publicSession.id to publicSession),
                activeSessionId = publicSession.id // CRITICAL: The public session is active
            ))
            .withInitialState("knowledgegraph", KnowledgeGraphState())
            .withInitialState("gateway", GatewayState())
            .build(platform = platform)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Sovereign agent should use its HKG and post to its PrivateSession`() = runTest {
        // --- ARRANGE ---
        val harness = setupTestEnvironment()
        harness.runAndLogOnFailure {
            // --- ACT 1: Load the HKG ---
            // CRITICAL FIX: Dispatch as "knowledgegraph" so the response routes back to it.
            // The KG feature needs the file list to load the persona.
            harness.store.dispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            runCurrent() // Allow file listing and reading to complete.

            // --- ACT 2: Trigger the Agent's Cognitive Cycle ---
            harness.store.dispatch("ui", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "philosopher-1")
            }))
            runCurrent() // Allow the full cognitive cycle to execute.

            // --- ASSERT ---
            // 1. Assert Correct Context: The Gateway was called with a system prompt containing the HKG's axiom.
            val gatewayRequest = harness.processedActions.find { it.name == ActionNames.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayRequest, "A request to the Gateway should have been dispatched.")
            val systemPrompt = gatewayRequest.payload?.get("systemPrompt")?.jsonPrimitive?.content
            assertNotNull(systemPrompt, "The Gateway request should have a system prompt.")
            assertTrue(
                systemPrompt.contains("Axiom: The unexamined life is not worth programming."),
                "The system prompt must contain the unique axiom from the agent's HKG."
            )

            // 2. Assert Correct Routing: A new message was posted to the agent's private session.
            val postToPrivateSession = harness.processedActions.find {
                it.name == ActionNames.SESSION_POST && it.payload?.get("session")?.jsonPrimitive?.content == "private-session-1"
            }
            assertNotNull(postToPrivateSession, "A message should have been posted to the private session.")
            assertEquals(
                "Response from the Gateway.",
                postToPrivateSession.payload?.get("message")?.jsonPrimitive?.content,
                "The content of the private message should be the gateway's response."
            )

            // 3. Assert Incorrect Routing Prevention: NO new message was posted to the active public session.
            val postToPublicSession = harness.processedActions.find {
                it.name == ActionNames.SESSION_POST && it.payload?.get("session")?.jsonPrimitive?.content == "public-session-1"
            }
            assertNull(
                postToPublicSession,
                "A message should NOT have been posted to the public session."
            )
        }
    }
}