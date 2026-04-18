package asareon.raam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic spacing tokens on the Material 3 8dp grid.
 *
 * Prefer these over raw `.dp` literals so the app reskins from one place.
 * Accessed via [MaterialTheme.spacing] (provided through [LocalSpacing]).
 */
@Immutable
data class Spacing(
    /** Outer padding against screen edges. */
    val screenEdge: Dp = 16.dp,
    /** Between major sections within a screen. */
    val sectionGap: Dp = 24.dp,
    /** Between list items / cards. */
    val itemGap: Dp = 12.dp,
    /** Inside cards; between an icon and its adjacent label. */
    val inner: Dp = 8.dp,
    /** Tightest spacing — chip interiors, micro padding. */
    val tight: Dp = 4.dp,
    /** Height of the app's standard top bar (primary views). */
    val topBarHeight: Dp = 56.dp,
    /**
     * Height of a secondary top bar — the header on a subordinate surface
     * like a [asareon.raam.ui.components.sidepane.SidePane]. Sized to ~75%
     * of [topBarHeight] so pane chrome reads lighter than view chrome.
     */
    val secondaryTopBarHeight: Dp = 42.dp,
    /** Width of the GlobalActionRibbon. */
    val ribbonWidth: Dp = 50.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** Convenience accessor. Usage: `MaterialTheme.spacing.screenEdge`. */
val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable
    get() = LocalSpacing.current
