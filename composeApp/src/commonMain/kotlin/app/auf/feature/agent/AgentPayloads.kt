package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.core.IdentityUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * ## Mandate
 * Centralized repository for all internal, serializable data classes used by
 * the AgentRuntimeFeature for decoding action and envelope payloads.
 * Separates data contracts from the feature's behavioral logic.
 *
 * Command-dispatchable payloads carry `correlationId` so the resulting
 * `agent.ACTION_RESULT` broadcast can be correlated back to the originating session.
 */

// =============================================================================
// External-event payloads (session/identity/gateway/ledger envelopes)
// =============================================================================

// SessionNamesPayload removed — SESSION_NAMES_UPDATED now sends a richer
// "sessions" array parsed directly in the reducer.

@Serializable internal data class GraphNamesPayload(val names: Map<String, String>)
@Serializable internal data class ReservedIdsPayload(val reservedIds: Set<String>)

/**
 * Decodes the GATEWAY_RETURN_RESPONSE payload.
 *
 * [rateLimitInfo] is the raw JSON object from the gateway's RateLimitInfo serialization.
 * The agent feature reads specific fields (e.g., retryAfterMs) without importing the
 * gateway feature's data classes, preserving inter-feature decoupling.
 */
@Serializable internal data class GatewayResponsePayload(
    val correlationId: String,
    val rawContent: String? = null,
    val errorMessage: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val rateLimitInfo: JsonObject? = null
)

@Serializable internal data class LedgerResponsePayload(val correlationId: String, val messages: List<JsonObject>) // Generic JsonObject representing LedgerEntry

/**
 * Payload from session.MESSAGE_POSTED broadcast.
 * [sessionId] is the session localHandle (always present, legacy).
 * [sessionUUID] is the session's immutable UUID (preferred for matching).
 */
@Serializable internal data class MessagePostedPayload(
    val sessionId: String,
    val sessionUUID: String? = null,
    val entry: JsonObject
)

/**
 * Payload from session.MESSAGE_DELETED broadcast.
 * [sessionId] is the session localHandle (always present, legacy).
 * [sessionUUID] is the session's immutable UUID (preferred for matching).
 */
@Serializable internal data class MessageDeletedPayload(
    val sessionId: String,
    val sessionUUID: String? = null,
    val messageId: String
)

@Serializable internal data class IdentitiesUpdatedPayload(val identities: List<Identity>, val activeId: String?)

// =============================================================================
// Command-dispatchable payloads — these carry `correlationId` for ACTION_RESULT
// =============================================================================

/**
 * Shared payload shape for the many agent actions that only require an `agentId`.
 * Covers: CLONE, DELETE, TOGGLE_AUTOMATIC_MODE, TOGGLE_ACTIVE,
 * EXECUTE_PREVIEWED_TURN, DISCARD_PREVIEW, CANCEL_TURN.
 */
@Serializable internal data class AgentIdPayload(
    val agentId: IdentityUUID,
    val correlationId: String? = null
)

@Serializable internal data class InitiateTurnPayload(
    val agentId: IdentityUUID,
    val preview: Boolean = false,
    val correlationId: String? = null
)
@Serializable internal data class UpdateNvramPayload(
    val agentId: IdentityUUID,
    val updates: JsonObject,
    val correlationId: String? = null
)
@Serializable internal data class SaveResourcePayload(
    val resourceId: String,
    val content: String,
    val correlationId: String? = null
)
@Serializable internal data class CreateResourcePayload(
    val name: String,
    val type: String,
    val initialContent: String? = null,
    val correlationId: String? = null
)
@Serializable internal data class RenameResourcePayload(
    val resourceId: String,
    val newName: String,
    val correlationId: String? = null
)
@Serializable internal data class DeleteResourcePayload(
    val resourceId: String,
    val correlationId: String? = null
)

// =============================================================================
// Internal / pipeline payloads (not command-dispatchable — no correlationId)
// =============================================================================

@Serializable internal data class SetPreviewDataPayload(val agentId: IdentityUUID, val agnosticRequest: GatewayRequest, val rawRequestJson: String, val estimatedInputTokens: Int? = null)
@Serializable internal data class GatewayPreviewResponsePayload(val correlationId: String, val agnosticRequest: GatewayRequest, val rawRequestJson: String, val estimatedInputTokens: Int? = null)
@Serializable internal data class StageTurnContextPayload(val agentId: IdentityUUID, val messages: List<GatewayMessage>)
@Serializable internal data class SetHkgContextPayload(val agentId: IdentityUUID, val context: JsonObject)
@Serializable internal data class AvatarMovedPayload(val agentId: IdentityUUID, val sessionId: IdentityUUID, val messageId: String)

// =============================================================================
// Pending Command Tracking payloads
// =============================================================================

@Serializable internal data class RegisterPendingCommandPayload(
    val correlationId: String,
    val agentId: IdentityUUID,
    val agentName: String,
    val sessionId: IdentityUUID,
    val actionName: String
)
@Serializable internal data class ClearPendingCommandPayload(val correlationId: String)