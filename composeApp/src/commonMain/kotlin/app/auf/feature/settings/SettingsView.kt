package app.auf.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.Store
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SettingsView(
    store: Store,
    onClose: () -> Unit
) {
    val settingsState by remember {
        derivedStateOf { store.state.value.featureStates["SettingsFeature"] as? SettingsState }
    }

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

                items(definitions, key = { it["key"]!!.jsonPrimitive.content }) { definitionJson ->
                    SettingRow(
                        definitionJson = definitionJson,
                        currentValue = "N/A" // Placeholder
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    definitionJson: JsonObject,
    currentValue: Any?
) {
    // At the view layer, we dynamically parse the properties from the JSON contract.
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
                    checked = currentValue as? Boolean ?: false,
                    onCheckedChange = { /* TODO: dispatch action */ }
                )
            }
            "NUMERIC_LONG" -> {
                OutlinedTextField(
                    value = (currentValue as? Long ?: 0L).toString(),
                    onValueChange = { /* TODO: dispatch action */ },
                    modifier = Modifier.width(150.dp),
                    singleLine = true
                )
            }
        }
    }
}