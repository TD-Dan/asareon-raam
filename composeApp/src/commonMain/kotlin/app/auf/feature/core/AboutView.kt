package app.auf.feature.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.Version

@Composable
fun AboutView(store: Store) {
    val appState by store.state.collectAsState()
    val loadedFeatures = appState.featureStates.keys.sorted()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // --- Header ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = { store.dispatch(Action("core.SHOW_DEFAULT_VIEW", null, "core.ui")) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(16.dp))
            Text("About", style = MaterialTheme.typography.headlineSmall)
        }

        // --- Content ---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Version Info
            item {
                Text(
                    text = "Version Information",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("AUF App Version: ${Version.APP_VERSION}")
            }

            // Loaded Features
            item {
                Text(
                    text = "Loaded Features",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                HorizontalDivider()
            }
            items(loadedFeatures) { featureName ->
                Text("• $featureName", modifier = Modifier.padding(start = 16.dp))
            }

            // Copyright and Links
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Copyright & Links",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("AUF (Ai User Framework) and AUF App copyright 2025 Daniel Herkert")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = "https://github.com/TD-Dan/Ai-User-Framework",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Framework Repository") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = "https://github.com/TD-Dan/AUF-App",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Application Repository") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}