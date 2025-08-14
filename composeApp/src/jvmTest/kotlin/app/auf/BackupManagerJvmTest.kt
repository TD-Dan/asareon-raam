package app.auf

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-specific integration tests for the BackupManager.
 *
 * ---
 * ## Mandate
 * This suite verifies the file system interactions of the `BackupManager`.
 * It uses a temporary directory to test the creation and integrity of backup
 * zip archives in an isolated environment, ensuring the core backup logic is correct.
 *
 * ---
 * ## Dependencies
 * - `java.io.File` for temporary directory management.
 *
 * @version 1.0
 * @since 2025-08-14
 */
class BackupManagerJvmTest {

    private lateinit var tempDir: File
    private lateinit var settingsDir: File
    private lateinit var holonsDir: File
    private lateinit var backupManager: BackupManager

    @Before
    fun setup() {
        // 1. Create a unique temporary directory for the entire test run.
        tempDir = File.createTempFile("auf-test-", "").apply {
            delete()
            mkdirs()
        }
        settingsDir = File(tempDir, ".auf").apply { mkdirs() }
        holonsDir = File(tempDir, "holons").apply { mkdirs() }

        // 2. Create a dummy file structure to be backed up.
        File(holonsDir, "sage-root.json").writeText("{'id':'sage'}")
        val projectDir = File(holonsDir, "project-1").apply { mkdirs() }
        File(projectDir, "project-1.json").writeText("{'id':'project-1'}")

        // 3. Instantiate the manager with our temporary paths.
        backupManager = BackupManager(holonsDir.absolutePath, settingsDir)
    }

    @After
    fun teardown() {
        // 4. Clean up the temporary directory after the test.
        tempDir.deleteRecursively()
    }

    @Test
    fun `createBackup creates a valid zip archive with correct contents`() = runBlocking {
        // Arrange
        val trigger = "test-backup"

        // Act
        backupManager.createBackup(trigger)

        // Assert
        // The backup runs in a background coroutine, so we wait briefly for it to finish.
        delay(500)

        val backupsDir = File(settingsDir, "backups")
        assertTrue(backupsDir.exists(), "Backups directory should be created.")

        val backupFiles = backupsDir.listFiles()
        assertEquals(1, backupFiles?.size, "Exactly one backup file should be created.")
        val backupFile = backupFiles!!.first()
        assertTrue(backupFile.name.startsWith("auf-backup-"), "Backup file name should have correct prefix.")
        assertTrue(backupFile.name.endsWith("-$trigger.zip"), "Backup file name should have correct trigger and extension.")

        // Verify the contents of the zip file
        val zipContents = ZipFile(backupFile).entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
        val expectedContents = setOf(
            "sage-root.json",
            "project-1/",
            "project-1/project-1.json"
        )
        assertEquals(expectedContents, zipContents, "Zip file contents must match the source directory structure.")
    }
}