package asareon.raam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.core.resolveDisplayColor
import asareon.raam.feature.core.ConfirmationDialog
import asareon.raam.feature.core.CoreState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
            store.dispatch("system", Action(ActionRegistry.Names.CORE_CLEAR_TOAST))
        }
    }

    // ── Identity-based theme override ────────────────────────────────
    // When the setting is enabled, pass the active user's displayColor
    // as primaryOverride. Theme.kt derives secondary (−30° hue) and
    // tertiary (+60° hue) automatically.
    val primaryOverride: Color? = if (coreState?.useIdentityColorAsPrimary == true) {
        val activeIdentity = coreState.activeUserId?.let { appState.identityRegistry[it] }
        activeIdentity?.resolveDisplayColor()
    } else null

    AppTheme(primaryOverride = primaryOverride) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                MainAppContent(store, features)

                // --- GLOBAL DIALOGS ---
                coreState?.confirmationRequest?.let { request ->
                    ConfirmationDialog(
                        request = request,
                        onConfirm = {
                            // THE FIX: Dispatch the secure response action.
                            store.dispatch("system", Action(ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
                                put("confirmed", true)
                            }))
                        },
                        onDismiss = {
                            // THE FIX: Explicitly dispatch the 'dismiss' response.
                            store.dispatch("system", Action(ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
                                put("confirmed", false)
                            }))
                        }
                    )
                }
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