package app.auf.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.Store
import app.auf.core.findByUUID
import app.auf.core.generated.ActionRegistry
import app.auf.core.resolveDisplayColor
import app.auf.ui.components.CodeEditor
import app.auf.ui.components.IconRegistry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        agentRuntimeState: AgentRuntimeState,
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

        // 2. Use the caller-provided post-reducer state to avoid stale reads from store.state.value
        val agentState = agentRuntimeState
        val agent = agentState.agents[agentId] ?: return
        val statusInfo = agentState.agentStatuses[agentId] ?: AgentStatusInfo()
        val registry = store.state.value.identityRegistry

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

private fun formatTokenCount(count: Int): String {
    return count.toString().reversed().chunked(3).joinToString(",").reversed()
}

private val prettyJson = Json { prettyPrint = true }

// --- Composables ---

@Composable
fun AgentAvatarCard(
    agent: AgentInstance,
    sessionUUID: String? = null,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    val appState by store.state.collectAsState()
    val agentState = appState.featureStates["agent"] as? AgentRuntimeState ?: return

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        AgentControlCard(
            agent = agent,
            agentState = agentState,
            sessionUUID = sessionUUID,
            store = store,
            platformDependencies = platformDependencies
        )
    }
}

/**
 * Shared core composable for agent cards. Used by both [AgentAvatarCard] (session embed)
 * and the Agent Manager card. Renders:
 * - Header: bolt icon + name + status
 * - Runtime controls: kebab menu, power, auto-mode, trigger/cancel
 * - Extended info drawer (subscriptions, model, strategy) — eye-toggled
 * - NVRAM drawer — toggled from kebab menu, local state
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentControlCard(
    agent: AgentInstance,
    agentState: AgentRuntimeState,
    sessionUUID: String? = null,
    store: Store,
    platformDependencies: PlatformDependencies,
    /** Externally controlled extended info visibility (for manager global toggle). Null = local state. */
    showExtendedInfoOverride: Boolean? = null,
    /** When true, kebab shows Clone/Delete instead of Preview Turn/Remove from session. */
    showManagementActions: Boolean = false,
    /** Called when the user requests edit mode (kebab or double-click). */
    onEditRequest: (() -> Unit)? = null,
    /** Called when the user requests cloning. Only used when showManagementActions = true. */
    onCloneRequest: (() -> Unit)? = null,
    /** Called when the user requests deletion. Only used when showManagementActions = true. */
    onDeleteRequest: (() -> Unit)? = null
) {
    val identityRegistry = store.state.collectAsState().value.identityRegistry
    val statusInfo = agentState.agentStatuses[agent.identityUUID] ?: AgentStatusInfo()

    var processingTime by remember { mutableStateOf("00:00") }
    var rateLimitCountdown by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var localShowNvram by remember { mutableStateOf(false) }

    // Extended info: local state, initialized from global override.
    // Individual eye toggle always works. Global toggle resets all cards.
    var localShowExtendedInfo by remember { mutableStateOf(showExtendedInfoOverride ?: false) }
    LaunchedEffect(showExtendedInfoOverride) {
        if (showExtendedInfoOverride != null) localShowExtendedInfo = showExtendedInfoOverride
    }
    val showExtendedInfo = localShowExtendedInfo
    val isViewingNvram = localShowNvram

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

    LaunchedEffect(statusInfo.status, statusInfo.rateLimitedUntilMs) {
        if (statusInfo.status == AgentStatus.RATE_LIMITED && statusInfo.rateLimitedUntilMs != null) {
            while (true) {
                val remainingMs = statusInfo.rateLimitedUntilMs - platformDependencies.currentTimeMillis()
                if (remainingMs <= 0) {
                    rateLimitCountdown = "resuming..."
                    break
                }
                val remainingSec = (remainingMs / 1000).toInt()
                rateLimitCountdown = "${remainingSec}s"
                delay(1000)
            }
        } else {
            rateLimitCountdown = ""
        }
    }

    val canInitiateTurn = (statusInfo.status == AgentStatus.IDLE || statusInfo.status == AgentStatus.WAITING || statusInfo.status == AgentStatus.ERROR) && (agent.subscribedSessionIds.isNotEmpty() || agent.outputSessionId != null) && agent.isAgentActive

    val statusText = when (statusInfo.status) {
        AgentStatus.PROCESSING -> {
            val step = statusInfo.processingStep ?: "Processing..."
            "$step ($processingTime)"
        }
        AgentStatus.RATE_LIMITED -> {
            if (rateLimitCountdown.isNotBlank()) "Waiting for API limit ($rateLimitCountdown)"
            else "Rate limited"
        }
        else -> statusInfo.strategyDisplayHint ?: statusInfo.status.name
    }

    val agentUuidStr = agent.identityUUID.uuid

    // Resolve display color from identity registry
    val agentIdentity = identityRegistry[agent.identity.handle]
    val accentColor: Color = agentIdentity?.resolveDisplayColor()
        ?: if (agent.isAgentActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    val nameColor: Color = agentIdentity?.resolveDisplayColor()
        ?: MaterialTheme.colorScheme.onSurface

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // ── Left accent bar ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor)
        )

        Column(modifier = Modifier.weight(1f)) {
            // ── Header Row ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Agent icon — emoji (full-color) or Material icon (tinted with accent)
                val iconTint = if (agent.isAgentActive) accentColor
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

                if (agentIdentity?.displayEmoji != null) {
                    Text(
                        text = agentIdentity.displayEmoji!!,
                        fontSize = 30.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    val iconVector = IconRegistry.resolve(agentIdentity?.displayIcon) ?: IconRegistry.defaultAgentIcon
                    Icon(
                        imageVector = iconVector,
                        contentDescription = "Agent Icon",
                        modifier = Modifier.size(48.dp),
                        tint = iconTint
                    )
                }

                // Name + Status — double-click enters edit mode (manager shortcut)
                Column(
                    modifier = Modifier.weight(1f).combinedClickable(
                        onClick = { localShowExtendedInfo = !localShowExtendedInfo },
                        onDoubleClick = { onEditRequest?.invoke() }
                    )
                ) {
                    Text(agent.identity.name, style = MaterialTheme.typography.titleMedium, color = nameColor)
                    Text("Status: $statusText", style = MaterialTheme.typography.bodyMedium)
                    if (statusInfo.status == AgentStatus.ERROR && statusInfo.errorMessage != null) {
                        Text(
                            text = statusInfo.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (statusInfo.status == AgentStatus.RATE_LIMITED && statusInfo.errorMessage != null) {
                        Text(
                            text = statusInfo.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // ── Runtime Controls ─────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Eye toggle for extended info
                    IconButton(onClick = {
                        localShowExtendedInfo = !localShowExtendedInfo
                    }) {
                        Icon(
                            imageVector = if (showExtendedInfo) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Details",
                            tint = if (showExtendedInfo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Kebab menu — contextual: manager vs avatar
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            // Edit Agent — always available
                            DropdownMenuItem(
                                text = { Text("Edit Agent") },
                                onClick = {
                                    if (onEditRequest != null) {
                                        onEditRequest()
                                    } else {
                                        // Avatar context: navigate to manager and open editor
                                        store.dispatch("agent", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.manager") }))
                                        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_SET_EDITING, buildJsonObject { put("agentId", agentUuidStr) }))
                                    }
                                    menuExpanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )

                            // NVRAM — always available
                            DropdownMenuItem(
                                text = { Text(if (isViewingNvram) "Hide NVRAM" else "Inspect NVRAM") },
                                onClick = {
                                    localShowNvram = !localShowNvram
                                    menuExpanded = false
                                },
                                leadingIcon = { Icon(Icons.Default.Memory, null) }
                            )

                            // ── Avatar-only items ────────────────────────
                            if (!showManagementActions) {
                                DropdownMenuItem(
                                    text = { Text("Preview Turn") },
                                    onClick = {
                                        store.dispatch("agent", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                                            put("agentId", agentUuidStr)
                                            put("preview", true)
                                        }))
                                        menuExpanded = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                    enabled = canInitiateTurn
                                )
                                if (sessionUUID != null) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from session") },
                                        onClick = {
                                            store.dispatch("agent", Action(ActionRegistry.Names.AGENT_REMOVE_SESSION_SUBSCRIPTION, buildJsonObject {
                                                put("agentId", agentUuidStr)
                                                put("sessionId", sessionUUID)
                                            }))
                                            menuExpanded = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.LinkOff, null) }
                                    )
                                }
                            }

                            // ── Manager-only items ───────────────────────
                            if (showManagementActions) {
                                if (onCloneRequest != null) {
                                    DropdownMenuItem(
                                        text = { Text("Clone Agent") },
                                        onClick = {
                                            onCloneRequest()
                                            menuExpanded = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                    )
                                }
                                if (onDeleteRequest != null) {
                                    DropdownMenuItem(
                                        text = { Text("Delete Agent") },
                                        onClick = {
                                            onDeleteRequest()
                                            menuExpanded = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                                    )
                                }
                            }
                        }
                    }

                    // Power toggle
                    val activeSwitchTooltipState = remember { TooltipState() }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Toggle Active State") } },
                        state = activeSwitchTooltipState
                    ) {
                        IconButton(onClick = {
                            store.dispatch("agent", Action(ActionRegistry.Names.AGENT_TOGGLE_ACTIVE, buildJsonObject { put("agentId", agentUuidStr) }))
                        }) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Toggle Active State",
                                tint = if (agent.isAgentActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }

                    // Auto-mode toggle
                    val autoModeSwitchTooltipState = remember { TooltipState() }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Toggle Automatic Mode") } },
                        state = autoModeSwitchTooltipState
                    ) {
                        IconButton(onClick = {
                            store.dispatch("agent", Action(ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", agentUuidStr) }))
                        }) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = "Toggle Automatic Mode",
                                tint = if (agent.automaticMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    }

                    // Trigger / Cancel
                    if (statusInfo.status == AgentStatus.PROCESSING) {
                        Button(
                            onClick = { store.dispatch("agent", Action(ActionRegistry.Names.AGENT_CANCEL_TURN, buildJsonObject { put("agentId", agentUuidStr) })) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancel Turn")
                        }
                    } else {
                        Button(
                            onClick = {
                                store.dispatch("agent", Action(ActionRegistry.Names.AGENT_INITIATE_TURN, buildJsonObject {
                                    put("agentId", agentUuidStr)
                                    put("preview", false)
                                }))
                            },
                            enabled = canInitiateTurn
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Trigger Turn")
                        }
                    }
                }
            }

            // ── Extended Info Drawer ─────────────────────────────────────
            AnimatedVisibility(visible = showExtendedInfo) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val sessionNames = agent.subscribedSessionIds.mapNotNull { agentState.subscribableSessionNames[it] }
                    val sessionSummary = when {
                        sessionNames.isEmpty() -> "Not Subscribed"
                        sessionNames.size == 1 -> sessionNames.first()
                        else -> sessionNames.joinToString(", ")
                    }
                    Text("Subscribed: $sessionSummary", style = MaterialTheme.typography.bodyMedium)

                    if (agent.outputSessionId != null) {
                        val outputSessionName = identityRegistry.findByUUID(agent.outputSessionId)?.name
                            ?: agentState.subscribableSessionNames[agent.outputSessionId]
                            ?: agent.outputSessionId.uuid
                        Text("Primary Session: $outputSessionName", style = MaterialTheme.typography.bodyMedium)
                    }

                    val kgId = agent.strategyConfig["knowledgeGraphId"]
                        ?.let { it as? JsonPrimitive }
                        ?.contentOrNull
                    if (kgId != null) {
                        val hkgName = agentState.knowledgeGraphNames[kgId] ?: "Unknown"
                        Text("Knowledge Graph: $hkgName", style = MaterialTheme.typography.bodyMedium)
                    }

                    Text("Model: ${agent.modelProvider}/${agent.modelName}", style = MaterialTheme.typography.bodyMedium)
                    Text("Strategy: ${agent.cognitiveStrategyId}", style = MaterialTheme.typography.bodyMedium)

                    // Token usage from last request
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
            }

            // ── NVRAM Drawer ─────────────────────────────────────────────
            AnimatedVisibility(visible = isViewingNvram) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Cognitive State (NVRAM)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    val nvramText = remember(agent.cognitiveState) {
                        prettyJson.encodeToString(agent.cognitiveState)
                    }
                    CodeEditor(
                        value = nvramText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.height(150.dp)
                    )
                }
            }
        }  // Column
    }  // Row (accent bar)
}