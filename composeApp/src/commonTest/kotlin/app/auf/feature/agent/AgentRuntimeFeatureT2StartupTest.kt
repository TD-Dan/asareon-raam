package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.test.TestEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ## Mandate
 * T2 Integration Test verifying the startup sequence of the AgentRuntimeFeature.
 * Specifically targets the loading of Agents and Shared Resources (Constitutions) from disk.
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

        // Access the harness to inject files BEFORE build (if supported) or use platform directly
        // Since we build first, we use the harness platform.
        val harness = environment.build()

        // Inject the file into the fake FS
        harness.platform.writtenFiles["resources/$resourceId.json"] = resourceContent

        // 2. Action: Trigger the Startup Sequence
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

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
                    type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST,
                    payload = listPayload
                )
            )

            // Now the feature should have dispatched a READ request.
            // Let's verify that request looks correct.
            val readAction = harness.processedActions.find {
                it.name == ActionNames.FILESYSTEM_SYSTEM_READ &&
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
                    type = ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ,
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
}