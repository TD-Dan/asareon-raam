package app.auf.service

import app.auf.core.*
import app.auf.util.fakes.FakePlatformDependencies
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class SettingsPersistenceServiceTest {

    // --- Test Doubles: Self-contained fakes, local to this test file ---

    @Serializable
    data class WidgetFeatureState(val count: Int) : FeatureState
    class WidgetFeature : Feature { override val name: String = "WidgetFeature" }

    @Serializable
    data class GadgetFeatureState(val name: String, val isEnabled: Boolean) : FeatureState
    class GadgetFeature : Feature { override val name: String = "GadgetFeature" }

    private lateinit var platform: FakePlatformDependencies
    private lateinit var jsonParser: Json
    private lateinit var settingsPersistenceService: SettingsPersistenceService

    private val settingsFilePath = "/fake/settings/user_settings.json"

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies()

        // CRITICAL: The JSON parser for this test ONLY knows about the local fakes.
        // It has NO knowledge of SystemClockState, HkgAgentState, etc.
        jsonParser = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                polymorphic(FeatureState::class) {
                    subclass(WidgetFeatureState::class)
                    subclass(GadgetFeatureState::class)
                }
            }
        }

        // Initialize the service with its new, leaner constructor.
        settingsPersistenceService = SettingsPersistenceService(platform, jsonParser)
    }

    @Test
    fun `saveSettings and loadSettings correctly persist and restore UserSettings with local fakes`() {
        // --- ARRANGE ---
        // Create a complex state object using ONLY the locally defined fake states.
        val originalSettings = UserSettings(
            windowWidth = 1280,
            windowHeight = 720,
            featureStates = mapOf(
                "WidgetFeature" to WidgetFeatureState(count = 42),
                "GadgetFeature" to GadgetFeatureState(name = "Zapper", isEnabled = true)
            )
        )

        // --- ACT ---
        settingsPersistenceService.saveSettings(originalSettings)
        val loadedSettings = settingsPersistenceService.loadSettings()

        // --- ASSERT ---
        assertTrue(platform.fileExists(settingsFilePath), "Settings file should have been created.")
        assertNotNull(loadedSettings, "Loaded settings should not be null.")
        assertEquals(originalSettings, loadedSettings, "Loaded settings should be equal to the original settings.")

        // Verify the types of the deserialized feature states.
        val loadedWidgetState = loadedSettings.featureStates["WidgetFeature"]
        val loadedGadgetState = loadedSettings.featureStates["GadgetFeature"]
        assertIs<WidgetFeatureState>(loadedWidgetState)
        assertIs<GadgetFeatureState>(loadedGadgetState)

        // Verify the content of the deserialized states.
        assertEquals(42, loadedWidgetState.count)
        assertEquals(true, loadedGadgetState.isEnabled)
        assertEquals("Zapper", loadedGadgetState.name)
    }

    @Test
    fun `loadSettings returns null when the settings file does not exist`() {
        // --- ACT ---
        val loadedSettings = settingsPersistenceService.loadSettings()
        // --- ASSERT ---
        assertNull(loadedSettings, "Should return null when no settings file exists.")
    }

    @Test
    fun `loadSettings returns null when the settings file is corrupt`() {
        // --- ARRANGE ---
        platform.writeFileContent(settingsFilePath, "{ \"windowWidth\": 1024, \"windowHeight\": \"not-a-number\" }")
        // --- ACT ---
        val loadedSettings = settingsPersistenceService.loadSettings()
        // --- ASSERT ---
        assertNull(loadedSettings, "Should return null for a corrupt settings file to prevent crashing.")
    }

    @Test
    fun `SettingsPersistenceService constructor creates settings directory if it does not exist`() {
        // --- ARRANGE ---
        val localPlatform = FakePlatformDependencies() // Use a fresh, clean fake for this test
        val settingsDirPath = "/fake/settings"
        assertFalse(localPlatform.directories.contains(settingsDirPath), "Precondition: Directory should not exist.")

        // --- ACT ---
        // Instantiate the service here, which triggers the `init` block.
        SettingsPersistenceService(localPlatform, jsonParser)

        // --- ASSERT ---
        assertTrue(localPlatform.directories.contains(settingsDirPath), "Settings directory should have been created on initialization.")
    }
}