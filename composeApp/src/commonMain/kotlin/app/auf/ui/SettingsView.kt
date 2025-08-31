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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.AppState
import app.auf.feature.systemclock.SystemClockState
import app.auf.model.CompilerSettings
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
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
 */
@Composable
fun SettingsView(
    definitions: List<SettingDefinition>,
    appState: AppState, // --- MODIFICATION: Pass full AppState for feature access ---
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
                        appState = appState,
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
    appState: AppState, // --- MODIFICATION: Pass full AppState ---
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
            SettingType.BOOLEAN -> {
                val isChecked = when (definition.key) {
                    // Core settings
                    "compiler.removeWhitespace" -> appState.compilerSettings.removeWhitespace
                    "compiler.cleanHeaders" -> appState.compilerSettings.cleanHeaders
                    "compiler.minifyJson" -> appState.compilerSettings.minifyJson
                    // Feature settings
                    "clock.isEnabled" -> (appState.featureStates["SystemClockFeature"] as? SystemClockState)?.isEnabled ?: false
                    else -> false
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = { newValue ->
                        onSettingChanged(SettingValue(key = definition.key, value = newValue))
                    }
                )
            }
            // --- MODIFICATION START: Add renderer for NUMERIC_LONG ---
            SettingType.NUMERIC_LONG -> {
                val currentValue = when (definition.key) {
                    "clock.intervalMillis" -> (appState.featureStates["SystemClockFeature"] as? SystemClockState)?.intervalMillis ?: 0L
                    else -> 0L
                }
                var textValue by remember(currentValue) { mutableStateOf(currentValue.toString()) }

                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        val filtered = it.filter { char -> char.isDigit() }
                        if (filtered.length <= 18) { // Prevent overflow
                            textValue = filtered
                            val longValue = filtered.toLongOrNull()
                            if (longValue != null) {
                                onSettingChanged(SettingValue(key = definition.key, value = longValue))
                            }
                        }
                    },
                    modifier = Modifier.width(150.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            // --- MODIFICATION END ---
        }
    }
}