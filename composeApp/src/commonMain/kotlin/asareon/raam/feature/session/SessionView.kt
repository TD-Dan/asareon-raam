package asareon.raam.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.core.*
import asareon.raam.core.resolveDisplayColor
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.core.CoreState
import asareon.raam.ui.components.IconRegistry
import asareon.raam.ui.components.fileDropTargetModifier
import asareon.raam.util.PlatformDependencies
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Max content width for the ledger pane, derived from ~80 characters
 * of body text at Material 3 bodyLarge metrics. This is a content
 * constraint, not a pane constraint — the pane itself fills available
 * space, but message cards are capped for readability.
 */
private val LEDGER_MAX_CONTENT_WIDTH = 720.dp

/** Min width for the workspace pane when open. */
private val WORKSPACE_PANE_MIN_WIDTH = 260.dp

/** Default width for the workspace pane. */
private val WORKSPACE_PANE_DEFAULT_WIDTH = 320.dp

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
    // No `remember` for any derived values — avoids stale-index crashes
    // when the filtered session list changes between recomposition frames.
    val sessions = deriveOrderedSessions(
        sessionState?.sessions ?: emptyMap(),
        sessionState?.sessionOrder ?: emptyList(),
        hideHidden
    )
    val activeSession = sessions.find {
        it.identity.localHandle == sessionState?.activeSessionLocalHandle
    }
    val activeTabIndex = if (sessions.isEmpty()) 0
    else sessions.indexOf(activeSession).coerceIn(0, sessions.lastIndex)

    val isWorkspaceOpen = sessionState?.isWorkspacePaneOpen ?: false

    Column(modifier = Modifier.fillMaxSize()) {

        // ══════════════════════════════════════════════════════════════════
        // Tab bar row — tabs + kebab menu + workspace toggle
        // ══════════════════════════════════════════════════════════════════
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (sessions.isNotEmpty()) {
                // `key` forces ScrollableTabRow to fully recompose (not just
                // update) when the tab count changes. Without this, its
                // internal indicator state can hold a stale index for one
                // frame, causing IndexOutOfBoundsException.
                key(sessions.size) {
                    ScrollableTabRow(
                        selectedTabIndex = activeTabIndex,
                        modifier = Modifier.weight(1f),
                        edgePadding = 8.dp
                    ) {
                        sessions.forEach { session ->
                            SessionTab(store, session, session.identity.localHandle == activeSession?.identity?.localHandle, session.identity.localHandle == sessionState?.editingSessionLocalHandle)
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // ── Session kebab menu (consolidates tab bar actions) ──────────
            SessionKebabMenu(
                store = store,
                sessionState = sessionState,
                hideHidden = hideHidden,
                activeSession = activeSession
            )

            // ── Workspace pane toggle ─────────────────────────────────────
            IconButton(
                onClick = {
                    store.dispatch("session", Action(
                        ActionRegistry.Names.SESSION_TOGGLE_WORKSPACE_PANE
                    ))
                    // Refresh file list when opening
                    if (!isWorkspaceOpen && activeSession != null) {
                        store.dispatch("session", Action(
                            ActionRegistry.Names.SESSION_REFRESH_WORKSPACE,
                            buildJsonObject {
                                put("session", activeSession.identity.localHandle)
                            }
                        ))
                    }
                }
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = if (isWorkspaceOpen) "Hide files" else "Show files",
                    tint = if (isWorkspaceOpen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // Main content area — wrapped in a drop target for the entire
        // session region. Files can be dropped even when the workspace
        // pane is hidden; it will auto-open on drop.
        // ══════════════════════════════════════════════════════════════════
        var isDragHovering by remember { mutableStateOf(false) }

        // Build the drop target modifier at this composable scope so the
        // remembered DragAndDropTarget survives recomposition.
        val dropModifier = fileDropTargetModifier(
            onDragEntered = { isDragHovering = true },
            onDragExited = { isDragHovering = false },
            onFilesDropped = { droppedFiles ->
                val session = activeSession
                val sessionUuid = session?.identity?.uuid
                if (session != null && sessionUuid != null) {
                    val sessionLocalHandle = session.identity.localHandle

                    // Write each file to the workspace folder
                    droppedFiles.forEach { file ->
                        try {
                            val absPath = platformDependencies.resolveAbsoluteSandboxPath(
                                "session",
                                "$sessionUuid/workspace/${file.name}"
                            )
                            platformDependencies.writeFileBytes(absPath, file.bytes)
                        } catch (_: Exception) {
                            // Individual write failures are non-fatal
                        }
                    }

                    // Auto-open the workspace pane if it was closed
                    if (sessionState?.isWorkspacePaneOpen != true) {
                        store.dispatch("session", Action(
                            ActionRegistry.Names.SESSION_TOGGLE_WORKSPACE_PANE
                        ))
                    }

                    // Refresh the file list
                    store.dispatch("session", Action(
                        ActionRegistry.Names.SESSION_REFRESH_WORKSPACE,
                        buildJsonObject { put("session", sessionLocalHandle) }
                    ))
                }
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            if (activeSession == null) {
                Box(
                    Modifier.fillMaxSize().then(dropModifier),
                    Alignment.Center
                ) { Text("No active session. Create one to begin.") }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().then(dropModifier)) {
                    val inputMaxHeight = maxHeight / 2
                    Column(Modifier.fillMaxSize()) {

                        // ── Ledger + Workspace row ────────────────────────────
                        Row(modifier = Modifier.weight(1f)) {

                            // Ledger pane — fills available space, content width-capped
                            LedgerPane(
                                store = store,
                                activeSession = activeSession,
                                sessionState = sessionState,
                                features = features,
                                platformDependencies = platformDependencies,
                                maxContentWidth = LEDGER_MAX_CONTENT_WIDTH,
                                modifier = Modifier.weight(1f)
                            )

                            // Workspace pane — slides in/out from right
                            AnimatedVisibility(
                                visible = isWorkspaceOpen,
                                enter = expandHorizontally(expandFrom = Alignment.Start),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.Start)
                            ) {
                                Row {
                                    VerticalDivider()
                                    WorkspacePane(
                                        store = store,
                                        session = activeSession,
                                        sessionState = sessionState!!,
                                        platformDependencies = platformDependencies,
                                        modifier = Modifier
                                            .widthIn(min = WORKSPACE_PANE_MIN_WIDTH)
                                            .width(WORKSPACE_PANE_DEFAULT_WIDTH)
                                    )
                                }
                            }
                        }

                        // ── Message input — full width below both panes ───────
                        Box(modifier = Modifier.heightIn(max = inputMaxHeight)) {
                            MessageInput(store, activeSession, features, platformDependencies) { message ->
                                val activeUserId = sessionState?.activeUserId ?: "user"
                                store.dispatch("session", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                                    put("session", activeSession.identity.localHandle); put("senderId", activeUserId); put("message", message)
                                }))
                            }
                        }
                    }
                }
            }

            // ── Drop zone overlay — covers the entire session area ─────
            DropZoneOverlay(visible = isDragHovering)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Session Kebab Menu — consolidates tab bar actions into a dropdown
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SessionKebabMenu(
    store: Store,
    sessionState: SessionState?,
    hideHidden: Boolean,
    activeSession: Session?
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Session options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // New session
            DropdownMenuItem(
                text = { Text("New session") },
                onClick = {
                    store.dispatch("session", Action(ActionRegistry.Names.SESSION_CREATE))
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.Add, null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Toggle hidden session visibility
            DropdownMenuItem(
                text = {
                    Text(if (hideHidden) "Show hidden sessions" else "Hide hidden sessions")
                },
                onClick = {
                    val newValue = !hideHidden
                    store.dispatch("session", Action(
                        ActionRegistry.Names.SETTINGS_UPDATE,
                        buildJsonObject {
                            put("key", SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                            put("value", newValue.toString())
                        }
                    ))
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        if (hideHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        null
                    )
                }
            )

            // Open workspace folder in OS (only when a session is active)
            if (activeSession != null) {
                DropdownMenuItem(
                    text = { Text("Open workspace folder") },
                    onClick = {
                        val uuid = activeSession.identity.uuid
                        if (uuid != null) {
                            store.dispatch("session", Action(
                                ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER,
                                buildJsonObject { put("path", "$uuid/workspace") }
                            ))
                        }
                        expanded = false
                    },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Drop Zone Overlay — full-area visual indicator during file drags
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DropZoneOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val borderColor = MaterialTheme.colorScheme.primary
        val bgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(color = bgColor)
                    drawRect(
                        color = borderColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(12.dp.toPx(), 8.dp.toPx())
                            )
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Drop files to import",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Session Tab (unchanged)
// ═══════════════════════════════════════════════════════════════════════════

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
                            store.dispatch("session", Action(ActionRegistry.Names.SESSION_UPDATE_CONFIG, buildJsonObject {
                                put("session", session.identity.localHandle); put("name", text)
                            }))
                            return@onKeyEvent true
                        }
                        Key.Escape -> {
                            store.dispatch("session", Action(ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject {
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
            onClick = { store.dispatch("session", Action(ActionRegistry.Names.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.identity.localHandle) })) },
            modifier = Modifier.combinedClickable(
                onClick = { store.dispatch("session", Action(ActionRegistry.Names.SESSION_SET_ACTIVE_TAB, buildJsonObject { put("session", session.identity.localHandle) })) },
                onDoubleClick = { store.dispatch("session", Action(ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME, buildJsonObject { put("sessionId", session.identity.localHandle) })) }
            )
        ) { Text(session.identity.name, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = tabTextColor) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Ledger Pane — updated with maxContentWidth for ~80 char readability
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun LedgerPane(
    store: Store,
    activeSession: Session,
    sessionState: SessionState?,
    features: List<Feature>,
    platformDependencies: PlatformDependencies,
    maxContentWidth: Dp = LEDGER_MAX_CONTENT_WIDTH,
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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(8.dp),
        // Center cards when pane is wider than max content width
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(activeSession.ledger, key = { _, entry -> entry.id }) { index, entry ->
            // ── Width cap for readability ──────────────────────────────
            Box(modifier = Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
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
                        val partialContext = mapOf(
                            "senderId" to entry.senderId,
                            "sessionUUID" to (activeSession.identity.uuid ?: "")
                        )
                        targetFeature?.composableProvider?.PartialView(store, viewKey, partialContext)
                    } else {
                        // Backward compatibility: legacy agent avatar cards without routing metadata
                        val partialContext = mapOf(
                            "senderId" to entry.senderId,
                            "sessionUUID" to (activeSession.identity.uuid ?: "")
                        )
                        agentFeature?.composableProvider?.PartialView(store, "agent.avatar", partialContext)
                    }
                } else {
                    val senderName = remember(entry.senderId, identityRegistry) {
                        identityRegistry[entry.senderId]?.name ?: entry.senderId
                    }
                    val isCurrentUserMessage = entry.senderId == activeUserId

                    val senderColor = identityRegistry[entry.senderId]?.resolveDisplayColor()

                    LedgerEntryCard(
                        store = store,
                        session = activeSession,
                        entry = entry,
                        senderName = senderName,
                        isCurrentUserMessage = isCurrentUserMessage,
                        isEditingThisMessage = sessionState?.editingMessageId == entry.id,
                        editingContent = sessionState?.editingMessageContent,
                        platformDependencies = platformDependencies,
                        senderColor = senderColor
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Message Input (unchanged — preserves full autocomplete, history,
// draft debounce, clear session dialog, and agent subscription menu)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageInput(store: Store, activeSession: Session, features: List<Feature>, platformDependencies: PlatformDependencies, onSend: (String) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    // ── Autocomplete state ──
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    var acState by remember { mutableStateOf<SlashCommandEngine.AutocompleteState?>(null) }
    val inputFocusRequester = remember { FocusRequester() }

    // ── Draft text is driven from the store — survives tab switches and app restarts ──
    val sessionLocalHandle = activeSession.identity.localHandle
    val storeText = sessionState?.draftInputs?.get(sessionLocalHandle) ?: ""

    // ── Two-mode input: history mode vs edit mode ──
    // History mode (false): Up/Down cycle through sent-message history.
    // Edit mode (true): Up/Down move the caret within multiline text.
    // Engages on any user interaction other than Up/Down. Resets when input is empty.
    var editMode by remember(sessionLocalHandle) { mutableStateOf(false) }

    // Local TextFieldValue state — allows cursor (selection) control.
    // Initialized with cursor at end; re-keyed when switching sessions.
    var textFieldValue by remember(sessionLocalHandle) {
        mutableStateOf(TextFieldValue(storeText, TextRange(storeText.length)))
    }

    // Sync from store when text changes externally (history navigation, tab switch).
    // When the store-driven text differs from what the user last typed, update the
    // local TextFieldValue and place the cursor at the end of the new text.
    LaunchedEffect(storeText, sessionLocalHandle) {
        if (textFieldValue.text != storeText) {
            textFieldValue = TextFieldValue(storeText, TextRange(storeText.length))
        }
    }

    // ── Debounced draft dispatch ──────────────────────────────────────────
    // Typing updates the local TextFieldValue immediately for a responsive UI,
    // but the store action is throttled to fire at most once every 2 seconds.
    // This prevents log spam and unnecessary reducer churn on every keystroke.
    val draftDebounceScope = rememberCoroutineScope()
    var draftDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Flush any pending debounced draft when switching sessions.
    // During app shutdown, the feature layer handles flushing via SYSTEM_CLOSING,
    // so we skip the dispatch if the lifecycle has moved past RUNNING.
    DisposableEffect(sessionLocalHandle) {
        onDispose {
            if (draftDebounceJob?.isActive == true) {
                draftDebounceJob?.cancel()
                val lifecycle = (store.state.value.featureStates["core"] as? CoreState)?.lifecycle
                if (lifecycle == AppLifecycle.RUNNING) {
                    store.dispatch("session", Action(
                        ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                        buildJsonObject {
                            put("sessionId", sessionLocalHandle)
                            put("draft", textFieldValue.text)
                        }
                    ))
                }
            }
        }
    }

    /** Dispatches INPUT_DRAFT_CHANGED immediately (no debounce). */
    fun dispatchDraftChangeImmediate(newText: String) {
        draftDebounceJob?.cancel()
        draftDebounceJob = null
        store.dispatch("session", Action(
            ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
            buildJsonObject {
                put("sessionId", sessionLocalHandle)
                put("draft", newText)
            }
        ))
    }

    /**
     * Debounced draft dispatch — schedules a store update after a 2-second quiet period.
     * Called on every keystroke; only the trailing edge fires.
     */
    fun dispatchDraftChange(newText: String) {
        draftDebounceJob?.cancel()
        draftDebounceJob = draftDebounceScope.launch {
            delay(2_000)
            store.dispatch("session", Action(
                ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED,
                buildJsonObject {
                    put("sessionId", sessionLocalHandle)
                    put("draft", newText)
                }
            ))
        }
    }

    /**
     * Sets the local TextFieldValue with the cursor at the end and dispatches
     * the draft change **immediately** (used by slash-command completions and
     * programmatic text changes that need instant store synchronisation).
     */
    fun setTextAndDispatch(newText: String) {
        textFieldValue = TextFieldValue(newText, TextRange(newText.length))
        dispatchDraftChangeImmediate(newText)
    }

    val engine = remember(
        appState.identityRegistry,
        sessionState?.activeSessionLocalHandle,
        sessionState?.activeUserId
    ) {
        SlashCommandEngine(
            featureDescriptors = ActionRegistry.features,
            identityRegistry = appState.identityRegistry,
            activeSessionLocalHandle = sessionState?.activeSessionLocalHandle,
            activeUserId = sessionState?.activeUserId
        )
    }

    /**
     * Parses the current text field content to sync the autocomplete state.
     */
    fun syncAutocompleteFromText(newText: String) {
        val isAdmin = newText.startsWith("//")
        val prefix = if (isAdmin) "//" else "/"

        if (!newText.startsWith("/")) {
            acState = null
            return
        }

        val body = newText.removePrefix(prefix)
        val dotIndex = body.indexOf('.')

        if (dotIndex >= 0) {
            // Has dot → ACTION stage
            val featureName = body.substring(0, dotIndex)
            val actionQuery = body.substring(dotIndex + 1)

            if (acState == null || acState?.adminMode != isAdmin || acState?.selectedFeature != featureName) {
                var s = engine.initialState(adminMode = isAdmin)
                s = engine.selectFeature(s, featureName)
                acState = engine.updateQuery(s, actionQuery)
            } else {
                acState = engine.updateQuery(acState!!, actionQuery)
            }
        } else {
            // No dot → FEATURE stage
            if (acState == null || acState?.adminMode != isAdmin || acState?.stage != SlashCommandEngine.Stage.FEATURE) {
                acState = engine.initialState(adminMode = isAdmin)
            }
            acState = engine.updateQuery(acState!!, body)
        }
    }

    /**
     * Returns the current candidate count for the active stage, used by
     * highlight navigation to clamp bounds.
     */
    fun currentCandidateCount(): Int {
        val s = acState ?: return 0
        return when (s.stage) {
            SlashCommandEngine.Stage.FEATURE ->
                engine.featureCandidates(s.query, s.adminMode).size
            SlashCommandEngine.Stage.ACTION ->
                engine.actionCandidates(s.selectedFeature ?: "", s.query, s.adminMode).size
            SlashCommandEngine.Stage.PARAMS -> 0
        }
    }

    /**
     * Handles Tab/Enter selection in the candidate list.
     * FEATURE → completes feature name in text, advances to ACTION.
     * ACTION → enters PARAMS stage with auto-fill.
     */
    fun handleCandidateSelection(): Boolean {
        val s = acState ?: return false
        val prefix = if (s.adminMode) "//" else "/"

        when (s.stage) {
            SlashCommandEngine.Stage.FEATURE -> {
                val candidates = engine.featureCandidates(s.query, s.adminMode)
                val index = s.highlightedIndex.coerceIn(0, candidates.lastIndex)
                if (candidates.isEmpty()) return false

                val selected = candidates[index]
                val newText = "$prefix${selected.name}."
                setTextAndDispatch(newText)
                acState = engine.selectFeature(s, selected.name)
                return true
            }
            SlashCommandEngine.Stage.ACTION -> {
                val feature = s.selectedFeature ?: return false
                val candidates = engine.actionCandidates(feature, s.query, s.adminMode)
                val index = s.highlightedIndex.coerceIn(0, candidates.lastIndex)
                if (candidates.isEmpty()) return false

                val selected = candidates[index]
                val newText = "$prefix${feature}.${selected.descriptor.suffix}"
                setTextAndDispatch(newText)
                acState = engine.selectAction(s, selected.descriptor)
                return true
            }
            SlashCommandEngine.Stage.PARAMS -> return false
        }
    }

    // ── Clear session confirmation dialog ──
    var sessionToClear by remember { mutableStateOf<Session?>(null) }

    sessionToClear?.let { session ->
        val survivorCount = session.ledger.count { it.isLocked || it.doNotClear }
        val removedCount = session.ledger.size - survivorCount
        val detail = if (survivorCount > 0) {
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
                        store.dispatch("session", Action(ActionRegistry.Names.SESSION_CLEAR, buildJsonObject { put("session", session.identity.localHandle) }))
                        sessionToClear = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = { Button(onClick = { sessionToClear = null }) { Text("Cancel") } }
        )
    }

    // ── Layout: autocomplete panel above input ──
    Column(modifier = Modifier.fillMaxWidth()) {

        // Autocomplete panel (renders above the input bar)
        AnimatedVisibility(
            visible = acState != null,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            acState?.let { state ->
                SlashCommandPanel(
                    engine = engine,
                    state = state,
                    onStateChange = { newState ->
                        val prevStage = acState?.stage
                        acState = newState
                        if (newState == null) {
                            setTextAndDispatch("")
                        } else if (newState.stage == SlashCommandEngine.Stage.ACTION
                            && prevStage == SlashCommandEngine.Stage.FEATURE
                            && newState.selectedFeature != null
                        ) {
                            // Feature selected via mouse click — sync input text to match
                            val prefix = if (newState.adminMode) "//" else "/"
                            setTextAndDispatch("$prefix${newState.selectedFeature}.")
                        } else if (newState.stage == SlashCommandEngine.Stage.PARAMS
                            && prevStage == SlashCommandEngine.Stage.ACTION
                            && newState.selectedFeature != null
                            && newState.selectedAction != null
                        ) {
                            // Action selected via mouse click — sync input text to match
                            val prefix = if (newState.adminMode) "//" else "/"
                            setTextAndDispatch("$prefix${newState.selectedFeature}.${newState.selectedAction!!.suffix}")
                        }
                    },
                    onInsert = { codeBlock ->
                        setTextAndDispatch(codeBlock)
                        acState = null
                        inputFocusRequester.requestFocus()
                    }
                )
            }
        }

        // Input bar
        Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
            Row(Modifier.padding(8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val textChanged = newValue.text != textFieldValue.text
                        textFieldValue = newValue
                        // Any direct user interaction engages edit mode (typing,
                        // clicking to reposition cursor, selecting text, etc.).
                        // Empty input resets back to history mode so Up immediately
                        // recalls previous entries.
                        editMode = newValue.text.isNotEmpty()
                        // Always dispatch so that any user interaction (including cursor
                        // movement) commits the current text as the draft and exits
                        // history navigation mode if active.
                        dispatchDraftChange(newValue.text)
                        if (textChanged) {
                            syncAutocompleteFromText(newValue.text)
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(inputFocusRequester).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            // ── Autocomplete key handling (highest priority when active) ──
                            if (acState != null && acState?.stage != SlashCommandEngine.Stage.PARAMS) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        acState = engine.moveHighlight(acState!!, -1, currentCandidateCount())
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionDown -> {
                                        acState = engine.moveHighlight(acState!!, 1, currentCandidateCount())
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.Tab -> {
                                        return@onPreviewKeyEvent handleCandidateSelection()
                                    }
                                    Key.Enter -> {
                                        if (!event.isCtrlPressed && !event.isMetaPressed) {
                                            return@onPreviewKeyEvent handleCandidateSelection()
                                        }
                                    }
                                    Key.Escape -> {
                                        acState = null
                                        setTextAndDispatch("")
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> {}
                                }
                            }

                            // ── PARAMS stage key handling ──
                            if (acState?.stage == SlashCommandEngine.Stage.PARAMS) {
                                when (event.key) {
                                    Key.Escape -> {
                                        acState = null
                                        setTextAndDispatch("")
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.Enter -> {
                                        if (!event.isCtrlPressed && !event.isMetaPressed) {
                                            val descriptor = acState?.selectedAction
                                            val paramValues = acState?.paramValues ?: emptyMap()
                                            if (descriptor != null) {
                                                val codeBlock = engine.generateCodeBlock(descriptor, paramValues)
                                                setTextAndDispatch(codeBlock)
                                                acState = null
                                                inputFocusRequester.requestFocus()
                                            }
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    else -> {}
                                }
                            }

                            // ── Edit-mode engagement: any key other than Up/Down switches to edit mode ──
                            if (acState == null && event.key != Key.DirectionUp && event.key != Key.DirectionDown) {
                                editMode = true
                            }

                            // ── History navigation (Up/Down in history mode only) ──
                            if (acState == null && !editMode) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        store.dispatch("session", Action(
                                            ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                                            buildJsonObject {
                                                put("sessionId", sessionLocalHandle)
                                                put("direction", "UP")
                                            }
                                        ))
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionDown -> {
                                        store.dispatch("session", Action(
                                            ActionRegistry.Names.SESSION_HISTORY_NAVIGATE,
                                            buildJsonObject {
                                                put("sessionId", sessionLocalHandle)
                                                put("direction", "DOWN")
                                            }
                                        ))
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> {}
                                }
                            }

                            // ── Ctrl+Enter to send ──
                            if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                                if (textFieldValue.text.isNotBlank()) { draftDebounceJob?.cancel(); onSend(textFieldValue.text); acState = null; editMode = false }
                                return@onPreviewKeyEvent true
                            }

                            // ── Shift+Enter inserts a newline (same as bare Enter) ──
                            if (event.key == Key.Enter && event.isShiftPressed) {
                                val sel = textFieldValue.selection
                                val newText = textFieldValue.text.replaceRange(sel.min, sel.max, "\n")
                                val newCursor = sel.min + 1
                                textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                                dispatchDraftChange(newText)
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    },
                    placeholder = { Text("Enter message (Ctrl+Enter to send, / for commandline)...") }
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
                                store.dispatch("session", Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", transcript) }))
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
                        DropdownMenuItem(
                            text = { Text("Collapse All") },
                            onClick = {
                                store.dispatch("session", Action(
                                    ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
                                    buildJsonObject {
                                        put("sessionId", activeSession.identity.localHandle)
                                        put("targetCollapsed", true)
                                    }
                                ))
                                menuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.UnfoldLess, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Uncollapse All") },
                            onClick = {
                                store.dispatch("session", Action(
                                    ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED,
                                    buildJsonObject {
                                        put("sessionId", activeSession.identity.localHandle)
                                        put("targetCollapsed", false)
                                    }
                                ))
                                menuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.UnfoldMore, null) }
                        )

                        // --- Feature-contributed menu items ---
                        val activeSessionUUID = activeSession.identity.uuid
                        if (activeSessionUUID != null) {
                            features.forEach { feature ->
                                feature.composableProvider?.PartialView(
                                    store = store,
                                    partId = "session.message.menu",
                                    context = mapOf(
                                        "sessionUUID" to activeSessionUUID,
                                        "onDismiss" to { menuExpanded = false }
                                    )
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { if (textFieldValue.text.isNotBlank()) { draftDebounceJob?.cancel(); onSend(textFieldValue.text); acState = null; editMode = false } }, enabled = textFieldValue.text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}