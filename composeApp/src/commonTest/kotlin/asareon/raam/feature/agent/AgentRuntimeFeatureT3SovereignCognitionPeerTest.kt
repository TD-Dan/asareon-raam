package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.IdentityUUID
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.feature.gateway.GatewayFeature
import asareon.raam.feature.gateway.GatewayState
import asareon.raam.feature.gateway.UniversalGatewayProvider
import asareon.raam.feature.knowledgegraph.KnowledgeGraphFeature
import asareon.raam.feature.knowledgegraph.KnowledgeGraphState
import asareon.raam.feature.session.Session
import asareon.raam.feature.session.SessionFeature
import asareon.raam.feature.session.SessionState
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.test.TestHarness
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
        override fun handleSideEffects(action: Action, store: Store, previousState: asareon.raam.core.FeatureState?, newState: asareon.raam.core.FeatureState?) {
            if (action.name == ActionRegistry.Names.GATEWAY_GENERATE_CONTENT) {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.content ?: return
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("rawContent", "Response from the Gateway.")
                }
                store.dispatch(
                    identity.handle,
                    Action(ActionRegistry.Names.GATEWAY_RETURN_RESPONSE, responsePayload, targetRecipient = "agent")
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

        // KnowledgeGraphFeature dispatches FILESYSTEM_LIST (no path) which FileSystemFeature
        // resolves against the knowledgegraph sandbox: {APP_ZONE}/knowledgegraph/. Write the
        // HKG file under that sandbox so the listing discovers the persona directory.
        val kgSandbox = "${platform.getBasePathFor(asareon.raam.util.BasePath.APP_ZONE)}/knowledgegraph"
        val personaDir = "$kgSandbox/$personaId"
        val path = "$personaDir/$personaId.json"
        platform.createDirectories(personaDir)
        platform.writeFileContent(path, hkgContent)

        val privateSessionUUID = "a0000000-0000-0000-0000-000000000001"
        val publicSessionUUID = "a0000000-0000-0000-0000-000000000002"
        val agentUUID = "b0000000-0000-0000-0000-000000000001"

        val privateSession = testSession(privateSessionUUID, "p-cognition: Philosopher")
        val publicSession = testSession(publicSessionUUID, "Public Discussion", timestamp = 2L)

        val philosopherAgent = testAgent(
            id = agentUUID,
            name = "Philosopher",
            knowledgeGraphId = personaId,
            modelProvider = "fake",
            modelName = "fake",
            cognitiveStrategyId = "sovereign_v1",
            privateSessionId = privateSession.identity.uuid!!,
            subscribedSessionIds = listOf(publicSession.identity.uuid!!),
            resources = mapOf(
                "system_instruction" to "res-sys-instruction-v1",
                "constitution" to "res-sovereign-constitution-v1",
                "bootloader" to "res-boot-sentinel-v1"
            )
        )
        val philosopherUUID = IdentityUUID(philosopherAgent.identity.uuid!!)

        return TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(platform, scope))
            .withFeature(KnowledgeGraphFeature(platform, scope))
            .withFeature(SessionFeature(platform, scope))
            .withFeature(FileSystemFeature(platform))
            .withFeature(FakeGatewayFeature(platform, scope))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .withInitialState("agent", AgentRuntimeState(
                agents = mapOf(philosopherUUID to philosopherAgent),
                subscribableSessionNames = mapOf(
                    IdentityUUID(privateSession.identity.uuid!!) to privateSession.identity.name,
                    IdentityUUID(publicSession.identity.uuid!!) to publicSession.identity.name
                ),
                resources = testBuiltInResources()
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
            // Register identities
            harness.registerAgentIdentity(
                harness.store.state.value.let {
                    (it.featureStates["agent"] as AgentRuntimeState).agents.values.first()
                }
            )
            // Register both sessions so avatar system and pipeline can resolve UUIDs
            val sessionState = harness.store.state.value.featureStates["session"] as SessionState
            sessionState.sessions.values.forEach { session ->
                harness.registerSessionIdentity(session)
            }

            // ACT 1: Force KG to load personas.
            // We use the feature's own discovery mechanism.
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_LIST))
            runCurrent()

            // ACT 2: Initiate Turn
            harness.store.dispatch("core", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                put("agentId", "b0000000-0000-0000-0000-000000000001")
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

            // Avatar system resolves UUIDs → handles, so SESSION_POST uses handles
            val privateSessionHandle = sessionState.sessions["a0000000-0000-0000-0000-000000000001"]?.identity?.handle
            val publicSessionHandle = sessionState.sessions["a0000000-0000-0000-0000-000000000002"]?.identity?.handle

            val postToPrivate = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST &&
                        (it.payload?.get("session")?.jsonPrimitive?.content == "a0000000-0000-0000-0000-000000000001" ||
                                it.payload?.get("session")?.jsonPrimitive?.content == privateSessionHandle)
            }
            assertNotNull(postToPrivate, "Should post to private session.")

            val postToPublic = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_POST &&
                        (it.payload?.get("session")?.jsonPrimitive?.content == "a0000000-0000-0000-0000-000000000002" ||
                                it.payload?.get("session")?.jsonPrimitive?.content == publicSessionHandle) &&
                        it.payload?.containsKey("message") == true
            }
            assertNull(postToPublic, "Should NOT post to public session.")
        }
    }
}