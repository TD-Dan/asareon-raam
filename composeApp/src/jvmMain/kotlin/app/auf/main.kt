package app.auf

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * The pure, logic-less entry point for the application.
 * Its ONLY responsibilities are to:
 * 1. Set up the native window and top-level composition scope.
 * 2. Instantiate the AppContainer, injecting the app version into PlatformDependencies.
 * 3. Render the root App UI.
 * 4. Read the initial window size from the CoreState.
 * 5. Dispatch lifecycle and window resize actions.
 *
 * @version 2.2
 */
@OptIn(FlowPreview::class)
fun main() = application {
    val coroutineScope = rememberCoroutineScope()
    val platformDependencies = remember { PlatformDependencies(Version.APP_VERSION_MAJOR) }
    val container = remember(platformDependencies, coroutineScope) {
        AppContainer(platformDependencies, coroutineScope)
    }

    val appState by container.store.state.collectAsState()
    val coreState = appState.featureStates["CoreFeature"] as? CoreState

    val windowState = rememberWindowState(
        width = (coreState?.windowWidth ?: 1200).dp,
        height = (coreState?.windowHeight ?: 800).dp
    )

    Window(
        onCloseRequest = {
            container.store.dispatch(Action("app.CLOSING"))
            Thread.sleep(250)
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

        // --- THE FIX: PART 1 ---
        // This effect SYNCHRONIZES THE WINDOW TO THE STATE.
        // It runs whenever CoreState's dimensions change, ensuring the window size
        // always reflects the single source of truth. This fixes both the startup
        // race and the update blindness.
        LaunchedEffect(coreState?.windowWidth, coreState?.windowHeight) {
            coreState?.let {
                val stateSize = DpSize(it.windowWidth.dp, it.windowHeight.dp)
                if (windowState.size != stateSize) {
                    windowState.size = stateSize
                }
            }
        }

        // --- THE FIX: PART 2 ---
        // This effect SYNCHRONIZES THE STATE TO THE WINDOW (after user mouse-drags).
        // It observes user-driven size changes and dispatches them to the store.
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { newSize ->
                    val width = newSize.width.value.toInt()
                    val height = newSize.height.value.toInt()

                    if (width != coreState?.windowWidth || height != (coreState.windowHeight)) {
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