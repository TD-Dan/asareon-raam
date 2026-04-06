package asareon.raam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import asareon.raam.core.Version
import asareon.raam.util.WindowsSnapHelper
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.WindowEvent

/** Title bar height — must match the Row height below. */
private val TITLE_BAR_HEIGHT = 36.dp

/** Width of each window control button (min / max / close). */
private val BUTTON_WIDTH = 46.dp

/** Width of the left ribbon + divider (must match GlobalActionRibbon). */
private val RIBBON_WIDTH = 51.dp  // 50.dp ribbon + 1.dp divider

/**
 * The JVM/Desktop custom title bar. Provides:
 * - Window drag (native via HTCAPTION on Windows, Compose fallback elsewhere)
 * - Minimize, maximize (with Windows 11 Snap Layout flyout), close
 * - Resize borders via [WindowsSnapHelper]
 *
 * @param window The AWT Window reference from the Compose Desktop Window scope.
 */
@Composable
fun CustomTitleBar(window: Window) {
    val density = LocalDensity.current

    // Install the Windows snap helper once, converting dp → physical pixels.
    LaunchedEffect(window) {
        with(density) {
            WindowsSnapHelper.install(
                window = window,
                titleBarHeightPx = TITLE_BAR_HEIGHT.roundToPx(),
                buttonWidthPx = BUTTON_WIDTH.roundToPx(),
                ribbonWidthPx = RIBBON_WIDTH.roundToPx()
            )
        }
    }

    // Fallback drag handler for non-Windows (or if snap helper fails to install)
    var dragStartMousePos by remember { mutableStateOf(Point(0, 0)) }
    var dragStartWindowPos by remember { mutableStateOf(Point(0, 0)) }
    val useComposeDrag = !WindowsSnapHelper.isInstalled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TITLE_BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer)
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

        // ── Window control buttons (square, Windows 11 style) ──
        // On Windows these are handled natively by WindowsSnapHelper (HTMINBUTTON,
        // HTMAXBUTTON, HTCLOSE). The onClick handlers are cross-platform fallbacks.

        // ── Minimize ──
        TitleBarButton(
            icon = Icons.Default.Minimize,
            contentDescription = "Minimize",
            onClick = { (window as? Frame)?.state = Frame.ICONIFIED }
        )

        // ── Maximize / Restore ──
        TitleBarButton(
            icon = Icons.Default.CropSquare,
            contentDescription = "Maximize",
            onClick = {
                val frame = window as? Frame ?: return@TitleBarButton
                frame.extendedState = if (frame.extendedState == Frame.MAXIMIZED_BOTH)
                    Frame.NORMAL else Frame.MAXIMIZED_BOTH
            }
        )

        // ── Close (red hover) ──
        TitleBarButton(
            icon = Icons.Default.Close,
            contentDescription = "Close",
            hoverColor = Color(0xFFE81123),
            hoverIconTint = Color.White,
            onClick = {
                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            }
        )
    }
}

/**
 * A flat, square title bar button following Windows 11 chrome style.
 * Shows a subtle background on hover — no rounded corners.
 */
@Composable
private fun TitleBarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    hoverColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    hoverIconTint: Color? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val iconTint = if (isHovered && hoverIconTint != null) hoverIconTint
    else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .width(BUTTON_WIDTH)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // no ripple — flat Windows 11 style
                onClick = onClick
            )
            .background(if (isHovered) hoverColor else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
    }
}