package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.test.TestEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ## Mandate
 * T2 Integration Test verifying the startup sequence of the AgentRuntimeFeature.
 * Specifically, targets the loading of Agents and Shared Resources (Constitutions) from disk.
 */
class AgentRuntimeFeatureT2StartupTest {

    @Test
    fun `should load existing resources from disk on startup`() {
        // 1. Setup: Create a pre-existing resource in the Fake FileSystem
        val resourceId = "res-123"
        val resourceContent = """
            {
                "id": "$resourceId",
                "type": "CONSTITUTION",
                "name": "Test Constitution",
                "content": "<xml>valid</xml>",
                "isBuiltIn": false,
                "path": "resources/$resourceId.json"
            }
        """.trimIndent()

        val environment = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(app.auf.fakes.FakePlatformDependencies("1.0"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))

        val harness = environment.build()

        // Inject the file into the fake FS
        harness.platform.writtenFiles["resources/$resourceId.json"] = resourceContent

        // 2. Action: Trigger the Startup Sequence
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            // Simulating FileSystem Response for "resources" listing.
            // Targeted action — must include targetRecipient.
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("resources"))
                put("listing", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("$resourceId.json"))
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(false))
                    })
                })
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = listPayload,
                targetRecipient = "agent"
            ))

            // Now the feature should have dispatched a READ request.
            // Let's verify that request looks correct.
            val readAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_READ &&
                        it.payload?.get("path")?.toString()?.contains(resourceId) == true
            }
            assertNotNull(readAction, "Should have dispatched a READ action for the resource")

            // Simulate the READ response — mirror back the exact path.
            val requestedPath = readAction.payload!!["path"]!!.toString().replace("\"", "")

            val readPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(requestedPath))
                put("content", kotlinx.serialization.json.JsonPrimitive(resourceContent))
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload = readPayload,
                targetRecipient = "agent"
            ))

            // 3. Assert: Resource is loaded into state
            val agentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val loadedResource = agentState.resources.find { it.id == resourceId }

            assertNotNull(loadedResource, "Resource should be present in state after loading")
            assertEquals("Test Constitution", loadedResource.name)
        }
    }

    @Test
    fun `should load existing agent config from disk via explicit filename routing`() {
        // 1. Setup: An agent config file living at "agent-uuid/agent.json"
        val agentId = "agent-abc"
        val agentConfigJson = """
            {
                "identity": {
                    "uuid": "$agentId",
                    "localHandle": "restored-agent",
                    "handle": "agent.restored-agent",
                    "name": "Restored Agent",
                    "parentHandle": "agent"
                },
                "modelProvider": "gemini",
                "modelName": "gemini-1.5-pro"
            }
        """.trimIndent()

        val environment = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(app.auf.fakes.FakePlatformDependencies("1.0"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val harness = environment.build()

        harness.platform.writtenFiles["$agentId/agent.json"] = agentConfigJson

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            // Simulate root listing: one agent directory
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(""))
                put("listing", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive(agentId))
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(true))
                    })
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("resources"))
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(true))
                    })
                })
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = listPayload,
                targetRecipient = "agent"
            ))

            // Verify the feature dispatched a READ for "agent-abc/agent.json"
            val readAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_READ &&
                        it.payload?.get("path")?.toString()?.contains("agent.json") == true
            }
            assertNotNull(readAction, "Should have dispatched READ for agent.json")

            // Simulate READ response
            val readPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("$agentId/agent.json"))
                put("content", kotlinx.serialization.json.JsonPrimitive(agentConfigJson))
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload = readPayload,
                targetRecipient = "agent"
            ))

            // Assert: Agent loaded into state
            val agentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val loadedAgent = agentState.agents[agentId]
            assertNotNull(loadedAgent, "Agent should be present in state after loading")
            assertEquals("Restored Agent", loadedAgent.identity.name)

            // Assert: AGENTS_LOADED fired (agentLoadCount reached 0)
            val agentsLoadedAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_AGENTS_LOADED
            }
            assertNotNull(agentsLoadedAction, "AGENTS_LOADED should fire after all agents are read")
        }
    }

    @Test
    fun `unknown file path should be ignored without corrupting agent load tracking`() {
        // Setup: The feature expects one agent config. We deliver an unknown file first,
        // then the real agent config. The unknown file should NOT decrement agentLoadCount.
        val agentId = "agent-xyz"
        val agentConfigJson = """{"identity":{"uuid":"$agentId","localHandle":"real-agent","handle":"agent.real-agent","name":"Real Agent","parentHandle":"agent"},"modelProvider":"p","modelName":"m"}"""

        val environment = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(app.auf.fakes.FakePlatformDependencies("1.0"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
        val harness = environment.build()

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            // Root listing: one agent directory
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(""))
                put("listing", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive(agentId))
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(true))
                    })
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("resources"))
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(true))
                    })
                })
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = listPayload,
                targetRecipient = "agent"
            ))

            // Deliver an UNKNOWN file read response (e.g. a stale log file)
            val unknownPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("some/stale-file.txt"))
                put("content", kotlinx.serialization.json.JsonPrimitive("garbage data"))
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload = unknownPayload,
                targetRecipient = "agent"
            ))

            // AGENTS_LOADED should NOT have fired (agentLoadCount is still 1)
            val prematureLoaded = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_AGENTS_LOADED
            }
            assertNull(prematureLoaded, "Unknown file should not decrement agentLoadCount")

            // Now deliver the real agent config
            val realPayload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive("$agentId/agent.json"))
                put("content", kotlinx.serialization.json.JsonPrimitive(agentConfigJson))
            }

            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,
                payload = realPayload,
                targetRecipient = "agent"
            ))

            // NOW AGENTS_LOADED should fire
            val agentsLoaded = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_AGENTS_LOADED
            }
            assertNotNull(agentsLoaded, "AGENTS_LOADED should fire after the real agent config is loaded")

            // Agent should be loaded
            val agentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertNotNull(agentState.agents[agentId])
        }
    }
}