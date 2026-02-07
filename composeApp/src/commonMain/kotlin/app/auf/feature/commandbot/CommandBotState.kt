package app.auf.feature.commandbot

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * State model for CommandBot. Transitions it from a headless/stateless observer
 * to a stateful feature that can manage pending approval requests.
 */
@Serializable
data class CommandBotState(
    /**
     * Pending approval requests, keyed by a generated approval ID.
     * Each entry holds everything needed to dispatch the action if approved.
     */
    val pendingApprovals: Map<String, PendingApproval> = emptyMap(),

    /**
     * Resolved approvals, keyed by approval ID.
     * Kept for display purposes so the card can show "Approved" / "Denied"
     * after the PendingApproval has been consumed.
     * Cleaned up when the card's ledger entry is deleted or the session is cleared.
     */
    val resolvedApprovals: Map<String, ApprovalResolution> = emptyMap()
) : FeatureState

@Serializable
data class PendingApproval(
    val approvalId: String,
    /** The session where the command originated (and where the card is posted). */
    val sessionId: String,
    /** The ledger entry ID of the approval card in the session. */
    val cardMessageId: String,
    /** The agent that requested the action. */
    val requestingAgentId: String,
    /** Human-readable agent name (for display in the card). */
    val requestingAgentName: String,
    /** The fully-formed action name to dispatch if approved. */
    val actionName: String,
    /** The final payload to dispatch (after any sandbox rewrites). */
    val payload: JsonObject,
    /** The originator string to use when dispatching. */
    val dispatchOriginator: String,
    /** Timestamp of the request. */
    val requestedAt: Long
)

@Serializable
data class ApprovalResolution(
    val approvalId: String,
    val actionName: String,
    val requestingAgentName: String,
    val resolution: Resolution,
    val resolvedAt: Long,
    /** Retained from PendingApproval for dismiss/cleanup routing. */
    val sessionId: String,
    /** Retained from PendingApproval so the card entry can be deleted on dismiss. */
    val cardMessageId: String
)

@Serializable
enum class Resolution { APPROVED, DENIED }