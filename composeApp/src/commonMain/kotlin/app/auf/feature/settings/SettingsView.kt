package app.auf.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Feature
import app.auf.core.StateManager

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

                // Find the feature that provides the UI for this section
                val provider = features.find { feature ->
                    feature.composableProvider?.settingDefinitions?.any { it.section == section } ?: false
                }?.composableProvider

                if (provider != null) {
                    item {
                        provider.SettingsContent(stateManager)
                    }
                }
            }
        }
    }
}