package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.PermissionGrant
import app.auf.core.PermissionLevel
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import app.auf.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Tier 2 Side-Effect Tests for CREATE_HOLON and REPLACE_HOLON.
 *
 * Tests the full side-effect chain: payload validation, reservation guards,
 * filesystem writes, parent wiring, and persona reload.
 *
 * Follows conventions from KnowledgeGraphFeatureT2CoreTest:
 * - Agent originators pre-seeded via TestEnvironment.withIdentity() with KG permissions.
 * - All assertions wrapped in harness.runAndLogOnFailure for diagnostics on failure.
 */
class KnowledgeGraphFeatureT2HolonWriteTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val feature = KnowledgeGraphFeature(platform, testScope)

    private val AGENT_ALPHA = "agent.agent-alpha"
    private val AGENT_BETA = "agent.agent-beta"

    private val agentAlphaIdentity = Identity(
        uuid = "a0000000-0000-0000-0000-00000000aa01",
        localHandle = "agent-alpha",
        handle = AGENT_ALPHA,
        name = "Agent Alpha",
        parentHandle = "agent",
        permissions = mapOf(
            "knowledgegraph:read" to PermissionGrant(PermissionLevel.YES),
            "knowledgegraph:write" to PermissionGrant(PermissionLevel.YES)
        )
    )

    private val agentBetaIdentity = Identity(
        uuid = "a0000000-0000-0000-0000-00000000bb02",
        localHandle = "agent-beta",
        handle = AGENT_BETA,
        name = "Agent Beta",
        parentHandle = "agent",
        permissions = mapOf(
            "knowledgegraph:read" to PermissionGrant(PermissionLevel.YES),
            "knowledgegraph:write" to PermissionGrant(PermissionLevel.YES)
        )
    )

    // --- Reusable test fixtures ---

    private val rootId = "persona-1-20250101T000000Z"
    private val parentProjectId = "session-logs-20250101T000000Z"
    private val childHolonId = "holon-a-20250101T000000Z"

    private val rootHolon = Holon(
        header = HolonHeader(
            id = rootId, type = "AI_Persona_Root", name = "Persona One",
            filePath = "$rootId/$rootId.json",
            subHolons = listOf(SubHolonRef(parentProjectId, "Project", "Session Logs"))
        ),
        payload = buildJsonObject { put("purpose", "test") },
        rawContent = "{}"
    )

    private val parentProjectHolon = Holon(
        header = HolonHeader(
            id = parentProjectId, type = "Project", name = "Session Logs",
            summary = "Session log container",
            filePath = "$rootId/$parentProjectId/$parentProjectId.json",
            parentId = rootId, depth = 1,
            subHolons = listOf(SubHolonRef(childHolonId, "Log_Entry", "Existing child"))
        ),
        payload = buildJsonObject { put("status", "active") },
        rawContent = "{}"
    )

    private val childHolon = Holon(
        header = HolonHeader(
            id = childHolonId, type = "Log_Entry", name = "Existing Child",
            summary = "An existing child holon",
            version = "1.0.0",
            filePath = "$rootId/$parentProjectId/$childHolonId/$childHolonId.json",
            parentId = parentProjectId, depth = 2,
            relationships = listOf(Relationship(rootId, "BELONGS_TO"))
        ),
        payload = buildJsonObject { put("content", "original content") },
        execute = buildJsonObject { put("on_load", "do_something") },
        rawContent = "{}"
    )

    private fun buildLoadedState(
        reservations: Map<String, String> = emptyMap(),
        extraHolons: Map<String, Holon> = emptyMap()
    ): KnowledgeGraphState {
        val holons = mapOf(
            rootId to rootHolon,
            parentProjectId to parentProjectHolon,
            childHolonId to childHolon
        ) + extraHolons
        return KnowledgeGraphState(
            holons = holons,
            personaRoots = mapOf("Persona One" to rootId),
            reservations = reservations
        )
    }

    // ========================================================================================
    // CREATE_HOLON — Happy Path
    // ========================================================================================

    @Test
    fun `CREATE_HOLON should write new holon file and updated parent file`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "Session 003")
                    put("summary", "Third collaboration session")
                    put("payload", buildJsonObject {
                        put("session_number", 3)
                        put("date", "2026-03-14")
                    })
                }
            ))

            // Should produce exactly 2 FILESYSTEM_WRITE actions: new child + updated parent
            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertEquals(2, writeActions.size, "CREATE_HOLON should dispatch exactly 2 FILESYSTEM_WRITE actions (child + parent).")

            // --- Verify the new holon file ---
            val childWrite = writeActions.find {
                val path = it.payload?.get("path")?.jsonPrimitive?.content ?: ""
                path.contains("session-003") && !path.contains(parentProjectId + ".json")
            }
            assertNotNull(childWrite, "One write should be for the new child holon.")

            val childPath = childWrite.payload!!["path"]!!.jsonPrimitive.content
            assertTrue(childPath.startsWith("$rootId/$parentProjectId/"), "New child path should be under parent directory.")
            assertTrue(childPath.endsWith(".json"), "New child path should end with .json.")

            val childContent = json.parseToJsonElement(childWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val childHeader = childContent["header"]!!.jsonObject
            assertEquals("Log_Entry", childHeader["type"]!!.jsonPrimitive.content)
            assertEquals("Session 003", childHeader["name"]!!.jsonPrimitive.content)
            assertEquals("Third collaboration session", childHeader["summary"]!!.jsonPrimitive.content)
            assertEquals("1.0.0", childHeader["version"]!!.jsonPrimitive.content)
            assertNotNull(childHeader["created_at"]?.jsonPrimitive?.content, "created_at should be set.")
            assertNotNull(childHeader["modified_at"]?.jsonPrimitive?.content, "modified_at should be set.")

            val childPayload = childContent["payload"]!!.jsonObject
            assertEquals(3, childPayload["session_number"]!!.jsonPrimitive.content.toInt())

            // --- Verify the updated parent file ---
            val parentWrite = writeActions.find {
                it.payload?.get("path")?.jsonPrimitive?.content == parentProjectHolon.header.filePath
            }
            assertNotNull(parentWrite, "One write should be for the updated parent holon.")

            val parentContent = json.parseToJsonElement(parentWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val parentSubHolons = parentContent["header"]!!.jsonObject["sub_holons"]!!.jsonArray
            assertEquals(2, parentSubHolons.size, "Parent should now have 2 sub_holons (existing + new).")

            val newSubRef = parentSubHolons.find {
                it.jsonObject["type"]!!.jsonPrimitive.content == "Log_Entry" &&
                        it.jsonObject["summary"]!!.jsonPrimitive.content == "Third collaboration session"
            }
            assertNotNull(newSubRef, "Parent's sub_holons should include a ref to the new child.")

            // --- Verify persona reload ---
            assertTrue(
                harness.processedActions.any { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA },
                "CREATE_HOLON should trigger a LOAD_PERSONA reload."
            )
        }
    }

    @Test
    fun `CREATE_HOLON should generate a valid normalized ID from the name`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "My Complex Name With Spaces")
                    put("payload", buildJsonObject {})
                }
            ))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertTrue(writeActions.isNotEmpty(), "At least one write should have been dispatched.")

            val childWrite = writeActions.find {
                val path = it.payload?.get("path")?.jsonPrimitive?.content ?: ""
                path.contains("my-complex-name-with-spaces")
            }
            assertNotNull(childWrite, "Generated ID should be a lowercased, hyphenated version of the name.")

            val childContent = json.parseToJsonElement(childWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val generatedId = childContent["header"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(generatedId.startsWith("my-complex-name-with-spaces-"), "ID should start with normalized name.")
            assertTrue(generatedId.matches(Regex("^[a-z0-9-]+-\\d{8}T\\d{6}Z$")), "ID should match the canonical format.")
        }
    }

    @Test
    fun `CREATE_HOLON with optional execute block should include it in the written file`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "System_File")
                    put("name", "Executable Holon")
                    put("payload", buildJsonObject { put("data", "value") })
                    put("execute", buildJsonObject { put("boot_sequence", "init") })
                }
            ))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            val childWrite = writeActions.find {
                val path = it.payload?.get("path")?.jsonPrimitive?.content ?: ""
                path.contains("executable-holon")
            }
            assertNotNull(childWrite)

            val childContent = json.parseToJsonElement(childWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val executeBlock = childContent["execute"]?.jsonObject
            assertNotNull(executeBlock, "Execute block should be present when provided.")
            assertEquals("init", executeBlock["boot_sequence"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `CREATE_HOLON without summary should use name as SubHolonRef summary`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "No Summary Holon")
                    put("payload", buildJsonObject {})
                    // No summary field
                }
            ))

            val parentWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == parentProjectHolon.header.filePath }
            assertNotNull(parentWrite)

            val parentContent = json.parseToJsonElement(parentWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val subHolons = parentContent["header"]!!.jsonObject["sub_holons"]!!.jsonArray
            val newRef = subHolons.find { it.jsonObject["summary"]!!.jsonPrimitive.content == "No Summary Holon" }
            assertNotNull(newRef, "When summary is omitted, the SubHolonRef should use the name as fallback.")
        }
    }

    // ========================================================================================
    // CREATE_HOLON — Validation & Error Cases
    // ========================================================================================

    @Test
    fun `CREATE_HOLON with missing parentId should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("type", "Log_Entry")
                    put("name", "Orphan")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE },
                "No writes should occur when parentId is missing.")
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("'parentId'")
            })
        }
    }

    @Test
    fun `CREATE_HOLON with missing type should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("name", "No Type")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("'type'")
            })
        }
    }

    @Test
    fun `CREATE_HOLON with missing name should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("'name'")
            })
        }
    }

    @Test
    fun `CREATE_HOLON with missing payload should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "No Payload")
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("'payload'")
            })
        }
    }

    @Test
    fun `CREATE_HOLON with nonexistent parent should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", "nonexistent-parent-20250101T000000Z")
                    put("type", "Log_Entry")
                    put("name", "Orphan Child")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("not found in state")
            })
        }
    }

    @Test
    fun `CREATE_HOLON with name that fails normalizeHolonId should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Name with only special chars that will be stripped, leaving <3 alphanumerics
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "!@")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("CREATE_HOLON") && it.message.contains("Invalid name")
            })
        }
    }

    // ========================================================================================
    // CREATE_HOLON — Reservation Guards
    // ========================================================================================

    @Test
    fun `CREATE_HOLON should be blocked on a reserved HKG by non-owner`() {
        val initialState = buildLoadedState(reservations = mapOf(rootId to AGENT_ALPHA))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withIdentity(agentAlphaIdentity).withIdentity(agentBetaIdentity)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(AGENT_BETA, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "Blocked Child")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE },
                "CREATE_HOLON should be blocked for non-owner of reserved HKG.")
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.CORE_SHOW_TOAST })
            assertTrue(platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("Blocked modification") })
        }
    }

    @Test
    fun `CREATE_HOLON should be allowed on a reserved HKG by the owner`() {
        val initialState = buildLoadedState(reservations = mapOf(rootId to AGENT_ALPHA))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withIdentity(agentAlphaIdentity).withIdentity(agentBetaIdentity)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(AGENT_ALPHA, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                buildJsonObject {
                    put("parentId", parentProjectId)
                    put("type", "Log_Entry")
                    put("name", "Allowed Child")
                    put("payload", buildJsonObject {})
                }
            ))

            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE },
                "CREATE_HOLON should be allowed for the owner of the reserved HKG.")
        }
    }

    // ========================================================================================
    // REPLACE_HOLON — Happy Path
    // ========================================================================================

    @Test
    fun `REPLACE_HOLON should write replaced content preserving structural fields`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("name", "Replaced Name")
                    put("summary", "Replaced summary")
                    put("version", "2.0.0")
                    put("payload", buildJsonObject {
                        put("content", "completely new content")
                        put("new_field", "new value")
                    })
                }
            ))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertTrue(writeActions.isNotEmpty(), "REPLACE_HOLON should dispatch at least one FILESYSTEM_WRITE.")

            // Find the holon write (not the parent write)
            val holonWrite = writeActions.find {
                it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath
            }
            assertNotNull(holonWrite, "Should write to the existing holon's file path.")

            val writtenContent = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val header = writtenContent["header"]!!.jsonObject
            val payload = writtenContent["payload"]!!.jsonObject

            // --- Agent-replaceable fields should be updated ---
            assertEquals("Replaced Name", header["name"]!!.jsonPrimitive.content)
            assertEquals("Replaced summary", header["summary"]!!.jsonPrimitive.content)
            assertEquals("2.0.0", header["version"]!!.jsonPrimitive.content)
            assertEquals("completely new content", payload["content"]!!.jsonPrimitive.content)
            assertEquals("new value", payload["new_field"]!!.jsonPrimitive.content)

            // --- Structural fields MUST be preserved ---
            assertEquals(childHolonId, header["id"]!!.jsonPrimitive.content, "ID must be preserved.")
            assertEquals(childHolon.header.filePath, header["filePath"]!!.jsonPrimitive.content, "filePath must be preserved.")
            assertEquals(parentProjectId, header["parentId"]!!.jsonPrimitive.content, "parentId must be preserved.")
            assertEquals(2, header["depth"]!!.jsonPrimitive.content.toInt(), "depth must be preserved.")

            // --- modified_at should be updated ---
            assertNotNull(header["modified_at"]?.jsonPrimitive?.content, "modified_at should be stamped.")

            // --- Persona reload triggered ---
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA })
        }
    }

    @Test
    fun `REPLACE_HOLON should preserve existing type when type is omitted`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    // No "type" field — should preserve "Log_Entry"
                    put("payload", buildJsonObject { put("data", "new") })
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val header = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content)
                .jsonObject["header"]!!.jsonObject
            assertEquals("Log_Entry", header["type"]!!.jsonPrimitive.content, "Type should be preserved when omitted.")
        }
    }

    @Test
    fun `REPLACE_HOLON should preserve existing execute when execute key is omitted`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("data", "new") })
                    // No "execute" key — should preserve existing
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val content = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val execute = content["execute"]?.jsonObject
            assertNotNull(execute, "Execute should be preserved when key is omitted.")
            assertEquals("do_something", execute["on_load"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `REPLACE_HOLON should remove execute when explicit null is passed`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("data", "new") })
                    put("execute", JsonNull)
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val content = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            // execute should be absent or null in the serialized output
            val execute = content["execute"]
            assertTrue(execute == null || execute is JsonNull, "Execute should be removed when explicit null is passed.")
        }
    }

    @Test
    fun `REPLACE_HOLON should preserve existing relationships when not provided`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("data", "new") })
                    // No "relationships" key
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val header = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content)
                .jsonObject["header"]!!.jsonObject
            val relationships = header["relationships"]?.jsonArray
            assertNotNull(relationships, "Relationships should be preserved when omitted.")
            assertEquals(1, relationships.size)
            assertEquals(rootId, relationships[0].jsonObject["target_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `REPLACE_HOLON should allow replacing relationships`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("data", "new") })
                    put("relationships", buildJsonArray {
                        add(buildJsonObject {
                            put("target_id", parentProjectId)
                            put("type", "PART_OF")
                        })
                    })
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val header = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content)
                .jsonObject["header"]!!.jsonObject
            val relationships = header["relationships"]!!.jsonArray
            assertEquals(1, relationships.size)
            assertEquals(parentProjectId, relationships[0].jsonObject["target_id"]!!.jsonPrimitive.content)
            assertEquals("PART_OF", relationships[0].jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    // ========================================================================================
    // REPLACE_HOLON — Parent SubHolonRef Sync
    // ========================================================================================

    @Test
    fun `REPLACE_HOLON should update parent SubHolonRef when summary changes`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("summary", "Updated child summary")
                    put("payload", buildJsonObject { put("content", "new") })
                }
            ))

            val parentWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == parentProjectHolon.header.filePath }
            assertNotNull(parentWrite, "Parent should be written when child's summary changes.")

            val parentContent = json.parseToJsonElement(parentWrite.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val subHolons = parentContent["header"]!!.jsonObject["sub_holons"]!!.jsonArray
            val updatedRef = subHolons.find { it.jsonObject["id"]!!.jsonPrimitive.content == childHolonId }
            assertNotNull(updatedRef)
            assertEquals("Updated child summary", updatedRef.jsonObject["summary"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `REPLACE_HOLON should NOT write parent when nothing in SubHolonRef changed`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // Replace only payload — type and summary stay the same
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    // Keep same summary as what's in the SubHolonRef
                    put("summary", "Existing child")
                    put("payload", buildJsonObject { put("content", "new content only") })
                }
            ))

            val writeActions = harness.processedActions.filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            val parentWrite = writeActions.find {
                it.payload?.get("path")?.jsonPrimitive?.content == parentProjectHolon.header.filePath
            }
            // If the SubHolonRef type/summary didn't change, no parent write is needed
            // (only the child file should be written)
            assertEquals(1, writeActions.size, "Only the holon itself should be written when SubHolonRef is unchanged.")
            assertNull(parentWrite, "Parent should NOT be written when SubHolonRef fields are unchanged.")
        }
    }

    // ========================================================================================
    // REPLACE_HOLON — Validation & Error Cases
    // ========================================================================================

    @Test
    fun `REPLACE_HOLON with missing holonId should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("REPLACE_HOLON") && it.message.contains("'holonId'")
            })
        }
    }

    @Test
    fun `REPLACE_HOLON with missing payload should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    // No payload
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("REPLACE_HOLON") && it.message.contains("'payload'")
            })
        }
    }

    @Test
    fun `REPLACE_HOLON with nonexistent holonId should log warning and do nothing`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", "nonexistent-20250101T000000Z")
                    put("payload", buildJsonObject {})
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE })
            assertTrue(platform.capturedLogs.any {
                it.level == LogLevel.WARN && it.message.contains("REPLACE_HOLON") && it.message.contains("not found in state")
            })
        }
    }

    // ========================================================================================
    // REPLACE_HOLON — Reservation Guards
    // ========================================================================================

    @Test
    fun `REPLACE_HOLON should be blocked on a reserved HKG by non-owner`() {
        val initialState = buildLoadedState(reservations = mapOf(rootId to AGENT_ALPHA))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withIdentity(agentAlphaIdentity).withIdentity(agentBetaIdentity)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(AGENT_BETA, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("content", "hijack attempt") })
                }
            ))

            assertFalse(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE },
                "REPLACE_HOLON should be blocked for non-owner of reserved HKG.")
            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.CORE_SHOW_TOAST })
            assertTrue(platform.capturedLogs.any { it.level == LogLevel.WARN && it.message.contains("Blocked modification") })
        }
    }

    @Test
    fun `REPLACE_HOLON should be allowed on a reserved HKG by the owner`() {
        val initialState = buildLoadedState(reservations = mapOf(rootId to AGENT_ALPHA))
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withIdentity(agentAlphaIdentity).withIdentity(agentBetaIdentity)
            .withInitialState("knowledgegraph", initialState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch(AGENT_ALPHA, Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("content", "owner update") })
                }
            ))

            assertTrue(harness.processedActions.any { it.name == ActionRegistry.Names.FILESYSTEM_WRITE },
                "REPLACE_HOLON should be allowed for the owner of the reserved HKG.")
        }
    }

    // ========================================================================================
    // UPDATE_HOLON_CONTENT — Verify it still works for internal UI usage
    // ========================================================================================

    @Test
    fun `UPDATE_HOLON_CONTENT should still work when dispatched internally by knowledgegraph`() {
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("content", "ui edit") })
                }
            ))

            val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
            assertNotNull(writeAction, "UPDATE_HOLON_CONTENT should still work for internal dispatch.")

            val writtenContent = json.parseToJsonElement(writeAction.payload!!["content"]!!.jsonPrimitive.content).jsonObject
            val payload = writtenContent["payload"]!!.jsonObject
            assertEquals("ui edit", payload["content"]!!.jsonPrimitive.content)

            // Header should be preserved (only modified_at updated)
            val header = writtenContent["header"]!!.jsonObject
            assertEquals(childHolonId, header["id"]!!.jsonPrimitive.content)
        }
    }

    // ========================================================================================
    // Structural Integrity — Verify CREATE and REPLACE cannot break the tree
    // ========================================================================================

    @Test
    fun `REPLACE_HOLON must not allow overriding subHolons via payload`() {
        // The childHolon has no sub_holons. If an agent smuggles sub_holons into the replace
        // payload, the system should ignore it — sub_holons are structural.
        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", buildLoadedState())
            .build(platform = platform)

        harness.runAndLogOnFailure {
            // The REPLACE_HOLON schema does NOT include a "subHolons" field.
            // The handler preserves the existing header.subHolons unconditionally.
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("content", "attempted tree corruption") })
                    // Even if someone tries to sneak sub_holons into the payload, they are in header
                    // and the handler copies subHolons from the existing header. This test just
                    // confirms the structural field preservation holds.
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == childHolon.header.filePath }
            assertNotNull(holonWrite)

            val header = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content)
                .jsonObject["header"]!!.jsonObject

            // childHolon has no sub_holons — verify it stays that way
            val subHolons = header["sub_holons"]?.jsonArray
            assertTrue(subHolons == null || subHolons.isEmpty(),
                "sub_holons should remain empty — structural fields must not be overridden by REPLACE_HOLON.")
        }
    }

    @Test
    fun `REPLACE_HOLON must preserve createdAt timestamp`() {
        // Create a holon with a known createdAt
        val holonWithCreatedAt = childHolon.copy(
            header = childHolon.header.copy(createdAt = "2025-01-01T00:00:00Z")
        )
        val state = buildLoadedState(extraHolons = mapOf(childHolonId to holonWithCreatedAt))
        // Override the default childHolon in state
        val fixedState = state.copy(holons = state.holons + (childHolonId to holonWithCreatedAt))

        val harness = TestEnvironment.create()
            .withFeature(feature)
            .withInitialState("knowledgegraph", fixedState)
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("knowledgegraph", Action(
                ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                buildJsonObject {
                    put("holonId", childHolonId)
                    put("payload", buildJsonObject { put("content", "replaced") })
                }
            ))

            val holonWrite = harness.processedActions
                .filter { it.name == ActionRegistry.Names.FILESYSTEM_WRITE }
                .find { it.payload?.get("path")?.jsonPrimitive?.content == holonWithCreatedAt.header.filePath }
            assertNotNull(holonWrite)

            val header = json.parseToJsonElement(holonWrite.payload!!["content"]!!.jsonPrimitive.content)
                .jsonObject["header"]!!.jsonObject
            assertEquals("2025-01-01T00:00:00Z", header["created_at"]!!.jsonPrimitive.content,
                "createdAt must be preserved — it's a structural field.")
        }
    }
}