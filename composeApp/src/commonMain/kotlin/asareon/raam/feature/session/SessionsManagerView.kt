package asareon.raam.feature.session

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.ui.components.destructive.ConfirmDestructiveDialog
import asareon.raam.ui.components.destructive.DangerDropdownMenuItem
import asareon.raam.ui.components.footer.FooterActionEmphasis
import asareon.raam.ui.components.footer.FooterButton
import asareon.raam.ui.components.footer.ViewFooter
import asareon.raam.ui.components.identity.IdentityDraft
import asareon.raam.ui.components.identity.IdentityFieldsSection
import asareon.raam.ui.components.identity.toDraft
import asareon.raam.ui.components.topbar.HeaderAction
import asareon.raam.ui.components.topbar.HeaderActionEmphasis
import asareon.raam.ui.components.topbar.HeaderLeading
import asareon.raam.ui.components.topbar.RaamTopBarHeader
import asareon.raam.ui.theme.spacing
import asareon.raam.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Derives the ordered, optionally filtered list of sessions from the canonical sessionOrder.
 * Sessions not present in sessionOrder are appended at the end, sorted by createdAt descending.
 */
private fun deriveOrderedSessionsForManager(
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

// =====================================================================
// Drag-to-reorder state holder
// =====================================================================

/**
 * Manages the in-flight state of a drag reorder gesture initiated from a handle.
 *
 * IMPORTANT: This class must NOT be recreated during a drag. The mutable list is accessed
 * via [getList]/[setList] lambdas so the caller can swap items without invalidating this object.
 */
private class DragReorderState(
    private val lazyListState: LazyListState,
    private val getList: () -> List<Session>,
    private val setList: (List<Session>) -> Unit,
    private val onDragFinished: (newOrder: List<String>) -> Unit
) {
    var draggedIndex by mutableStateOf<Int?>(null)
        private set
    var dragOffset by mutableStateOf(0f)
        private set
    val isDragging: Boolean get() = draggedIndex != null

    /** Called from the drag handle's pointerInput with the explicit item index. */
    fun onDragStart(itemIndex: Int) {
        draggedIndex = itemIndex
        dragOffset = 0f
    }

    fun onDrag(change: Float) {
        val currentIndex = draggedIndex ?: return
        dragOffset += change

        val currentItem = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == currentIndex } ?: return
        val currentCenter = currentItem.offset + currentItem.size / 2 + dragOffset.toInt()

        val targetItem = lazyListState.layoutInfo.visibleItemsInfo
            .filterNot { it.index == currentIndex }
            .firstOrNull { currentCenter in it.offset..(it.offset + it.size) }

        if (targetItem != null) {
            val list = getList().toMutableList()
            list.add(targetItem.index, list.removeAt(currentIndex))
            setList(list)
            draggedIndex = targetItem.index
            // Adjust offset so the card follows the finger smoothly after the swap
            dragOffset += (currentItem.offset - targetItem.offset)
        }
    }

    fun onDragEnd() {
        val finalOrder = getList().map { it.identity.localHandle }
        draggedIndex = null
        dragOffset = 0f
        onDragFinished(finalOrder)
    }

    fun onDragCancel() {
        draggedIndex = null
        dragOffset = 0f
        // No commit — the list will resync from the store on the next recomposition.
    }

    /**
     * Auto-scroll when the dragged card is near the top or bottom 10% of the viewport.
     */
    fun autoScroll(coroutineScope: CoroutineScope) {
        val idx = draggedIndex ?: return
        val draggedItem = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == idx } ?: return
        val viewportHeight = lazyListState.layoutInfo.viewportSize.height
        val itemCenter = draggedItem.offset + draggedItem.size / 2 + dragOffset.toInt()
        coroutineScope.launch {
            when {
                itemCenter < viewportHeight * 0.1f -> lazyListState.scrollToItem(
                    (lazyListState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                )
                itemCenter > viewportHeight * 0.9f -> lazyListState.scrollToItem(
                    lazyListState.firstVisibleItemIndex + 1
                )
            }
        }
    }
}

@Composable
fun SessionsManagerView(store: Store, platformDependencies: PlatformDependencies) {
    val appState by store.state.collectAsState()
    val sessionState = appState.featureStates["session"] as? SessionState
    val hideHidden = sessionState?.hideHiddenInManager ?: true
    val storeSessionList = remember(sessionState?.sessions, sessionState?.sessionOrder, hideHidden) {
        deriveOrderedSessionsForManager(
            sessionState?.sessions ?: emptyMap(),
            sessionState?.sessionOrder ?: emptyList(),
            hideHidden
        )
    }

    var editTarget by remember { mutableStateOf<SessionEditTarget?>(null) }
    if (editTarget != null) {
        SessionEditorView(
            store = store,
            target = editTarget!!,
            currentSessions = sessionState?.sessions ?: emptyMap(),
            onClose = { editTarget = null },
        )
        return
    }

    // --- Drag-reorderable list state ---
    // This is a local mutable copy that the drag gesture manipulates.
    // It syncs FROM the store whenever the store changes AND no drag is in flight.
    var reorderableList by remember { mutableStateOf(storeSessionList) }
    var isDragging by remember { mutableStateOf(false) }

    // Sync from store only when not dragging.
    LaunchedEffect(storeSessionList, isDragging) {
        if (!isDragging) {
            reorderableList = storeSessionList
        }
    }

    // Confirmation dialog state
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }
    var sessionToClear by remember { mutableStateOf<Session?>(null) }

    // --- Confirmation Dialogs ---

    sessionToDelete?.let { session ->
        ConfirmDestructiveDialog(
            title = "Delete Session?",
            message = "Are you sure you want to permanently delete '${session.identity.name}'? This action cannot be undone.",
            onConfirm = {
                store.dispatch(
                    "session",
                    Action(
                        ActionRegistry.Names.SESSION_DELETE,
                        buildJsonObject { put("session", session.identity.localHandle) },
                    ),
                )
                sessionToDelete = null
            },
            onDismiss = { sessionToDelete = null },
        )
    }

    sessionToClear?.let { session ->
        val lockedCount = session.ledger.count { it.isLocked }
        val unlocked = session.ledger.size - lockedCount
        val detail = if (lockedCount > 0) {
            "$unlocked unlocked message(s) will be removed. $lockedCount locked message(s) will be preserved."
        } else {
            "All ${session.ledger.size} message(s) will be removed."
        }
        ConfirmDestructiveDialog(
            title = "Clear Session?",
            message = "Clear '${session.identity.name}'? $detail",
            confirmLabel = "Clear",
            icon = Icons.Default.ClearAll,
            onConfirm = {
                store.dispatch(
                    "session",
                    Action(
                        ActionRegistry.Names.SESSION_CLEAR,
                        buildJsonObject { put("session", session.identity.localHandle) },
                    ),
                )
                sessionToClear = null
            },
            onDismiss = { sessionToClear = null },
        )
    }

    // --- Layout ---

    Column(modifier = Modifier.fillMaxSize()) {
        RaamTopBarHeader(
            title = "Session Manager",
            leading = HeaderLeading.Back(onClick = {
                store.dispatch("core", Action(ActionRegistry.Names.CORE_SHOW_DEFAULT_VIEW))
            }),
            actions = listOf(
                HeaderAction(
                    id = "create-session",
                    label = "Create Session",
                    icon = Icons.Default.Add,
                    priority = 30,
                    emphasis = HeaderActionEmphasis.Create,
                    onClick = { editTarget = SessionEditTarget.Create },
                ),
                HeaderAction(
                    id = "toggle-show-hidden",
                    label = if (hideHidden) "Show hidden sessions" else "Hide hidden sessions",
                    icon = if (hideHidden) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                    priority = 10,
                    onClick = {
                        val newValue = !(sessionState?.hideHiddenInManager ?: true)
                        store.dispatch(
                            "session",
                            Action(
                                ActionRegistry.Names.SETTINGS_UPDATE,
                                buildJsonObject {
                                    put("key", SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                                    put("value", newValue.toString())
                                },
                            ),
                        )
                    },
                ),
            ),
        )

        // --- Session List ---
        val listPadding = MaterialTheme.spacing.screenEdge
        if (reorderableList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(listPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("No sessions found. Create one to begin.")
            }
        } else {
            val lazyListState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            // Stable reference — created once, survives list mutations during drag.
            val dragState = remember {
                DragReorderState(
                    lazyListState = lazyListState,
                    getList = { reorderableList },
                    setList = { reorderableList = it },
                    onDragFinished = { newOrder ->
                        isDragging = false
                        store.dispatch("session", Action(ActionRegistry.Names.SESSION_SET_ORDER, buildJsonObject {
                            put("order", buildJsonArray { newOrder.forEach { add(it) } })
                        }))
                    }
                )
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(horizontal = listPadding),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(reorderableList, key = { _, session -> session.identity.localHandle }) { index, session ->
                    val isDraggingThis = dragState.draggedIndex == index
                    val elevation by animateDpAsState(if (isDraggingThis) 8.dp else 0.dp)

                    // ── Drag handle gesture ──────────────────────────────────
                    //
                    // WHY pointerInput(Unit) and NOT pointerInput(index):
                    //
                    // When the user drags item A past item B, DragReorderState
                    // swaps them in reorderableList, which triggers recomposition.
                    // Because itemsIndexed uses key = session.identity.localHandle, Compose keeps
                    // each composable associated with its session — but 'index'
                    // changes (e.g. 0 → 1). If the pointerInput key were 'index',
                    // Compose would restart the coroutine and cancel the gesture
                    // mid-flight. Using Unit keeps the coroutine (and the gesture)
                    // alive across all recompositions.
                    //
                    // rememberUpdatedState(index) gives us a State<Int> whose
                    // .value always reflects the latest index, so onDragStart
                    // reads the correct position even if the composable was
                    // recomposed before the user started dragging.

                    val currentIndex by rememberUpdatedState(index)

                    val dragHandleModifier = Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragState.onDragStart(currentIndex)
                                isDragging = dragState.isDragging
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragState.onDrag(dragAmount.y)
                                dragState.autoScroll(coroutineScope)
                            },
                            onDragEnd = { dragState.onDragEnd() },
                            onDragCancel = {
                                dragState.onDragCancel()
                                isDragging = false
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .zIndex(if (isDraggingThis) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDraggingThis) dragState.dragOffset else 0f
                            }
                            .shadow(elevation, shape = MaterialTheme.shapes.medium)
                    ) {
                        SessionManagerCard(
                            session = session,
                            store = store,
                            platformDependencies = platformDependencies,
                            onEditRequest = { editTarget = SessionEditTarget.Edit(it.identity.localHandle) },
                            onDeleteRequest = { sessionToDelete = it },
                            onClearRequest = { sessionToClear = it },
                            dragHandleModifier = dragHandleModifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionManagerCard(
    session: Session,
    store: Store,
    platformDependencies: PlatformDependencies,
    onEditRequest: (Session) -> Unit,
    onDeleteRequest: (Session) -> Unit,
    onClearRequest: (Session) -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    // Dim hidden sessions
    val cardAlpha = if (session.isHidden) 0.6f else 1f
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().alpha(cardAlpha),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle — drag starts immediately on primary button press (no long-press required).
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = dragHandleModifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Lightning bolt icon for agent-private (p-cognition) sessions
            if (session.isPrivate) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = "Agent Private Session",
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(session.identity.name, style = MaterialTheme.typography.titleMedium)
                Text("Handle: ${session.identity.localHandle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Messages: ${session.ledger.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val lockedCount = session.ledger.count { it.isLocked }
                    if (lockedCount > 0) {
                        Text("Locked: $lockedCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Visibility toggle
            IconButton(onClick = {
                store.dispatch("session", Action(ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN, buildJsonObject { put("session", session.identity.localHandle) }))
            }) {
                Icon(
                    imageVector = if (session.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (session.isHidden) "Unhide Session" else "Hide Session"
                )
            }
            // Edit Button
            IconButton(onClick = { onEditRequest(session) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit Session")
            }
            // Kebab Menu — Clone, Clear, Delete
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Clone") },
                        onClick = {
                            store.dispatch("session", Action(ActionRegistry.Names.SESSION_CLONE, buildJsonObject { put("session", session.identity.localHandle) }))
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear") },
                        onClick = {
                            onClearRequest(session)
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DangerDropdownMenuItem(
                        label = "Delete",
                        onClick = {
                            onDeleteRequest(session)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

// ============================================================================
// Full-view Session Editor — create or edit a session's identity
// ============================================================================

/**
 * Which session the full-view editor is targeting. [Create] for a fresh
 * session, [Edit] wraps the localHandle of an existing one.
 */
private sealed interface SessionEditTarget {
    data object Create : SessionEditTarget
    data class Edit(val localHandle: String) : SessionEditTarget
}

@Composable
private fun SessionEditorView(
    store: Store,
    target: SessionEditTarget,
    currentSessions: Map<String, Session>,
    onClose: () -> Unit,
) {
    val existing = (target as? SessionEditTarget.Edit)?.let { currentSessions[it.localHandle] }

    val initial = remember(target) {
        existing?.identity?.toDraft() ?: IdentityDraft(name = "")
    }
    var draft by remember(target) { mutableStateOf(initial) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val dirty = draft != initial
    val canSave = draft.name.isNotBlank() && dirty

    val tryClose = {
        if (dirty) showDiscardDialog = true else onClose()
    }

    val onSave = {
        when (target) {
            is SessionEditTarget.Create -> {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_CREATE,
                    buildJsonObject {
                        put("name", draft.name)
                        if (draft.displayColor != null) put("displayColor", draft.displayColor)
                        if (draft.displayIcon != null) put("displayIcon", draft.displayIcon)
                        if (draft.displayEmoji != null) put("displayEmoji", draft.displayEmoji)
                    },
                ))
            }
            is SessionEditTarget.Edit -> {
                store.dispatch("session", Action(
                    ActionRegistry.Names.SESSION_UPDATE_CONFIG,
                    buildJsonObject {
                        put("session", target.localHandle)
                        put("name", draft.name)
                        put("displayColor", draft.displayColor)
                        put("displayIcon", draft.displayIcon)
                        put("displayEmoji", draft.displayEmoji)
                    },
                ))
            }
        }
        onClose()
    }

    Column(Modifier.fillMaxSize()) {
        RaamTopBarHeader(
            title = when (target) {
                is SessionEditTarget.Create -> "New Session"
                is SessionEditTarget.Edit -> existing?.identity?.name ?: "Edit Session"
            },
            subtitle = "Session Manager",
            leading = HeaderLeading.Back(onClick = tryClose),
        )
        IdentityFieldsSection(
            draft = draft,
            onDraftChange = { draft = it },
            nameLabel = "Session Name",
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.itemGap,
                ),
        )
        ViewFooter {
            FooterButton(FooterActionEmphasis.Cancel, "Cancel", onClick = tryClose)
            FooterButton(
                emphasis = FooterActionEmphasis.Confirm,
                label = if (target is SessionEditTarget.Create) "Create" else "Save",
                onClick = onSave,
                enabled = canSave,
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved edits will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}