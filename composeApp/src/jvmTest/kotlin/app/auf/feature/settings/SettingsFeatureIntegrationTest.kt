package app.auf.feature.settings

import app.auf.core.*
import app.auf.core.generated.ActionNames
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

    private val testActionRegistry = setOf( //TODO: just use the test environment real action names here?
        ActionNames.SYSTEM_PUBLISH_INITIALIZING, ActionNames.SYSTEM_PUBLISH_STARTING,
        ActionNames.SETTINGS_ADD, ActionNames.SETTINGS_UPDATE, ActionNames.SETTINGS_PUBLISH_LOADED, ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED,
        ActionNames.FILESYSTEM_SYSTEM_READ, ActionNames.FILESYSTEM_SYSTEM_WRITE, ActionNames.FILESYSTEM_NAVIGATE,
        ActionNames.FILESYSTEM_OPEN_APP_SUBFOLDER, ActionNames.FILESYSTEM_LOAD_CHILDREN, ActionNames.FILESYSTEM_INTERNAL_DIRECTORY_LOADED
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
        val addTestAction = Action(ActionNames.SETTINGS_ADD, buildJsonObject {
            put("key", "test.key")
            put("type", "STRING")
            put("label", "Test Key")
            put("description", "A key for testing.")
            put("section", "General")
            put("defaultValue", "default")
        })

        // --- SCOPE 1: Save the setting ---
        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

            val updateAction = Action(ActionNames.SETTINGS_UPDATE, buildJsonObject {
                put("key", "test.key")
                put("value", "live_value")
            })
            store.dispatch("settings.ui", updateAction)
        }

        // --- SCOPE 2: Re-initialize and reload the setting ---
        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action(ActionNames.SYSTEM_PUBLISH_STARTING))

            val finalState = store.state.value.featureStates["settings"] as? SettingsState
            assertNotNull(finalState, "SettingsState should not be null after reloading.")
            assertEquals("live_value", finalState.values["test.key"], "The reloaded value should match the persisted value.")
        }
    }
}