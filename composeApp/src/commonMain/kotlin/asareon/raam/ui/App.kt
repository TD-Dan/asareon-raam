package asareon.raam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.core.resolveDisplayColor
import asareon.raam.feature.core.ConfirmationDialog
import asareon.raam.feature.core.CoreState
import asareon.raam.util.WindowsSnapHelper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowEvent

/**
 * CompositionLocal that carries the AWT Window reference from main.kt
 * down to any composable that needs it (e.g. CustomTitleBar).
 */
val LocalAppWindow = staticCompositionLocalOf<Window> {
    error("LocalAppWindow not provided. Wrap your content in CompositionLocalProvider(LocalAppWindow provides window).")
}

@Composable
fun App(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val coreState = remember(appState.featureStates) {
        appState.featureStates["core"] as? CoreState
    }

    val snackbarHostState = remember { SnackbarHostState() }

    coreState?.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            store.dispatch("system", Action(ActionRegistry.Names.CORE_CLEAR_TOAST))
        }
    }

    // ── Identity-based theme override ────────────────────────────────
    val primaryOverride: Color? = if (coreState?.useIdentityColorAsPrimary == true) {
        val activeIdentity = coreState.activeUserId?.let { appState.identityRegistry[it] }
        activeIdentity?.resolveDisplayColor()
    } else null

    AppTheme(primaryOverride = primaryOverride) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                MainAppContent(store, features)

                // --- GLOBAL DIALOGS ---
                coreState?.confirmationRequest?.let { request ->
                    ConfirmationDialog(
                        request = request,
                        onConfirm = {
                            store.dispatch("system", Action(ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
                                put("confirmed", true)
                            }))
                        },
                        onDismiss = {
                            store.dispatch("system", Action(ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
                                put("confirmed", false)
                            }))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainAppContent(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val activeViewKey = (appState.featureStates["core"] as? CoreState)?.activeViewKey

    val activeStageContent: (@Composable (Store, List<Feature>) -> Unit)? = remember(features, activeViewKey) {
        features
            .asSequence()
            .mapNotNull { it.composableProvider?.stageViews }
            .mapNotNull { it[activeViewKey] }
            .firstOrNull()
    }

    Row(Modifier.fillMaxSize()) {
        GlobalActionRibbon(store, features, activeViewKey)
        VerticalDivider()
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CustomTitleBar()
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (activeStageContent != null) {
                    activeStageContent(store, features)
                } else {
                    Text("Error: No view found for key '$activeViewKey'")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Custom Title Bar
// ══════════════════════════════════════════════════════════════════════

/**
 * Converts Compose layout coordinates to a window-relative [Rectangle]
 * in logical (OS-scaled) pixels, suitable for WM_NCHITTEST comparison.
 */
private fun androidx.compose.ui.layout.LayoutCoordinates.toLogicalRect(window: Window): Rectangle {
    val scale = window.graphicsConfiguration.defaultTransform.scaleX
    val pos = positionInWindow()
    return Rectangle(
        (pos.x / scale).toInt(),
        (pos.y / scale).toInt(),
        (size.width / scale).toInt(),
        (size.height / scale).toInt()
    )
}

@Composable
private fun CustomTitleBar() {
    val window = LocalAppWindow.current

    // Install the Windows snap helper once
    LaunchedEffect(window) {
        WindowsSnapHelper.install(window)
    }

    // Fallback drag handler for non-Windows (or if snap helper fails to install)
    var dragStartMousePos by remember { mutableStateOf(Point(0, 0)) }
    var dragStartWindowPos by remember { mutableStateOf(Point(0, 0)) }
    val useComposeDrag = !WindowsSnapHelper.isInstalled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onGloballyPositioned { coords ->
                if (WindowsSnapHelper.isInstalled) {
                    WindowsSnapHelper.titleBarRel = coords.toLogicalRect(window)
                }
            }
            .then(
                if (useComposeDrag) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragStartMousePos = MouseInfo.getPointerInfo().location
                                dragStartWindowPos = window.location
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val currentMouse = MouseInfo.getPointerInfo().location
                                window.location = Point(
                                    dragStartWindowPos.x + (currentMouse.x - dragStartMousePos.x),
                                    dragStartWindowPos.y + (currentMouse.y - dragStartMousePos.y)
                                )
                            }
                        )
                    }
                } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        Text(
            text = Version.APP_NAME,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))

        // ── Window control buttons ──
        // Wrapped in a Row so we can track the combined bounds for WM_NCHITTEST.
        Row(
            modifier = Modifier.onGloballyPositioned { coords ->
                if (WindowsSnapHelper.isInstalled) {
                    WindowsSnapHelper.allButtonsRel = coords.toLogicalRect(window)
                }
            }
        ) {
            // ── Minimize ──
            IconButton(
                onClick = {
                    (window as? Frame)?.state = Frame.ICONIFIED
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Minimize,
                    contentDescription = "Minimize",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ── Maximize / Restore ──
            // When WindowsSnapHelper is active, Windows handles the click via HTMAXBUTTON.
            // The onClick is kept as a fallback for non-Windows platforms.
            IconButton(
                onClick = {
                    val frame = window as? Frame ?: return@IconButton
                    frame.extendedState = if (frame.extendedState == Frame.MAXIMIZED_BOTH)
                        Frame.NORMAL else Frame.MAXIMIZED_BOTH
                },
                modifier = Modifier
                    .size(36.dp)
                    .onGloballyPositioned { coords ->
                        if (WindowsSnapHelper.isInstalled) {
                            WindowsSnapHelper.maximizeButtonRel = coords.toLogicalRect(window)
                        }
                    }
            ) {
                Icon(
                    Icons.Default.CropSquare,
                    contentDescription = "Maximize",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ── Close ──
            IconButton(
                onClick = {
                    window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}