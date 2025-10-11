package app.auf.feature.settings

import app.auf.core.*
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.util.BasePath
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

/**
 * A high-fidelity test for the SettingsFeature that uses the REAL Store,
 * the REAL FileSystemFeature, and a FakePlatformDependencies to interact with a
 * temporary, in-memory file system. This verifies the full, integrated data flow.
 */
class SettingsFeatureIntegrationTest {

    private val testAppVersion = "2.0.0-test"
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var testStore: Store
    private lateinit var settingsFeature: SettingsFeature
    private lateinit var fileSystemFeature: FileSystemFeature
    private lateinit var coreFeature: CoreFeature
    private lateinit var settingsSandboxPath: String
    private lateinit var settingsFilePath: String


    @BeforeEach
    fun setUp() {
        // 1. Initialize dependencies.
        fakePlatform = FakePlatformDependencies(testAppVersion)
        coreFeature = CoreFeature(fakePlatform)
        settingsFeature = SettingsFeature(fakePlatform)
        fileSystemFeature = FileSystemFeature(fakePlatform)
        val features = listOf(coreFeature, settingsFeature, fileSystemFeature)

        // 2. Define critical file paths for assertions.
        settingsSandboxPath = fakePlatform.getBasePathFor(BasePath.APP_ZONE) + fakePlatform.pathSeparator + settingsFeature.name
        settingsFilePath = settingsSandboxPath + fakePlatform.pathSeparator + "settings.json"

        // 3. Create the Store with a BOOTING state.
        val initialState = AppState() // Start fresh
        testStore = Store(initialState, features, fakePlatform)

        // 4. Manually run the one-time init() for all features.
        testStore.initFeatureLifecycles()

        // 5. Run the full, synchronous startup lifecycle. This is the key fix.
        // It moves the store from BOOTING -> INITIALIZING -> RUNNING.
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
    }


    @Test
    fun `settings UPDATE action correctly persists settings to disk via FileSystemFeature`() {
        // Arrange: Add a setting definition to the store after it has initialized.
        val addAction = Action("settings.ADD", buildJsonObject {
            put("key", "test.key"); put("type", "STRING"); put("label", "Test")
            put("description", "A test key"); put("section", "Testing"); put("defaultValue", "default")
        })
        testStore.dispatch("test.setup", addAction)

        // Act: Dispatch the UPDATE action to trigger the persistence side-effect.
        val updateAction = Action("settings.UPDATE", buildJsonObject {
            put("key", "test.key")
            put("value", "persisted_value")
        })
        testStore.dispatch(settingsFeature.name, updateAction)

        // Assert
        // Check that the file was actually created by the FileSystemFeature in the fake file system.
        assertTrue(fakePlatform.fileExists(settingsFilePath), "settings.json file should have been created in the sandbox.")
        val fileContent = fakePlatform.readFileContent(settingsFilePath)
        // The content should be a JSON map of all settings, including the one we updated.
        assertTrue(fileContent.contains(""""test.key":"persisted_value""""), "The persisted file content is incorrect.")
    }


    @Test
    fun `app INITIALIZING action correctly loads persisted settings from disk`() {
        // Arrange
        // Pre-populate the fake file system with a settings file before the test runs.
        fakePlatform.writeFileContent(settingsFilePath, """{ "test.key": "loaded_value" }""")

        // Add a setting definition that matches the key in the pre-populated file.
        val addAction = Action("settings.ADD", buildJsonObject {
            put("key", "test.key"); put("type", "STRING"); put("label", "Test")
            put("description", "A test key"); put("section", "Testing"); put("defaultValue", "default")
        })
        testStore.dispatch("test.setup", addAction)

        // Act
        // The `system.INITIALIZING` action was already dispatched in setUp().
        // This action triggers SettingsFeature to dispatch `filesystem.SYSTEM_READ`, and its
        // onPrivateData handler then dispatches `settings.LOADED`.
        // We just need to check the final state.
        val finalState = testStore.state.value.featureStates[settingsFeature.name] as? SettingsState

        // Assert
        assertNotNull(finalState, "SettingsState should not be null.")
        assertEquals("loaded_value", finalState.values["test.key"], "The value from the persisted file was not loaded correctly.")
    }
}