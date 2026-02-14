package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
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
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentRuntimeFeatureT3SovereignCognitionPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val json = Json { prettyPrint = true }

    // Fake Gateway to intercept request and respond immediately
    private class FakeGatewayFeature(
        platformDependencies: FakePlatformDependencies,
        coroutineScope: CoroutineScope
    ) : GatewayFeature(platformDependencies, coroutineScope, emptyList<UniversalGatewayProvider>()) {
        override fun handleSideEffects(action: Action, store: Store, previousState: app.auf.core.FeatureState?, newState: app.auf.core.FeatureState?) {
            if (action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT) {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.content ?: return
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("rawContent", "Response from the Gateway.")
                }
                store.dispatch(
                    identity.handle,
                    Action(ActionRegistry.Names.GATEWAY_RESPONSE_RESPONSE, responsePayload)
                )
            }
        }
    }

    private fun setupTestEnvironment(): TestHarness {
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

        // [ROBUSTNESS FIX] Write specifically to the relative root so FakePlatformDependencies
        // listing logic (which likely defaults to listing ".") finds it.
        val path = "$personaId/$personaId.json"
        platform.createDirectories(personaId)
        platform.writeFileContent(path, hkgContent)

        val privateSession = testSession("private-session-1", "p-cognition: Philosopher")
        val publicSession = testSession("public-session-1", "Public Discussion", timestamp = 2L)

        val philosopherAgent = testAgent(
            id = "philosopher-1",
            name = "Philosopher",
            knowledgeGraphId = personaId,
            modelProvider = "fake",
            modelName = "fake",
            privateSessionId = privateSession.identity.uuid!!,
            subscribedSessionIds = listOf(publicSession.identity.uuid!!)
        )

        return TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(platform, scope))
            .withFeature(KnowledgeGraphFeature(platform, scope))
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withFeature(FakeGatewayFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(philosopherAgent.identity.uuid!! to philosopherAgent),
                sessionNames = mapOf(
                    privateSession.identity.uuid!! to privateSession.identity.name,
                    publicSession.identity.uuid!! to publicSession.identity.name
                )
            ))
            .withInitialState("session", SessionState(
                sessions = mapOf(privateSession.identity.uuid!! to privateSession, publicSession.identity.uuid!! to publicSession)
            ))
            .withInitialState("knowledgegraph", KnowledgeGraphState())
            .withInitialState("gateway", GatewayState())
            .build(platform = platform)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Sovereign agent should use its HKG and post to its PrivateSession`() = runTest {
        val harness = setupTestEnvironment()
        harness.runAndLogOnFailure {
            // ACT 1: Force KG to load personas.
            // We use the feature's own discovery mechanism.
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST))
            runCurrent()

            // ACT 2: Initiate Turn
            harness.store.dispatch("ui", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "philosopher-1")
            }))
            runCurrent()

            // ASSERT
            val gatewayRequest = harness.processedActions.find { it.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT }
            assertNotNull(gatewayRequest, "Gateway request missing.")

            val systemPrompt = gatewayRequest.payload?.get("systemPrompt")?.jsonPrimitive?.content ?: ""
            assertTrue(
                systemPrompt.contains("Axiom: The unexamined life is not worth programming."),
                "HKG Context missing from prompt. \nPrompt: $systemPrompt"
            )

            val postToPrivate = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST && it.payload?.get("session")?.jsonPrimitive?.content == "private-session-1"
            }
            assertNotNull(postToPrivate, "Should post to private session.")

            val postToPublic = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST && it.payload?.get("session")?.jsonPrimitive?.content == "public-session-1"
            }
            assertNull(postToPublic, "Should NOT post to public session.")
        }
    }
}