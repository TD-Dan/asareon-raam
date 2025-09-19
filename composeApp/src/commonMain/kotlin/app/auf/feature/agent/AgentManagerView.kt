package app.auf.feature.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.auf.core.StateManager

/**
 * The main UI for the Agent Manager feature.
 * This placeholder will be built out to allow configuration and monitoring of agent instances.
 */
@Composable
fun AgentManagerView(stateManager: StateManager) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Agent Manager View - To be implemented")
    }
}