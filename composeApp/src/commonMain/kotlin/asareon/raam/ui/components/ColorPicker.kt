package asareon.raam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// Public API
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A two-tab color picker with draft semantics.
 *
 * Changes are internal until the user commits with "Use" (or Ctrl+Enter).
 * "Cancel" (or Escape) discards the draft and invokes [onCancel].
 *
 * **Hue tab (default):** Single slider selecting hue 0–360°. Saturation and
 * lightness are locked to [referenceColor] so every selection matches the
 * design language.
 *
 * **Advanced tab:** Full HSL sliders + hex input (#RRGGBB).
 *
 * @param initialColor  The color shown when the picker opens.
 * @param onConfirm     Called with the selected color when the user commits.
 * @param onCancel      Called when the user discards changes.
 * @param referenceColor S/L source for the Hue tab. Defaults to theme primary.
 */
@Composable
fun ColorPicker(
    initialColor: Color,
    onConfirm: (Color) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    referenceColor: Color = MaterialTheme.colorScheme.primary
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Hue", "Advanced")

    // Draft color — internal until committed
    var draftColor by remember(initialColor) { mutableStateOf(initialColor) }

    val refHsl = remember(referenceColor) { colorToHsl(referenceColor) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceDim, MaterialTheme.shapes.small)
            .padding(12.dp)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        event.key == Key.Escape -> { onCancel(); true }
                        event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) -> {
                            onConfirm(draftColor); true
                        }
                        else -> false
                    }
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tab selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Text(title, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        when (selectedTab) {
            0 -> HueTab(
                color = draftColor,
                onColorChanged = { draftColor = it },
                referenceSaturation = refHsl[1],
                referenceLightness = refHsl[2]
            )
            1 -> AdvancedTab(
                color = draftColor,
                onColorChanged = { draftColor = it }
            )
        }

        // Commit / Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConfirm(draftColor) }) {
                Text("Use")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Hue Tab — single slider, locked S/L from reference color
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun HueTab(
    color: Color,
    onColorChanged: (Color) -> Unit,
    referenceSaturation: Float,
    referenceLightness: Float
) {
    val currentHsl = remember(color) { colorToHsl(color) }
    var hue by remember(color) { mutableStateOf(currentHsl[0]) }

    val hueGradient = remember(referenceSaturation, referenceLightness) {
        val stops = (0..6).map { i ->
            val h = i * 60f
            hslToColor(h, referenceSaturation, referenceLightness)
        }
        Brush.horizontalGradient(stops)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ColorSwatch(color = hslToColor(hue, referenceSaturation, referenceLightness), size = 40)
            Text(
                text = "Hue: ${hue.roundToInt()}°",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .align(Alignment.Center)
                    .clip(MaterialTheme.shapes.small)
                    .background(hueGradient)
            )
            Slider(
                value = hue,
                onValueChange = {
                    hue = it
                    onColorChanged(hslToColor(it, referenceSaturation, referenceLightness))
                },
                valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Advanced Tab — full HSL sliders + hex
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun AdvancedTab(
    color: Color,
    onColorChanged: (Color) -> Unit
) {
    val hsl = remember(color) { colorToHsl(color) }
    var hue by remember(color) { mutableStateOf(hsl[0]) }
    var saturation by remember(color) { mutableStateOf(hsl[1]) }
    var lightness by remember(color) { mutableStateOf(hsl[2]) }
    var hexInput by remember(color) { mutableStateOf(colorToHex(color)) }

    val currentColor = hslToColor(hue, saturation, lightness)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ColorSwatch(color = currentColor, size = 40)
            Text(
                text = colorToHex(currentColor),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LabeledSlider("H", hue, 0f..360f, "°") {
            hue = it
            val c = hslToColor(it, saturation, lightness)
            hexInput = colorToHex(c)
            onColorChanged(c)
        }
        LabeledSlider("S", saturation * 100f, 0f..100f, "%") {
            saturation = it / 100f
            val c = hslToColor(hue, it / 100f, lightness)
            hexInput = colorToHex(c)
            onColorChanged(c)
        }
        LabeledSlider("L", lightness * 100f, 0f..100f, "%") {
            lightness = it / 100f
            val c = hslToColor(hue, saturation, it / 100f)
            hexInput = colorToHex(c)
            onColorChanged(c)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Hex",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
            BasicTextField(
                value = hexInput,
                onValueChange = { input ->
                    hexInput = input
                    hexToColor(input)?.let { parsed ->
                        val parsedHsl = colorToHsl(parsed)
                        hue = parsedHsl[0]
                        saturation = parsedHsl[1]
                        lightness = parsedHsl[2]
                        onColorChanged(parsed)
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Shared UI components
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ColorSwatch(color: Color, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${value.roundToInt()}$suffix",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Color math — pure Kotlin, KMP-safe, RGB only (no alpha)
// ═══════════════════════════════════════════════════════════════════════════

/** Converts a Compose [Color] to HSL. Returns [hue (0-360), saturation (0-1), lightness (0-1)]. */
fun colorToHsl(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val delta = max - min

    if (delta < 0.00001f) {
        return floatArrayOf(0f, 0f, l)
    }

    val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val h = when (max) {
        r -> ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
        g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }
    return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

/** Converts HSL values to a Compose [Color]. Alpha is always 1.0. */
fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val h = hue.coerceIn(0f, 360f)
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    if (s < 0.00001f) {
        return Color(l, l, l)
    }

    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q

    fun hueToRgb(t: Float): Float {
        val tt = when {
            t < 0f -> t + 1f
            t > 1f -> t - 1f
            else -> t
        }
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }

    val hNorm = h / 360f
    return Color(
        red = hueToRgb(hNorm + 1f / 3f).coerceIn(0f, 1f),
        green = hueToRgb(hNorm).coerceIn(0f, 1f),
        blue = hueToRgb(hNorm - 1f / 3f).coerceIn(0f, 1f)
    )
}

/** Converts a Compose [Color] to "#RRGGBB" hex string. Alpha is ignored. */
fun colorToHex(color: Color): String {
    val r = (color.red * 255).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
    return "#" + toHexByte(r) + toHexByte(g) + toHexByte(b)
}

private fun toHexByte(value: Int): String {
    val hex = "0123456789ABCDEF"
    return "${hex[(value shr 4) and 0xF]}${hex[value and 0xF]}"
}

/**
 * Parses a "#RRGGBB" hex string to [Color]. Returns null on invalid input.
 */
fun hexToColor(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    return try {
        val rgb = clean.toLong(16)
        Color(
            red = ((rgb shr 16) and 0xFF).toInt() / 255f,
            green = ((rgb shr 8) and 0xFF).toInt() / 255f,
            blue = (rgb and 0xFF).toInt() / 255f
        )
    } catch (_: NumberFormatException) {
        null
    }
}