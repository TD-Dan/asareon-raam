package app.auf.feature.commandbot

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders an approval card for agent actions that require user confirmation.
 *
 * This composable is invoked by SessionView's generalized PartialView routing
 * when a LedgerEntry has metadata:
 *   partial_view_feature = "commandbot"
 *   partial_view_key = "commandbot.approval"
 *
 * The approvalId is extracted from the entry's senderId field (which is set to
 * the approval ID when the card is posted).
 */
@Composable
fun ApprovalCard(store: Store, approvalId: String) {
    val appState by store.state.collectAsState()
    val commandBotState = appState.featureStates["commandbot"] as? CommandBotState

    val pendingApproval = commandBotState?.pendingApprovals?.get(approvalId)
    val resolvedApproval = commandBotState?.resolvedApprovals?.get(approvalId)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = when {
                resolvedApproval?.resolution == Resolution.APPROVED ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                resolvedApproval?.resolution == Resolution.DENIED ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when {
                // Resolved state — show static result with dismiss button
                resolvedApproval != null -> {
                    ResolvedApprovalContent(resolvedApproval, store)
                }
                // Pending state — show action details + buttons
                pendingApproval != null -> {
                    PendingApprovalContent(pendingApproval, store)
                }
                // Orphaned card — approval was lost (e.g., app restart)
                else -> {
                    OrphanedApprovalContent(approvalId, store)
                }
            }
        }
    }
}

@Composable
private fun PendingApprovalContent(approval: PendingApproval, store: Store) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = "Approval Required",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = "Approval Required",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Spacer(Modifier.height(8.dp))

    Text(
        text = "${approval.requestingAgentName} wants to execute:",
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(Modifier.height(4.dp))

    // Action name badge
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = approval.actionName,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }

    Spacer(Modifier.height(8.dp))

    // Payload display (compact JSON)
    val payloadText = approval.payload.toString()
    if (payloadText != "{}") {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = payloadText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(8.dp),
                maxLines = 6
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = {
                store.dispatch("commandbot.ui", Action(
                    ActionRegistry.Names.COMMANDBOT_DENY,
                    buildJsonObject { put("approvalId", approval.approvalId) }
                ))
            },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Deny")
        }

        Spacer(Modifier.width(8.dp))

        Button(
            onClick = {
                store.dispatch("commandbot.ui", Action(
                    ActionRegistry.Names.COMMANDBOT_APPROVE,
                    buildJsonObject { put("approvalId", approval.approvalId) }
                ))
            }
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Approve")
        }
    }
}

@Composable
private fun ResolvedApprovalContent(resolution: ApprovalResolution, store: Store) {
    val (icon, text, color) = when (resolution.resolution) {
        Resolution.APPROVED -> Triple("✅", "Approved", MaterialTheme.colorScheme.primary)
        Resolution.DENIED -> Triple("❌", "Denied", MaterialTheme.colorScheme.error)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(icon)
            Column {
                Text(
                    text = "$text: ${resolution.actionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
                Text(
                    text = "Requested by ${resolution.requestingAgentName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Dismiss button — removes the card entry from the session ledger
        TextButton(
            onClick = {
                store.dispatch("commandbot.ui", Action(
                    ActionRegistry.Names.SESSION_DELETE_MESSAGE,
                    buildJsonObject {
                        put("session", resolution.sessionId)
                        put("messageId", resolution.cardMessageId)
                    }
                ))
            }
        ) {
            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OrphanedApprovalContent(approvalId: String, store: Store) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text("⏳")
            Text(
                text = "Approval request expired (app was restarted).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}