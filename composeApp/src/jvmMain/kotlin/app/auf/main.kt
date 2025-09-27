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
            container.store.dispatch(Action("app.CLOSING"))
            // A brief delay to allow persistence to complete before exiting.
            // A more robust solution would involve feedback from the store.
            // TODO: IMPORTANT
            Thread.sleep(1000)
            exitApplication()
        },
        title = "AUF v${Version.APP_VERSION}",
        state = windowState
    ) {
        // One-time effect to apply native decorations and signal app start.
        LaunchedEffect(Unit) {
            platformDependencies.applyNativeWindowDecorations(window)
            container.store.startFeatureLifecycles()
            container.store.dispatch(Action("app.STARTING"))
        }
        App(container)
    }
}