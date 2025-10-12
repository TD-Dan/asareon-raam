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

    // THE FIX: Define a minimal set of valid actions required for this specific test class.
    private val testActionRegistry = setOf(
        "system.INITIALIZING", "system.STARTING",
        "settings.ADD", "settings.UPDATE"
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

        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action("system.INITIALIZING"))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action("system.STARTING"))

            val updateAction = Action("settings.UPDATE", buildJsonObject {
                put("key", "test.key")
                put("value", "live_value")
            })
            store.dispatch("settings.ui", updateAction)
        }

        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform, testActionRegistry)
            features.forEach { it.init(store) }

            store.dispatch("system.test", Action("system.INITIALIZING"))
            store.dispatch("test.setup", addTestAction)
            store.dispatch("system.test", Action("system.STARTING"))

            val finalState = store.state.value.featureStates["settings"] as? SettingsState
            assertNotNull(finalState, "SettingsState should not be null after reloading.")
            assertEquals("live_value", finalState.values["test.key"], "The reloaded value should match the persisted value.")
        }
    }
}
