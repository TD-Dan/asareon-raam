package app.auf.feature.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Derives the ordered, optionally filtered list of sessions from the canonical sessionOrder.
 * Sessions not present in sessionOrder are appended at the end, sorted by createdAt descending.
 */
private fun deriveOrderedSessions(
    sessionsMap: Map<String, Session>,
    sessionOrder: List<String>,
    hideHidden: Boolean
): List<Session> {
    val ordered = sessionOrder.mapNotNull { sessionsMap[it] }
    val unordered = sessionsMap.values
        .filter { it.identity.localHandle !in sessionOrder }
        .sortedByDescending { it.createdAt }
    val all = ordered + unordered
    return if (hideHidden) all.filter { !it.isHidden } else all
}

@Composable
fun SessionView(store: Store, features: List<Feature>, platformDependencies: PlatformDependencies) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState

    val hideHidden = sessionState?.hideHiddenInViewer ?: true
    val sessions = remember(sessionState?.sessions, sessionState?.sessionOrder, hideHidden) {
        deriveOrderedSessions(
            sessionState?.sessions ?: emptyMap(),
            sessionState?.sessionOrder ?: emptyList(),
            hideHidden
        )
    }
    val activeSession = remember(sessionState?.activeSessionLocalHandle, sessions) {
        sessions.find { it.identity.localHandle == sessionState?.activeSessionLocalHandle }
    }
    val activeTabIndex = remember(activeSession, sessions) {
        sessions.indexOf(activeSession).coerceAtLeast(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (sessions.isNotEmpty()) {
                TabRow(selectedTabIndex = activeTabIndex, modifier = Modifier.weight(1f)) {
                    sessions.forEach { session ->
                        SessionTab(store, session, session.identity.localHandle == activeSession?.identity?.localHandle, session.identity.localHandle == sessionState?.editingSessionLocalHandle)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            // Hidden filter toggle — persisted via Settings feature
            IconButton(onClick = {
                val newValue = !(sessionState?.hideHiddenInViewer ?: true)
                store.dispatch("session.ui", Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
                    put("key", SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    put("value", newValue.toString())
                }))
            }) {
                Icon(
                    imageVector = if (hideHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (hideHidden) "Show Hidden Sessions" else "Hide Hidden Sessions",
                    tint = if (hideHidden) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_CREATE)) }) {
                Icon(Icons.Default.Add, "New Session")
            }
        }

        if (activeSession == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No active session. Create one to begin.") }
        } else {
            LedgerPane(store, activeSession, sessionState, features, platformDependencies, Modifier.weight(1f))
            MessageInput(store, activeSession, platformDependencies) { message ->
                val activeUserId = sessionState?.activeUserId ?: "user"
                store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                    put("session", activeSession.identity.localHandle); put("senderId", activeUserId); put("message", message)
                }))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(store: Store, session: Session, isSelected: Boolean, isEditing: Boolean) {
    if (isEditing) {
        var text by remember(session.identity.localHandle) { mutableStateOf(session.identity.name) }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.padding(4.dp).onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter -> {
                            store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_UPDATE_CONFIG, buildJsonObject {
                                put("session", session.identity.localHandle); put("name", text)
                            }))
                            return@onKeyEvent true
                        }
                        Key.Escape -> {
                            store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject {
                                put("sessionId", null as String?)
                            }))
                            return@onKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            }, singleLine = true, textStyle = MaterialTheme.typography.labelLarge
        )
    } else {
        // Dim hidden sessions when visible (filter is off)
        val tabTextColor = if (session.isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else LocalContentColor.current
        Tab(
            selected = isSelected,
            onClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.identity.localHandle) })) },
            modifier = Modifier.combinedClickable(
                onClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.identity.localHandle) })) },
                onDoubleClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject { put("sessionId", session.identity.localHandle) })) }
            )
        ) { Text(session.identity.name, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp), color = tabTextColor) }
    }
}

@Composable
private fun LedgerPane(
    store: Store,
    activeSession: Session,
    sessionState: SessionState?,
    features: List<Feature>,
    platformDependencies: PlatformDependencies,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val identityRegistry = store.state.collectAsState().value.identityRegistry
    val activeUserId = sessionState?.activeUserId

    // --- SLICE 1 CHANGE: Build a lookup map of feature name → feature for PartialView routing ---
    val featuresByName = remember(features) { features.associateBy { it.identity.handle } }
    // Keep agent feature reference for backward compatibility with cards that lack metadata
    val agentFeature = remember(features) { features.find { it.identity.handle == "agent" } }

    LaunchedEffect(activeSession.ledger.size) {
        if (activeSession.ledger.size > 1) {
            coroutineScope.launch {
                listState.animateScrollToItem(activeSession.ledger.size)
            }
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize().padding(8.dp)) {
        items(activeSession.ledger, key = { it.id }) { entry ->
            val isPartialView = entry.metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false

            if (isPartialView) {
                // --- SLICE 1 CHANGE: Generalized PartialView routing via metadata ---
                // New cards include "partial_view_feature" and "partial_view_key" in metadata.
                // Legacy cards (pre-migration agent avatars) fall back to the hardcoded agent path.
                val featureName = entry.metadata?.get("partial_view_feature")
                    ?.jsonPrimitive?.contentOrNull
                val viewKey = entry.metadata?.get("partial_view_key")
                    ?.jsonPrimitive?.contentOrNull

                if (featureName != null && viewKey != null) {
                    // New generalized path: route to whichever feature owns this partial view
                    val targetFeature = featuresByName[featureName]
                    targetFeature?.composableProvider?.PartialView(store, viewKey, entry.senderId)
                } else {
                    // Backward compatibility: legacy agent avatar cards without routing metadata
                    agentFeature?.composableProvider?.PartialView(store, "agent.avatar", entry.senderId)
                }
            } else {
                val senderName = remember(entry.senderId, identityRegistry) {
                    identityRegistry[entry.senderId]?.name ?: entry.senderId
                }
                val isCurrentUserMessage = entry.senderId == activeUserId
                LedgerEntryCard(
                    store = store,
                    session = activeSession,
                    entry = entry,
                    senderName = senderName,
                    isCurrentUserMessage = isCurrentUserMessage,
                    isEditingThisMessage = sessionState?.editingMessageId == entry.id,
                    editingContent = sessionState?.editingMessageContent,
                    platformDependencies = platformDependencies
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInput(store: Store, activeSession: Session, platformDependencies: PlatformDependencies, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    // Clear session confirmation dialog — mirrors the pattern used in SessionsManagerView.
    var sessionToClear by remember { mutableStateOf<Session?>(null) }

    sessionToClear?.let { session ->
        val survivorCount = session.ledger.count { it.isLocked || it.doNotClear }
        val removedCount = session.ledger.size - survivorCount
        val detail = if (survivorCount > 0) {
            //TODO: report this as "x locked messages and y other will be preserved"
            "$removedCount message(s) will be removed. $survivorCount protected message(s) will be preserved."
        } else {
            "All ${session.ledger.size} message(s) will be removed."
        }
        AlertDialog(
            onDismissRequest = { sessionToClear = null },
            title = { Text("Clear Session?") },
            text = { Text("Clear '${session.identity.name}'? $detail") },
            confirmButton = {
                Button(
                    onClick = {
                        store.dispatch("session.ui", Action(ActionRegistry.Names.SESSION_CLEAR, buildJsonObject { put("session", session.identity.localHandle) }))
                        sessionToClear = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = { Button(onClick = { sessionToClear = null }) { Text("Cancel") } }
        )
    }

    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Row(Modifier.padding(8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                        if (text.isNotBlank()) { onSend(text); text = "" }
                        return@onKeyEvent true
                    }
                    false
                },
                placeholder = { Text("Enter message (Ctrl+Enter to send)...") }
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy Transcript") },
                        onClick = {
                            val transcript = activeSession.ledger.joinToString("\n\n") { entry ->
                                val timestamp = platformDependencies.formatIsoTimestamp(entry.timestamp)
                                val senderName = store.state.value.identityRegistry[entry.senderId]?.name ?: entry.senderId
                                "$senderName @ $timestamp:\n${entry.rawContent}"
                            }
                            store.dispatch("ui.session.input", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", transcript) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Session") },
                        onClick = {
                            sessionToClear = activeSession
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ClearAll, null) }
                    )
                }
            }
            IconButton(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}