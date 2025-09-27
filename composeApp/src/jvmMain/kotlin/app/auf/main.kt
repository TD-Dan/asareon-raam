package app.auf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.Action
import app.auf.core.Version
import app.auf.di.AppContainer
import app.auf.ui.App
import app.auf.util.PlatformDependencies

/**
 * ## Mandate
 * The pure, logic-less entry point for the application.
 * Its ONLY responsibilities are to:
 * 1. Set up the native window and top-level composition scope.
 * 2. Instantiate the AppContainer.
 * 3. Render the root App UI.
 * 4. Dispatch the 'app.STARTING' and 'app.CLOSING' lifecycle actions.
 */
fun main() = application {
    val coroutineScope = rememberCoroutineScope()
    val platformDependencies = remember { PlatformDependencies() }
    val container = remember(platformDependencies, coroutineScope) {
        AppContainer(platformDependencies, coroutineScope)
    }
    val windowState = rememberWindowState()

    Window(
        onCloseRequest = {
            // Dispatch the closing signal and allow a moment for features to react.
            container.store.dispatch(Action("app.CLOSING"))
            Thread.sleep(250) // A simple mechanism to allow for persistence.
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
        App(container.store, container.features)
    }
}