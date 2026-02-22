package app.auf.feature.agent

import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * ## Mandate
 * This file acts as a centralized repository for all internal, serializable data classes
 * used by the AgentRuntimeFeature for decoding action and envelope payloads.
 * This separates data contracts from the feature's behavioral logic.
 *
 * [PHASE 6] Added `correlationId` to all command-dispatchable payload classes.
 * When an action is dispatched via `auf_` (by a user or agent), CommandBot sets
 * `correlationId` so the resulting `agent.ACTION_RESULT` broadcast can be
 * correlated back to the originating session.
 */

// =============================================================================
// External-event payloads (session/identity/gateway/ledger envelopes)
// =============================================================================

@Serializable internal data class SessionNamesPayload(val names: Map<IdentityHandle, String>)
@Serializable internal data class GraphNamesPayload(val names: Map<String, String>)
@Serializable internal data class ReservedIdsPayload(val reservedIds: Set<String>)
@Serializable internal data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null, val inputTokens: Int? = null, val outputTokens: Int? = null)
@Serializable internal data class LedgerResponsePayload(val correlationId: String, val messages: List<JsonObject>) // Generic JsonObject representing LedgerEntry
@Serializable internal data class MessagePostedPayload(val sessionId: IdentityHandle, val entry: JsonObject)
@Serializable internal data class MessageDeletedPayload(val sessionId: IdentityHandle, val messageId: String)
@Serializable internal data class IdentitiesUpdatedPayload(val identities: List<Identity>, val activeId: String?)

// =============================================================================
// Command-dispatchable payloads — these carry `correlationId` for ACTION_RESULT
// =============================================================================

/**
 * Shared payload shape for the many agent actions that only require an `agentId`.
 * Covers: CLONE, DELETE, TOGGLE_AUTOMATIC_MODE, TOGGLE_ACTIVE,
 * EXECUTE_PREVIEWED_TURN, DISCARD_PREVIEW, CANCEL_TURN.
 *
 * [PHASE 6] Added `correlationId`.
 */
@Serializable internal data class AgentIdPayload(
    val agentId: IdentityUUID,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
@Serializable internal data class InitiateTurnPayload(
    val agentId: IdentityUUID,
    val preview: Boolean = false,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
@Serializable internal data class UpdateNvramPayload(
    val agentId: IdentityUUID,
    val updates: JsonObject,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
@Serializable internal data class SaveResourcePayload(
    val resourceId: String,
    val content: String,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
@Serializable internal data class CreateResourcePayload(
    val name: String,
    val type: String,
    val initialContent: String? = null,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
@Serializable internal data class RenameResourcePayload(
    val resourceId: String,
    val newName: String,
    val correlationId: String? = null
)

/** [PHASE 6] `correlationId` added. */
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
@Serializable internal data class AvatarMovedPayload(val agentId: IdentityUUID, val sessionId: IdentityHandle, val messageId: String)

// =============================================================================
// Pending Command Tracking payloads
// =============================================================================

@Serializable internal data class RegisterPendingCommandPayload(
    val correlationId: String,
    val agentId: IdentityUUID,
    val agentName: String,
    val sessionId: IdentityHandle,
    val actionName: String
)
@Serializable internal data class ClearPendingCommandPayload(val correlationId: String)