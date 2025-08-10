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

@Composable
fun App(stateManager: StateManager) {
    val appState by stateManager.state.collectAsState()

    MaterialTheme {
        // --- KEY CHANGE: Conditional rendering based on gatewayStatus ---
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
                    SessionView(
                        appState = appState,
                        onFilter = { stateManager.setCatalogueFilter(it) },
                        onHolonSelected = {
                            stateManager.inspectHolon(it)
                            stateManager.toggleHolonActive(it)
                        },
                        modifier = Modifier.width(320.dp)
                    )

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    ChatView(
                        appState = appState,
                        stateManager = stateManager,
                        modifier = Modifier.weight(1f)
                    )

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    HolonInspectorView(
                        appState = appState,
                        modifier = Modifier.width(320.dp)
                    )
                }
            }
        }
    }
}