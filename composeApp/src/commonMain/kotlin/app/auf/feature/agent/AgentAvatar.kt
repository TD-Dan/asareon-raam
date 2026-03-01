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
import app.auf.core.IdentityUUID
import app.auf.core.Store
import app.auf.core.findByUUID
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * ## Mandate
 * Provides the Composable UI for an agent's "avatar card" and the pure, testable
 * logic for managing its presence within the public session ledger.
 *
 * All session references are [IdentityUUID]. Handles are resolved from the
 * identity registry at dispatch time for cross-feature session actions.
 */

// --- Logic ---

object AgentAvatarLogic {

    fun touchAgentAvatarCard(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {
        val agentUuid = agent.identityUUID
        val sessionMap = agentState.agentAvatarCardIds[agentUuid] ?: return
        val registry = store.state.value.identityRegistry
        sessionMap.forEach { (sessionUUID, messageId) ->
            val sessionHandle = registry.findByUUID(sessionUUID)?.handle ?: return@forEach
            store.deferredDispatch("agent", Action(
                name = ActionRegistry.Names.SESSION_UPDATE_MESSAGE,
                payload = buildJsonObject {
                    put("session", sessionHandle)
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
        agentId: IdentityUUID,
        store: Store,
        newStatus: AgentStatus? = null,
        newError: String? = null
    ) {
        // 1. Dispatch Status Change (if requested)
        if (newStatus != null) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
                put("agentId", agentId.uuid)
                put("status", newStatus.name)
                newError?.let { put("error", it) }
            }))
        }

        // 2. Fetch Latest State (Needed to get updated frontiers/status)
        val appState = store.state.value
        val agentState = appState.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = agentState.agents[agentId] ?: return
        val statusInfo = agentState.agentStatuses[agentId] ?: AgentStatusInfo()
        val registry = appState.identityRegistry

        // 3. Determine Target Sessions
        val targetSessions = (agent.subscribedSessionIds + listOfNotNull(agent.outputSessionId)).distinct()

        // 4. Determine Position
        val afterMessageId = when (statusInfo.status) {
            AgentStatus.PROCESSING -> statusInfo.processingFrontierMessageId
            else -> statusInfo.lastSeenMessageId
        }

        val platformDependencies = store.platformDependencies
        val currentCards = agentState.agentAvatarCardIds[agentId] ?: emptyMap()

        // DIAGNOSTIC LOG
        platformDependencies.log(
            LogLevel.DEBUG, "agent-avatar",
            "Updating avatars for ${agent.identity.name}. Targets: $targetSessions. CurrentCards: $currentCards. AfterMsg: $afterMessageId")

        // 5. Cleanup Zombies (Sessions we are no longer subscribed to)
        val zombies = currentCards.keys - targetSessions.toSet()
        zombies.forEach { sessionUUID ->
            val messageId = currentCards[sessionUUID]
            val sessionHandle = registry.findByUUID(sessionUUID)?.handle
            if (messageId != null && sessionHandle != null) {
                store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                    put("session", sessionHandle)
                    put("messageId", messageId)
                }))
            }
        }

        // 6. Update/Post Target Sessions
        targetSessions.forEach { sessionUUID ->
            val sessionHandle = registry.findByUUID(sessionUUID)?.handle ?: run {
                platformDependencies.log(LogLevel.WARN, "agent-avatar",
                    "Session UUID '$sessionUUID' not in registry for agent '${agent.identity.name}'. Skipping avatar.")
                return@forEach
            }
            val oldMessageId = currentCards[sessionUUID]

            // A. Generate New ID and Commit Intention (Sovereign Update)
            val newMessageId = platformDependencies.generateUUID()

            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_AVATAR_MOVED, buildJsonObject {
                put("agentId", agentId.uuid)
                put("sessionId", sessionUUID.uuid)
                put("messageId", newMessageId)
            }))

            // B. Delete Old (if exists)
            if (oldMessageId != null) {
                store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_DELETE_MESSAGE, buildJsonObject {
                    put("session", sessionHandle)
                    put("messageId", oldMessageId)
                }))
            }

            // C. Post New Card (Side Effect)
            val metadata = buildJsonObject {
                put("render_as_partial", true)
                put("is_transient", true)
                put("partial_view_feature", "agent")
                put("partial_view_key", "agent.avatar")
                put("agentStatus", statusInfo.status.name)
                statusInfo.errorMessage?.let { put("errorMessage", it) }
            }

            store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", sessionHandle)
                put("senderId", agent.identity.handle)
                put("messageId", newMessageId)
                put("metadata", metadata)
                put("doNotClear", true)
                afterMessageId?.let { put("afterMessageId", it) }
            }))
        }
    }
}

// --- Helpers ---

/**
 * Formats a token count for display. Examples: "1,234", "12,345".
 */
private fun formatTokenCount(count: Int): String {
    return count.toString().reversed().chunked(3).joinToString(",").reversed()
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
    val statusInfo = agentState?.agentStatuses?.get(agent.identityUUID) ?: AgentStatusInfo()

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
                val elapsed = platformDependencies.currentTimeMillis() - statusInfo.processingSinceTimestamp
                processingTime = elapsed.milliseconds.toComponents { minutes, seconds, _ ->
                    "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
                }
                delay(1000)
            }
        }
    }

    val canInitiateTurn = (statusInfo.status == AgentStatus.IDLE || statusInfo.status == AgentStatus.WAITING || statusInfo.status == AgentStatus.ERROR) && (agent.subscribedSessionIds.isNotEmpty() || agent.outputSessionId != null) && agent.isAgentActive

    val statusText = when (statusInfo.status) {
        AgentStatus.PROCESSING -> {
            val step = statusInfo.processingStep ?: "Processing..."
            "$step ($processingTime)"
        }
        else -> statusInfo.status.name
    }

    val agentUuidStr = agent.identityUUID.uuid

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
            Text(agent.identity.name, style = MaterialTheme.typography.titleMedium)
            Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium)
            if (statusInfo.status == AgentStatus.ERROR && statusInfo.errorMessage != null) {
                Text(
                    text = statusInfo.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Display last request token usage if available
            val lastInput = statusInfo.lastInputTokens
            val lastOutput = statusInfo.lastOutputTokens
            if (lastInput != null || lastOutput != null) {
                Text(
                    text = buildString {
                        append("Last request: ")
                        if (lastInput != null) append("${formatTokenCount(lastInput)} input")
                        if (lastInput != null && lastOutput != null) append(", ")
                        if (lastOutput != null) append("${formatTokenCount(lastOutput)} output")
                        append(" tokens")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
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
                            store.dispatch("ui.controls", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.manager") }))
                            store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_SET_EDITING, buildJsonObject { put("agentId", agentUuidStr) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Preview Turn") },
                        onClick = {
                            store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                                put("agentId", agentUuidStr)
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
                        store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_TOGGLE_ACTIVE, buildJsonObject { put("agentId", agentUuidStr) }))
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
                        store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", agentUuidStr) }))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = "Toggle Automatic Mode",
                        tint = if (agent.automaticMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }

            if (statusInfo.status == AgentStatus.PROCESSING) {
                Button(
                    onClick = { store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_CANCEL_TURN, buildJsonObject { put("agentId", agentUuidStr) })) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = "Cancel Turn")
                }
            } else {
                Button(
                    onClick = { store.dispatch("ui.controls", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                        put("agentId", agentUuidStr)
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