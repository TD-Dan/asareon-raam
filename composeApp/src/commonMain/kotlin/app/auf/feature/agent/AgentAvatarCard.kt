package app.auf.feature.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.auf.core.Store

@Composable
fun AgentAvatarCard(
    agent: AgentInstance,
    store: Store,
    platformDependencies: app.auf.util.PlatformDependencies
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        AgentControlCard(agent, store, platformDependencies)
    }
}