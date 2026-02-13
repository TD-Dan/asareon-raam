package app.auf.feature.commandbot

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for the CommandBotFeature reducer.
 *
 * Mandate (P-TEST-001, T1): To test a pure, isolated unit of logic — in this case,
 * the reducer function — with no dependencies on the Store, TestEnvironment, or
 * coroutine infrastructure. Each test calls `feature.reducer(state, action)` directly
 * and asserts on the returned state.
 *
 * ## Coverage Map
 *
 * ### COMMANDBOT_STAGE_APPROVAL
 * - Happy path: all fields present → adds to pendingApprovals
 * - Additivity: second staging adds alongside the first
 * - Null payload → returns current state
 * - Each required field missing → returns current state (approvalId, sessionId,
 *   cardMessageId, requestingAgentId, actionName)
 * - Optional field defaults: requestingAgentName → "Unknown Agent",
 *   payload → empty JsonObject
 * - requestedAt sourced from platformDependencies
 *
 * ### COMMANDBOT_RESOLVE_APPROVAL
 * - Happy path APPROVED: pending removed, resolved added
 * - Happy path DENIED: same transition with DENIED resolution
 * - Null payload → returns current state
 * - Missing approvalId or resolution → returns current state
 * - Invalid resolution string → returns current state
 * - Unknown approvalId (not in pending) → returns current state
 * - Only the targeted pending is resolved; others preserved
 * - resolvedAt sourced from platformDependencies
 * - Resolved entry retains sessionId and cardMessageId from pending
 *
 * ### SESSION_PUBLISH_MESSAGE_DELETED
 * - Matching cardMessageId → removes from resolvedApprovals
 * - Non-matching messageId → state unchanged
 * - Null messageId → returns current state
 * - Empty resolvedApprovals → state unchanged (no crash)
 * - Multiple resolved: only the matching one removed
 *
 * ### General
 * - Null state initializes to default CommandBotState
 * - Unknown action → returns current state unchanged
 */
class CommandBotFeatureT1ReducerTest {

    private fun createFeature(): CommandBotFeature {
        val platform = FakePlatformDependencies("test")
        return CommandBotFeature(platform)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Builds a full, valid STAGE_APPROVAL action payload. */
    private fun stageApprovalAction(
        approvalId: String = "approval-1",
        sessionId: String = "session-1",
        cardMessageId: String = "card-1",
        requestingAgentId: String = "agent-1",
        requestingAgentName: String? = "Test Agent",
        actionName: String = "session.CREATE",
        payload: kotlinx.serialization.json.JsonObject = buildJsonObject { put("name", "Test") },
    ): Action {
        return Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", approvalId)
            put("sessionId", sessionId)
            put("cardMessageId", cardMessageId)
            put("requestingAgentId", requestingAgentId)
            if (requestingAgentName != null) put("requestingAgentName", requestingAgentName)
            put("actionName", actionName)
            put("payload", payload)
        })
    }

    /** Builds a RESOLVE_APPROVAL action payload. */
    private fun resolveApprovalAction(
        approvalId: String = "approval-1",
        resolution: String = "APPROVED"
    ): Action {
        return Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
            put("approvalId", approvalId)
            put("resolution", resolution)
        })
    }

    /** Creates a PendingApproval for use as pre-existing state. */
    private fun pendingApproval(
        approvalId: String = "approval-1",
        sessionId: String = "session-1",
        cardMessageId: String = "card-1",
        requestingAgentId: String = "agent-1",
        requestingAgentName: String = "Test Agent",
        actionName: String = "session.CREATE",
    ): PendingApproval {
        return PendingApproval(
            approvalId = approvalId,
            sessionId = sessionId,
            cardMessageId = cardMessageId,
            requestingAgentId = requestingAgentId,
            requestingAgentName = requestingAgentName,
            actionName = actionName,
            payload = buildJsonObject {},
            requestedAt = 500L
        )
    }

    /** Creates a resolved ApprovalResolution for use as pre-existing state. */
    private fun resolvedApproval(
        approvalId: String = "approval-1",
        cardMessageId: String = "card-1",
        sessionId: String = "session-1",
        actionName: String = "session.CREATE",
        resolution: Resolution = Resolution.APPROVED
    ): ApprovalResolution {
        return ApprovalResolution(
            approvalId = approvalId,
            actionName = actionName,
            requestingAgentName = "Test Agent",
            resolution = resolution,
            resolvedAt = 900L,
            sessionId = sessionId,
            cardMessageId = cardMessageId
        )
    }

    // ========================================================================
    // General Reducer Behaviour
    // ========================================================================

    @Test
    fun `null state initialises to default CommandBotState`() {
        val feature = createFeature()
        val result = feature.reducer(null, Action("some.UNKNOWN_ACTION"))
        assertNotNull(result, "Reducer should return a non-null state when given null.")
        val state = result as CommandBotState
        assertTrue(state.pendingApprovals.isEmpty())
        assertTrue(state.resolvedApprovals.isEmpty())
    }

    @Test
    fun `unknown action returns current state unchanged`() {
        val feature = createFeature()
        val initial = CommandBotState()
        val result = feature.reducer(initial, Action("totally.UNKNOWN_ACTION"))
        assertSame(initial, result, "Unknown actions should return the exact same state instance.")
    }

    // ========================================================================
    // COMMANDBOT_STAGE_APPROVAL
    // ========================================================================

    @Test
    fun `STAGE_APPROVAL - happy path adds to pending map`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val result = feature.reducer(initial, stageApprovalAction()) as CommandBotState

        assertEquals(1, result.pendingApprovals.size)
        val pending = result.pendingApprovals["approval-1"]
        assertNotNull(pending)
        assertEquals("approval-1", pending.approvalId)
        assertEquals("session-1", pending.sessionId)
        assertEquals("card-1", pending.cardMessageId)
        assertEquals("agent-1", pending.requestingAgentId)
        assertEquals("Test Agent", pending.requestingAgentName)
        assertEquals("session.CREATE", pending.actionName)
        assertTrue(pending.requestedAt > 0, "requestedAt should be populated from platformDependencies.")
    }

    @Test
    fun `STAGE_APPROVAL - additivity preserves existing pending approvals`() {
        val feature = createFeature()
        val initial = CommandBotState(
            pendingApprovals = mapOf("existing-1" to pendingApproval(approvalId = "existing-1"))
        )

        val result = feature.reducer(initial, stageApprovalAction(approvalId = "approval-2")) as CommandBotState

        assertEquals(2, result.pendingApprovals.size)
        assertNotNull(result.pendingApprovals["existing-1"], "Pre-existing approval should be preserved.")
        assertNotNull(result.pendingApprovals["approval-2"], "New approval should be added.")
    }

    @Test
    fun `STAGE_APPROVAL - null payload returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, null)
        val result = feature.reducer(initial, action)

        assertSame(initial, result, "Null payload should return the same state instance.")
    }

    @Test
    fun `STAGE_APPROVAL - missing approvalId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            // approvalId omitted
            put("sessionId", "session-1")
            put("cardMessageId", "card-1")
            put("requestingAgentId", "agent-1")
            put("actionName", "session.CREATE")
            put("payload", buildJsonObject {})
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Missing approvalId should prevent staging.")
    }

    @Test
    fun `STAGE_APPROVAL - missing sessionId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            // sessionId omitted
            put("cardMessageId", "card-1")
            put("requestingAgentId", "agent-1")
            put("actionName", "session.CREATE")
            put("payload", buildJsonObject {})
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Missing sessionId should prevent staging.")
    }

    @Test
    fun `STAGE_APPROVAL - missing cardMessageId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            put("sessionId", "session-1")
            // cardMessageId omitted
            put("requestingAgentId", "agent-1")
            put("actionName", "session.CREATE")
            put("payload", buildJsonObject {})
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Missing cardMessageId should prevent staging.")
    }

    @Test
    fun `STAGE_APPROVAL - missing requestingAgentId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            put("sessionId", "session-1")
            put("cardMessageId", "card-1")
            // requestingAgentId omitted
            put("actionName", "session.CREATE")
            put("payload", buildJsonObject {})
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Missing requestingAgentId should prevent staging.")
    }

    @Test
    fun `STAGE_APPROVAL - missing actionName returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            put("sessionId", "session-1")
            put("cardMessageId", "card-1")
            put("requestingAgentId", "agent-1")
            // actionName omitted
            put("payload", buildJsonObject {})
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Missing actionName should prevent staging.")
    }

    @Test
    fun `STAGE_APPROVAL - missing requestingAgentName defaults to Unknown Agent`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val result = feature.reducer(
            initial,
            stageApprovalAction(requestingAgentName = null)
        ) as CommandBotState

        val pending = result.pendingApprovals["approval-1"]
        assertNotNull(pending)
        assertEquals("Unknown Agent", pending.requestingAgentName,
            "Missing requestingAgentName should default to 'Unknown Agent'.")
    }

    @Test
    fun `STAGE_APPROVAL - missing payload field defaults to empty JsonObject`() {
        val feature = createFeature()
        val initial = CommandBotState()

        val action = Action(ActionRegistry.Names.COMMANDBOT_STAGE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            put("sessionId", "session-1")
            put("cardMessageId", "card-1")
            put("requestingAgentId", "agent-1")
            put("actionName", "session.CREATE")
            // "payload" field omitted entirely
        })
        val result = feature.reducer(initial, action) as CommandBotState

        val pending = result.pendingApprovals["approval-1"]
        assertNotNull(pending)
        assertEquals(buildJsonObject {}, pending.payload,
            "Missing payload should default to an empty JsonObject.")
    }

    @Test
    fun `STAGE_APPROVAL - does not affect resolvedApprovals`() {
        val feature = createFeature()
        val initial = CommandBotState(
            resolvedApprovals = mapOf("old-1" to resolvedApproval(approvalId = "old-1"))
        )

        val result = feature.reducer(initial, stageApprovalAction()) as CommandBotState

        assertEquals(1, result.resolvedApprovals.size,
            "Staging should not modify the resolvedApprovals map.")
        assertNotNull(result.resolvedApprovals["old-1"])
    }

    // ========================================================================
    // COMMANDBOT_RESOLVE_APPROVAL
    // ========================================================================

    @Test
    fun `RESOLVE_APPROVAL - APPROVED moves pending to resolved`() {
        val feature = createFeature()
        val pending = pendingApproval()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pending))

        val result = feature.reducer(initial, resolveApprovalAction("approval-1", "APPROVED")) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty(), "Pending should be empty after resolution.")
        assertEquals(1, result.resolvedApprovals.size)

        val resolved = result.resolvedApprovals["approval-1"]
        assertNotNull(resolved)
        assertEquals(Resolution.APPROVED, resolved.resolution)
        assertEquals("session.CREATE", resolved.actionName)
        assertEquals("Test Agent", resolved.requestingAgentName)
        assertTrue(resolved.resolvedAt > 0, "resolvedAt should be populated from platformDependencies.")
    }

    @Test
    fun `RESOLVE_APPROVAL - DENIED resolution`() {
        val feature = createFeature()
        val pending = pendingApproval(approvalId = "approval-2", actionName = "session.DELETE")
        val initial = CommandBotState(pendingApprovals = mapOf("approval-2" to pending))

        val result = feature.reducer(initial, resolveApprovalAction("approval-2", "DENIED")) as CommandBotState

        assertTrue(result.pendingApprovals.isEmpty())
        val resolved = result.resolvedApprovals["approval-2"]
        assertNotNull(resolved)
        assertEquals(Resolution.DENIED, resolved.resolution)
        assertEquals("session.DELETE", resolved.actionName)
    }

    @Test
    fun `RESOLVE_APPROVAL - retains sessionId and cardMessageId from pending`() {
        val feature = createFeature()
        val pending = pendingApproval(sessionId = "sess-99", cardMessageId = "card-77")
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pending))

        val result = feature.reducer(initial, resolveApprovalAction()) as CommandBotState

        val resolved = result.resolvedApprovals["approval-1"]
        assertNotNull(resolved)
        assertEquals("sess-99", resolved.sessionId,
            "Resolved entry should retain sessionId from the pending approval.")
        assertEquals("card-77", resolved.cardMessageId,
            "Resolved entry should retain cardMessageId from the pending approval.")
    }

    @Test
    fun `RESOLVE_APPROVAL - null payload returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pendingApproval()))

        val action = Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, null)
        val result = feature.reducer(initial, action)

        assertSame(initial, result)
    }

    @Test
    fun `RESOLVE_APPROVAL - missing approvalId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pendingApproval()))

        val action = Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
            put("resolution", "APPROVED")
            // approvalId omitted
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.pendingApprovals.size, "Missing approvalId should leave state unchanged.")
    }

    @Test
    fun `RESOLVE_APPROVAL - missing resolution returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pendingApproval()))

        val action = Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            // resolution omitted
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.pendingApprovals.size, "Missing resolution should leave state unchanged.")
    }

    @Test
    fun `RESOLVE_APPROVAL - invalid resolution string returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pendingApproval()))

        val action = Action(ActionRegistry.Names.COMMANDBOT_RESOLVE_APPROVAL, buildJsonObject {
            put("approvalId", "approval-1")
            put("resolution", "MAYBE")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.pendingApprovals.size,
            "Invalid resolution string should leave state unchanged.")
        assertTrue(result.resolvedApprovals.isEmpty(),
            "No resolved entry should be created for an invalid resolution.")
    }

    @Test
    fun `RESOLVE_APPROVAL - unknown approvalId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(pendingApprovals = mapOf("approval-1" to pendingApproval()))

        val result = feature.reducer(
            initial,
            resolveApprovalAction(approvalId = "approval-does-not-exist")
        ) as CommandBotState

        assertEquals(1, result.pendingApprovals.size,
            "Unknown approvalId should leave pending map intact.")
        assertTrue(result.resolvedApprovals.isEmpty(),
            "Unknown approvalId should not create a resolved entry.")
    }

    @Test
    fun `RESOLVE_APPROVAL - only targeted pending is resolved, others preserved`() {
        val feature = createFeature()
        val initial = CommandBotState(
            pendingApprovals = mapOf(
                "approval-A" to pendingApproval(approvalId = "approval-A", actionName = "session.CREATE"),
                "approval-B" to pendingApproval(approvalId = "approval-B", actionName = "session.DELETE")
            )
        )

        val result = feature.reducer(initial, resolveApprovalAction("approval-A", "APPROVED")) as CommandBotState

        assertEquals(1, result.pendingApprovals.size, "Only the targeted approval should be removed.")
        assertNotNull(result.pendingApprovals["approval-B"], "Untargeted approval should be preserved.")
        assertNull(result.pendingApprovals["approval-A"], "Targeted approval should be removed from pending.")
        assertEquals(1, result.resolvedApprovals.size)
        assertNotNull(result.resolvedApprovals["approval-A"])
    }

    @Test
    fun `RESOLVE_APPROVAL - preserves existing resolved approvals`() {
        val feature = createFeature()
        val initial = CommandBotState(
            pendingApprovals = mapOf("approval-2" to pendingApproval(approvalId = "approval-2")),
            resolvedApprovals = mapOf("approval-1" to resolvedApproval(approvalId = "approval-1"))
        )

        val result = feature.reducer(initial, resolveApprovalAction("approval-2", "DENIED")) as CommandBotState

        assertEquals(2, result.resolvedApprovals.size, "New resolved should be added alongside existing.")
        assertNotNull(result.resolvedApprovals["approval-1"])
        assertNotNull(result.resolvedApprovals["approval-2"])
    }

    // ========================================================================
    // SESSION_PUBLISH_MESSAGE_DELETED
    // ========================================================================

    @Test
    fun `MESSAGE_DELETED - matching cardMessageId removes from resolved`() {
        val feature = createFeature()
        val initial = CommandBotState(
            resolvedApprovals = mapOf("approval-1" to resolvedApproval(cardMessageId = "card-1"))
        )

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            put("messageId", "card-1")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.resolvedApprovals.isEmpty(),
            "Resolved approval should be cleaned up when its card message is deleted.")
    }

    @Test
    fun `MESSAGE_DELETED - non-matching messageId leaves state unchanged`() {
        val feature = createFeature()
        val resolved = resolvedApproval(cardMessageId = "card-1")
        val initial = CommandBotState(resolvedApprovals = mapOf("approval-1" to resolved))

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            put("messageId", "some-other-message")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.resolvedApprovals.size,
            "Non-matching messageId should leave resolved approvals intact.")
    }

    @Test
    fun `MESSAGE_DELETED - null messageId returns current state`() {
        val feature = createFeature()
        val initial = CommandBotState(
            resolvedApprovals = mapOf("approval-1" to resolvedApproval())
        )

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            // messageId omitted
        })
        val result = feature.reducer(initial, action)

        assertSame(initial, result, "Null messageId should return the same state instance.")
    }

    @Test
    fun `MESSAGE_DELETED - empty resolvedApprovals is safe no-op`() {
        val feature = createFeature()
        val initial = CommandBotState() // no resolved approvals

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            put("messageId", "card-1")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertTrue(result.resolvedApprovals.isEmpty())
        // Verify it returns the same instance (no unnecessary copy)
        assertSame(initial, result,
            "When no resolved approvals match, the exact same state instance should be returned.")
    }

    @Test
    fun `MESSAGE_DELETED - only the matching resolved is removed, others preserved`() {
        val feature = createFeature()
        val initial = CommandBotState(
            resolvedApprovals = mapOf(
                "approval-A" to resolvedApproval(approvalId = "approval-A", cardMessageId = "card-A"),
                "approval-B" to resolvedApproval(approvalId = "approval-B", cardMessageId = "card-B")
            )
        )

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            put("messageId", "card-A")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.resolvedApprovals.size)
        assertNull(result.resolvedApprovals["approval-A"], "Matching resolved should be removed.")
        assertNotNull(result.resolvedApprovals["approval-B"], "Non-matching resolved should be preserved.")
    }

    @Test
    fun `MESSAGE_DELETED - does not affect pendingApprovals`() {
        val feature = createFeature()
        val initial = CommandBotState(
            pendingApprovals = mapOf("pending-1" to pendingApproval(approvalId = "pending-1", cardMessageId = "card-1")),
            resolvedApprovals = mapOf("resolved-1" to resolvedApproval(approvalId = "resolved-1", cardMessageId = "card-1"))
        )

        val action = Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
            put("sessionId", "session-1")
            put("messageId", "card-1")
        })
        val result = feature.reducer(initial, action) as CommandBotState

        assertEquals(1, result.pendingApprovals.size,
            "MESSAGE_DELETED should never remove entries from pendingApprovals.")
        assertNotNull(result.pendingApprovals["pending-1"])
        assertTrue(result.resolvedApprovals.isEmpty(),
            "Only the matching resolved approval should be removed.")
    }
}