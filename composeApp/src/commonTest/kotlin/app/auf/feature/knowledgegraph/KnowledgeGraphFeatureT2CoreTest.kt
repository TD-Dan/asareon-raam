package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.Feature
import app.auf.core.FeatureState
import app.auf.core.Identity
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for KnowledgeGraphFeature.
 *
 * [MIGRATION] Tests updated for Action Bus v2.0:
 *   - Replaced all `deliverPrivateData` calls with targeted `deferredDispatch`.
 *   - Replaced all `Envelopes.*` references with flat `ActionRegistry.Names.*`.
 *   - Updated assertions from `deliveredPrivateData` to `processedActions` for targeted actions.
 *
 * [PHASE C FIX] Tests updated for Store originator validation:
 *   - Agent originators ("agent-alpha", "agent-beta") must be registered identities.
 *   - A stub "agent" feature is added to the harness so identities can be registered
 *     in the "agent.*" namespace via CORE_REGISTER_IDENTITY.
 *   - Agent handles are now namespaced: "agent.agent-alpha", "agent.agent-beta".
 */
class KnowledgeGraphFeatureT2CoreTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = KnowledgeGraphFeature(platform, testScope)

    // Namespaced agent handles — registered via registerTestAgents()
    private val AGENT_ALPHA = "agent.agent-alpha"
    private val AGENT_BETA = "agent.agent-beta"

    /**
     * Stub feature that provides the "agent" namespace for registering test agent identities.
     * Only needed because the Store validates action originators against registered identities/features.
     */
    private val agentFeatureStub = object : Feature {
        override val identity = Identity(uuid = null, handle = "agent", localHandle = "agent", name = "Agent", parentHandle = null)
        override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {}
        override fun reducer(state: FeatureState?, action: Action): FeatureState? = state
    }

    /**
     * Registers AGENT_ALPHA and AGENT_BETA as identities in the "agent.*" namespace.
     * Must be called after harness.build() and before dispatching from these handles.
     */
    private fun registerTestAgents(harness: TestHarness) {
        harness.store.dispatch("agent", Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
            put("name", "Agent Alpha"); put("localHandle", "agent-alpha")
        }))
        harness.store.dispatch("agent", Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
            put("name", "Agent Beta"); put("localHandle", "agent-beta")
        }))
    }

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

    // ========================================================================================
    // LOAD SEQUENCE — Migrated from deliverPrivateData to targeted deferredDispatch
    // ========================================================================================

    @Test
    fun `full load sequence correctly populates and synchronizes holons`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "persona-1-20251112T190000Z") }))

            // [MIGRATED] Targeted dispatch replaces deliverPrivateData
            harness.store.deferredDispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = buildJsonObject {
                    put("path", "persona-1-20251112T190000Z")
                    put("listing", buildJsonArray {
                        add(json.encodeToJsonElement(FileEntry("persona-1-20251112T190000Z/persona-1-20251112T190000Z.json", false)))
                        add(json.encodeToJsonElement(FileEntry("persona-1-20251112T190000Z/holon-a-20251112T190000Z/holon-a-20251112T190000Z.json", false)))
                    })
                },
                targetRecipient = "knowledgegraph"
            ))

            harness.store.deferredDispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT,
                payload = buildJsonObject {
                    put("correlationId", JsonNull)
                    put("contents", buildJsonObject {
                        put("persona-1-20251112T190000Z/persona-1-20251112T190000Z.json", persona1Content)
                        put("persona-1-20251112T190000Z/holon-a-20251112T190000Z/holon-a-20251112T190000Z.json", holonAContent)
                    })
                },
                targetRecipient = "knowledgegraph"
            ))

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
    fun `full load sequence handles empty directory gracefully`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        harness.runAndLogOnFailure {
            // ACT 1: Initiate load
            harness.store.dispatch("system", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", "empty-persona") }))

            // ACT 2: Simulate empty recursive listing response — targeted dispatch
            harness.store.deferredDispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = buildJsonObject {
                    put("path", "empty-persona")
                    put("listing", buildJsonArray { }) // Empty list
                },
                targetRecipient = "knowledgegraph"
            ))

            // ASSERT
            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState

            // Ensure we are NOT still loading
            assertFalse(finalState.isLoading, "Loading state should be cleared when directory is empty.")

            // Ensure the error was captured
            assertEquals("No holon files found in persona directory.", finalState.fatalError)
        }
    }

    // ========================================================================================
    // REQUEST_CONTEXT — Fixed: assert on processedActions instead of deliveredPrivateData
    // ========================================================================================

    @Test
    fun `REQUEST_CONTEXT should return full holon context via targeted dispatch`() {
        // Arrange: Populate state with a persona and child holon
        val p1 = createHolonFromString(persona1Content, "persona-1-20251112T190000Z.json", platform)
        val h1 = createHolonFromString(holonAContent, "holon-a-20251112T190000Z.json", platform)
        val h1Enriched = h1.copy(header = h1.header.copy(parentId = p1.header.id))

        val initialState = KnowledgeGraphState(
            holons = mapOf(p1.header.id to p1, h1Enriched.header.id to h1Enriched),
            personaRoots = mapOf(p1.header.name to p1.header.id)
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(agentFeatureStub)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerTestAgents(harness)

            // ACT
            harness.store.dispatch(AGENT_ALPHA, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT, buildJsonObject {
                put("personaId", p1.header.id)
                put("correlationId", "req-123")
            }))

            // ASSERT — [MIGRATED] Check processedActions for the targeted response action
            val responseAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT &&
                        it.targetRecipient == AGENT_ALPHA
            }
            assertNotNull(responseAction, "A targeted RETURN_CONTEXT should be dispatched to the requesting agent.")

            val payload = responseAction.payload!!
            assertEquals("req-123", payload["correlationId"]?.jsonPrimitive?.content)
            assertEquals(p1.header.id, payload["personaId"]?.jsonPrimitive?.content)

            val contextMap = payload["context"]?.jsonObject
            assertNotNull(contextMap)
            // Should contain both the root and the child because the root links to the child
            assertTrue(contextMap.containsKey(p1.header.id))
            assertTrue(contextMap.containsKey(h1.header.id))
        }
    }

    // ========================================================================================
    // RENAME HOLON (existing test — no migration needed)
    // ========================================================================================

    @Test
    fun `RENAME_HOLON should update name, timestamp, rawContent and trigger reload`() {
        val rootId = "p1-20250101T000000Z"
        val parentRoot = Holon(
            header = HolonHeader(id = rootId, type = "AI_Persona_Root", name = "Root"),
            payload = buildJsonObject { },
            rawContent = "{}"
        )

        val holonToRename = Holon(
            header = HolonHeader(id = "h1", type="TestHolon", name = "Old Name", filePath = "p1/h1.json", parentId = rootId),
            payload = buildJsonObject{},
            rawContent = "stale"
        )
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", KnowledgeGraphState(holons = mapOf("h1" to holonToRename, rootId to parentRoot)))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject {
                put("holonId", "h1")
                put("newName", "New Name")
            }))

            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction)

            val writtenContent = writeAction.payload!!.jsonObject["content"]!!.jsonPrimitive.content
            val parsedWrittenJson = json.parseToJsonElement(writtenContent).jsonObject
            val parsedHeader = parsedWrittenJson["header"]!!.jsonObject

            assertEquals("New Name", parsedHeader["name"]!!.jsonPrimitive.content)
            assertNotNull(parsedHeader["modified_at"]?.jsonPrimitive?.content)

            // Assert a reload was triggered to update the in-memory state
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA })
        }
    }

    // ========================================================================================
    // RESERVATION TESTS (existing — no migration needed)
    // ========================================================================================

    @Test
    fun `onAction RESERVE_HKG should broadcast the updated reservations list`() {
        val harness = TestEnvironment.create().withFeature(feature).withFeature(agentFeatureStub).build(platform = platform)
        harness.runAndLogOnFailure {
            registerTestAgents(harness)

            // ACT
            harness.store.dispatch(AGENT_ALPHA, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject {
                put("personaId", "persona-1")
            }))

            // ASSERT
            val broadcastAction = harness.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVATIONS_UPDATED }
            assertNotNull(broadcastAction, "A RESERVATIONS_UPDATED broadcast should have been dispatched.")

            val payload = broadcastAction.payload!!.jsonObject
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
        val ownerAgent = AGENT_ALPHA
        val otherAgent = AGENT_BETA
        val holon = Holon(HolonHeader(id = holonId, name="H", type="T", parentId = personaId), buildJsonObject{})

        // A map of the action to test and the forbidden action it would cause
        val actionsToTest = mapOf(
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT, buildJsonObject { put("holonId", holonId) }) to ActionRegistry.Names.FILESYSTEM_WRITE,
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", "X") }) to ActionRegistry.Names.FILESYSTEM_WRITE,
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject { put("holonId", holonId) }) to ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY,
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) }) to ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY
        )

        actionsToTest.forEach { (actionToDispatch, forbiddenAction) ->
            val initialState = KnowledgeGraphState(
                reservations = mapOf(personaId to ownerAgent),
                holons = mapOf(holonId to holon, personaId to Holon(HolonHeader(personaId, "AI_Persona_Root", "P"), buildJsonObject{}))
            )
            val harness = TestEnvironment.create().withFeature(feature).withFeature(agentFeatureStub)
                .withInitialState("knowledgegraph", initialState).build(platform = platform)

            harness.runAndLogOnFailure {
                registerTestAgents(harness)

                // ACT: Another agent tries to modify
                harness.store.dispatch(otherAgent, actionToDispatch)

                // ASSERT
                assertTrue(harness.processedActions.none { it.name == forbiddenAction },
                    "Action ${actionToDispatch.name} should have been blocked, but caused $forbiddenAction.")
                assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.CORE_SHOW_TOAST },
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
        val ownerAgent = AGENT_ALPHA
        val holon = Holon(HolonHeader(id = holonId, name="H", type="T", parentId = personaId, filePath = "p1/h1.json"), buildJsonObject{})

        val actionsToTest = mapOf(
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON, buildJsonObject { put("holonId", holonId); put("newName", "X") }) to ActionRegistry.Names.FILESYSTEM_WRITE,
            Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA, buildJsonObject { put("personaId", personaId) }) to ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY
        )

        actionsToTest.forEach { (actionToDispatch, expectedAction) ->
            val initialState = KnowledgeGraphState(
                reservations = mapOf(personaId to ownerAgent),
                holons = mapOf(holonId to holon, personaId to Holon(HolonHeader(personaId, "AI_Persona_Root", "P"), buildJsonObject{}))
            )
            val harness = TestEnvironment.create().withFeature(feature).withFeature(agentFeatureStub)
                .withInitialState("knowledgegraph", initialState).build(platform = platform)

            harness.runAndLogOnFailure {
                registerTestAgents(harness)

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
        val initialState = KnowledgeGraphState(reservations = mapOf("persona-1" to AGENT_ALPHA))
        val harness = TestEnvironment.create().withFeature(feature).withFeature(agentFeatureStub)
            .withInitialState("knowledgegraph", initialState).build(platform = platform)

        harness.runAndLogOnFailure {
            registerTestAgents(harness)

            // ACT: Another agent tries to reserve the same HKG
            harness.store.dispatch(AGENT_BETA, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, buildJsonObject {
                put("personaId", "persona-1")
            }))

            // ASSERT
            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            assertEquals(AGENT_ALPHA, finalState.reservations["persona-1"], "Reservation owner should not have changed.")
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.CORE_SHOW_TOAST })
            assertTrue(platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("already reserved") })
        }
    }

    // ========================================================================================
    // NEW TESTS — Side-effect coverage gaps identified in Task 3
    // ========================================================================================

    @Test
    fun `SYSTEM_STARTING should dispatch initial FILESYSTEM_LIST`() {
        // SYSTEM_STARTING is only allowed during INITIALIZING lifecycle
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_STARTING))

            val listAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LIST }
            assertNotNull(listAction, "SYSTEM_STARTING should trigger an initial FILESYSTEM_LIST to discover personas.")
        }
    }

    @Test
    fun `PERSONA_LOADED should broadcast AVAILABLE_PERSONAS_UPDATED when roots change`() {
        val p1 = Holon(HolonHeader("p1", "AI_Persona_Root", "Persona One"), buildJsonObject {})
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            // ACT: Dispatch PERSONA_LOADED with a new persona
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_PERSONA_LOADED, buildJsonObject {
                put("holons", json.encodeToJsonElement(mapOf("p1" to p1)))
            }))

            // ASSERT: A broadcast should have been dispatched
            val broadcast = harness.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_AVAILABLE_PERSONAS_UPDATED }
            assertNotNull(broadcast, "PERSONA_LOADED should broadcast AVAILABLE_PERSONAS_UPDATED when persona roots change.")

            val namesMap = broadcast.payload?.get("names")?.jsonObject
            assertNotNull(namesMap)
            // The map is id→name, so key=p1, value=Persona One
            assertEquals("Persona One", namesMap["p1"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `RELEASE_HKG should broadcast RESERVATIONS_UPDATED`() {
        val initialState = KnowledgeGraphState(reservations = mapOf("persona-1" to AGENT_ALPHA))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withFeature(agentFeatureStub)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            registerTestAgents(harness)

            harness.store.dispatch(AGENT_ALPHA, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG, buildJsonObject {
                put("personaId", "persona-1")
            }))

            val broadcast = harness.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVATIONS_UPDATED }
            assertNotNull(broadcast, "RELEASE_HKG should broadcast RESERVATIONS_UPDATED.")

            val reservedIds = broadcast.payload?.get("reservedIds")?.jsonArray
            assertNotNull(reservedIds)
            assertEquals(0, reservedIds.size, "Reserved IDs list should be empty after release.")
        }
    }

    @Test
    fun `CREATE_PERSONA should write file and trigger filesystem reload`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA, buildJsonObject {
                put("name", "Test Persona")
            }))

            // Assert a FILESYSTEM_WRITE was dispatched
            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction, "CREATE_PERSONA should dispatch FILESYSTEM_WRITE.")

            val writtenContent = writeAction.payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(writtenContent)
            val parsedContent = json.parseToJsonElement(writtenContent).jsonObject
            assertEquals("Test Persona", parsedContent["header"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("AI_Persona_Root", parsedContent["header"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            // Assert a FILESYSTEM_LIST reload was triggered
            val reloadAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_LIST }
            assertNotNull(reloadAction, "CREATE_PERSONA should trigger a FILESYSTEM_LIST reload.")

            // Assert a toast was shown
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.CORE_SHOW_TOAST })
        }
    }

    @Test
    fun `DELETE_HOLON should delete directory, update parent, and confirm deletion`() {
        val rootId = "p1-20250101T000000Z"
        val childId = "h1-20250101T000000Z"

        val parentRoot = Holon(
            header = HolonHeader(
                id = rootId, type = "AI_Persona_Root", name = "Root",
                filePath = "$rootId/$rootId.json",
                subHolons = listOf(SubHolonRef(childId, "T", "S"))
            ),
            payload = buildJsonObject { },
            rawContent = "{}"
        )
        val childHolon = Holon(
            header = HolonHeader(
                id = childId, type = "TestHolon", name = "Child",
                filePath = "$rootId/$childId/$childId.json",
                parentId = rootId
            ),
            payload = buildJsonObject { },
            rawContent = "{}"
        )
        val initialState = KnowledgeGraphState(holons = mapOf(rootId to parentRoot, childId to childHolon))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject {
                put("holonId", childId)
            }))

            // Assert directory deletion was dispatched
            val deleteDir = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY }
            assertNotNull(deleteDir, "DELETE_HOLON should dispatch FILESYSTEM_DELETE_FILE_DIRECTORY.")
            assertEquals("$rootId/$childId", deleteDir.payload?.get("path")?.jsonPrimitive?.content)

            // Assert parent holon was updated to remove the child from sub_holons
            val parentWrite = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(parentWrite, "DELETE_HOLON should dispatch FILESYSTEM_WRITE to update parent.")
            val writtenContent = parentWrite.payload?.get("content")?.jsonPrimitive?.content
            assertNotNull(writtenContent)
            val parsedParent = json.parseToJsonElement(writtenContent).jsonObject
            val subHolons = parsedParent["header"]!!.jsonObject["sub_holons"]?.jsonArray
            assertTrue(subHolons == null || subHolons.isEmpty(), "Parent's sub_holons should be empty or absent after child deletion.")

            // Assert CONFIRM_DELETE_HOLON was dispatched to clean up state
            val confirmDelete = harness.processedActions.find { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_HOLON }
            assertNotNull(confirmDelete, "DELETE_HOLON should dispatch CONFIRM_DELETE_HOLON.")
            assertEquals(childId, confirmDelete.payload?.get("holonId")?.jsonPrimitive?.content)

            // Assert the reducer actually removed the holon from state
            val finalState = harness.store.state.value.featureStates["knowledgegraph"] as KnowledgeGraphState
            assertFalse(finalState.holons.containsKey(childId), "Child holon should be removed from state.")
            assertTrue(finalState.holons.containsKey(rootId), "Parent should still exist.")
        }
    }

    @Test
    fun `RETURN_LIST non-recursive should discover persona directories and load each`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            // Simulate a non-recursive listing response (no path = top-level discovery)
            harness.store.deferredDispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = buildJsonObject {
                    // No "path" field means non-recursive
                    put("listing", buildJsonArray {
                        add(json.encodeToJsonElement(FileEntry("persona-alpha", true)))
                        add(json.encodeToJsonElement(FileEntry("persona-beta", true)))
                    })
                },
                targetRecipient = "knowledgegraph"
            ))

            // Assert a LOAD_PERSONA was dispatched for each directory
            val loadActions = harness.processedActions.filter { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA }
            assertEquals(2, loadActions.size, "A LOAD_PERSONA should be dispatched for each discovered persona directory.")

            val loadedIds = loadActions.map { it.payload?.get("personaId")?.jsonPrimitive?.content }.toSet()
            assertTrue(loadedIds.contains("persona-alpha"))
            assertTrue(loadedIds.contains("persona-beta"))
        }
    }

    // ========================================================================================
    // SILENT RETURN LOGGING — Verify Task 2 logging additions
    // ========================================================================================

    @Test
    fun `side effects with missing payload fields should log warnings`() {
        val harness = TestEnvironment.create().withFeature(feature).withFeature(agentFeatureStub).build(platform = platform)

        harness.runAndLogOnFailure {
            registerTestAgents(harness)

            // Dispatch actions with missing required fields
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA, buildJsonObject { }))
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { }))
            harness.store.dispatch(AGENT_ALPHA, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT, buildJsonObject { }))

            // Assert warnings were logged for each
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_PERSONA") && it.message.contains("'name'")
            }, "Missing 'name' in CREATE_PERSONA should be logged.")

            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("LOAD_PERSONA") && it.message.contains("'personaId'")
            }, "Missing 'personaId' in LOAD_PERSONA should be logged.")

            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("REQUEST_CONTEXT") && it.message.contains("'personaId'")
            }, "Missing 'personaId' in REQUEST_CONTEXT should be logged.")
        }
    }

    @Test
    fun `DELETE_HOLON with nonexistent holonId should log warning and not crash`() {
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON, buildJsonObject {
                put("holonId", "nonexistent-holon")
            }))

            // Should not dispatch any filesystem actions
            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY },
                "No filesystem actions should be dispatched for a nonexistent holon.")

            // Should log a warning
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("DELETE_HOLON") && it.message.contains("nonexistent-holon") && it.message.contains("not found")
            }, "A warning should be logged when trying to delete a nonexistent holon.")
        }
    }
}