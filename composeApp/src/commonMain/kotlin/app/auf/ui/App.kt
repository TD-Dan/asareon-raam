package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.auf.core.Action
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.feature.core.CoreState

@Composable
fun App(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val coreState = remember(appState.featureStates) {
        appState.featureStates["CoreFeature"] as? CoreState
    }

    val snackbarHostState = remember { SnackbarHostState() }

    coreState?.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            store.dispatch(Action("core.CLEAR_TOAST", null, "app"))
        }
    }

    AppTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // The main content is no longer gated by a specific feature's state.
                // It renders the generic application structure.
                MainAppContent(store, features)
            }
        }
    }
}

@Composable
private fun MainAppContent(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val activeViewKey = (appState.featureStates["CoreFeature"] as? CoreState)?.activeViewKey

    // Find the feature whose ComposableProvider has the currently active key.
    val activeFeatureProvider = features
        .mapNotNull { it.composableProvider }
        .find { it.viewKey == activeViewKey }

    Row(Modifier.fillMaxSize()) {
        // 1. The Ribbon is built dynamically from ALL features.
        GlobalActionRibbon(store, features, activeViewKey)
        VerticalDivider()

        // 2. The Stage renders the content from the ONE active feature.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            activeFeatureProvider?.StageContent(store)
                ?: Text("Error: No view found for key '$activeViewKey'")
        }
    }
}