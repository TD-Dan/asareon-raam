package app.auf.feature.settings

import app.auf.core.*
import app.auf.feature.core.CoreFeature
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.util.BasePath
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    private lateinit var settingsFilePath: String

    @BeforeEach
    fun setUp() {
        // 1. Initialize dependencies.
        fakePlatform = FakePlatformDependencies(testAppVersion)
        val coreFeature = CoreFeature(fakePlatform)
        settingsFeature = SettingsFeature(fakePlatform)
        val fileSystemFeature = FileSystemFeature(fakePlatform)
        val features = listOf(coreFeature, settingsFeature, fileSystemFeature)

        // 2. Define critical file path.
        val settingsSandboxPath = fakePlatform.getBasePathFor(BasePath.APP_ZONE) + fakePlatform.pathSeparator + settingsFeature.name
        settingsFilePath = settingsSandboxPath + fakePlatform.pathSeparator + "settings.json"

        // 3. Create the Store with a fresh BOOTING state.
        testStore = Store(AppState(), features, fakePlatform)
        testStore.initFeatureLifecycles()
    }


    @Test
    fun `settings UPDATE action correctly persists settings to disk via FileSystemFeature`() {
        // Arrange:
        // 1. Move the store to a state where it can accept settings changes.
        testStore.dispatch("system.test", Action("system.INITIALIZING"))
        // 2. Add a setting definition.
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
        assertTrue(fakePlatform.fileExists(settingsFilePath), "settings.json file should have been created in the sandbox.")
        val fileContent = fakePlatform.readFileContent(settingsFilePath)
        assertTrue(fileContent.contains(""""test.key":"persisted_value""""), "The persisted file content is incorrect.")
    }


    @Test
    fun `app INITIALIZING action correctly loads persisted settings from disk`() {
        // Arrange
        // 1. Pre-populate the file system with a settings file.
        fakePlatform.writeFileContent(settingsFilePath, """{ "test.key": "loaded_value" }""")

        // 2. Bring the store to the INITIALIZING state. This is crucial.
        // It allows the next ADD action to be accepted by the lifecycle guard.
        testStore.dispatch("system.test", Action("system.INITIALIZING"))

        // 3. Add the setting definition AFTER the store is in a valid state.
        val addAction = Action("settings.ADD", buildJsonObject {
            put("key", "test.key"); put("type", "STRING"); put("label", "Test")
            put("description", "A test key"); put("section", "Testing"); put("defaultValue", "default")
        })
        testStore.dispatch("test.setup", addAction)

        // Act: Manually simulate the FileSystemFeature's response by delivering the
        // pre-populated file content via the secure onPrivateData channel.
        // This triggers the `settings.LOADED` action inside the SettingsFeature.
        val privateDataPayload = buildJsonObject {
            put("subpath", "settings.json")
            put("content", fakePlatform.readFileContent(settingsFilePath))
        }
        testStore.deliverPrivateData("filesystem", settingsFeature.name, privateDataPayload)


        // Assert
        val finalState = testStore.state.value.featureStates[settingsFeature.name] as? SettingsState
        assertNotNull(finalState, "SettingsState should not be null.")
        assertEquals("loaded_value", finalState.values["test.key"], "The value from the persisted file was not loaded correctly.")
    }
}