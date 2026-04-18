package asareon.raam.feature.session

import asareon.raam.core.Action
import asareon.raam.core.Identity
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.test.TestEnvironment
import asareon.raam.test.TestHarness
import asareon.raam.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Contract Tests for SessionFeature's domain actions.
 *
 * These tests verify cross-cutting obligations that every domain action must satisfy:
 *   1. Publish a success ACTION_RESULT on the happy path.
 *   2. Publish a failure ACTION_RESULT when an error occurs.
 *   3. Log at ERROR level when an error occurs.
 *
 * Action-specific behaviour (ledger mutation, persistence, broadcasting, etc.)
 * is tested in SessionFeatureT2CoreTest.
 *
 * ## Excluded actions
 * The following public actions are excluded from the ACTION_RESULT contract
 * and tracked separately:
 *
 * - **session.CREATE / session.CLONE**: Two-phase identity flow. The side-effect
 *   handler dispatches REGISTER_IDENTITY and the session materialises on
 *   RETURN_REGISTER_IDENTITY. Neither phase currently publishes ACTION_RESULT.
 *   TODO: Thread a correlationId through the two-phase flow and publish
 *         ACTION_RESULT on completion / failure.
 *
 * - **session.REQUEST_WORKSPACE_FILES / session.READ_WORKSPACE_FILE**: Delegation
 *   pattern. These respond via targeted RETURN_WORKSPACE_FILES / RETURN_WORKSPACE_FILE
 *   actions rather than the broadcast ACTION_RESULT convention.
 */
class SessionFeatureT2ContractTest {

    private val featureHandle = "session"

    // ====================================================================
    // Shared test data
    // ====================================================================

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val testIdentity = Identity(
        uuid = "00000000-0000-4000-a000-000000000001",
        localHandle = "sid-1",
        handle = "session.sid-1",
        name = "Test Session",
        parentHandle = "session"
    )

    private val testEntry = LedgerEntry(
        id = "msg-1",
        timestamp = 1738953600000L, // 2025-02-07T18:40:00Z
        senderId = "user",
        rawContent = "Hello World"
    )

    private val lockedEntry = LedgerEntry(
        id = "msg-locked",
        timestamp = 1738953600000L,
        senderId = "user",
        rawContent = "Locked content",
        isLocked = true
    )

    private val testSession = Session(
        identity = testIdentity,
        ledger = listOf(testEntry),
        createdAt = 1L
    )

    private val sessionWithLockedEntry = Session(
        identity = testIdentity,
        ledger = listOf(lockedEntry),
        createdAt = 1L
    )

    /** Baseline state with one session containing one message. */
    private val baseState = SessionState(
        sessions = mapOf(testSession.identity.localHandle to testSession)
    )

    /** State with a locked message (for UNLOCK_MESSAGE happy path and locked failure cases). */
    private val lockedState = SessionState(
        sessions = mapOf(sessionWithLockedEntry.identity.localHandle to sessionWithLockedEntry)
    )

    // ====================================================================
    // Test-case descriptors
    // ====================================================================

    /**
     * A happy-path test case. [initialState] seeds the feature state.
     * [setup] receives the platform for any filesystem pre-work.
     */
    private data class HappyCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val initialState: SessionState,
        val setup: (FakePlatformDependencies) -> Unit = { }
    )

    /**
     * A failure-path test case. The payload is constructed to trigger a
     * known error path (typically: session not found, missing fields).
     */
    private data class FailureCase(
        val label: String,
        val actionName: String,
        val originator: String,
        val payload: JsonObject,
        val initialState: SessionState = SessionState()
    )

    // -- Happy cases -------------------------------------------------------

    private val happyCases = listOf(
        HappyCase(
            label = "POST",
            actionName = ActionRegistry.Names.SESSION_POST,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("senderId", "user")
                put("message", "Test message")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "UPDATE_CONFIG",
            actionName = ActionRegistry.Names.SESSION_UPDATE_CONFIG,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("name", "Renamed Session")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "DELETE",
            actionName = ActionRegistry.Names.SESSION_DELETE,
            originator = "session",
            payload = buildJsonObject { put("session", "sid-1") },
            initialState = baseState
        ),
        HappyCase(
            label = "UPDATE_MESSAGE",
            actionName = ActionRegistry.Names.SESSION_UPDATE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("messageId", "msg-1")
                put("newContent", "Updated content")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "DELETE_MESSAGE",
            actionName = ActionRegistry.Names.SESSION_DELETE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("messageId", "msg-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "REQUEST_LEDGER_CONTENT",
            actionName = ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT,
            originator = "agent",
            payload = buildJsonObject {
                put("sessionId", "sid-1")
                put("correlationId", "test-corr-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "TOGGLE_MESSAGE_COLLAPSED (single)",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
            originator = "session",
            payload = buildJsonObject {
                put("sessionId", "sid-1")
                put("messageId", "msg-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "TOGGLE_MESSAGE_COLLAPSED (bulk)",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
            originator = "session",
            payload = buildJsonObject {
                put("sessionId", "sid-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "TOGGLE_SESSION_HIDDEN",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN,
            originator = "session",
            payload = buildJsonObject { put("session", "sid-1") },
            initialState = baseState
        ),
        HappyCase(
            label = "TOGGLE_MESSAGE_LOCKED",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED,
            originator = "session",
            payload = buildJsonObject {
                put("sessionId", "sid-1")
                put("messageId", "msg-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "CLEAR",
            actionName = ActionRegistry.Names.SESSION_CLEAR,
            originator = "session",
            payload = buildJsonObject { put("session", "sid-1") },
            initialState = baseState
        ),
        HappyCase(
            label = "LIST_SESSIONS",
            actionName = ActionRegistry.Names.SESSION_LIST_SESSIONS,
            originator = "agent",
            payload = buildJsonObject {
                put("responseSession", "sid-1")
            },
            initialState = baseState
        ),
        HappyCase(
            label = "LOCK_MESSAGE",
            actionName = ActionRegistry.Names.SESSION_LOCK_MESSAGE,
            originator = "agent",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("senderId", "user")
                put("timestamp", "2025-02-07T18:40:00Z")
            },
            initialState = baseState // entry is unlocked → LOCK flips to locked
        ),
        HappyCase(
            label = "UNLOCK_MESSAGE",
            actionName = ActionRegistry.Names.SESSION_UNLOCK_MESSAGE,
            originator = "agent",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("senderId", "user")
                put("timestamp", "2025-02-07T18:40:00Z")
            },
            initialState = lockedState // entry is locked → UNLOCK flips to unlocked
        )
    )

    // -- Failure cases -----------------------------------------------------

    private val failureCases = listOf(
        FailureCase(
            label = "POST (session not found)",
            actionName = ActionRegistry.Names.SESSION_POST,
            originator = "session",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("senderId", "user")
                put("message", "Test")
            }
        ),
        FailureCase(
            label = "UPDATE_CONFIG (session not found)",
            actionName = ActionRegistry.Names.SESSION_UPDATE_CONFIG,
            originator = "session",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("name", "X")
            }
        ),
        FailureCase(
            label = "DELETE (session not found)",
            actionName = ActionRegistry.Names.SESSION_DELETE,
            originator = "session",
            payload = buildJsonObject { put("session", "nonexistent") }
        ),
        FailureCase(
            label = "UPDATE_MESSAGE (session not found)",
            actionName = ActionRegistry.Names.SESSION_UPDATE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("messageId", "msg-1")
                put("newContent", "X")
            }
        ),
        FailureCase(
            label = "UPDATE_MESSAGE (message locked)",
            actionName = ActionRegistry.Names.SESSION_UPDATE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("messageId", "msg-locked")
                put("newContent", "Attempted edit")
            },
            initialState = lockedState
        ),
        FailureCase(
            label = "DELETE_MESSAGE (session not found)",
            actionName = ActionRegistry.Names.SESSION_DELETE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("messageId", "msg-1")
            }
        ),
        FailureCase(
            label = "DELETE_MESSAGE (message locked)",
            actionName = ActionRegistry.Names.SESSION_DELETE_MESSAGE,
            originator = "session",
            payload = buildJsonObject {
                put("session", "sid-1")
                put("messageId", "msg-locked")
            },
            initialState = lockedState
        ),
        FailureCase(
            label = "REQUEST_LEDGER_CONTENT (session not found)",
            actionName = ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT,
            originator = "agent",
            payload = buildJsonObject {
                put("sessionId", "nonexistent")
                put("correlationId", "test-corr-err")
            }
        ),
        FailureCase(
            label = "TOGGLE_MESSAGE_COLLAPSED (session not found)",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
            originator = "session",
            payload = buildJsonObject {
                put("sessionId", "nonexistent")
                put("messageId", "msg-1")
            }
        ),
        FailureCase(
            label = "TOGGLE_SESSION_HIDDEN (session not found)",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN,
            originator = "session",
            payload = buildJsonObject { put("session", "nonexistent") }
        ),
        FailureCase(
            label = "TOGGLE_MESSAGE_LOCKED (session not found)",
            actionName = ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED,
            originator = "session",
            payload = buildJsonObject {
                put("sessionId", "nonexistent")
                put("messageId", "msg-1")
            }
        ),
        FailureCase(
            label = "CLEAR (session not found)",
            actionName = ActionRegistry.Names.SESSION_CLEAR,
            originator = "session",
            payload = buildJsonObject { put("session", "nonexistent") }
        ),
        FailureCase(
            label = "LIST_SESSIONS (no responseSession)",
            actionName = ActionRegistry.Names.SESSION_LIST_SESSIONS,
            originator = "agent",
            payload = buildJsonObject { },
            initialState = baseState
        ),
        FailureCase(
            label = "LOCK_MESSAGE (session not found)",
            actionName = ActionRegistry.Names.SESSION_LOCK_MESSAGE,
            originator = "agent",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("senderId", "user")
                put("timestamp", "2025-02-07T18:40:00Z")
            }
        ),
        FailureCase(
            label = "UNLOCK_MESSAGE (session not found)",
            actionName = ActionRegistry.Names.SESSION_UNLOCK_MESSAGE,
            originator = "agent",
            payload = buildJsonObject {
                put("session", "nonexistent")
                put("senderId", "user")
                put("timestamp", "2025-02-07T18:40:00Z")
            }
        )
    )

    // ====================================================================
    // Failure-collecting helper
    // ====================================================================

    /**
     * Runs [block] for each item in the list, catching assertion failures.
     * After all items have been evaluated, throws a single [AssertionError]
     * listing every failure — or passes silently if all succeeded.
     */
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

    /** Builds a fresh harness for a single case. */
    private fun buildHarness(
        platform: FakePlatformDependencies,
        initialState: SessionState
    ): TestHarness {
        val sessionFeature = SessionFeature(platform, scope)
        val fileSystemFeature = FileSystemFeature(platform)
        return TestEnvironment.create()
            .withFeature(sessionFeature)
            .withFeature(fileSystemFeature)
            .withInitialState(featureHandle, initialState)
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
            .build(platform = platform)
    }

    // ====================================================================
    // Contract: ACTION_RESULT on success
    // ====================================================================

    @Test
    fun `all domain actions publish success ACTION_RESULT on happy path`() {
        assertAllCases(happyCases) { case ->
            val platform = FakePlatformDependencies("test")
            case.setup(platform)
            val harness = buildHarness(platform, case.initialState)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish SESSION_ACTION_RESULT on success.")
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
            val platform = FakePlatformDependencies("test")
            val harness = buildHarness(platform, case.initialState)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            val result = harness.processedActions.find {
                it.name == ActionRegistry.Names.SESSION_ACTION_RESULT
            }
            assertNotNull(result,
                "[${case.label}] should publish SESSION_ACTION_RESULT on failure.")
            assertEquals(false, result.payload?.get("success")?.jsonPrimitive?.boolean,
                "[${case.label}] ACTION_RESULT should have success=false.")
            assertEquals(case.actionName, result.payload?.get("requestAction")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT.requestAction should match the dispatched action.")
            assertNotNull(result.payload?.get("error")?.jsonPrimitive?.content,
                "[${case.label}] ACTION_RESULT should contain a non-null error field.")
        }
    }

    // ====================================================================
    // Contract: ERROR-level log on failure
    // ====================================================================

    @Test
    fun `all domain actions log at ERROR level on error`() {
        assertAllCases(failureCases) { case ->
            val platform = FakePlatformDependencies("test")
            val harness = buildHarness(platform, case.initialState)

            harness.store.dispatch(case.originator, Action(case.actionName, case.payload))

            assertTrue(
                platform.capturedLogs.any { it.level == LogLevel.ERROR },
                "[${case.label}] should log at ERROR level on failure."
            )
        }
    }

    // ====================================================================
    // Discovery Audit: every public action must have contract coverage
    // ====================================================================

    /**
     * Actions excluded from the ACTION_RESULT contract by design.
     * Each exclusion requires a documented rationale (see class KDoc).
     */
    private val excludedFromContract = setOf(
        // Two-phase identity flow — ACTION_RESULT not yet wired through the
        // REGISTER_IDENTITY → RETURN_REGISTER_IDENTITY roundtrip.
        ActionRegistry.Names.SESSION_CREATE,
        ActionRegistry.Names.SESSION_CLONE,
        // Delegation pattern — responds via targeted RETURN_* actions, not ACTION_RESULT.
        ActionRegistry.Names.SESSION_REQUEST_WORKSPACE_FILES,
        ActionRegistry.Names.SESSION_READ_WORKSPACE_FILE
    )

    @Test
    fun `all public session actions are covered by contract test cases`() {
        val featureDescriptor = ActionRegistry.features[featureHandle]
            ?: throw AssertionError("Feature '$featureHandle' not found in ActionRegistry.")

        val publicActions = featureDescriptor.actions.values
            .filter { it.public && !it.response }
            .map { it.fullName }
            .toSet()

        val testedActions = (happyCases.map { it.actionName } + failureCases.map { it.actionName }).toSet()
        val covered = testedActions + excludedFromContract
        val untested = publicActions - covered

        assertTrue(
            untested.isEmpty(),
            "The following public '$featureHandle' actions have no contract test coverage:\n" +
                    untested.sorted().joinToString("\n") { "  • $it" } +
                    "\n\nAdd HappyCase/FailureCase entries for each, or document the " +
                    "exclusion in excludedFromContract with a rationale."
        )
    }

    @Test
    fun `excluded actions are still public and not stale`() {
        val featureDescriptor = ActionRegistry.features[featureHandle]
            ?: throw AssertionError("Feature '$featureHandle' not found in ActionRegistry.")

        val publicActions = featureDescriptor.actions.values
            .filter { it.public }
            .map { it.fullName }
            .toSet()

        val staleExclusions = excludedFromContract - publicActions
        assertTrue(
            staleExclusions.isEmpty(),
            "The following excludedFromContract entries are no longer public actions " +
                    "(remove them from the exclusion set):\n" +
                    staleExclusions.sorted().joinToString("\n") { "  • $it" }
        )
    }
}