package app.auf.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.Action
import app.auf.core.Store
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
fun SettingsView(
    store: Store,
    onClose: () -> Unit
) {
    val appState by store.state.collectAsState()
    val settingsState = appState.featureStates["settings"] as? SettingsState

    val groupedSettings = remember(settingsState?.definitions) {
        settingsState?.definitions?.groupBy { it["section"]?.jsonPrimitive?.content ?: "Uncategorized" } ?: emptyMap()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(16.dp))
            Text("Application Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { store.dispatch("settings.ui", Action("settings.OPEN_FOLDER")) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Open Settings Folder")
            }
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

                items(definitions, key = { it["key"]!!.jsonPrimitive.content }) { definitionJson ->
                    val key = definitionJson["key"]!!.jsonPrimitive.content
                    val defaultValue = definitionJson["defaultValue"]!!.jsonPrimitive.content
                    // The UI now displays the transient input value for responsiveness.
                    val currentValue = settingsState?.inputValues?.get(key) ?: settingsState?.values?.get(key) ?: defaultValue

                    SettingRow(
                        definitionJson = definitionJson,
                        currentValue = currentValue,
                        onValueChange = { newValue ->
                            val payload = buildJsonObject {
                                put("key", key)
                                put("value", newValue.toString())
                            }
                            // Dispatch the correct action based on the setting type
                            val actionName = when (definitionJson["type"]?.jsonPrimitive?.content) {
                                "BOOLEAN" -> "settings.UPDATE" // Booleans update instantly
                                else -> "settings.INPUT_CHANGED" // Text fields are debounced
                            }
                            store.dispatch("settings.ui", Action(actionName, payload))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    definitionJson: JsonObject,
    currentValue: String,
    onValueChange: (Any) -> Unit
) {
    val label = definitionJson["label"]?.jsonPrimitive?.content ?: "No Label"
    val description = definitionJson["description"]?.jsonPrimitive?.content ?: ""
    val type = definitionJson["type"]?.jsonPrimitive?.content

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }

        when (type) {
            "BOOLEAN" -> {
                Switch(
                    checked = currentValue.toBoolean(),
                    onCheckedChange = { onValueChange(it) }
                )
            }
            "NUMERIC_LONG" -> {
                OutlinedTextField(
                    value = currentValue,
                    onValueChange = {
                        if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                            onValueChange(it)
                        }
                    },
                    modifier = Modifier.width(150.dp),
                    singleLine = true
                )
            }
        }
    }
}