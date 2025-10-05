package app.auf

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.Action
import app.auf.core.Version
import app.auf.core.AppContainer
import app.auf.feature.core.CoreState
import app.auf.ui.App
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * The pure, logic-less entry point for the application.
 *
 * @version 2.4
 */
@OptIn(FlowPreview::class)
fun main() = application {
    val coroutineScope = rememberCoroutineScope()
    val platformDependencies = remember { PlatformDependencies(Version.APP_VERSION_MAJOR) }

    val container = remember(platformDependencies, coroutineScope) {
        AppContainer(platformDependencies, coroutineScope).also {
            it.store.initFeatureLifecycles()
        }
    }

    val appState by container.store.state.collectAsState()
    val coreState = appState.featureStates["CoreFeature"] as? CoreState

    val windowState = rememberWindowState(
        width = (coreState?.windowWidth ?: 1200).dp,
        height = (coreState?.windowHeight ?: 800).dp
    )

    Window(
        onCloseRequest = {
            container.store.dispatch("system.main", Action("system.CLOSING"))
            Thread.sleep(250)
            exitApplication()
        },
        title = "AUF v${Version.APP_VERSION}",
        state = windowState
    ) {
        /**
         * ## Hardened Startup Lifecycle Orchestration
         * This effect orchestrates the critical, two-phase application startup to prevent race conditions.
         * The sequence is guaranteed to be synchronous and sequential because `store.dispatch()` is a
         * blocking call that completes all reducer and onAction invocations before returning.
         *
         * @see P-SYSTEM-002: Synchronous Startup Mandate
         */
        LaunchedEffect(Unit) {
            platformDependencies.applyNativeWindowDecorations(window)
            // Phase 1: INITIALIZING. Triggers all features to register their settings definitions.
            // The SettingsFeature then chains the load-from-disk sequence. All features must complete
            // their synchronous setup in this phase.
            container.store.dispatch("system.main", Action("system.INITIALIZING"))

            // Phase 2: STARTING. Signals that all setup is complete. Features can now execute
            // their main runtime logic (e.g., navigating, starting timers).
            container.store.dispatch("system.main", Action("system.STARTING"))
        }

        // Synchronizes THE WINDOW TO THE STATE.
        LaunchedEffect(coreState?.windowWidth, coreState?.windowHeight) {
            coreState?.let {
                val stateSize = DpSize(it.windowWidth.dp, it.windowHeight.dp)
                if (windowState.size != stateSize) {
                    windowState.size = stateSize
                }
            }
        }

        // Synchronizes THE STATE TO THE WINDOW (after user mouse-drags).
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { newSize ->
                    val width = newSize.width.value.toInt()
                    val height = newSize.height.value.toInt()
                    val currentCoreState = container.store.state.value.featureStates["CoreFeature"] as? CoreState

                    if (width != currentCoreState?.windowWidth || height != currentCoreState.windowHeight) {
                        val payload = buildJsonObject {
                            put("width", width)
                            put("height", height)
                        }
                        container.store.dispatch("system.window", Action("core.UPDATE_WINDOW_SIZE", payload))
                    }
                }
        }

        App(container.store, container.features)
    }
}