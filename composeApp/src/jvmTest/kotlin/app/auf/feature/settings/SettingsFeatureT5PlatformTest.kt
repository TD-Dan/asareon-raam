package app.auf.feature.settings

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 5 Platform Test for SettingsFeature.
 *
 * Mandate (P-TEST-001, T5): To test the full, end-to-end workflow of a feature using
 * its actual platform-specific dependencies (in this case, the real file system).
 */
class SettingsFeatureT5PlatformTest {

    private val testAppVersion = "2.0.0-test"

    // A custom PlatformDependencies that uses a temporary directory on the real file system.
    private class JvmTestPlatformDependencies(appVersion: String) : PlatformDependencies(appVersion) {
        val tempDir: File = createTempDirectory("auf-integration-test-").toFile()
        override val pathSeparator: Char = File.separatorChar

        init { tempDir.deleteOnExit() }

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
        val addTestAction = Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
            put("key", "test.key"); put("type", "STRING"); put("label", "Test Key")
            put("description", "A key for testing."); put("section", "General"); put("defaultValue", "default")
        })

        // --- SCOPE 1: Save the setting ---
        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, ActionRegistry.Names.allActionNames)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_STARTING))

            val updateAction = Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
                put("key", "test.key"); put("value", "live_value")
            })
            store.dispatch("settings.ui", updateAction)
        }

        // --- SCOPE 2: Re-initialize and reload the setting ---
        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, ActionRegistry.Names.allActionNames)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_STARTING))

            val finalState = store.state.value.featureStates["settings"] as? SettingsState
            assertNotNull(finalState, "SettingsState should not be null after reloading.")
            assertEquals("live_value", finalState.values["test.key"], "The reloaded value should match the persisted value.")
        }
    }
}
