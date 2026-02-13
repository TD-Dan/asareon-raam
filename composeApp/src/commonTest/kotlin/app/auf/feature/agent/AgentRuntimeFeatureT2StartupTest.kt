package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
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
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))

        // Access the harness to inject files BEFORE build (if supported) or use the platform directly
        // Since we build first, we use the harness platform.
        val harness = environment.build()

        // Inject the file into the fake FS
        harness.platform.writtenFiles["resources/$resourceId.json"] = resourceContent

        // 2. Action: Trigger the Startup Sequence
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_PUBLISH_STARTING))

            // The feature dispatches SYSTEM_LIST, then the Store (via logic not shown but assumed in FakeStore integration)
            // or the harness needs to process the side effects.
            // NOTE: In T2 tests with TestEnvironment + RecordingStore, we usually need to manually pump
            // the "FakeFileSystem" responses if the environment doesn't auto-wire them.
            // Assuming the standard TestEnvironment auto-wires Feature <-> FakePlatform interactions is risky.
            // Let's check `TestEnvironment`: it wires Real Store + Real Features + Fake Platform.
            // Real Features call `platformDependencies`.
            // BUT `AgentRuntimeFeature` uses `deferredDispatch` for FS actions.
            // We need to simulate the FileSystemFeature's response because we didn't include FileSystemFeature in the `.withFeature()`.

            // Simulating FileSystem Response for "resources" listing
            // The feature listens for ENVELOPE: FILESYSTEM_RESPONSE_LIST
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive("resources"))
                put("listing", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("$resourceId.json")) // Simulating relative path return
                        put("isDirectory", kotlinx.serialization.json.JsonPrimitive(false))
                    })
                })
            }

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_LIST,
                    payload = listPayload
                )
            )

            // Now the feature should have dispatched a READ request.
            // Let's verify that request looks correct.
            val readAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_READ &&
                        it.payload?.get("subpath")?.toString()?.contains(resourceId) == true
            }
            assertNotNull(readAction, "Should have dispatched a READ action for the resource")

            // Simulate the READ response
            // CRITICAL: This is where the bug manifests. If the feature requested just "$resourceId.json",
            // we mirror that back.
            val requestedPath = readAction.payload!!["subpath"]!!.toString().replace("\"", "")

            val readPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive(requestedPath))
                put("content", kotlinx.serialization.json.JsonPrimitive(resourceContent))
            }

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_READ,
                    payload = readPayload
                )
            )

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
                "id": "$agentId",
                "name": "Restored Agent",
                "modelProvider": "gemini",
                "modelName": "gemini-1.5-pro"
            }
        """.trimIndent()

        val environment = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(app.auf.fakes.FakePlatformDependencies("1.0"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
        val harness = environment.build()

        harness.platform.writtenFiles["$agentId/agent.json"] = agentConfigJson

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_PUBLISH_STARTING))

            // Simulate root listing: one agent directory
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive(""))
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

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_LIST,
                    payload = listPayload
                )
            )

            // Verify the feature dispatched a READ for "agent-abc/agent.json"
            val readAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_READ &&
                        it.payload?.get("subpath")?.toString()?.contains("agent.json") == true
            }
            assertNotNull(readAction, "Should have dispatched READ for agent.json")

            // Simulate READ response
            val readPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive("$agentId/agent.json"))
                put("content", kotlinx.serialization.json.JsonPrimitive(agentConfigJson))
            }

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_READ,
                    payload = readPayload
                )
            )

            // Assert: Agent loaded into state
            val agentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            val loadedAgent = agentState.agents[agentId]
            assertNotNull(loadedAgent, "Agent should be present in state after loading")
            assertEquals("Restored Agent", loadedAgent.name)

            // Assert: AGENTS_LOADED fired (agentLoadCount reached 0)
            val agentsLoadedAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_INTERNAL_AGENTS_LOADED
            }
            assertNotNull(agentsLoadedAction, "AGENTS_LOADED should fire after all agents are read")
        }
    }

    @Test
    fun `unknown file path should be ignored without corrupting agent load tracking`() {
        // Setup: The feature expects one agent config. We deliver an unknown file first,
        // then the real agent config. The unknown file should NOT decrement agentLoadCount.
        val agentId = "agent-xyz"
        val agentConfigJson = """{"id":"$agentId","name":"Real Agent","modelProvider":"p","modelName":"m"}"""

        val environment = TestEnvironment.create()
            .withFeature(AgentRuntimeFeature(app.auf.fakes.FakePlatformDependencies("1.0"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
        val harness = environment.build()

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_PUBLISH_STARTING))

            // Root listing: one agent directory
            val listPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive(""))
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

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_LIST,
                    payload = listPayload
                )
            )

            // Deliver an UNKNOWN file read response (e.g. a stale log file)
            val unknownPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive("some/stale-file.txt"))
                put("content", kotlinx.serialization.json.JsonPrimitive("garbage data"))
            }

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_READ,
                    payload = unknownPayload
                )
            )

            // AGENTS_LOADED should NOT have fired (agentLoadCount is still 1)
            val prematureLoaded = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_INTERNAL_AGENTS_LOADED
            }
            assertNull(prematureLoaded, "Unknown file should not decrement agentLoadCount")

            // Now deliver the real agent config
            val realPayload = kotlinx.serialization.json.buildJsonObject {
                put("subpath", kotlinx.serialization.json.JsonPrimitive("$agentId/agent.json"))
                put("content", kotlinx.serialization.json.JsonPrimitive(agentConfigJson))
            }

            harness.store.deliverPrivateData(
                originator = "filesystem",
                recipient = "agent",
                envelope = app.auf.core.PrivateDataEnvelope(
                    type = ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_READ,
                    payload = realPayload
                )
            )

            // NOW AGENTS_LOADED should fire
            val agentsLoaded = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_INTERNAL_AGENTS_LOADED
            }
            assertNotNull(agentsLoaded, "AGENTS_LOADED should fire after the real agent config is loaded")

            // Agent should be loaded
            val agentState = harness.store.state.value.featureStates["agent"] as AgentRuntimeState
            assertNotNull(agentState.agents[agentId])
        }
    }
}