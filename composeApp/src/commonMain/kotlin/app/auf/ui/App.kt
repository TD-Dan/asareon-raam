package app.auf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.StateManager
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode

/**
 * The root Composable for the entire AUF application's UI.
 */
@Composable
fun App(stateManager: StateManager) {
    val appState by stateManager.state.collectAsState()
    val kgState = remember(appState.featureStates) {
        appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    appState.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            stateManager.clearToast()
        }
    }

    AppTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                when {
                    kgState.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Loading Knowledge Graph...")
                            }
                        }
                    }
                    kgState.fatalError != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(kgState.fatalError)
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = { stateManager.retryLoadHolonGraph() }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    else -> {
                        MainAppContent(stateManager)
                    }
                }
            }
        }
    }
}

/**
 * Extracted the main UI content to a separate composable for clarity and reuse.
 */
@Composable
private fun MainAppContent(stateManager: StateManager) {
    val appState by stateManager.state.collectAsState()
    val kgState = remember(appState.featureStates) {
        appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
    }


    Row(Modifier.fillMaxSize()) {
        GlobalActionRibbon(stateManager)
        VerticalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Main content area, reacts to the feature's view mode
            if (kgState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
                Row(Modifier.fillMaxSize()) {
                    SessionView(
                        kgState = kgState,
                        onFilter = { stateManager.setCatalogueFilter(it) },
                        onHolonSelected = { stateManager.onHolonClicked(it) },
                        modifier = Modifier.width(320.dp)
                    )
                    VerticalDivider()
                    Box(modifier = Modifier.weight(1f)) {
                        ChatView(stateManager = stateManager)
                    }
                    VerticalDivider()
                    HolonInspectorView(kgState = kgState, modifier = Modifier.width(320.dp))
                }
            } else {
                // When in a special view mode, it takes over the main area.
                when (kgState.viewMode) {
                    KnowledgeGraphViewMode.SETTINGS -> {
                        SettingsView(
                            definitions = stateManager.getSettingDefinitions(),
                            appState = appState,
                            onSettingChanged = { stateManager.updateSetting(it) },
                            onClose = { stateManager.setKnowledgeGraphViewMode(KnowledgeGraphViewMode.INSPECTOR) }
                        )
                    }
                    KnowledgeGraphViewMode.EXPORT -> {
                        ExportView(
                            kgState = kgState,
                            stateManager = stateManager
                        )
                    }
                    KnowledgeGraphViewMode.IMPORT -> {
                        ImportView(
                            kgState = kgState,
                            stateManager = stateManager
                        )
                    }
                    else -> {} // Should not happen
                }
            }
        }
    }
}