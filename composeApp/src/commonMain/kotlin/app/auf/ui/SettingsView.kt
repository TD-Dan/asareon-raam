package app.auf.ui

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
    // A feature is considered to have settings if it provides a non-empty settings content composable.
    // This is a bit of a placeholder check, a more robust system might have a dedicated flag.
    val featuresWithSettings = features.filter { it.composableProvider != null }

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
            // Group features by their first defined setting's section for UI grouping
            val groupedProviders = featuresWithSettings
                .mapNotNull { it.composableProvider }
                .groupBy {
                    // Heuristic to get a section name. This part is a bit tricky with the new model
                    // but we can default to the feature name. A more robust implementation might
                    // have a dedicated 'settingsSection' property in the provider.
                    it.javaClass.simpleName.replace("ComposableProvider", "")
                }


            groupedProviders.forEach { (section, providers) ->
                item {
                    Text(
                        text = section,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    HorizontalDivider()
                }
                item {
                    providers.forEach { provider ->
                        provider.SettingsContent(stateManager)
                    }
                }
            }
        }
    }
}