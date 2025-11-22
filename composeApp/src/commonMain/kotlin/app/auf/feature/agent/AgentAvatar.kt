package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * ## Mandate
 * To provide the Composable UI for an agent's "avatar card" and the pure, testable
 * logic for managing its presence within the public session ledger.
 */

// --- Logic ---

object AgentAvatarLogic {
    fun touchAgentAvatarCard(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val cardInfo = agentState.agentAvatarCardIds[agent.id] ?: return
        store.dispatch("agent", Action(
            name = ActionNames.SESSION_UPDATE_MESSAGE,
            payload = buildJsonObject {
                put("session", cardInfo.sessionId)
                put("messageId", cardInfo.messageId)
            }
        ))
    }

    fun updateAgentAvatarCard(agentId: String, status: AgentStatus, error: String? = null, store: Store) {
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val platformDependencies = store.platformDependencies

        // Determine the session to post in. A sovereign agent's avatar is in its private session.
        val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()

        // 1. Atomically delete the old card
        agentState.agentAvatarCardIds[agentId]?.let { oldCardInfo ->
            store.dispatch("agent", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                put("session", oldCardInfo.sessionId); put("messageId", oldCardInfo.messageId)
            }))
        }

        // 2. Set the agent's new status
        store.dispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
            put("agentId", agentId); put("status", status.name); error?.let { put("error", it) }
        }))

        // 3. If there's no session to post to, we're done.
        if (targetSessionId == null) return

        // 4. Re-fetch the state *after* the status change to get the latest frontiers.
        val latestAgentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val latestStatus = latestAgentState.agentStatuses[agentId] ?: AgentStatusInfo()

        // 5. Determine where in the ledger the new card should be placed.
        val afterMessageId = when (status) {
            AgentStatus.PROCESSING -> latestStatus.processingFrontierMessageId
            else -> latestStatus.lastSeenMessageId
        }

        // 6. Post the new card.
        val messageId = platformDependencies.generateUUID()
        val metadata = buildJsonObject {
            put("render_as_partial", true); put("is_transient", true); put("agentStatus", status.name)
            error?.let { put("errorMessage", it) }
        }
        store.dispatch("agent", Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", targetSessionId); put("senderId", agentId); put("messageId", messageId)
            put("metadata", metadata); afterMessageId?.let { put("afterMessageId", it) }
        }))
    }
}


// --- Composables ---

@Composable
fun AgentAvatarCard(
    agent: AgentInstance,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    val appState by store.state.collectAsState()
    val agentState = appState.featureStates["agent"] as? AgentRuntimeState
    // REF: Slice 3 - Resolve status
    val statusInfo = agentState?.agentStatuses?.get(agent.id) ?: AgentStatusInfo()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        AgentControlCard(agent, statusInfo, store, platformDependencies)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentControlCard(
    agent: AgentInstance,
    statusInfo: AgentStatusInfo = AgentStatusInfo(),
    store: Store,
    platformDependencies: PlatformDependencies
) {
    var processingTime by remember { mutableStateOf("00:00") }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(statusInfo.status, statusInfo.processingSinceTimestamp) {
        if (statusInfo.status == AgentStatus.PROCESSING && statusInfo.processingSinceTimestamp != null) {
            while (true) {
                val elapsed = platformDependencies.getSystemTimeMillis() - statusInfo.processingSinceTimestamp
                processingTime = elapsed.milliseconds.toComponents { minutes, seconds, _ ->
                    "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                }
                delay(1000)
            }
        }
    }

    val canInitiateTurn = (statusInfo.status == AgentStatus.IDLE || statusInfo.status == AgentStatus.WAITING || statusInfo.status == AgentStatus.ERROR) && (agent.subscribedSessionIds.isNotEmpty() || agent.privateSessionId != null) && agent.isAgentActive

    val statusText = when (statusInfo.status) {
        AgentStatus.PROCESSING -> {
            val step = statusInfo.processingStep ?: "Processing..."
            "$step ($processingTime)"
        }
        else -> statusInfo.status.name
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
            if (statusInfo.status == AgentStatus.ERROR && statusInfo.errorMessage != null) {
                Text(
                    text = statusInfo.errorMessage,
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

            if (statusInfo.status == AgentStatus.PROCESSING) {
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