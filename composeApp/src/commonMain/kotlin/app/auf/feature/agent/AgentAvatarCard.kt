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
import app.auf.core.Action
import app.auf.core.Store
import app.auf.feature.session.Session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun AgentAvatarCard(agent: AgentInstance, session: Session, store: Store) {
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
                Text(agent.name, style = MaterialTheme.typography.titleMedium)
                Text("Status: ${agent.status}", style = MaterialTheme.typography.bodyMedium)
                if (agent.status == AgentStatus.ERROR && agent.errorMessage != null) {
                    Text(
                        text = agent.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Right side: Action Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (agent.status == AgentStatus.PROCESSING) {
                    // Show Cancel button when processing
                    Button(
                        onClick = {
                            val payload = buildJsonObject { put("agentId", agent.id) }
                            store.dispatch("ui.agentAvatar", Action("agent.CANCEL_TURN", payload))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel Turn")
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                } else {
                    // Show Trigger button otherwise
                    Button(
                        onClick = {
                            val payload = buildJsonObject {
                                put("agentId", agent.id)
                                put("lastMessage", session.ledger.lastOrNull()?.rawContent ?: "")
                            }
                            store.dispatch("ui.agentAvatar", Action("agent.TRIGGER_MANUAL_TURN", payload))
                        },
                        enabled = (agent.status == AgentStatus.IDLE || agent.status == AgentStatus.ERROR) && session.ledger.isNotEmpty()
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