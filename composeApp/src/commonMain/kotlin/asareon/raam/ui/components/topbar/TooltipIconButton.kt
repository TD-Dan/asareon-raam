package asareon.raam.ui.components.topbar

import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Default hover delay before the tooltip appears, in milliseconds. */
const val DefaultTooltipHoverDelayMillis: Long = 700L

/**
 * An `IconButton` that shows a Material 3 plain tooltip with [label] after
 * [hoverDelayMillis] of sustained hover (desktop) or on long-press (touch).
 *
 * M3's built-in hover trigger shows tooltips almost immediately, which feels
 * noisy on densely-packed header bars. This wrapper disables the built-in
 * trigger and debounces the hover signal so tooltips only appear on genuine
 * intent-to-read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hoverDelayMillis: Long = DefaultTooltipHoverDelayMillis,
    content: @Composable () -> Unit,
) {
    val tooltipState = rememberTooltipState()
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(interactionSource, hoverDelayMillis) {
        var showJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is HoverInteraction.Enter -> {
                    showJob?.cancel()
                    showJob = scope.launch {
                        delay(hoverDelayMillis)
                        tooltipState.show()
                    }
                }
                is HoverInteraction.Exit -> {
                    showJob?.cancel()
                    tooltipState.dismiss()
                }
            }
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = tooltipState,
        enableUserInput = false,
    ) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            interactionSource = interactionSource,
            content = content,
        )
    }
}
