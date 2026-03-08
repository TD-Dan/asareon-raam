package app.auf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.core.resolveDisplayColor
import app.auf.feature.core.ConfirmationDialog
import app.auf.feature.core.CoreState
import app.auf.ui.components.colorToHsl
import app.auf.ui.components.hslToColor
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
    // When the setting is enabled, derive primary + secondary from the
    // active user's displayColor. Secondary is hue-30°, S×0.75, L×0.75.
    val primaryOverride: Color?
    val secondaryOverride: Color?

    if (coreState?.useIdentityColorAsPrimary == true) {
        val activeIdentity = coreState.activeUserId?.let { appState.identityRegistry[it] }
        val identityColor = activeIdentity?.resolveDisplayColor()
        if (identityColor != null) {
            primaryOverride = identityColor
            val hsl = colorToHsl(identityColor)
            val secHue = (hsl[0] + 20f + 360f) % 360f
            val secSat = (hsl[1] * 0.75f).coerceIn(0f, 1f)
            val secLit = (hsl[2] * 0.75f).coerceIn(0f, 1f)
            secondaryOverride = hslToColor(secHue, secSat, secLit)
        } else {
            primaryOverride = null
            secondaryOverride = null
        }
    } else {
        primaryOverride = null
        secondaryOverride = null
    }

    AppTheme(primaryOverride = primaryOverride, secondaryOverride = secondaryOverride) {
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