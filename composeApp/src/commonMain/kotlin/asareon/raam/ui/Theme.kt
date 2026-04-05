package asareon.raam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

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
        // Simple luminance threshold avoids a full contrast-ratio computation.
        val primaryLuminance = primaryOverride.red * 0.2126f +
                primaryOverride.green * 0.7152f +
                primaryOverride.blue * 0.0722f
        val onPrimary = if (primaryLuminance > 0.5f) Color.Black else Color.White

        // For containers, lighten in dark theme and darken in light theme.
        val primaryContainer = if (darkTheme)
            primaryOverride.copy(alpha = 1f)  // use as-is in dark
        else
            primaryOverride.copy(alpha = 1f)

        val resolvedSecondary = secondaryOverride ?: baseScheme.secondary
        val secondaryLuminance = resolvedSecondary.red * 0.2126f +
                resolvedSecondary.green * 0.7152f +
                resolvedSecondary.blue * 0.0722f
        val onSecondary = if (secondaryLuminance > 0.5f) Color.Black else Color.White

        baseScheme.copy(
            primary = primaryOverride,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimary,
            inversePrimary = if (darkTheme) primaryOverride else primaryOverride,
            secondary = resolvedSecondary,
            onSecondary = onSecondary,
            secondaryContainer = blendColor(resolvedSecondary, baseScheme.surface, 0.3f),
            onSecondaryContainer = resolvedSecondary
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