package asareon.raam.ui.components.identity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.core.Identity
import asareon.raam.ui.components.ColorPicker
import asareon.raam.ui.components.IconRegistry
import asareon.raam.ui.components.colorToHex
import asareon.raam.ui.components.hexToColor

/**
 * Draft state for the identity fields shared by every identity asset
 * (user, agent, session, script). Mirrors the three fields on [Identity]
 * that a user may edit.
 *
 * Immutable; callers hold it in `mutableStateOf` and produce a new copy
 * via the `onDraftChange` callback. Kept separate from [Identity] so a
 * form can stage edits without mutating the registry until Save.
 */
@Immutable
data class IdentityDraft(
    val name: String,
    val displayColor: String? = null,   // "#RRGGBB" or null for theme default
    val displayIcon: String? = null,    // IconRegistry key or null
    val displayEmoji: String? = null,   // single emoji; takes precedence over displayIcon
)

/** Snapshot the editable identity fields into a draft for a form to stage edits on. */
fun Identity.toDraft(): IdentityDraft = IdentityDraft(
    name = name,
    displayColor = displayColor,
    displayIcon = displayIcon,
    displayEmoji = displayEmoji,
)

/**
 * The shared identity-fields section of an asset editor. Renders the
 * name input, a color-picker row, and an icon-picker row (Material icon
 * grid + emoji override).
 *
 * Every identity-asset editor (user, agent, session, script) embeds this
 * as its first section. For an asset that has nothing beyond its identity
 * (e.g. user), this is the whole editor.
 *
 * @param iconCatalog Which IconRegistry keys to show in the grid. Defaults
 *   to [IconRegistry.agentIcons] — callers may pass a narrower set.
 * @param nameLabel Field label for the name input. Defaults to "Name".
 */
@Composable
fun IdentityFieldsSection(
    draft: IdentityDraft,
    onDraftChange: (IdentityDraft) -> Unit,
    modifier: Modifier = Modifier,
    iconCatalog: List<String> = IconRegistry.agentIcons,
    nameLabel: String = "Name",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text(nameLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ColorRow(draft, onDraftChange)
        IconRow(draft, onDraftChange, iconCatalog)
    }
}

// ────────────────────────────────────────────────────────────────
// Color row
// ────────────────────────────────────────────────────────────────

@Composable
private fun ColorRow(
    draft: IdentityDraft,
    onDraftChange: (IdentityDraft) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val draftColor: Color? = draft.displayColor?.let { hexToColor(it) }
    val swatchColor = draftColor ?: MaterialTheme.colorScheme.primary

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(swatchColor)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        OutlinedButton(onClick = { showPicker = !showPicker }) {
            Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (showPicker) "Hide color picker" else "Set color")
        }
        if (draft.displayColor != null) {
            TextButton(onClick = {
                onDraftChange(draft.copy(displayColor = null))
                showPicker = false
            }) {
                Text("Reset to default")
            }
        }
    }
    AnimatedVisibility(visible = showPicker) {
        ColorPicker(
            initialColor = swatchColor,
            onConfirm = { color ->
                onDraftChange(draft.copy(displayColor = colorToHex(color)))
                showPicker = false
            },
            onCancel = { showPicker = false },
        )
    }
}

// ────────────────────────────────────────────────────────────────
// Icon row
// ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun IconRow(
    draft: IdentityDraft,
    onDraftChange: (IdentityDraft) -> Unit,
    iconCatalog: List<String>,
) {
    var showPicker by remember { mutableStateOf(false) }
    val draftColor: Color? = draft.displayColor?.let { hexToColor(it) }
    val previewTint = draftColor ?: MaterialTheme.colorScheme.primary

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (draft.displayEmoji != null) {
            Text(
                draft.displayEmoji,
                fontSize = 24.sp,
                color = previewTint,
                modifier = Modifier.size(32.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            val iconVector = IconRegistry.resolve(draft.displayIcon) ?: IconRegistry.defaultAgentIcon
            Icon(iconVector, contentDescription = null, modifier = Modifier.size(32.dp), tint = previewTint)
        }
        OutlinedButton(onClick = { showPicker = !showPicker }) {
            Icon(Icons.Default.EmojiEmotions, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (showPicker) "Hide icon picker" else "Set icon")
        }
        if (draft.displayIcon != null || draft.displayEmoji != null) {
            TextButton(onClick = {
                onDraftChange(draft.copy(displayIcon = null, displayEmoji = null))
                showPicker = false
            }) {
                Text("Reset to default")
            }
        }
    }
    AnimatedVisibility(visible = showPicker) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceDim, MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Material Icons",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                iconCatalog.forEach { key ->
                    val icon = IconRegistry.resolve(key) ?: return@forEach
                    val isSelected = draft.displayIcon == key && draft.displayEmoji == null
                    IconButton(
                        onClick = {
                            onDraftChange(draft.copy(displayIcon = key, displayEmoji = null))
                        },
                        modifier = Modifier.size(40.dp).then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            } else Modifier,
                        ),
                    ) {
                        Icon(icon, contentDescription = key, tint = previewTint, modifier = Modifier.size(24.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "Or paste an emoji",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.displayEmoji ?: "",
                    onValueChange = { input ->
                        if (input.isBlank()) {
                            onDraftChange(draft.copy(displayEmoji = null))
                        } else {
                            onDraftChange(
                                draft.copy(
                                    displayEmoji = input.take(2),
                                    displayIcon = null,
                                ),
                            )
                        }
                    },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
                if (draft.displayEmoji != null) {
                    Text(draft.displayEmoji, fontSize = 28.sp, color = previewTint)
                }
            }
        }
    }
}
