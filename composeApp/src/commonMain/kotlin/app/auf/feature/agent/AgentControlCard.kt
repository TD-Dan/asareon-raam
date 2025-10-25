package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentControlCard(
    agent: AgentInstance,
    store: Store,
    platformDependencies: app.auf.util.PlatformDependencies
) {
    var processingTime by remember { mutableStateOf("00:00") }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(agent.status, agent.processingSinceTimestamp) {
        if (agent.status == AgentStatus.PROCESSING && agent.processingSinceTimestamp != null) {
            while (true) {
                val elapsed = platformDependencies.getSystemTimeMillis() - agent.processingSinceTimestamp
                processingTime = elapsed.milliseconds.toComponents { minutes, seconds, _ ->
                    "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                }
                delay(1000)
            }
        }
    }

    val canInitiateTurn = (agent.status == AgentStatus.IDLE || agent.status == AgentStatus.WAITING || agent.status == AgentStatus.ERROR) && agent.primarySessionId != null && agent.isAgentActive

    val statusText = when (agent.status) {
        AgentStatus.PROCESSING -> {
            val step = agent.processingStep ?: "Processing..."
            "$step ($processingTime)"
        }
        else -> agent.status.name
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = "Agent Icon",
            modifier = Modifier.size(48.dp),
            tint = if (agent.isAgentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(agent.name, style = MaterialTheme.typography.titleMedium)
            Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium)
            if (agent.status == AgentStatus.ERROR && agent.errorMessage != null) {
                Text(
                    text = agent.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit Agent") },
                        onClick = {
                            store.dispatch("ui.controls", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.manager") }))
                            store.dispatch("ui.controls", Action(ActionNames.AGENT_SET_EDITING, buildJsonObject { put("agentId", agent.id) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Preview Turn") },
                        onClick = {
                            store.dispatch("ui.controls", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                                put("agentId", agent.id)
                                put("preview", true)
                            }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.Visibility, null) },
                        enabled = canInitiateTurn
                    )
                }
            }

            val activeSwitchTooltipState = remember { TooltipState() }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Toggle Active State") } },
                state = activeSwitchTooltipState
            ) {
                IconButton(
                    onClick = {
                        store.dispatch("ui.controls", Action(ActionNames.AGENT_TOGGLE_ACTIVE, buildJsonObject { put("agentId", agent.id) }))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Toggle Active State",
                        tint = if (agent.isAgentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            val autoModeSwitchTooltipState = remember { TooltipState() }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Toggle Automatic Mode") } },
                state = autoModeSwitchTooltipState
            ) {
                IconButton(
                    onClick = {
                        store.dispatch("ui.controls", Action(ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", agent.id) }))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = "Toggle Automatic Mode",
                        tint = if (agent.automaticMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            if (agent.status == AgentStatus.PROCESSING) {
                Button(
                    onClick = { store.dispatch("ui.controls", Action(ActionNames.AGENT_CANCEL_TURN, buildJsonObject { put("agentId", agent.id) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = "Cancel Turn")
                }
            } else {
                Button(
                    onClick = { store.dispatch("ui.controls", Action(ActionNames.AGENT_INITIATE_TURN, buildJsonObject {
                        put("agentId", agent.id)
                        put("preview", false) // Direct execution
                    })) },
                    enabled = canInitiateTurn
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Trigger Turn")
                }
            }
        }
    }
}