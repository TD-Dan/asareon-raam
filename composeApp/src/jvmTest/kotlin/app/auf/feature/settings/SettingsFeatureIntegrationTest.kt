package app.auf.feature.settings

import app.auf.core.*
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFeatureIntegrationTest {

    private val testAppVersion = "2.0.0-test"

    // THE FIX: The action registry for an integration test must be comprehensive.
    // It needs to include all actions that are dispatched by all features involved
    // during the test's execution.
    private val testActionRegistry = setOf(
        // Lifecycle
        "system.INITIALIZING", "system.STARTING",
        // Settings Feature's own actions
        "settings.ADD", "settings.UPDATE", "settings.publish.LOADED", "settings.publish.VALUE_CHANGED",
        // FileSystem Feature's actions (used by SettingsFeature)
        "filesystem.SYSTEM_READ", "filesystem.SYSTEM_WRITE", "filesystem.NAVIGATE",
        "filesystem.OPEN_APP_SUBFOLDER", "filesystem.LOAD_CHILDREN", "filesystem.internal.DIRECTORY_LOADED"
    )

    private class JvmTestPlatformDependencies(appVersion: String) : PlatformDependencies(appVersion) {
        val tempDir: File = createTempDirectory("auf-integration-test-").toFile()
        override val pathSeparator: Char = File.separatorChar

        init {
            tempDir.deleteOnExit()
        }

        override fun getBasePathFor(type: BasePath): String {
            return when (type) {
                BasePath.APP_ZONE -> tempDir.absolutePath
                BasePath.USER_ZONE -> System.getProperty("user.home")
            }
        }
    }

    @Test
    fun `settings UPDATE action correctly persists and reloads settings via FileSystemFeature`() = runTest {
        val platform = JvmTestPlatformDependencies(testAppVersion)
        val addTestAction = Action("settings.ADD", buildJsonObject {
            put("key", "test.key")
            put("type", "STRING")
            put("label", "Test Key")
            put("description", "A key for testing.")
            put("section", "General")
            put("defaultValue", "default")
        })

        // --- SCOPE 1: Save the setting ---
        run {
            // Instantiate all features required for the integration test.
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            // Run the application startup sequence.
            store.dispatch("system.test", Action("system.INITIALIZING"))
            store.dispatch("test.setup", addTestAction) // Add our test setting definition
            store.dispatch("system.test", Action("system.STARTING"))

            // Dispatch the action to update the setting's value.
            val updateAction = Action("settings.UPDATE", buildJsonObject {
                put("key", "test.key")
                put("value", "live_value")
            })
            store.dispatch("settings.ui", updateAction)
        }

        // --- SCOPE 2: Re-initialize and reload the setting ---
        run {
            // Create a fresh set of features and a new store to simulate an app restart.
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            // Run the startup sequence again. This will trigger the SettingsFeature to
            // dispatch a `filesystem.SYSTEM_READ` action.
            store.dispatch("system.test", Action("system.INITIALIZING"))
            store.dispatch("test.setup", addTestAction) // Re-add the definition
            store.dispatch("system.test", Action("system.STARTING"))

            // Get the final state after the settings have been loaded from disk.
            val finalState = store.state.value.featureStates["settings"] as? SettingsState
            assertNotNull(finalState, "SettingsState should not be null after reloading.")
            assertEquals("live_value", finalState.values["test.key"], "The reloaded value should match the persisted value.")
        }
    }
}