package app.auf.service

import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.systemclock.SystemClockState
import app.auf.util.fakes.FakePlatformDependencies
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class SettingsPersistenceServiceTest {

    // --- Test Doubles: Replicating a minimal feature set for state serialization ---
    @Serializable
    data class TestFeatureState(val value: String) : FeatureState
    class TestFeature : Feature {
        override val name: String = "TestFeature"
    }

    private lateinit var platform: FakePlatformDependencies
    private lateinit var jsonParser: Json
    private lateinit var settingsPersistenceService: SettingsPersistenceService
    private lateinit var testFeatures: List<Feature>

    private val settingsFilePath = "/fake/settings/user_settings.json"

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies()
        testFeatures = listOf(TestFeature()) // Keep it simple for most tests

        // CRITICAL: The JSON parser for tests MUST know about all possible FeatureState subclasses.
        jsonParser = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                polymorphic(FeatureState::class) {
                    subclass(TestFeatureState::class)
                    subclass(SystemClockState::class)
                    subclass(HkgAgentFeatureState::class)
                    // Add other real feature states here if needed for more complex tests
                }
            }
        }

        // We initialize the service in each test to control the setup.
    }

    @Test
    fun `saveSettings and loadSettings correctly persist and restore complex UserSettings`() {
        // --- ARRANGE ---
        settingsPersistenceService = SettingsPersistenceService(platform, jsonParser, testFeatures)

        // Create a complex state object that mimics the real application's structure
        val originalSettings = UserSettings(
            windowWidth = 1280,
            windowHeight = 720,
            featureStates = mapOf(
                "TestFeature" to TestFeatureState("some data"),
                "SystemClockFeature" to SystemClockState(isEnabled = true, intervalMillis = 99000L),
                "HkgAgentFeature" to HkgAgentFeatureState() // Include a default state for another feature
            )
        )

        // --- ACT ---
        // 1. Save the settings object to the fake file system.
        settingsPersistenceService.saveSettings(originalSettings)

        // 2. Load the settings back from the fake file system.
        val loadedSettings = settingsPersistenceService.loadSettings()

        // --- ASSERT ---
        // Assert that a file was actually created at the correct path.
        assertTrue(platform.fileExists(settingsFilePath), "Settings file should have been created.")

        // Assert that the loaded object is not null and is identical to the original.
        assertNotNull(loadedSettings, "Loaded settings should not be null.")
        assertEquals(originalSettings, loadedSettings, "Loaded settings should be equal to the original settings.")

        // Go one step further and verify the types of the deserialized feature states.
        assertIs<TestFeatureState>(loadedSettings.featureStates["TestFeature"])
        assertIs<SystemClockState>(loadedSettings.featureStates["SystemClockFeature"])
        assertEquals(99000L, (loadedSettings.featureStates["SystemClockFeature"] as SystemClockState).intervalMillis)
    }

    @Test
    fun `loadSettings returns null when the settings file does not exist`() {
        // --- ARRANGE ---
        // Initialize with a fresh, empty platform
        settingsPersistenceService = SettingsPersistenceService(platform, jsonParser, testFeatures)

        // --- ACT ---
        val loadedSettings = settingsPersistenceService.loadSettings()

        // --- ASSERT ---
        assertNull(loadedSettings, "Should return null when no settings file exists.")
    }

    @Test
    fun `loadSettings returns null when the settings file is corrupt`() {
        // --- ARRANGE ---
        // Pre-populate the fake file system with invalid JSON.
        platform.writeFileContent(settingsFilePath, "{ \"windowWidth\": 1024, \"windowHeight\": \"not-a-number\" }")
        settingsPersistenceService = SettingsPersistenceService(platform, jsonParser, testFeatures)

        // --- ACT ---
        val loadedSettings = settingsPersistenceService.loadSettings()

        // --- ASSERT ---
        assertNull(loadedSettings, "Should return null for a corrupt settings file to prevent crashing.")
    }

    @Test
    fun `SettingsPersistenceService constructor creates settings directory if it does not exist`() {
        // --- ARRANGE ---
        val settingsDirPath = "/fake/settings"
        assertFalse(platform.directories.contains(settingsDirPath), "Precondition: Directory should not exist.")

        // --- ACT ---
        // The directory creation happens in the `init` block of the class.
        settingsPersistenceService = SettingsPersistenceService(platform, jsonParser, testFeatures)

        // --- ASSERT ---
        assertTrue(platform.directories.contains(settingsDirPath), "Settings directory should have been created on initialization.")
    }
}