package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.test.TestEnvironment
import app.auf.test.TestHarness
import app.auf.util.LogLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Contract Tests for KnowledgeGraphFeature's domain actions.
 *
 * These tests verify cross-cutting obligations that every command-dispatchable
 * domain action must satisfy:
 *   1. Publish a success ACTION_RESULT on the happy path.
 *   2. Publish a failure ACTION_RESULT when an error occurs.
 *   3. Log at WARN or ERROR level when an error occurs.
 *
 * Action-specific behaviour (holon enrichment, import analysis, reservation
 * semantics, etc.) is tested in the corresponding T2CoreTest files.
 *
 * ## Scope
 *
 * Only actions that have `publishActionResult` calls in the feature code are
 * tested here. Actions like REQUEST_CONTEXT, RESERVE_HKG, and RELEASE_HKG
 * do not currently publish ACTION_RESULT — if they should, adding them here
 * will immediately surface the gap.
 */
class KnowledgeGraphFeatureT2ContractTest {

    private val featureHandle = "knowledgegraph"

    // ====================================================================
    // Test fixtures: a minimal loaded persona with one child holon
    // ====================================================================

    private val PERSONA_ID = "test-persona-20260101t000000z"
    private val CHILD_HOLON_ID = "child-holon-20260101t000000z"

    private fun testPersonaHolon() = Holon(
        header = HolonHeader(
            id = PERSONA_ID,
            type = "AI_Persona_Root",
            name = "Test Persona",
            summary = "A test persona for contract tests.",
            version = "1.0.0",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            filePath = "$PERSONA_ID/$PERSONA_ID.json",
            parentId = null,
            depth = 0,
            subHolons = listOf(
                SubHolonRef(id = CHILD_HOLON_ID, type = "System_File", summary = "A child holon")
            )
        ),
        payload = buildJsonObject { put("content", "persona payload") },
        rawContent = "raw persona content"
    )

    private fun testChildHolon() = Holon(
        header = HolonHeader(
            id = CHILD_HOLON_ID,
            type = "System_File",
            name = "Child Holon",
            summary = "A child holon for contract tests.",
            version = "1.0.0",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            filePath = "$PERSONA_ID/$CHILD_HOLON_ID/$CHILD_HOLON_ID.json",
            parentId = PERSONA_ID,
            depth = 1
        ),
        payload = buildJsonObject { put("content", "child payload") },
        rawContent = "raw child content"
    )

    /** State with a loaded persona and child holon. */
    private fun loadedState() = KnowledgeGraphState(
        holons = mapOf(
            PERSONA_ID to testPersonaHolon(),
            CHILD_HOLON_ID to testChildHolon()
        ),
        personaRoots = mapOf("Test Persona" to PERSONA_ID)
    )

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildState: () -> KnowledgeGraphState = { KnowledgeGraphState() },
        val buildPlatform: () -> FakePlatformDependencies = { FakePlatformDependencies("test") },
        val extraIdentities: List<Identity> = emptyList()
    )

    private data class FailureCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val buildState: () -> KnowledgeGraphState = { KnowledgeGraphState() },
        val buildPlatform: () -> FakePlatformDependencies = { FakePlatformDependencies("test") },
        val extraIdentities: List<Identity> = emptyList()
    )

    // ====================================================================
    // Harness builder
    // ====================================================================

    /** Identity for non-owner originator used in reservation lock tests. */
    private val outsiderIdentity = Identity(
        uuid = null,
        handle = "outsider",
        localHandle = "outsider",
        name = "Outsider",
        parentHandle = "core"
    )

    private fun buildHarness(
        platform: FakePlatformDependencies,
        kgState: KnowledgeGraphState,
        extraIdentities: List<Identity> = emptyList()
    ): TestHarness {
        val kgFeature = KnowledgeGraphFeature(platform, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val filesystemFeature = FileSystemFeature(platform)

        val builder = TestEnvironment.create()
            .withFeature(kgFeature)
            .withFeature(filesystemFeature)
            .withInitialState(featureHandle, kgState)
            .withInitialState("core", app.auf.feature.core.CoreState(lifecycle = app.auf.feature.core.AppLifecycle.RUNNING))

        extraIdentities.forEach { builder.withIdentity(it) }

        return builder.build(platform = platform)
    }

    // ====================================================================
    // Failure-collecting helper
    // ====================================================================

    private fun <T> assertAllCases(cases: List<T>, block: (T) -> Unit) {
        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                block(case)
            } catch (e: Throwable) {
                failures.add(e.message ?: e.toString())
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${cases.size} cases failed:\n\n" +
                        failures.joinToString("\n\n") { "  • $it" }
            )
        }
    }

    // -- Happy cases -------------------------------------------------------

    private val happyCases: List<HappyCase> by lazy {
        listOf(
            HappyCase(
                label = "CREATE_PERSONA",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA,
                originator = "core",
                payload = buildJsonObject { put("name", "New Persona") }
            ),
            HappyCase(
                label = "DELETE_PERSONA",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA,
                originator = "core",
                payload = buildJsonObject { put("personaId", PERSONA_ID) },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "CREATE_HOLON",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("parentId", PERSONA_ID)
                    put("type", "System_File")
                    put("name", "New Child")
                    put("payload", buildJsonObject { put("content", "new content") })
                },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "REPLACE_HOLON",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", CHILD_HOLON_ID)
                    put("payload", buildJsonObject { put("content", "replaced content") })
                },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "RENAME_HOLON",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", CHILD_HOLON_ID)
                    put("newName", "Renamed Child")
                },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "DELETE_HOLON",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON,
                originator = "core",
                payload = buildJsonObject { put("holonId", CHILD_HOLON_ID) },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "RESERVE_HKG",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                originator = "core",
                payload = buildJsonObject { put("personaId", PERSONA_ID) },
                buildState = ::loadedState
            ),
            HappyCase(
                label = "RELEASE_HKG",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
                originator = "core",
                payload = buildJsonObject { put("personaId", PERSONA_ID) },
                buildState = {
                    loadedState().copy(reservations = mapOf(PERSONA_ID to "core"))
                }
            ),
            HappyCase(
                label = "REQUEST_CONTEXT",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                originator = "agent",
                payload = buildJsonObject {
                    put("personaId", PERSONA_ID)
                    put("correlationId", "test-correlation-1")
                },
                buildState = ::loadedState
            )
        )
    }

    // -- Failure cases -----------------------------------------------------

    private val failureCases: List<FailureCase> by lazy {
        listOf(
            // CREATE_PERSONA
            FailureCase(
                label = "CREATE_PERSONA (missing name)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA,
                originator = "core",
                payload = buildJsonObject { } // no "name" field
            ),
            // DELETE_PERSONA
            FailureCase(
                label = "DELETE_PERSONA (missing personaId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA,
                originator = "core",
                payload = buildJsonObject { }
            ),
            FailureCase(
                label = "DELETE_PERSONA (locked by another user)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA,
                originator = "outsider",
                payload = buildJsonObject { put("personaId", PERSONA_ID) },
                buildState = {
                    loadedState().copy(reservations = mapOf(PERSONA_ID to "owner-agent"))
                },
                extraIdentities = listOf(outsiderIdentity)
            ),
            // CREATE_HOLON
            FailureCase(
                label = "CREATE_HOLON (missing parentId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("type", "System_File")
                    put("name", "Orphan")
                    put("payload", buildJsonObject { })
                }
            ),
            FailureCase(
                label = "CREATE_HOLON (missing type)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("parentId", PERSONA_ID)
                    put("name", "No Type")
                    put("payload", buildJsonObject { })
                },
                buildState = ::loadedState
            ),
            FailureCase(
                label = "CREATE_HOLON (missing name)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("parentId", PERSONA_ID)
                    put("type", "System_File")
                    put("payload", buildJsonObject { })
                },
                buildState = ::loadedState
            ),
            FailureCase(
                label = "CREATE_HOLON (missing payload)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("parentId", PERSONA_ID)
                    put("type", "System_File")
                    put("name", "No Payload")
                },
                buildState = ::loadedState
            ),
            FailureCase(
                label = "CREATE_HOLON (parent not found)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("parentId", "nonexistent-parent")
                    put("type", "System_File")
                    put("name", "Orphan")
                    put("payload", buildJsonObject { })
                }
                // empty state — parent doesn't exist
            ),
            FailureCase(
                label = "CREATE_HOLON (locked by another user)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON,
                originator = "outsider",
                payload = buildJsonObject {
                    put("parentId", PERSONA_ID)
                    put("type", "System_File")
                    put("name", "Blocked")
                    put("payload", buildJsonObject { })
                },
                buildState = {
                    loadedState().copy(reservations = mapOf(PERSONA_ID to "owner-agent"))
                },
                extraIdentities = listOf(outsiderIdentity)
            ),
            // REPLACE_HOLON
            FailureCase(
                label = "REPLACE_HOLON (missing holonId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("payload", buildJsonObject { })
                }
            ),
            FailureCase(
                label = "REPLACE_HOLON (missing payload)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", CHILD_HOLON_ID)
                },
                buildState = ::loadedState
            ),
            FailureCase(
                label = "REPLACE_HOLON (holon not found)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", "nonexistent-holon")
                    put("payload", buildJsonObject { })
                }
            ),
            // RENAME_HOLON
            FailureCase(
                label = "RENAME_HOLON (missing holonId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("newName", "No Target")
                }
            ),
            FailureCase(
                label = "RENAME_HOLON (missing newName)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", CHILD_HOLON_ID)
                },
                buildState = ::loadedState
            ),
            FailureCase(
                label = "RENAME_HOLON (holon not found)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", "nonexistent-holon")
                    put("newName", "Ghost")
                }
            ),
            // DELETE_HOLON
            FailureCase(
                label = "DELETE_HOLON (missing holonId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON,
                originator = "core",
                payload = buildJsonObject { }
            ),
            FailureCase(
                label = "DELETE_HOLON (holon not found)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON,
                originator = "core",
                payload = buildJsonObject {
                    put("holonId", "nonexistent-holon")
                }
            ),
            // RESERVE_HKG
            FailureCase(
                label = "RESERVE_HKG (missing personaId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                originator = "core",
                payload = buildJsonObject { }
            ),
            FailureCase(
                label = "RESERVE_HKG (already reserved)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG,
                originator = "outsider",
                payload = buildJsonObject { put("personaId", PERSONA_ID) },
                buildState = {
                    loadedState().copy(reservations = mapOf(PERSONA_ID to "owner-agent"))
                },
                extraIdentities = listOf(outsiderIdentity)
            ),
            // RELEASE_HKG
            FailureCase(
                label = "RELEASE_HKG (missing personaId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG,
                originator = "core",
                payload = buildJsonObject { }
            ),
            // REQUEST_CONTEXT
            FailureCase(
                label = "REQUEST_CONTEXT (missing personaId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                originator = "agent",
                payload = buildJsonObject {
                    put("correlationId", "test-correlation-1")
                }
            ),
            FailureCase(
                label = "REQUEST_CONTEXT (missing correlationId)",
                actionName = ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT,
                originator = "agent",
                payload = buildJsonObject {
                    put("personaId", PERSONA_ID)
                },
                buildState = ::loadedState
            )
        )
    }

    // ====================================================================
    // Contract: ACTION_RESULT on success
    // ====================================================================

    @Test
    fun `all domain actions publish success ACTION_RESULT on happy path`() {
        assertAllCases(happyCases) { case ->
            val platform = case.buildPlatform()
            val harness = buildHarness(platform, case.buildState(), case.extraIdentities)

            harness.store.processedActions.clear()
            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish KNOWLEDGEGRAPH_ACTION_RESULT on success.")
            assertEquals(true, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=true.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
        }
    }

    // ====================================================================
    // Contract: ACTION_RESULT on failure
    // ====================================================================

    @Test
    fun `all domain actions publish failure ACTION_RESULT on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val harness = buildHarness(platform, case.buildState(), case.extraIdentities)

            harness.store.processedActions.clear()
            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.KNOWLEDGEGRAPH_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish KNOWLEDGEGRAPH_ACTION_RESULT on failure.")
            assertEquals(false, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=false.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
            assertNotNull(result.payload?.get("error")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT should contain a non-null error field.")
        }
    }

    // ====================================================================
    // Contract: WARN or ERROR level log on failure
    // ====================================================================

    @Test
    fun `all domain actions log at WARN or ERROR level on error`() {
        assertAllCases(failureCases) { case ->
            val platform = case.buildPlatform()
            val harness = buildHarness(platform, case.buildState(), case.extraIdentities)

            platform.capturedLogs.clear()
            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            assertTrue(
                platform.capturedLogs.any { it.level == LogLevel.ERROR || it.level == LogLevel.WARN },
                "[${case.label}] should log at WARN or ERROR level on failure. " +
                        "Captured logs: ${platform.capturedLogs.map { "[${it.level}] ${it.message}" }}"
            )
        }
    }

    // ====================================================================
    // Discovery Audit: every public action must have contract coverage
    // ====================================================================

    @Test
    fun `all public knowledgegraph actions are covered by contract test cases`() {
        val featureDescriptor = ActionRegistry.features[featureHandle]
            ?: throw AssertionError("Feature '$featureHandle' not found in ActionRegistry.")

        val publicActions = featureDescriptor.actions.values
            .filter { it.public }
            .map { it.fullName }
            .toSet()

        val testedActions = (happyCases.map { it.actionName } + failureCases.map { it.actionName }).toSet()
        val untested = publicActions - testedActions

        assertTrue(
            untested.isEmpty(),
            "The following public '$featureHandle' actions have no contract test coverage:\n" +
                    untested.sorted().joinToString("\n") { "  • $it" } +
                    "\n\nAdd HappyCase and/or FailureCase entries for each."
        )
    }
}