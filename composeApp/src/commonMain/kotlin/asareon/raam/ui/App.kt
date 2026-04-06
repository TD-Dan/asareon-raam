package asareon.raam.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.core.resolveDisplayColor
import asareon.raam.feature.core.BootLogEntry
import asareon.raam.feature.core.ConfirmationDialog
import asareon.raam.feature.core.CoreState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun App(
    store: Store,
    features: List<Feature>,
    bootLog: List<BootLogEntry>,
    titleBar: @Composable () -> Unit = {}
) {
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
    val primaryOverride: Color? = if (coreState?.useIdentityColorAsPrimary == true) {
        val activeIdentity = coreState.activeUserId?.let { appState.identityRegistry[it] }
        activeIdentity?.resolveDisplayColor()
    } else null

    AppTheme(primaryOverride = primaryOverride) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                Crossfade(targetState = coreState?.booting != false, animationSpec = tween(2000)) { isBooting ->
                    if (isBooting) {
                        if (coreState?.showBootConsole == true) {
                            BootConsoleView(bootLog)
                        }
                        // else: blank screen while booting (still defers MainAppContent to fix window-size race)
                    } else {
                        MainAppContent(store, features, titleBar)
                    }
                }

                // --- GLOBAL DIALOGS ---
                coreState?.confirmationRequest?.let { request ->
                    ConfirmationDialog(
                        request = request,
                        onConfirm = {
                            store.dispatch("system", Action(ActionRegistry.Names.CORE_DISMISS_CONFIRMATION_DIALOG, buildJsonObject {
                                put("confirmed", true)
                            }))
                        },
                        onDismiss = {
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
private fun MainAppContent(
    store: Store,
    features: List<Feature>,
    titleBar: @Composable () -> Unit
) {
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
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            titleBar()
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (activeStageContent != null) {
                    activeStageContent(store, features)
                } else {
                    Text("Error: No view found for key '$activeViewKey'")
                }
            }
        }
    }
}