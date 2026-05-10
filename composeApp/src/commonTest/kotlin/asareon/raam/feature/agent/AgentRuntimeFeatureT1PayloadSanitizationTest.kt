package asareon.raam.feature.agent

import asareon.raam.core.Action
import asareon.raam.core.AppState
import asareon.raam.core.Identity
import asareon.raam.core.PermissionGrant
import asareon.raam.core.PermissionLevel
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 tests for agent-payload sanitization in
 * [AgentRuntimeFeature.handleSideEffects] / `COMMANDBOT_ACTION_CREATED`.
 *
 * What the sanitizer guarantees:
 *  - Strips agent-supplied fields not in the visible schema (unknown OR
 *    [PayloadField.agentInternal] OR [PayloadField.agentAutofill] OR
 *    permission-gated and not held).
 *  - Injects [PayloadField.agentAutofill] values from agent runtime context.
 *  - Posts a single per-action SYSTEM SENTINEL summarising every field name
 *    that was stripped, with identical wording for unknown vs internal so the
 *    catalog can't be probed.
 */
class AgentRuntimeFeatureT1PayloadSanitizationTest {

    private val platform = FakePlatformDependencies("test")

    private val agentUuid = "agent-uuid-1"
    private val agentHandle = "agent.test-agent"
    private val sessionUuid = "session-uuid-1"
    private val sessionHandle = "session.testing-session"

    private val agent = testAgent(
        id = agentUuid,
        name = "Test Agent",
        cognitiveStrategyId = "agent.strategy.minimal",
        subscribedSessionIds = listOf(sessionUuid),
        privateSessionId = sessionUuid,
        modelProvider = "test",
        modelName = "test-model",
    )

    private fun setup(): Pair<AgentRuntimeFeature, FakeStore> {
        val feature = AgentRuntimeFeature(platform, CoroutineScope(Job()))
        val store = FakeStore(
            AppState(
                actionDescriptors = ActionRegistry.byActionName,
                identityRegistry = mapOf(
                    agentHandle to Identity(
                        uuid = agentUuid,
                        localHandle = "test-agent",
                        handle = agentHandle,
                        name = "Test Agent",
                        parentHandle = "agent",
                        permissions = mapOf(
                            "session:write" to PermissionGrant(PermissionLevel.YES),
                            "session:read" to PermissionGrant(PermissionLevel.YES),
                            "agent:cognition" to PermissionGrant(PermissionLevel.YES),
                        ),
                    ),
                    sessionHandle to Identity(
                        uuid = sessionUuid,
                        localHandle = "testing-session",
                        handle = sessionHandle,
                        name = "Testing Session",
                        parentHandle = "session",
                    ),
                )
            ),
            platform
        )
        return feature to store
    }

    private fun actionCreated(actionName: String, actionPayload: JsonObject): Action {
        return Action(
            ActionRegistry.Names.COMMANDBOT_ACTION_CREATED,
            buildJsonObject {
                put("correlationId", "corr-1")
                put("originatorId", agentHandle)
                put("originatorName", "Test Agent")
                put("sessionId", sessionUuid)
                put("actionName", actionName)
                put("actionPayload", actionPayload)
            }
        )
    }

    private fun runDispatch(action: Action): FakeStore {
        val (feature, store) = setup()
        val agentState = AgentRuntimeState(agents = mapOf(uid(agentUuid) to agent))
        feature.handleSideEffects(action, store, null, agentState)
        return store
    }

    /**
     * Returns the agent's actual domain dispatch (skips advisory POSTs that the
     * agent feature emits with senderId = "system" alongside the real action).
     */
    private fun finalDomainPayload(store: FakeStore, actionName: String): JsonObject? {
        return store.dispatchedActions
            .filter { it.name == actionName }
            .map { it.payload }
            .firstOrNull { p ->
                p?.get("senderId")?.jsonPrimitive?.contentOrNull != "system"
            }
    }

    private fun sanitizationSentinel(store: FakeStore): JsonObject? {
        return store.dispatchedActions
            .filter { it.name == ActionRegistry.Names.SESSION_POST }
            .map { it.payload }
            .firstOrNull { p ->
                p?.get("senderId")?.jsonPrimitive?.contentOrNull == "system" &&
                    (p["message"]?.jsonPrimitive?.contentOrNull ?: "").contains("payload sanitized")
            }
    }

    // =========================================================================
    // session.POST — senderId hardening + internal field strip
    // =========================================================================

    @Test
    fun `agent-supplied senderId is overwritten with the agent's own handle`() {
        // Agent attempts to impersonate "core.daniel" by setting senderId.
        val store = runDispatch(actionCreated(
            actionName = ActionRegistry.Names.SESSION_POST,
            actionPayload = buildJsonObject {
                put("session", sessionHandle)
                put("senderId", "core.daniel")  // spoofing attempt
                put("message", "Hello from impersonator")
            }
        ))

        val domainPayload = finalDomainPayload(store, ActionRegistry.Names.SESSION_POST)
        assertNotNull(domainPayload, "session.POST should still be dispatched.")
        assertEquals(
            agentHandle,
            domainPayload["senderId"]?.jsonPrimitive?.contentOrNull,
            "senderId must be hard-overwritten to the agent's own handle — impersonation blocked.",
        )

        val sentinel = sanitizationSentinel(store)
        assertNotNull(sentinel, "Spoofing attempt must produce a sanitization advisory.")
        val msg = sentinel["message"]?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(msg.contains("'senderId' not found"),
            "Advisory should report senderId as 'not found' (not 'autofilled' or similar — " +
                "we don't leak the existence of internal field names). Got: $msg")
    }

    @Test
    fun `agent-supplied internal fields on session-POST are stripped`() {
        val store = runDispatch(actionCreated(
            actionName = ActionRegistry.Names.SESSION_POST,
            actionPayload = buildJsonObject {
                put("session", sessionHandle)
                put("message", "valid")
                put("messageId", "rogue-id-1")
                put("metadata", buildJsonObject { put("default_collapsed", true) })
                put("doNotClear", true)
                put("afterMessageId", "some-other-message")
            }
        ))

        val domainPayload = finalDomainPayload(store, ActionRegistry.Names.SESSION_POST)
        assertNotNull(domainPayload)
        assertNull(domainPayload["messageId"], "Agent-supplied messageId must be stripped.")
        assertNull(domainPayload["metadata"], "Agent-supplied metadata must be stripped.")
        assertNull(domainPayload["doNotClear"], "Agent-supplied doNotClear must be stripped.")
        assertNull(domainPayload["afterMessageId"], "Agent-supplied afterMessageId must be stripped.")

        val sentinel = sanitizationSentinel(store)
        assertNotNull(sentinel)
        val msg = sentinel["message"]?.jsonPrimitive?.contentOrNull ?: ""
        listOf("messageId", "metadata", "doNotClear", "afterMessageId").forEach { field ->
            assertTrue(msg.contains("'$field' not found"),
                "Advisory should list '$field' as not found. Got: $msg")
        }
    }

    @Test
    fun `unknown agent-supplied field is stripped and reported with same wording as internal`() {
        val store = runDispatch(actionCreated(
            actionName = ActionRegistry.Names.SESSION_POST,
            actionPayload = buildJsonObject {
                put("session", sessionHandle)
                put("message", "ok")
                put("totallyMadeUp", "value")  // not in schema at all
                put("messageId", "rogue")       // in schema but agent_internal
            }
        ))

        val sentinel = sanitizationSentinel(store)
        assertNotNull(sentinel)
        val msg = sentinel["message"]?.jsonPrimitive?.contentOrNull ?: ""
        assertTrue(msg.contains("'totallyMadeUp' not found"))
        assertTrue(msg.contains("'messageId' not found"))
        // CRITICAL: same wording for both — no probing path.
        val unknownLine = msg.lineSequence().first { it.contains("totallyMadeUp") }
        val internalLine = msg.lineSequence().first { it.contains("messageId") }
        // Both lines should be of the form "warning: parameter '<name>' not found"
        // — only the field name differs.
        val pattern = Regex("""^warning: parameter '[^']+' not found$""")
        assertTrue(pattern.matches(unknownLine.trim()),
            "Unknown-field advisory must use the canonical wording. Got: $unknownLine")
        assertTrue(pattern.matches(internalLine.trim()),
            "Internal-field advisory must use the same canonical wording. Got: $internalLine")
    }

    @Test
    fun `clean session-POST payload produces no sanitization advisory`() {
        val store = runDispatch(actionCreated(
            actionName = ActionRegistry.Names.SESSION_POST,
            actionPayload = buildJsonObject {
                put("session", sessionHandle)
                put("message", "hi")
            }
        ))

        assertNull(sanitizationSentinel(store),
            "A clean payload should not generate a sanitization sentinel.")
        // senderId should have been auto-filled even though the agent didn't set it.
        val domainPayload = finalDomainPayload(store, ActionRegistry.Names.SESSION_POST)
        assertNotNull(domainPayload)
        assertEquals(agentHandle, domainPayload["senderId"]?.jsonPrimitive?.contentOrNull,
            "senderId must be auto-filled from the originator handle.")
    }

    // =========================================================================
    // session.LIST_SESSIONS — responseSession autofill
    // =========================================================================

    @Test
    fun `session-LIST_SESSIONS auto-fills responseSession to the agent's primary session`() {
        val store = runDispatch(actionCreated(
            actionName = ActionRegistry.Names.SESSION_LIST_SESSIONS,
            actionPayload = buildJsonObject { /* empty — agent shouldn't need to set anything */ }
        ))

        val domainPayload = finalDomainPayload(store, ActionRegistry.Names.SESSION_LIST_SESSIONS)
        assertNotNull(domainPayload)
        assertEquals(
            sessionHandle,
            domainPayload["responseSession"]?.jsonPrimitive?.contentOrNull,
            "responseSession must auto-fill to the agent's primary session handle.",
        )
    }
}
