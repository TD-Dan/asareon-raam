package app.auf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.ViewMode
import app.auf.model.CompilerSettings
import app.auf.model.SettingDefinition
import app.auf.model.SettingValue

/**
 * A dynamically generated view for displaying and modifying application settings.
 *
 * ---
 * ## Mandate
 * This composable's sole responsibility is to render a UI based on a list of
 * `SettingDefinition` objects. It is a "dumb" component that is completely decoupled
 * from the services that define the settings. It groups settings by section and renders
 * the appropriate control (e.g., a Switch for a Boolean) for each definition.
 *
 * ---
 * ## Dependencies
 * - `app.auf.model.SettingDefinition`: The schema for rendering.
 * - `app.auf.model.CompilerSettings`: The current state of the settings.
 *
 * @version 1.0
 * @since 2025-08-25
 */
@Composable
fun SettingsView(
    definitions: List<SettingDefinition>,
    compilerSettings: CompilerSettings,
    onSettingChanged: (SettingValue) -> Unit,
    onClose: () -> Unit
) {
    val groupedDefinitions = definitions.groupBy { it.section }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to Chat")
            }
            Spacer(Modifier.width(16.dp))
            Text("Application Settings", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedDefinitions.forEach { (section, defs) ->
                item {
                    Text(
                        text = section,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    HorizontalDivider()
                }
                items(defs) { definition ->
                    SettingRow(
                        definition = definition,
                        compilerSettings = compilerSettings,
                        onSettingChanged = onSettingChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    definition: SettingDefinition,
    compilerSettings: CompilerSettings,
    onSettingChanged: (SettingValue) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(definition.label, fontWeight = FontWeight.SemiBold)
            Text(
                definition.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )
        }

        // This `when` block makes the UI extensible to new setting types.
        when (definition.type) {
            app.auf.model.SettingType.BOOLEAN -> {
                val isChecked = when (definition.key) {
                    "compiler.removeWhitespace" -> compilerSettings.removeWhitespace
                    "compiler.cleanHeaders" -> compilerSettings.cleanHeaders
                    "compiler.minifyJson" -> compilerSettings.minifyJson
                    else -> false
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = { newValue ->
                        onSettingChanged(SettingValue(key = definition.key, value = newValue))
                    }
                )
            }
        }
    }
}