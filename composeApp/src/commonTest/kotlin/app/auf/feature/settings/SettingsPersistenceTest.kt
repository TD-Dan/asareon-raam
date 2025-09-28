package app.auf.feature.settings

import app.auf.fakes.FakePlatformDependencies
import app.auf.util.BasePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsPersistenceTest {

    @Test
    fun `loadSettings returns empty map when file does not exist`() {
        // Arrange
        // FIX: Pass a version string to the constructor
        val fakePlatform = FakePlatformDependencies("v-test")
        val persistence = SettingsPersistence(fakePlatform)

        // Act
        val settings = persistence.loadSettings()

        // Assert
        assertTrue(settings.isEmpty(), "Should return an empty map for a non-existent file.")
    }

    @Test
    fun `saveSettings and loadSettings correctly write and read data`() {
        // Arrange
        // FIX: Pass a version string to the constructor
        val fakePlatform = FakePlatformDependencies("v-test")
        val persistence = SettingsPersistence(fakePlatform)
        val settingsToSave = mapOf("key1" to "value1", "key2" to "true")
        val expectedPath = fakePlatform.getBasePathFor(BasePath.SETTINGS) + fakePlatform.pathSeparator + "settings.json"


        // Act
        persistence.saveSettings(settingsToSave)
        val loadedSettings = persistence.loadSettings()

        // Assert
        assertTrue(fakePlatform.fileExists(expectedPath), "The settings file should have been created at '$expectedPath'.")
        assertEquals(settingsToSave, loadedSettings, "Loaded settings should match the saved settings.")
    }

    @Test
    fun `loadSettings returns empty map for corrupt JSON`() {
        // Arrange
        // FIX: Pass a version string to the constructor
        val fakePlatform = FakePlatformDependencies("v-test")
        val persistence = SettingsPersistence(fakePlatform)
        val settingsPath = fakePlatform.getBasePathFor(BasePath.SETTINGS) + fakePlatform.pathSeparator + "settings.json"
        fakePlatform.writeFileContent(settingsPath, "this is not valid json")

        // Act
        val settings = persistence.loadSettings()

        // Assert
        assertTrue(settings.isEmpty(), "Should return an empty map when JSON is corrupt.")
    }
}