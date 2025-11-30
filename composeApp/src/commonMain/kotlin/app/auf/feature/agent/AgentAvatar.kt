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
import app.auf.util.LogLevel
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
        val sessionMap = agentState.agentAvatarCardIds[agent.id] ?: return
        sessionMap.forEach { (sessionId, messageId) ->
            store.deferredDispatch("agent", Action(
                name = ActionNames.SESSION_UPDATE_MESSAGE,
                payload = buildJsonObject {
                    put("session", sessionId)
                    put("messageId", messageId)
                }
            ))
        }
    }

    /**
     * Reconciles the agent's visual presence in the ledger with its current configuration and state.
     * Uses the "Sovereign Avatar" pattern: Commit intention to state FIRST, then execute side effects.
     */
    fun updateAgentAvatars(
        agentId: String,
        store: Store,
        newStatus: AgentStatus? = null,
        newError: String? = null
    ) {
        // 1. Dispatch Status Change (if requested)
        if (newStatus != null) {
            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
                put("agentId", agentId)
                put("status", newStatus.name)
                newError?.let { put("error", it) }
            }))
        }

        // 2. Fetch Latest State (Needed to get updated frontiers/status)
        val appState = store.state.value
        val agentState = appState.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val statusInfo = agentState.agentStatuses[agentId] ?: AgentStatusInfo()

        // 3. Determine Target Sessions
        val targetSessions = (agent.subscribedSessionIds + listOfNotNull(agent.privateSessionId)).distinct()

        // 4. Determine Position
        val afterMessageId = when (statusInfo.status) {
            AgentStatus.PROCESSING -> statusInfo.processingFrontierMessageId
            else -> statusInfo.lastSeenMessageId
        }

        val platformDependencies = store.platformDependencies
        val currentCards = agentState.agentAvatarCardIds[agentId] ?: emptyMap()

        // DIAGNOSTIC LOG
        platformDependencies.log(
            LogLevel.INFO, "agent-avatar",
            "Updating avatars for ${agent.name}. Targets: $targetSessions. CurrentCards: $currentCards. AfterMsg: $afterMessageId")

        // 5. Cleanup Zombies (Sessions we are no longer subscribed to)
        val zombies = currentCards.keys - targetSessions.toSet()
        zombies.forEach { sessionId ->
            val messageId = currentCards[sessionId]
            if (messageId != null) {
                // [CLEANUP] We don't need to explicitly clean state here; the Reducer reacts to SESSION_DELETE_MESSAGE
                // We just send the command to the ledger.
                store.deferredDispatch("agent", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                    put("session", sessionId)
                    put("messageId", messageId)
                }))
            }
        }

        // 6. Update/Post Target Sessions
        targetSessions.forEach { sessionId ->
            val oldMessageId = currentCards[sessionId]

            // A. Generate New ID and Commit Intention (Sovereign Update)
            val newMessageId = platformDependencies.generateUUID()

            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_AVATAR_MOVED, buildJsonObject {
                put("agentId", agentId)
                put("sessionId", sessionId)
                put("messageId", newMessageId)
            }))

            // B. Delete Old (if exists)
            if (oldMessageId != null) {
                store.deferredDispatch("agent", Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                    put("session", sessionId)
                    put("messageId", oldMessageId)
                }))
            }

            // C. Post New Card (Side Effect)
            val metadata = buildJsonObject {
                put("render_as_partial", true)
                put("is_transient", true)
                put("agentStatus", statusInfo.status.name)
                statusInfo.errorMessage?.let { put("errorMessage", it) }
            }

            store.deferredDispatch("agent", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", sessionId)
                put("senderId", agentId)
                put("messageId", newMessageId)
                put("metadata", metadata)
                afterMessageId?.let { put("afterMessageId", it) }
            }))
        }
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