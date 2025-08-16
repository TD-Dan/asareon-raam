package app.auf

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.auf.core.GatewayStatus
import app.auf.core.ViewMode
import app.auf.ui.ChatView
import app.auf.ui.ExportView
import app.auf.ui.GlobalActionRibbon
import app.auf.ui.HolonInspectorView
import app.auf.ui.ImportView
import app.auf.ui.SessionView

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
 * - `app.auf.StateManager`
 *
 * @version 2.3
 * @since 2025-08-15
 */
@Composable
fun App(stateManager: StateManager) {
    val appState by stateManager.state.collectAsState()

    MaterialTheme {
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
                        Text("Error", color = Color.Red, style = MaterialTheme.typography.h4)
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
                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    SessionView(
                        appState = appState,
                        onFilter = { stateManager.setCatalogueFilter(it) },
                        onHolonSelected = { stateManager.onHolonClicked(it) },
                        modifier = Modifier.width(320.dp)
                    )

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        when (appState.currentViewMode) {
                            ViewMode.CHAT -> ChatView(appState = appState, stateManager = stateManager)
                            ViewMode.EXPORT -> ExportView(appState = appState, stateManager = stateManager)
                            ViewMode.IMPORT -> {
                                ImportView(
                                    viewModel = stateManager.importExportViewModel,
                                    currentGraph = appState.holonGraph,
                                    personaId = appState.aiPersonaId ?: "",
                                    // --- FIX IS HERE: `holonsBasePath` argument removed. ---
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    HolonInspectorView(appState = appState, modifier = Modifier.width(320.dp))
                }
            }
        }
    }
}