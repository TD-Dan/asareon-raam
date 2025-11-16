package app.auf.feature.agent

import app.auf.core.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * ## Mandate
 * This file acts as a centralized repository for all internal, serializable data classes
 * used by the AgentRuntimeFeature for decoding action and envelope payloads.
 * This separates data contracts from the feature's behavioral logic.
 */

@Serializable internal data class SessionNamesPayload(val names: Map<String, String>)
@Serializable internal data class GraphNamesPayload(val names: Map<String, String>)
@Serializable internal data class ReservedIdsPayload(val reservedIds: Set<String>)
@Serializable internal data class GatewayResponsePayload(val correlationId: String, val rawContent: String? = null, val errorMessage: String? = null)
@Serializable internal data class LedgerResponsePayload(val correlationId: String, val messages: List<JsonObject>) // Generic JsonObject representing LedgerEntry
@Serializable internal data class MessagePostedPayload(val sessionId: String, val entry: JsonObject)
@Serializable internal data class MessageDeletedPayload(val sessionId: String, val messageId: String)
@Serializable internal data class InitiateTurnPayload(val agentId: String, val preview: Boolean = false)
@Serializable internal data class SetPreviewDataPayload(val agentId: String, val agnosticRequest: GatewayRequest, val rawRequestJson: String)
@Serializable internal data class GatewayPreviewResponsePayload(val correlationId: String, val agnosticRequest: GatewayRequest, val rawRequestJson: String)
@Serializable internal data class IdentitiesUpdatedPayload(val identities: List<Identity>, val activeId: String?)
@Serializable internal data class StageTurnContextPayload(val agentId: String, val messages: List<GatewayMessage>)
@Serializable internal data class SetHkgContextPayload(val agentId: String, val context: JsonObject)