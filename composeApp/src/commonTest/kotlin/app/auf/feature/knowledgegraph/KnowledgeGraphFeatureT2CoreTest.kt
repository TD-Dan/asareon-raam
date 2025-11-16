package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for KnowledgeGraphFeature.
 */
class KnowledgeGraphFeatureT2CoreTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = KnowledgeGraphFeature(platform, testScope)

    private val persona1Content = """
        {
            "header": { "id": "persona-1-20251112T190000Z", "type": "AI_Persona_Root", "name": "Persona One", "sub_holons": [{"id": "holon-a-20251112T190000Z", "type": "T", "summary": "S"}] },
            "payload": {}, "execute": null
        }
    """.trimIndent()
    private val holonAContent = """
        {
            "header": { "id": "holon-a-20251112T190000Z", "type": "Test_Holon", "name": "Holon A" },
            "payload": {}, "execute": null
        }
    """.trimIndent()


    @Test
    fun `full load sequence correctly populates and synchronizes holons`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "persona-1-20251112T190000Z") }))
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST, buildJsonObject {
                put("subpath", "persona-1-20251112T190000Z")
                put("listing", buildJsonArray {
                    add(json.encodeToJsonElement(FileEntry("persona-1-20251112T190000Z/persona-1-20251112T190000Z.json", false)))
                    add(json.encodeToJsonElement(FileEntry("persona-1-20251112T190000Z/holon-a-20251112T190000Z/holon-a-20251112T190000Z.json", false)))
                })
            }))
            harness.store.deliverPrivateData("filesystem", "knowledgegraph", PrivateDataEnvelope(ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT, buildJsonObject {
                put("correlationId", JsonNull)
                put("contents", buildJsonObject {
                    put("persona-1-20251112T190000Z/persona-1-20251112T190000Z.json", persona1Content)
                    put("persona-1-20251112T190000Z/holon-a-20251112T190000Z/holon-a-20251112T190000Z.json", holonAContent)
                })
            }))

            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState

            // Assert enrichment
            val loadedChild = finalState.holons["holon-a-20251112T190000Z"]!!
            assertEquals(1, loadedChild.header.depth)
            assertEquals("persona-1-20251112T190000Z", loadedChild.header.parentId)

            // Assert synchronization
            val parsedRawContent = json.parseToJsonElement(loadedChild.rawContent).jsonObject
            assertEquals("persona-1-20251112T190000Z", parsedRawContent["header"]!!.jsonObject["parentId"]!!.jsonPrimitive.content)
            assertEquals(1, parsedRawContent["header"]!!.jsonObject["depth"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `RENAME_HOLON should update name, timestamp, and synchronize rawContent before writing`() {
        val holonToRename = Holon(
            header = HolonHeader(id = "h1", type="TestHolon", name = "Old Name", filePath = "p1/h1.json"),
            payload = buildJsonObject{},
            rawContent = "stale"
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(holons = mapOf("h1" to holonToRename), activePersonaIdForView = "p1"))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("ui", Action(ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject {
                put("holonId", "h1")
                put("newName", "New Name")
            }))

            val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
            assertNotNull(writeAction)

            val writtenContent = writeAction.payload!!.jsonObject["content"]!!.jsonPrimitive.content
            val parsedWrittenJson = json.parseToJsonElement(writtenContent).jsonObject
            val parsedHeader = parsedWrittenJson["header"]!!.jsonObject

            assertEquals("New Name", parsedHeader["name"]!!.jsonPrimitive.content)
            assertNotNull(parsedHeader["modified_at"]?.jsonPrimitive?.content)

            // Assert a reload was triggered to update the in-memory state
            assertTrue(harness.processedActions.any { it.name == ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA })
        }
    }

    // --- NEW TESTS for Slice 2.4.5 ---

    @Test
    fun `onAction RESERVE_HKG should broadcast the updated reservations list`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.runAndLogOnFailure {
            // ACT
            harness.store.dispatch("agent-alpha", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject {
                put("personaId", "persona-1")
            }))

            // ASSERT
            val broadcastAction = harness.processedActions.find { it.name == ActionNames.KNOWLEDGEGRAPH_PUBLISH_RESERVATIONS_UPDATED }
            assertNotNull(broadcastAction, "A RESERVATIONS_UPDATED broadcast should have been dispatched.")

            val payload = broadcastAction.payload!!.jsonObject
            // [FIX] Correctly assert against the `reservedIds` array as per the contract.
            val reservedIds = payload["reservedIds"]?.jsonArray
            assertNotNull(reservedIds)
            assertEquals(1, reservedIds.size)
            assertEquals("persona-1", reservedIds.first().jsonPrimitive.content)
        }
    }

    @Test
    fun `onAction should block modification actions on a reserved HKG from a non owner`() {
        val personaId = "persona-1"
        val holonId = "holon-a"
        val ownerAgent = "agent-alpha"
        val otherAgent = "agent-beta"
        val holon = Holon(HolonHeader(id = holonId, name="H", type="T", parentId = personaId), buildJsonObject{})

        // A map of the action to test and the forbidden action it would cause
        val actionsToTest = mapOf(
            Action(ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT, buildJsonObject { put("holonId", holonId) }) to ActionNames.FILESYSTEM_SYSTEM_WRITE,
            Action(ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", "X") }) to ActionNames.FILESYSTEM_SYSTEM_WRITE,
            Action(ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject { put("holonId", holonId) }) to ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY,
            Action(ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) }) to ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY
        )

        actionsToTest.forEach { (actionToDispatch, forbiddenAction) ->
            val initialState = KnowledgeGraphState(
                reservations = mapOf(personaId to ownerAgent),
                holons = mapOf(holonId to holon, personaId to Holon(HolonHeader(personaId, "AI_Persona_Root", "P"), buildJsonObject{}))
            )
            val harness = TestEnvironment.create().withFeature(feature)
                .withInitialState("knowledgegraph", initialState).build(platform = platform)

            harness.runAndLogOnFailure {
                // ACT: Another agent tries to modify
                harness.store.dispatch(otherAgent, actionToDispatch)

                // ASSERT
                assertTrue(harness.processedActions.none { it.name == forbiddenAction },
                    "Action ${actionToDispatch.name} should have been blocked, but caused $forbiddenAction.")
                assertTrue(harness.processedActions.any { it.name == ActionNames.CORE_SHOW_TOAST },
                    "A toast notification should be shown for blocked action ${actionToDispatch.name}.")
                assertTrue(platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("Blocked modification") },
                    "A warning should be logged for blocked action ${actionToDispatch.name}.")
            }
        }
    }

    @Test
    fun `onAction should ALLOW modification actions on a reserved HKG from the owner`() {
        val personaId = "persona-1"
        val holonId = "holon-a"
        val ownerAgent = "agent-alpha"
        val holon = Holon(HolonHeader(id = holonId, name="H", type="T", parentId = personaId, filePath = "p1/h1.json"), buildJsonObject{})

        val actionsToTest = mapOf(
            Action(ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", "X") }) to ActionNames.FILESYSTEM_SYSTEM_WRITE,
            Action(ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) }) to ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY
        )

        actionsToTest.forEach { (actionToDispatch, expectedAction) ->
            val initialState = KnowledgeGraphState(
                reservations = mapOf(personaId to ownerAgent),
                holons = mapOf(holonId to holon, personaId to Holon(HolonHeader(personaId, "AI_Persona_Root", "P"), buildJsonObject{}))
            )
            val harness = TestEnvironment.create().withFeature(feature)
                .withInitialState("knowledgegraph", initialState).build(platform = platform)

            harness.runAndLogOnFailure {
                // ACT: The owner agent tries to modify
                harness.store.dispatch(ownerAgent, actionToDispatch)

                // ASSERT
                assertTrue(harness.processedActions.any { it.name == expectedAction },
                    "Action ${actionToDispatch.name} by owner should have been allowed, but did not cause $expectedAction.")
            }
        }
    }

    @Test
    fun `onAction should block re reserving an already reserved HKG`() {
        val initialState = KnowledgeGraphState(reservations = mapOf("persona-1" to "agent-alpha"))
        val harness = TestEnvironment.create().withFeature(feature)
            .withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Another agent tries to reserve the same HKG
            harness.store.dispatch("agent-beta", Action(ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject {
                put("personaId", "persona-1")
            }))

            // ASSERT
            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            assertEquals("agent-alpha", finalState.reservations["persona-1"], "Reservation owner should not have changed.")
            assertTrue(harness.processedActions.any { it.name == ActionNames.CORE_SHOW_TOAST })
            assertTrue(platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("already reserved") })
        }
    }
}