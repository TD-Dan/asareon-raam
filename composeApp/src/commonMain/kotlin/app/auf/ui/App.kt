package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.auf.core.*
import app.auf.feature.knowledgegraph.KnowledgeGraphState

@Composable
fun App(stateManager: StateManager, features: List<Feature>) {
    val appState by stateManager.state.collectAsState()
    // We still need the KG state for the initial loading/error screens.
    val kgState = remember(appState.featureStates) {
        appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: KnowledgeGraphState()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    appState.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            stateManager.dispatch(ClearToast)
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
    val activeViewKey = appState.activeViewKey

    // Find the feature whose ComposableProvider has the currently active key.
    val activeFeatureProvider = features
        .mapNotNull { it.composableProvider }
        .find { it.viewKey == activeViewKey }

    Row(Modifier.fillMaxSize()) {
        // 1. The Ribbon is built dynamically from ALL features.
        GlobalActionRibbon(stateManager, features, activeViewKey)
        VerticalDivider()

        // 2. The Stage renders the content from the ONE active feature.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            activeFeatureProvider?.StageContent(stateManager)
                ?: Text("Error: No view found for key '$activeViewKey'")
        }
    }
}