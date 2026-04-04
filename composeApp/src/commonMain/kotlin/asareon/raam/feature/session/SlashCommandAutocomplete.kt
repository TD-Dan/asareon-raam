package app.auf.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auf.core.generated.ActionRegistry

/**
 * Renders the slash-command autocomplete panel above the message input.
 *
 * This is a thin rendering layer over [SlashCommandEngine]. All logic —
 * filtering, matching, auto-fill, code generation — lives in the engine.
 * This composable only maps engine state to Material 3 components.
 *
 * Rendered conditionally by [MessageInput] when autocomplete is active.
 */
@Composable
fun SlashCommandPanel(
    engine: SlashCommandEngine,
    state: SlashCommandEngine.AutocompleteState,
    onStateChange: (SlashCommandEngine.AutocompleteState?) -> Unit,
    onInsert: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar with stage breadcrumb and admin badge
            PanelHeader(state, onDismiss = { onStateChange(null) })

            HorizontalDivider()

            // Stage content
            when (state.stage) {
                SlashCommandEngine.Stage.FEATURE -> FeatureStage(engine, state, onStateChange)
                SlashCommandEngine.Stage.ACTION -> ActionStage(engine, state, onStateChange)
                SlashCommandEngine.Stage.PARAMS -> ParamsStage(engine, state, onStateChange, onInsert)
            }
        }
    }
}

// ============================================================================
// Header
// ============================================================================

@Composable
private fun PanelHeader(
    state: SlashCommandEngine.AutocompleteState,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            // Breadcrumb: / > session > POST
            Text(
                text = if (state.adminMode) "//" else "/",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )

            state.selectedFeature?.let { feature ->
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = feature,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            state.selectedAction?.let { action ->
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = action.suffix,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (state.adminMode) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "ADMIN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Stage 1: Feature Selection
// ============================================================================

@Composable
private fun FeatureStage(
    engine: SlashCommandEngine,
    state: SlashCommandEngine.AutocompleteState,
    onStateChange: (SlashCommandEngine.AutocompleteState?) -> Unit
) {
    val candidates = remember(state.query, state.adminMode) {
        engine.featureCandidates(state.query, state.adminMode)
    }

    if (candidates.isEmpty()) {
        EmptyState("No matching features")
    } else {
        val listState = rememberLazyListState()

        LaunchedEffect(state.highlightedIndex) {
            if (candidates.isNotEmpty()) {
                listState.animateScrollToItem(state.highlightedIndex.coerceIn(0, candidates.lastIndex))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
        ) {
            itemsIndexed(candidates) { index, candidate ->
                CandidateRow(
                    isHighlighted = index == state.highlightedIndex,
                    onClick = {
                        onStateChange(engine.selectFeature(state, candidate.name))
                    }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = candidate.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = candidate.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "${candidate.actionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ============================================================================
// Stage 2: Action Selection
// ============================================================================

@Composable
private fun ActionStage(
    engine: SlashCommandEngine,
    state: SlashCommandEngine.AutocompleteState,
    onStateChange: (SlashCommandEngine.AutocompleteState?) -> Unit
) {
    val featureName = state.selectedFeature ?: return
    val candidates = remember(featureName, state.query, state.adminMode) {
        engine.actionCandidates(featureName, state.query, state.adminMode)
    }

    if (candidates.isEmpty()) {
        EmptyState("No matching actions")
    } else {
        val listState = rememberLazyListState()

        LaunchedEffect(state.highlightedIndex) {
            if (candidates.isNotEmpty()) {
                listState.animateScrollToItem(state.highlightedIndex.coerceIn(0, candidates.lastIndex))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
        ) {
            itemsIndexed(candidates) { index, candidate ->
                val descriptor = candidate.descriptor
                CandidateRow(
                    isHighlighted = index == state.highlightedIndex,
                    onClick = {
                        onStateChange(engine.selectAction(state, descriptor))
                    }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = descriptor.suffix,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Medium
                            )
                            // Type badge for admin mode — helps identify internal/event/response
                            if (state.adminMode && !descriptor.public) {
                                val label = when {
                                    descriptor.isInternal -> "internal"
                                    descriptor.isEvent -> "event"
                                    descriptor.isResponse -> "response"
                                    else -> "restricted"
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = descriptor.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Stage 3: Parameter Entry
// ============================================================================

@Composable
private fun ParamsStage(
    engine: SlashCommandEngine,
    state: SlashCommandEngine.AutocompleteState,
    onStateChange: (SlashCommandEngine.AutocompleteState?) -> Unit,
    onInsert: (String) -> Unit
) {
    val descriptor = state.selectedAction ?: return
    val autoFilledKeys = remember(descriptor) {
        engine.autoFillParams(descriptor).keys
    }

    // Focus the first parameter field when entering PARAMS stage
    val firstFieldFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (descriptor.payloadFields.isNotEmpty()) {
            firstFieldFocusRequester.requestFocus()
        }
    }

    // Shared Insert action — used by both the button and Enter key on param fields
    fun performInsert() {
        val codeBlock = engine.generateCodeBlock(descriptor, state.paramValues)
        onInsert(codeBlock)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Action summary
        Text(
            text = descriptor.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        if (descriptor.payloadFields.isEmpty()) {
            Text(
                text = "No parameters. Insert with empty payload?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Parameter fields
            descriptor.payloadFields.forEachIndexed { fieldIndex, field ->
                val isRequired = field.name in descriptor.requiredFields
                val isAutoFilled = field.name in autoFilledKeys
                val currentValue = state.paramValues[field.name] ?: ""
                val isFirstField = fieldIndex == 0

                OutlinedTextField(
                    value = currentValue,
                    onValueChange = { newValue ->
                        onStateChange(engine.updateParamValue(state, field.name, newValue))
                    },
                    label = {
                        Text(buildString {
                            append(field.name)
                            if (isRequired) append(" *")
                        })
                    },
                    placeholder = {
                        if (field.description.isNotBlank()) {
                            Text(
                                text = field.description,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    trailingIcon = {
                        if (isAutoFilled && currentValue == (engine.autoFillParams(descriptor)[field.name] ?: "")) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "auto",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isFirstField) Modifier.focusRequester(firstFieldFocusRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter
                                && !event.isCtrlPressed && !event.isMetaPressed
                            ) {
                                performInsert()
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { onStateChange(null) }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { performInsert() }
            ) {
                Text("Insert")
            }
        }
    }
}

// ============================================================================
// Shared Components
// ============================================================================

@Composable
private fun CandidateRow(
    isHighlighted: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isHighlighted) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}