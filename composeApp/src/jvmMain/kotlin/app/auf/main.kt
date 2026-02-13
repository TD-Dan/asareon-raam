package app.auf

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.core.CoreState
import app.auf.ui.App
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * ## Mandate
 * The pure, logic-less entry point for the application.
 *
 * @version 2.6
 */
@OptIn(FlowPreview::class)
fun main() {
    // [FIX] Instantiate dependencies globally to enable robust logging in the exception handler.
    val platformDependencies = PlatformDependencies(Version.APP_VERSION_MAJOR)

    // [FIX] Universal Exception Guard
    // This catches ALL uncaught exceptions from ANY thread (UI, IO, etc.) that bubble up to the top.
    // It ensures that:
    // 1. The error is written to our durable 'app.log' file (Universal Logging).
    // 2. The user is notified via a dialog instead of a silent crash.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // 1. Log to System.err for immediate console visibility
        System.err.println("FATAL: Uncaught exception on thread '${thread.name}'")
        throwable.printStackTrace()

        // 2. Log to our internal durable logger
        platformDependencies.log(
            LogLevel.FATAL,
            "UncaughtExceptionHandler",
            "Uncaught exception on thread '${thread.name}'",
            throwable
        )

        // 3. Notify the user (Universal UI Feedback)
        // We use SwingUtilities because we might be on a non-EDT thread, or the EDT might be broken.
        SwingUtilities.invokeLater {
            try {
                JOptionPane.showMessageDialog(
                    null,
                    "A critical error occurred:\n${throwable.message}\n\nThe error has been logged.",
                    "Critical Error",
                    JOptionPane.ERROR_MESSAGE
                )
            } catch (e: Exception) {
                // If even the dialog fails, we can't do much more.
                System.err.println("Failed to show error dialog: ${e.message}")
            }
        }
    }

    application {
        val coroutineScope = rememberCoroutineScope()
        // reuse the global instance
        val dependencies = remember { platformDependencies }

        val container = remember(dependencies, coroutineScope) {
            AppContainer(dependencies, coroutineScope).also {
                it.store.initFeatureLifecycles()
            }
        }

        val appState by container.store.state.collectAsState()
        val coreState = appState.featureStates["core"] as? CoreState

        val windowState = rememberWindowState(
            width = (coreState?.windowWidth ?: 1200).dp,
            height = (coreState?.windowHeight ?: 800).dp
        )

        Window(
            onCloseRequest = {
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_PUBLISH_CLOSING))
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
                dependencies.applyNativeWindowDecorations(window)
                // Phase 1: INITIALIZING. Triggers all features to register their settings definitions.
                // The SettingsFeature then chains the load-from-disk sequence. All features must complete
                // their synchronous setup in this phase.
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_PUBLISH_INITIALIZING))

                // Phase 2: STARTING. Signals that all setup is complete. Features can now execute
                // their main runtime logic (e.g., navigating, starting timers).
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_PUBLISH_STARTING))
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
                        val currentCoreState = container.store.state.value.featureStates["core"] as? CoreState

                        if (width != currentCoreState?.windowWidth || height != currentCoreState.windowHeight) {
                            val payload = buildJsonObject {
                                put("width", width)
                                put("height", height)
                            }
                            container.store.dispatch("system.window", Action(ActionRegistry.Names.CORE_UPDATE_WINDOW_SIZE, payload))
                        }
                    }
            }

            App(container.store, container.features)
        }
    }
}