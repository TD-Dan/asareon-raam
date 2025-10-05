package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Store
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test to verify the full persistence and loading cycle of the SettingsFeature
 * through its collaboration with the FileSystemFeature against a real temporary file system.
 */
class SettingsFeatureIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private val testAppVersion = "v2-integration"
    private lateinit var userHome: File
    private lateinit var platform: PlatformDependencies

    @BeforeEach
    fun setUp() {
        // Set up a fake user home inside the temporary directory to isolate the test.
        userHome = File(tempDir, "fake_user_home")
        userHome.mkdir()
        System.setProperty("user.home", userHome.absolutePath)
        platform = PlatformDependencies(testAppVersion)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("user.home")
    }

    @Test
    fun `settings UPDATE action correctly persists settings to disk via FileSystemFeature`() {
        // ARRANGE
        val settingsFeature = SettingsFeature(platform)
        val fileSystemFeature = FileSystemFeature(platform)
        val features = listOf(settingsFeature, fileSystemFeature)
        val store = Store(AppState(), features, platform)
        features.forEach { it.init(store) }

        // Manually add a setting definition
        store.dispatch("test.setup", Action("settings.ADD", buildJsonObject {
            put("key", "test.key")
            put("type", "STRING")
            put("label", "Test")
            put("description", "A test key")
            put("section", "Testing")
            put("defaultValue", "default")
        }))

        // ACT
        // Dispatch an UPDATE action, which should trigger the onAction persistence logic.
        store.dispatch(settingsFeature.name, Action("settings.UPDATE", buildJsonObject {
            put("key", "test.key")
            put("value", "persisted_value")
        }))

        // ASSERT
        // Verify that the file was actually written to the correct sandboxed location.
        val expectedSandboxPath = platform.getBasePathFor(app.auf.util.BasePath.APP_ZONE) +
                platform.pathSeparator + settingsFeature.name
        val expectedFile = File(expectedSandboxPath, "settings.json")

        assertTrue(expectedFile.exists(), "settings.json file should have been created in the sandbox.")
        val fileContent = expectedFile.readText()
        assertEquals("""{"test.key":"persisted_value"}""", fileContent)
    }

    @Test
    fun `app INITIALIZING action correctly loads persisted settings from disk`() {
        // ARRANGE
        // 1. Pre-populate a settings file on disk.
        val settingsFeatureName = "settings"
        val expectedSandboxPath = platform.getBasePathFor(app.auf.util.BasePath.APP_ZONE) +
                platform.pathSeparator + settingsFeatureName
        File(expectedSandboxPath).mkdirs()
        File(expectedSandboxPath, "settings.json").writeText("""{"test.key":"loaded_value"}""")

        // 2. Set up a new "restarted" app instance.
        val settingsFeature = SettingsFeature(platform)
        val fileSystemFeature = FileSystemFeature(platform)
        val features = listOf(settingsFeature, fileSystemFeature)
        val store = Store(AppState(), features, platform)
        features.forEach { it.init(store) }

        // 3. Register the setting definition so the feature knows about "test.key".
        store.dispatch("test.setup", Action("settings.ADD", buildJsonObject {
            put("key", "test.key")
            put("type", "STRING")
            put("label", "Test")
            put("description", "A test key")
            put("section", "Testing")
            put("defaultValue", "default")
        }))

        // ACT
        // Dispatch the app lifecycle action that triggers loading.
        store.dispatch("system.main", Action("system.INITIALIZING"))

        // ASSERT
        // Verify that the feature's state was correctly hydrated from the file.
        val finalSettingsState = store.state.value.featureStates[settingsFeatureName] as? SettingsState
        assertNotNull(finalSettingsState)
        assertEquals("loaded_value", finalSettingsState.values["test.key"])
    }
}