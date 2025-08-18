package app.auf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The private, internal Material 3 ColorScheme for the light theme,
 * with colors inspired by the content-brand holon.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5), // A strong, accessible version of 'Host LLM Blue'
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0097A7), // A professional, deep version of 'Idea-Flow Cyan'
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFE91E63), // A vibrant, accessible version of 'Framework Pink'
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB00020), // 'Hazard Red'
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5), // A slightly off-white for comfort
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF), // Pure white for cards and surfaces
    onSurface = Color(0xFF1C1B1F),
)

/**
 * The private, internal Material 3 ColorScheme for the dark theme,
 * with colors inspired by the content-brand holon.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF17afb4), // 'Idea Flow cyan'
    onPrimary = Color(0xFF00363D),
    secondary = Color(0xFF4DD0E1), // A brighter version of 'Idea-Flow Cyan' for dark mode
    onSecondary = Color(0xFF00363D),
    tertiary = Color(0xFFF48FB1), // A softer 'Framework Pink' for dark mode
    onTertiary = Color(0xFF5C002B),
    error = Color(0xFFCC354B), // 'Crab red'
    onError = Color(0xFF2B131B),
    background = Color(0xFF121212), // A near-black for the main background
    onBackground = Color(0xFFE7E7E7),
    surface = Color(0xFF232323), // A slightly lighter gray for cards and surfaces
    onSurface = Color(0xFF2D2D2D),
)

/**
 * The main theme composable for the AUF application, now using Material 3.
 * It automatically selects between the Dark and Light color schemes based on the system theme.
 */
@Composable
fun AUFTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}