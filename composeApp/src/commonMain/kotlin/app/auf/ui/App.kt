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
import app.auf.core.GatewayStatus
import app.auf.core.ViewMode

/**
 * The root Composable for the entire AUF application's UI.
 *
 * ---
 * ## Mandate
 * This component's sole responsibility is to act as the main router and orchestrator for the UI.
 * It observes the state from the provided StateManager and wires "dumb" view components
 * to the appropriate ViewModel or StateManager functions, passing state down and routing events up.
 * It does NOT create or manage its own dependencies.
 *
 * ---
 * ## Dependencies
 * - `app.auf.core.StateManager`
 *
 * @version 3.1
 * @since 2025-08-17
 */
@Composable
fun App(stateManager: StateManager) {
    val appState by stateManager.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    appState.toastMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            stateManager.clearToast()
        }
    }

    AUFTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                when (appState.gatewayStatus) {
                    GatewayStatus.LOADING -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Loading Knowledge Graph...")
                            }
                        }
                    }
                    GatewayStatus.ERROR -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineMedium)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(appState.errorMessage ?: "An unknown error occurred.")
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = { stateManager.retryLoadHolonGraph() }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    GatewayStatus.OK, GatewayStatus.IDLE -> {
                        Row(Modifier.fillMaxSize()) {
                            GlobalActionRibbon(stateManager)
                            VerticalDivider()

                            SessionView(
                                appState = appState,
                                onFilter = { stateManager.setCatalogueFilter(it) },
                                onHolonSelected = { stateManager.onHolonClicked(it) },
                                modifier = Modifier.width(320.dp)
                            )

                            VerticalDivider()

                            Box(modifier = Modifier.weight(1f)) {
                                when (appState.currentViewMode) {
                                    ViewMode.CHAT -> ChatView(appState = appState, stateManager = stateManager)
                                    ViewMode.EXPORT -> ExportView(appState = appState, stateManager = stateManager)
                                    ViewMode.IMPORT -> {
                                        ImportView(
                                            viewModel = stateManager.importExportViewModel,
                                            currentGraph = appState.holonGraph.map { it.header },
                                            personaId = appState.aiPersonaId ?: "",
                                            onCancel = { stateManager.setViewMode(ViewMode.CHAT) }
                                        )
                                    }
                                }
                            }

                            VerticalDivider()

                            HolonInspectorView(appState = appState, modifier = Modifier.width(320.dp))
                        }
                    }
                }
            }
        }
    }
}