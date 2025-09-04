package app.auf.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.Feature
import app.auf.core.StateManager
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue

/**
 * A dynamically generated view for displaying and modifying application settings.
 * It acts as a host, collecting and rendering settings UI from all registered features.
 */
@Composable
fun SettingsView(
    stateManager: StateManager,
    features: List<Feature>,
    onClose: () -> Unit
) {
    val allSettingsDefinitions = remember(features) {
        features.flatMap { it.composableProvider?.settingDefinitions ?: emptyList() }
    }
    val groupedSettings = remember(allSettingsDefinitions) {
        allSettingsDefinitions.groupBy { it.section }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(16.dp))
            Text("Application Settings", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedSettings.forEach { (section, definitions) ->
                item {
                    Text(
                        text = section,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    HorizontalDivider()
                }

                items(definitions, key = { it.key }) { definition ->
                    SettingRow(
                        definition = definition,
                        stateManager = stateManager,
                        features = features
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    definition: SettingDefinition,
    stateManager: StateManager,
    features: List<Feature>
) {
    val appState by stateManager.state.collectAsState()

    val currentValue = remember(appState, definition.key, features) {
        // 1. Find the feature that owns this setting definition.
        val ownerFeature = features.find { feature: Feature ->
            feature.composableProvider?.settingDefinitions?.any { it.key == definition.key } ?: false
        }
        // 2. Ask that feature's provider for the current value from the AppState.
        ownerFeature?.composableProvider?.getSettingValue(appState, definition.key)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(definition.label, fontWeight = FontWeight.SemiBold)
            Text(definition.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }

        when (definition.type) {
            SettingType.BOOLEAN -> {
                Switch(
                    checked = currentValue as? Boolean ?: false,
                    onCheckedChange = { newValue ->
                        stateManager.updateSetting(SettingValue(key = definition.key, value = newValue))
                    }
                )
            }
            SettingType.NUMERIC_LONG -> {
                var textValue by remember(currentValue) { mutableStateOf((currentValue as? Long ?: 0L).toString()) }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        val filtered = it.filter { char -> char.isDigit() }
                        if (filtered.length <= 18) {
                            textValue = filtered
                            filtered.toLongOrNull()?.let { longValue ->
                                stateManager.updateSetting(SettingValue(key = definition.key, value = longValue))
                            }
                        }
                    },
                    modifier = Modifier.width(150.dp),
                    singleLine = true
                )
            }
        }
    }
}