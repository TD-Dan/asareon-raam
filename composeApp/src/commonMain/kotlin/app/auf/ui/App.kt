package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.Feature
import app.auf.core.StateManager
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode

@Composable
fun App(stateManager: StateManager, features: List<Feature>) {
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
                    kgState.isLoading -> { /* TODO: Loading indicator */ }
                    kgState.fatalError != null -> { /* TODO: Fatal error screen */ }
                    else -> {
                        MainAppContent(stateManager, features)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainAppContent(stateManager: StateManager, features: List<Feature>) {
    val appState by stateManager.state.collectAsState()
    val kgState = remember(appState.featureStates) {
        appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
    }

    Row(Modifier.fillMaxSize()) {
        GlobalActionRibbon(stateManager)
        VerticalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (kgState.viewMode == KnowledgeGraphViewMode.INSPECTOR) {
                Row(Modifier.fillMaxSize()) {
                    KnowledgeGraphCatalogueView(
                        kgState = kgState,
                        onFilter = { stateManager.setCatalogueFilter(it) },
                        onHolonSelected = { stateManager.onHolonClicked(it) },
                        modifier = Modifier.width(320.dp)
                    )
                    VerticalDivider()
                    Box(modifier = Modifier.weight(1f)) {
                        SessionView(stateManager = stateManager, features = features)
                    }
                    VerticalDivider()
                    HolonInspectorView(kgState = kgState, modifier = Modifier.width(320.dp))
                }
            } else {
                // This will be the next refactor target to clean up the main view
                when (kgState.viewMode) {
                    KnowledgeGraphViewMode.SETTINGS -> SettingsView(
                        definitions = stateManager.getSettingDefinitions(),
                        appState = appState,
                        onSettingChanged = { stateManager.updateSetting(it) },
                        onClose = { stateManager.setKnowledgeGraphViewMode(KnowledgeGraphViewMode.INSPECTOR) }
                    )
                    KnowledgeGraphViewMode.EXPORT -> ExportView(kgState, stateManager)
                    KnowledgeGraphViewMode.IMPORT -> ImportView(kgState, stateManager)
                    else -> {}
                }
            }
        }
    }
}