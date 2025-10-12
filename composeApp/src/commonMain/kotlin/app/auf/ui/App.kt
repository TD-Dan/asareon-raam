package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.auf.core.*
import app.auf.feature.core.CoreState

@Composable
fun App(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val coreState = remember(appState.featureStates) {
        appState.featureStates["core"] as? CoreState
    }

    val snackbarHostState = remember { SnackbarHostState() }

    coreState?.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            store.dispatch("system.ui", Action("core.CLEAR_TOAST"))
        }
    }

    AppTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                MainAppContent(store, features)
            }
        }
    }
}

@Composable
private fun MainAppContent(store: Store, features: List<Feature>) {
    val appState by store.state.collectAsState()
    val activeViewKey = (appState.featureStates["core"] as? CoreState)?.activeViewKey

    val activeStageContent: (@Composable (Store, List<Feature>) -> Unit)? = remember(features, activeViewKey) {
        features
            .asSequence()
            .mapNotNull { it.composableProvider?.stageViews }
            .mapNotNull { it[activeViewKey] }
            .firstOrNull()
    }

    Row(Modifier.fillMaxSize()) {
        GlobalActionRibbon(store, features, activeViewKey)
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (activeStageContent != null) {
                // Pass the full feature list down to the active view.
                activeStageContent(store, features)
            } else {
                Text("Error: No view found for key '$activeViewKey'")
            }
        }
    }
}