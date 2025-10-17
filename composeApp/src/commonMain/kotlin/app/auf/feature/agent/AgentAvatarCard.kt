package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stateless, presentational composable for displaying an agent's status and actions.
 * It receives all data as parameters and uses callbacks for user interactions.
 */
@Composable
fun AgentAvatarCard(
    agentName: String,
    agentStatus: AgentStatus,
    errorMessage: String?,
    onTrigger: () -> Unit,
    onCancel: () -> Unit,
    canTrigger: Boolean,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: Agent Info
            Column(modifier = Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium)
                Text("Status: $agentStatus", style = MaterialTheme.typography.bodyMedium)
                if (agentStatus == AgentStatus.ERROR && errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Right side: Action Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (agentStatus == AgentStatus.PROCESSING || agentStatus == AgentStatus.WAITING) {
                    // Show Cancel button when busy
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel Turn")
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                } else {
                    // Show Trigger button otherwise
                    Button(
                        onClick = onTrigger,
                        enabled = canTrigger
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Trigger Turn")
                        Spacer(Modifier.width(4.dp))
                        Text("Trigger")
                    }
                }
            }
        }
    }
}