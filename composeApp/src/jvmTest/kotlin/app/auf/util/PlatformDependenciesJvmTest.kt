package app.auf.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the actual JVM implementation of PlatformDependencies.
 * These tests run on the JVM and interact with the real file system via a
 * temporary directory managed by JUnit 5.
 */
class PlatformDependenciesJvmTest {

    // JUnit 5 will create a temporary directory for each test and inject its path here.
    // It will also handle cleanup automatically.
    @TempDir
    lateinit var tempDir: File

    private val testAppVersion = "v2-test"
    private lateinit var platform: PlatformDependencies

    // A custom root for our fake ".auf" directory, placed inside the tempDir.
    private lateinit var userHome: File

    @BeforeEach
    fun setUp() {
        userHome = File(tempDir, "fake_user_home")
        userHome.mkdir()
        System.setProperty("user.home", userHome.absolutePath)

        platform = PlatformDependencies(testAppVersion)
    }

    @AfterEach
    fun tearDown() {
        // Reset the system property to avoid side effects in other test suites.
        System.clearProperty("user.home")
    }

    @Test
    fun `getUserHomePath returns correct path from system property`() {
        // Act
        val actualHomePath = platform.getUserHomePath()

        // Assert
        assertEquals(userHome.absolutePath, actualHomePath)
    }

    @Test
    fun `getBasePathFor creates correct versioned paths for user-data directories`() {
        // Arrange
        val expectedSettingsPath = File(userHome, ".auf/$testAppVersion/settings").absolutePath
        val expectedLogsPath = File(userHome, ".auf/$testAppVersion/logs").absolutePath
        val expectedBackupsPath = File(userHome, ".auf/$testAppVersion/backups").absolutePath

        // Act
        val actualSettingsPath = platform.getBasePathFor(BasePath.SETTINGS)
        val actualLogsPath = platform.getBasePathFor(BasePath.LOGS)
        val actualBackupsPath = platform.getBasePathFor(BasePath.BACKUPS)

        // Assert
        assertEquals(expectedSettingsPath, actualSettingsPath)
        assertEquals(expectedLogsPath, actualLogsPath)
        assertEquals(expectedBackupsPath, actualBackupsPath)
    }

    @Test
    fun `getBasePathFor returns correct project-relative paths`() {
        // Arrange
        val expectedHolonsPath = File("holons").absolutePath

        // Act
        val actualHolonsPath = platform.getBasePathFor(BasePath.HOLONS)

        // Assert
        assertEquals(expectedHolonsPath, actualHolonsPath)
    }


    @Test
    fun `writeFileContent and readFileContent work correctly`() {
        // Arrange
        val testFile = File(tempDir, "test.txt")
        val content = "Hello, World!"

        // Act
        platform.writeFileContent(testFile.absolutePath, content)
        val readContent = platform.readFileContent(testFile.absolutePath)

        // Assert
        assertTrue(testFile.exists(), "File should have been created.")
        assertEquals(content, readContent, "Read content should match written content.")
    }

    @Test
    fun `createDirectories creates nested directories`() {
        // Arrange
        val nestedDirPath = File(tempDir, "dir1/dir2/dir3").absolutePath

        // Act
        platform.createDirectories(nestedDirPath)

        // Assert
        assertTrue(File(nestedDirPath).exists(), "Nested directory structure should exist.")
        assertTrue(File(nestedDirPath).isDirectory, "Path should be a directory.")
    }

    @Test
    fun `listDirectory correctly lists file and directory entries`() {
        // Arrange
        val dir = File(tempDir, "listing_test")
        dir.mkdir()
        File(dir, "file1.txt").createNewFile()
        File(dir, "file2.txt").createNewFile()
        File(dir, "subdir").mkdir()

        // Act
        val entries = platform.listDirectory(dir.absolutePath)

        // Assert
        assertEquals(3, entries.size, "Should list 3 entries.")
        assertTrue(entries.any { it.path.endsWith("file1.txt") && !it.isDirectory }, "Should contain file1.txt")
        assertTrue(entries.any { it.path.endsWith("subdir") && it.isDirectory }, "Should contain subdir.")
    }

    @Test
    fun `log function creates and writes to a versioned log file`() {
        // Act
        platform.log(LogLevel.INFO, "TestTag", "This is a test log message.")

        // Assert
        val majorVersion = testAppVersion.split('.').first()
        val logDir = File(userHome, ".auf/$majorVersion/logs")
        assertTrue(logDir.exists(), "Log directory should have been created.")
        assertTrue(logDir.isDirectory, "Log path should be a directory.")

        val logFiles = logDir.listFiles()
        assertNotNull(logFiles, "Log directory should not be null.")
        assertEquals(1, logFiles.size, "Exactly one log file should be created.")

        val logFile = logFiles.first()
        val logContent = logFile.readText()
        assertTrue(logFile.name.startsWith("session-"), "Log file name is incorrect.")
        assertTrue(logContent.contains("[INFO] [TestTag] This is a test log message."), "Log file content is incorrect.")
    }
}