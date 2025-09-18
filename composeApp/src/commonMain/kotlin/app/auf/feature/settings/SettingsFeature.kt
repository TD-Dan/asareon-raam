package app.auf.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*

/**
 * ## Mandate
 * A dedicated, self-contained feature whose sole responsibility is to provide the main
 * application settings view. It acts as a host, dynamically collecting and rendering
 * the settings UI from all other registered features.
 */
class SettingsFeature(
    private val allFeatures: List<Feature>
) : Feature {
    override val name: String = "SettingsFeature"
    override val composableProvider: Feature.ComposableProvider = SettingsComposableProvider()

    inner class SettingsComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.settings.main"

        @Composable
        override fun RibbonButton(stateManager: StateManager, isActive: Boolean) {
            IconButton(onClick = { stateManager.dispatch(SetActiveView(viewKey)) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(stateManager: StateManager) {
            SettingsView(
                stateManager = stateManager,
                features = allFeatures,
                onClose = { stateManager.dispatch(SetActiveView(stateManager.state.value.defaultViewKey)) }
            )
        }
    }
}