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

    // Use a custom JvmPlatformDependencies that points to a real, temporary directory
    // This is a private inner class because it's specific to this test file.
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
        // --- SHARED SETUP ---
        val platform = JvmTestPlatformDependencies(testAppVersion)
        val addTestAction = Action("settings.ADD", buildJsonObject {
            put("key", "test.key")
            put("type", "STRING")
            put("label", "Test Key")
            put("description", "A key for testing.")
            put("section", "General")
            put("defaultValue", "default")
        })

        // --- PHASE 1: SAVE VALUE TO DISK ---
        run {
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform)
            features.forEach { it.init(store) }

            // 1. Properly orchestrate the startup lifecycle.
            store.dispatch("system.test", Action("system.INITIALIZING"))
            // 2. Register the test setting definition while in the INITIALIZING state.
            store.dispatch("test.setup", addTestAction)
            // 3. Move to the RUNNING state.
            store.dispatch("system.test", Action("system.STARTING"))

            // 4. Dispatch the update action. Now it will be accepted and persisted.
            val updateAction = Action("settings.UPDATE", buildJsonObject {
                put("key", "test.key")
                put("value", "live_value")
            })
            store.dispatch("settings.ui", updateAction)
        }


        // --- PHASE 2: RELOAD VALUE FROM DISK (Simulating App Restart) ---
        run {
            // 1. Create new, clean instances. They will read from the same temp directory.
            val features = listOf(CoreFeature(platform), SettingsFeature(platform), FileSystemFeature(platform))
            val store = Store(AppState(), features, platform)
            features.forEach { it.init(store) }

            // 2. Orchestrate the new startup lifecycle.
            store.dispatch("system.test", Action("system.INITIALIZING"))
            // 3. CRITICAL: The "new app" must also register its setting definitions
            //    so it knows what to do with the loaded data.
            store.dispatch("test.setup", addTestAction)
            // 4. Move to the RUNNING state. The file load will have been processed.
            store.dispatch("system.test", Action("system.STARTING"))


            // --- ASSERT ---
            // Verify the final, in-memory state of the *new* feature instance.
            val finalState = store.state.value.featureStates["settings"] as? SettingsState
            assertNotNull(finalState, "SettingsState should not be null after reloading.")
            assertEquals("live_value", finalState.values["test.key"], "The reloaded value should match the persisted value.")
        }
    }
}