package asareon.raam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.core.CoreState
import asareon.raam.ui.App
import asareon.raam.ui.AppTheme
import asareon.raam.ui.CustomTitleBar
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
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
 * @version 2.8
 */
@OptIn(FlowPreview::class)
fun main() {
    val platformDependencies = PlatformDependencies(Version.APP_VERSION_MAJOR)

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("FATAL: Uncaught exception on thread '${thread.name}'")
        throwable.printStackTrace()

        platformDependencies.log(
            LogLevel.FATAL,
            "UncaughtExceptionHandler",
            "Uncaught exception on thread '${thread.name}'",
            throwable
        )

        SwingUtilities.invokeLater {
            try {
                JOptionPane.showMessageDialog(
                    null,
                    "A critical error occurred:\n${throwable.message}\n\nThe error has been logged.",
                    "Critical Error",
                    JOptionPane.ERROR_MESSAGE
                )
            } catch (e: Exception) {
                System.err.println("Failed to show error dialog: ${e.message}")
            }
        }
    }

    application {
        val coroutineScope = rememberCoroutineScope()
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
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_CLOSING))
                Thread.sleep(250)
                exitApplication()
            },
            title = Version.APP_NAME,
            state = windowState,
            undecorated = true,
        ) {
            LaunchedEffect(Unit) {
                dependencies.applyNativeWindowDecorations(window)
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
                container.store.dispatch("system.main", Action(ActionRegistry.Names.SYSTEM_RUNNING))
            }

            LaunchedEffect(coreState?.windowWidth, coreState?.windowHeight) {
                coreState?.let {
                    val stateSize = DpSize(it.windowWidth.dp, it.windowHeight.dp)
                    if (windowState.size != stateSize) {
                        windowState.size = stateSize
                    }
                }
            }

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

            // DWM handles rounded corners on Windows 11 via WindowsSnapHelper.
            // The window ref flows through the lambda closure to CustomTitleBar.
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    App(
                        store = container.store,
                        features = container.features,
                        titleBar = { CustomTitleBar(window) }
                    )
                }
            }
        }
    }
}