package app.auf

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.Action
import app.auf.core.Version
import app.auf.di.AppContainer
import app.auf.feature.core.CoreState
import app.auf.ui.App
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * The pure, logic-less entry point for the application.
 * Its ONLY responsibilities are to:
 * 1. Set up the native window and top-level composition scope.
 * 2. Instantiate the AppContainer.
 * 3. Render the root App UI.
 * 4. Read the initial window size from the CoreState.
 * 5. Dispatch lifecycle and window resize actions.
 *
 * @version 2.0
 */
fun main() = application {
    val coroutineScope = rememberCoroutineScope()
    val platformDependencies = remember { PlatformDependencies() }
    val container = remember(platformDependencies, coroutineScope) {
        AppContainer(platformDependencies, coroutineScope)
    }

    // Collect the app state to read the initial window size.
    val appState by container.store.state.collectAsState()
    val coreState = appState.featureStates["CoreFeature"] as? CoreState

    // Use the state to initialize the window, with defaults if the state isn't ready.
    val windowState = rememberWindowState(
        width = (coreState?.windowWidth ?: 1200).dp,
        height = (coreState?.windowHeight ?: 800).dp
    )

    Window(
        onCloseRequest = {
            // Dispatch the closing signal and allow a moment for features to react.
            container.store.dispatch(Action("app.CLOSING"))
            // TODO: IMPLEMENT Proper 'wait for features to close' check
            Thread.sleep(250) // A simple dangerous stupid mechanism to allow for persistence.
            exitApplication()
        },
        title = "AUF v${Version.APP_VERSION}",
        state = windowState
    ) {
        // One-time effect to apply native decorations and signal app start.
        LaunchedEffect(Unit) {
            platformDependencies.applyNativeWindowDecorations(window)
            container.store.initFeatureLifecycles()
            container.store.dispatch(Action("app.STARTING"))
        }

        // Observe window size changes and dispatch actions to the store.
        // `snapshotFlow` turns compose state reads into a flow, and `debounce`
        // prevents an action storm during rapid resizing.
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { newSize ->
                    val width = newSize.width.value.toInt()
                    val height = newSize.height.value.toInt()

                    // Only dispatch if the size is actually different from the state
                    // to prevent redundant updates.
                    if (width != coreState?.windowWidth || height != coreState?.windowHeight) {
                        val payload = buildJsonObject {
                            put("width", width)
                            put("height", height)
                        }
                        container.store.dispatch(Action("core.UPDATE_WINDOW_SIZE", payload))
                    }
                }
        }

        App(container.store, container.features)
    }
}