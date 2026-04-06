package asareon.raam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended semantic colors not covered by the Material 3 core color scheme.
 * Accessed via [MaterialTheme.extendedColors] (provided through [LocalExtendedColors]).
 */
@Immutable
data class ExtendedColors(
    /** Warning/caution — "proceed with care". Permission CAUTION level, escalation. */
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Success/safe — permission LOW level, healthy status, confirmations. */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** Danger/critical — permission DANGER level, destructive actions, security. */
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color
)

private val lightExtendedColors = ExtendedColors(
    warning = warningLight,
    onWarning = onWarningLight,
    warningContainer = warningContainerLight,
    onWarningContainer = onWarningContainerLight,
    success = successLight,
    onSuccess = onSuccessLight,
    successContainer = successContainerLight,
    onSuccessContainer = onSuccessContainerLight,
    danger = dangerLight,
    onDanger = onDangerLight,
    dangerContainer = dangerContainerLight,
    onDangerContainer = onDangerContainerLight
)

private val darkExtendedColors = ExtendedColors(
    warning = warningDark,
    onWarning = onWarningDark,
    warningContainer = warningContainerDark,
    onWarningContainer = onWarningContainerDark,
    success = successDark,
    onSuccess = onSuccessDark,
    successContainer = successContainerDark,
    onSuccessContainer = onSuccessContainerDark,
    danger = dangerDark,
    onDanger = onDangerDark,
    dangerContainer = dangerContainerDark,
    onDangerContainer = onDangerContainerDark
)

val LocalExtendedColors = staticCompositionLocalOf { lightExtendedColors }

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

/**
 * The main theme composable for the Raam application, using Material 3.
 * It automatically selects between the Dark and Light color schemes based on the system theme.
 *
 * When [primaryOverride] is non-null, the primary and secondary color families
 * are replaced with the provided colors, enabling per-user theming.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    primaryOverride: Color? = null,
    secondaryOverride: Color? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) darkScheme else lightScheme

    val colorScheme = if (primaryOverride != null) {
        // Compute onPrimary: white for dark colors, black for light.
        val onPrimary = contrastingOnColor(primaryOverride)

        // For containers, lighten in dark theme and darken in light theme.
        val primaryContainer = if (darkTheme)
            primaryOverride.copy(alpha = 1f)
        else
            primaryOverride.copy(alpha = 1f)

        // ── Derive secondary: hue+10°, saturation×0.75, lightness×1.00 ──
        val resolvedSecondary = secondaryOverride ?: run {
            val (h, s, l) = colorToHsl(primaryOverride)
            hslToColor((h + 10f + 360f) % 360f, s * 0.75f, l * 1.0f)
        }
        val onSecondary = contrastingOnColor(resolvedSecondary)

        // ── Derive tertiary: hue+60°, saturation×0.85, lightness×1.00 ───
        val resolvedTertiary = run {
            val (h, s, l) = colorToHsl(primaryOverride)
            hslToColor((h + 60f) % 360f, s * 0.85f, l * 1.00f)
        }
        val onTertiary = contrastingOnColor(resolvedTertiary)

        baseScheme.copy(
            primary = primaryOverride,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimary,
            inversePrimary = if (darkTheme) primaryOverride else primaryOverride,
            secondary = resolvedSecondary,
            onSecondary = onSecondary,
            secondaryContainer = blendColor(resolvedSecondary, baseScheme.surface, 0.3f),
            onSecondaryContainer = resolvedSecondary,
            tertiary = resolvedTertiary,
            onTertiary = onTertiary,
            tertiaryContainer = blendColor(resolvedTertiary, baseScheme.surface, 0.3f),
            onTertiaryContainer = resolvedTertiary
        )
    } else {
        baseScheme
    }

    val extendedColors = if (darkTheme) darkExtendedColors else lightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content
        )
    }
}

/**
 * Convenience accessor for extended semantic colors.
 * Usage: `MaterialTheme.extendedColors.warning`
 */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable @ReadOnlyComposable
    get() = LocalExtendedColors.current

/** Blends [foreground] over [background] at the given [ratio] (0.0 = all background, 1.0 = all foreground). */
private fun blendColor(foreground: Color, background: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = foreground.red * r + background.red * (1f - r),
        green = foreground.green * r + background.green * (1f - r),
        blue = foreground.blue * r + background.blue * (1f - r)
    )
}

/**
 * Returns [Color.White] for dark colors and [Color.Black] for light colors.
 * Uses BT.709 luminance coefficients with a 0.5 threshold.
 */
private fun contrastingOnColor(color: Color): Color {
    val luminance = color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f
    return if (luminance > 0.5f) Color.Black else Color.White
}

// ═══════════════════════════════════════════════════════════════════════════
// HSL ↔ Compose Color conversion
// ═══════════════════════════════════════════════════════════════════════════
// Compose Multiplatform does not include HSL utilities, so we provide a
// minimal implementation for hue-rotating the primary identity color into
// harmonious secondary (−30°) and tertiary (+60°) colors.

/** HSL triple: hue 0–360, saturation 0–1, lightness 0–1. */
private data class Hsl(val h: Float, val s: Float, val l: Float)

/** Converts a Compose [Color] (sRGB) to HSL. */
private fun colorToHsl(color: Color): Hsl {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, l) // achromatic

    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
        g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return Hsl(h % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

/** Converts HSL back to a Compose [Color]. */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val sClamped = s.coerceIn(0f, 1f)
    val lClamped = l.coerceIn(0f, 1f)
    if (sClamped == 0f) return Color(lClamped, lClamped, lClamped)

    val q = if (lClamped < 0.5f) lClamped * (1f + sClamped) else lClamped + sClamped - lClamped * sClamped
    val p = 2f * lClamped - q
    val hNorm = h / 360f
    return Color(
        red = hueToRgb(p, q, hNorm + 1f / 3f),
        green = hueToRgb(p, q, hNorm),
        blue = hueToRgb(p, q, hNorm - 1f / 3f)
    )
}

private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
    val t = when {
        tIn < 0f -> tIn + 1f
        tIn > 1f -> tIn - 1f
        else -> tIn
    }
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}