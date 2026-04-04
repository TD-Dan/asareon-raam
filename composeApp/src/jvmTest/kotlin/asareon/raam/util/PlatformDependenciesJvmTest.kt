package asareon.raam.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `getBasePathFor returns correct paths for APP_ZONE and USER_ZONE`() {
        // Arrange
        val expectedAppZonePath = File(userHome, ".auf/$testAppVersion").absolutePath
        val expectedUserZonePath = userHome.absolutePath

        // Act
        val actualAppZonePath = platform.getBasePathFor(BasePath.APP_ZONE)
        val actualUserZonePath = platform.getBasePathFor(BasePath.USER_ZONE)

        // Assert
        assertEquals(expectedAppZonePath, actualAppZonePath, "APP_ZONE path is incorrect.")
        assertEquals(expectedUserZonePath, actualUserZonePath, "USER_ZONE path is incorrect.")
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
    fun `listDirectory throws IOException for non-existent path`() {
        // Arrange
        val nonExistentPath = File(tempDir, "non_existent_dir").absolutePath

        // Act & Assert
        val exception = assertThrows<IOException> {
            platform.listDirectory(nonExistentPath)
        }
        assertTrue(exception.message!!.contains("Path does not exist"), "Exception message is incorrect.")
    }

    @Test
    fun `listDirectory throws IOException for path that is a file`() {
        // Arrange
        val filePath = File(tempDir, "i_am_a_file.txt").absolutePath
        platform.writeFileContent(filePath, "content") // Ensure the file exists

        // Act & Assert
        val exception = assertThrows<IOException> {
            platform.listDirectory(filePath)
        }
        assertTrue(exception.message!!.contains("Path is not a directory"), "Exception message is incorrect.")
    }

    @Test
    fun `listDirectoryRecursive finds all files in nested structure`() {
        // Arrange
        val root = File(tempDir, "recursive_root")
        val sub1 = File(root, "sub1")
        val sub2 = File(root, "sub2")
        platform.createDirectories(sub1.absolutePath)
        platform.createDirectories(sub2.absolutePath)

        platform.writeFileContent(File(root, "root_file.txt").absolutePath, "")
        platform.writeFileContent(File(sub1, "sub1_file.txt").absolutePath, "")
        platform.writeFileContent(File(sub2, "sub2_file.txt").absolutePath, "")
        platform.writeFileContent(File(sub2, "another.log").absolutePath, "")

        // Act
        val entries = platform.listDirectoryRecursive(root.absolutePath)
        val entryPaths = entries.map { it.path }

        // Assert
        assertEquals(6, entries.size, "Should find all 4 files and 2 directories.")
        assertTrue(entryPaths.any { it.endsWith("root_file.txt") })
        assertTrue(entryPaths.any { it.endsWith("sub1_file.txt") })
        assertTrue(entryPaths.any { it.endsWith("sub2_file.txt") })
        assertTrue(entryPaths.any { it.endsWith("another.log") })
    }

    @Test
    fun `deleteFile removes the specified file`() {
        // Arrange
        val fileToDelete = File(tempDir, "to_delete.txt")
        platform.writeFileContent(fileToDelete.absolutePath, "content")
        assertTrue(fileToDelete.exists(), "Precondition failed: File was not created.")

        // Act
        platform.deleteFile(fileToDelete.absolutePath)

        // Assert
        assertFalse(fileToDelete.exists(), "File should have been deleted.")
    }

    @Test
    fun `deleteDirectory removes directory and its contents`() {
        // Arrange
        val dirToDelete = File(tempDir, "dir_to_delete")
        val subDir = File(dirToDelete, "subdir")
        val fileInSubDir = File(subDir, "file.txt")

        platform.createDirectories(subDir.absolutePath)
        platform.writeFileContent(fileInSubDir.absolutePath, "content")
        assertTrue(dirToDelete.exists(), "Precondition: Root directory should exist.")
        assertTrue(fileInSubDir.exists(), "Precondition: Nested file should exist.")

        // Act
        platform.deleteDirectory(dirToDelete.absolutePath)

        // Assert
        assertFalse(dirToDelete.exists(), "Directory should have been deleted.")
        assertFalse(fileInSubDir.exists(), "Nested file should also be deleted.")
    }

    @Test
    fun `log function creates and writes to a versioned log file within the APP_ZONE`() {
        // Act
        platform.log(LogLevel.INFO, "TestTag", "This is a test log message.")

        // Assert
        val logDir = File(userHome, ".auf/$testAppVersion/logs")
        assertTrue(logDir.exists(), "Log directory should have been created in the APP_ZONE.")
        assertTrue(logDir.isDirectory, "Log path should be a directory.")

        val logFiles = logDir.listFiles()
        assertNotNull(logFiles, "Log directory should not be null.")
        assertEquals(1, logFiles.size, "Exactly one log file should be created.")

        val logFile = logFiles.first()
        val logContent = logFile.readText()
        assertTrue(logFile.name.startsWith("session-"), "Log file name is incorrect.")
        assertTrue(logContent.contains("This is a test log message."), "Log file content is incorrect.")
    }

    // === New tests for workspace context feature support ===

    @Test
    fun `listDirectory should populate lastModified on file entries`() {
        // Arrange
        val dir = File(tempDir, "lastmod_test")
        dir.mkdir()
        val testFile = File(dir, "recent.txt")
        testFile.writeText("content")

        // Act
        val entries = platform.listDirectory(dir.absolutePath)

        // Assert
        val fileEntry = entries.find { it.path.endsWith("recent.txt") }
        assertNotNull(fileEntry, "Should find recent.txt")
        assertNotNull(fileEntry.lastModified, "lastModified should be populated for files")
        assertTrue(fileEntry.lastModified!! > 0, "lastModified should be a positive timestamp")
    }

    @Test
    fun `listDirectoryRecursive should populate lastModified on all file entries`() {
        // Arrange
        val root = File(tempDir, "recursive_lastmod")
        val sub = File(root, "sub")
        platform.createDirectories(sub.absolutePath)
        platform.writeFileContent(File(root, "a.txt").absolutePath, "a")
        platform.writeFileContent(File(sub, "b.txt").absolutePath, "b")

        // Act
        val entries = platform.listDirectoryRecursive(root.absolutePath)

        // Assert
        assertEquals(3, entries.size)
        entries.forEach { entry ->
            assertNotNull(entry.lastModified, "lastModified should be populated for ${entry.path}")
            assertTrue(entry.lastModified!! > 0, "lastModified should be positive for ${entry.path}")
        }
    }

    @Test
    fun `scheduleDelayed should execute callback after delay`() {
        // Act
        var callbackFired = false
        val handle = platform.scheduleDelayed(50L) { callbackFired = true }

        // Assert: not fired immediately
        assertFalse(callbackFired, "Callback should not fire immediately")
        assertNotNull(handle, "Should return a cancellation handle")

        // Wait for callback
        Thread.sleep(200)
        assertTrue(callbackFired, "Callback should have fired after delay")
    }

    @Test
    fun `cancelScheduled should prevent callback from firing`() {
        // Act
        var callbackFired = false
        val handle = platform.scheduleDelayed(100L) { callbackFired = true }
        platform.cancelScheduled(handle)

        // Wait longer than the delay
        Thread.sleep(300)

        // Assert
        assertFalse(callbackFired, "Cancelled callback should not fire")
    }

    @Test
    fun `parseIsoTimestamp should correctly round-trip with formatIsoTimestamp`() {
        // Arrange
        val originalTimestamp = 1707400000000L // Some epoch millis

        // Act
        val formatted = platform.formatIsoTimestamp(originalTimestamp)
        val parsed = platform.parseIsoTimestamp(formatted)

        // Assert
        assertNotNull(parsed, "Should successfully parse a valid ISO timestamp")
        // Note: round-trip may lose sub-second precision since format uses seconds
        assertEquals(
            originalTimestamp / 1000,
            parsed / 1000,
            "Parsed timestamp should match original (to second precision)"
        )
    }

    @Test
    fun `parseIsoTimestamp should return null for invalid input`() {
        // Act
        val result = platform.parseIsoTimestamp("not-a-timestamp")

        // Assert
        assertNull(result, "Should return null for unparseable input")
    }

    // === Tests for byte-level file I/O ===

    @Test
    fun `writeFileBytes and readFileBytes round-trip binary content`() {
        // Arrange
        val filePath = File(tempDir, "binary_test.dat").absolutePath
        val binaryContent = byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte(), 0xFE.toByte(), 0x42)

        // Act
        platform.writeFileBytes(filePath, binaryContent)
        val readBack = platform.readFileBytes(filePath)

        // Assert
        assertTrue(File(filePath).exists(), "File should have been created.")
        assertTrue(binaryContent.contentEquals(readBack),
            "Read bytes should match written bytes.")
    }

    @Test
    fun `writeFileBytes creates parent directories if needed`() {
        // Arrange
        val filePath = File(tempDir, "nested/dirs/binary.dat").absolutePath

        // Act
        platform.writeFileBytes(filePath, byteArrayOf(0x42))

        // Assert
        assertTrue(File(filePath).exists(), "File should exist in nested directory structure.")
    }

    // === Tests for copyFile ===

    @Test
    fun `copyFile duplicates file content to destination`() {
        // Arrange
        val src = File(tempDir, "copy_source.txt")
        val dst = File(tempDir, "copy_dest.txt")
        platform.writeFileContent(src.absolutePath, "original content")

        // Act
        platform.copyFile(src.absolutePath, dst.absolutePath)

        // Assert
        assertTrue(dst.exists(), "Destination file should exist.")
        assertEquals("original content", platform.readFileContent(dst.absolutePath),
            "Destination content should match source.")
        assertTrue(src.exists(), "Source file should still exist after copy.")
    }

    @Test
    fun `copyFile overwrites existing destination`() {
        // Arrange
        val src = File(tempDir, "overwrite_src.txt")
        val dst = File(tempDir, "overwrite_dst.txt")
        platform.writeFileContent(src.absolutePath, "new content")
        platform.writeFileContent(dst.absolutePath, "old content")

        // Act
        platform.copyFile(src.absolutePath, dst.absolutePath)

        // Assert
        assertEquals("new content", platform.readFileContent(dst.absolutePath),
            "Destination should be overwritten with source content.")
    }

    // === Tests for fileSize ===

    @Test
    fun `fileSize returns correct size for existing file`() {
        // Arrange
        val file = File(tempDir, "sized_file.txt")
        val content = "Hello, World!" // 13 bytes in UTF-8
        platform.writeFileContent(file.absolutePath, content)

        // Act
        val size = platform.fileSize(file.absolutePath)

        // Assert
        assertEquals(content.toByteArray().size.toLong(), size,
            "fileSize should return the byte length of the file.")
    }

    @Test
    fun `fileSize returns 0 for non-existent file`() {
        // Arrange
        val nonExistent = File(tempDir, "no_such_file.txt").absolutePath

        // Act
        val size = platform.fileSize(nonExistent)

        // Assert
        assertEquals(0L, size, "fileSize should return 0 for a non-existent file.")
    }

    // === Tests for createZipArchive and extractZipArchive ===

    @Test
    fun `createZipArchive produces a valid zip file`() {
        // Arrange
        val sourceDir = File(tempDir, "zip_source")
        platform.createDirectories(sourceDir.absolutePath)
        platform.writeFileContent(File(sourceDir, "file1.txt").absolutePath, "content-one")
        platform.writeFileContent(File(sourceDir, "file2.md").absolutePath, "content-two")
        val zipPath = File(tempDir, "output.zip").absolutePath

        // Act
        platform.createZipArchive(sourceDir.absolutePath, zipPath, "")

        // Assert
        assertTrue(File(zipPath).exists(), "Zip file should have been created.")
        assertTrue(File(zipPath).length() > 0, "Zip file should not be empty.")
    }

    @Test
    fun `createZipArchive excludes named directory`() {
        // Arrange
        val sourceDir = File(tempDir, "zip_exclude_source")
        platform.createDirectories(sourceDir.absolutePath)
        platform.writeFileContent(File(sourceDir, "keep.txt").absolutePath, "keep me")
        val excludedDir = File(sourceDir, "_backups")
        platform.createDirectories(excludedDir.absolutePath)
        platform.writeFileContent(File(excludedDir, "old.zip").absolutePath, "old backup data")
        val zipPath = File(tempDir, "excluded.zip").absolutePath

        // Act
        platform.createZipArchive(sourceDir.absolutePath, zipPath, "_backups")

        // Assert: extract and verify _backups was excluded
        val extractDir = File(tempDir, "zip_exclude_verify")
        platform.extractZipArchive(zipPath, extractDir.absolutePath)

        assertTrue(File(extractDir, "keep.txt").exists(),
            "Non-excluded file should be in the archive.")
        assertFalse(File(extractDir, "_backups").exists(),
            "Excluded directory should NOT be in the archive.")
    }

    @Test
    fun `extractZipArchive restores all files from an archive`() {
        // Arrange: create source, zip it, then extract to a different location
        val sourceDir = File(tempDir, "extract_source")
        val subDir = File(sourceDir, "subdir")
        platform.createDirectories(subDir.absolutePath)
        platform.writeFileContent(File(sourceDir, "root.txt").absolutePath, "root-content")
        platform.writeFileContent(File(subDir, "nested.txt").absolutePath, "nested-content")
        val zipPath = File(tempDir, "extract_test.zip").absolutePath
        platform.createZipArchive(sourceDir.absolutePath, zipPath, "")

        val targetDir = File(tempDir, "extract_target")

        // Act
        platform.extractZipArchive(zipPath, targetDir.absolutePath)

        // Assert
        assertTrue(targetDir.exists(), "Target directory should exist.")
        val rootFile = File(targetDir, "root.txt")
        assertTrue(rootFile.exists(), "root.txt should be extracted.")
        assertEquals("root-content", rootFile.readText(), "root.txt content should match.")
        val nestedFile = File(targetDir, "subdir/nested.txt")
        assertTrue(nestedFile.exists(), "Nested file should be extracted.")
        assertEquals("nested-content", nestedFile.readText(), "Nested file content should match.")
    }

    @Test
    fun `createZipArchive and extractZipArchive round-trip preserves content`() {
        // Arrange
        val sourceDir = File(tempDir, "roundtrip_source")
        platform.createDirectories(sourceDir.absolutePath)
        val fileContents = mapOf(
            "config.json" to """{"key": "value"}""",
            "data/records.csv" to "id,name\n1,Alice\n2,Bob",
            "notes/readme.md" to "# Hello\nThis is a test."
        )
        fileContents.forEach { (path, content) ->
            platform.writeFileContent(File(sourceDir, path).absolutePath, content)
        }
        val zipPath = File(tempDir, "roundtrip.zip").absolutePath
        val extractDir = File(tempDir, "roundtrip_extract")

        // Act
        platform.createZipArchive(sourceDir.absolutePath, zipPath, "")
        platform.extractZipArchive(zipPath, extractDir.absolutePath)

        // Assert
        fileContents.forEach { (path, expectedContent) ->
            val extractedFile = File(extractDir, path)
            assertTrue(extractedFile.exists(), "Extracted file '$path' should exist.")
            assertEquals(expectedContent, extractedFile.readText(),
                "Content of '$path' should survive round-trip.")
        }
    }

    @Test
    fun `extractZipArchive creates target directory if it does not exist`() {
        // Arrange
        val sourceDir = File(tempDir, "auto_create_source")
        platform.createDirectories(sourceDir.absolutePath)
        platform.writeFileContent(File(sourceDir, "file.txt").absolutePath, "data")
        val zipPath = File(tempDir, "auto_create.zip").absolutePath
        platform.createZipArchive(sourceDir.absolutePath, zipPath, "")
        val targetDir = File(tempDir, "deeply/nested/target")
        assertFalse(targetDir.exists(), "Precondition: target should not exist yet.")

        // Act
        platform.extractZipArchive(zipPath, targetDir.absolutePath)

        // Assert
        assertTrue(targetDir.exists(), "Target directory should have been auto-created.")
        assertTrue(File(targetDir, "file.txt").exists(), "Extracted file should exist.")
    }

    // === Tests for resolveAbsoluteSandboxPath ===

    @Test
    fun `resolveAbsoluteSandboxPath builds correct path for simple feature handle`() {
        // Act
        val path = platform.resolveAbsoluteSandboxPath("session", "workspace/file.txt")

        // Assert
        val expectedBase = platform.getBasePathFor(BasePath.APP_ZONE)
        val sep = File.separatorChar
        assertEquals("$expectedBase${sep}session${sep}workspace/file.txt", path)
    }

    @Test
    fun `resolveAbsoluteSandboxPath strips sub-identity from dotted handle`() {
        // "agent.coder-1" should resolve to the "agent" sandbox, not "agent.coder-1"
        // Act
        val path = platform.resolveAbsoluteSandboxPath("agent.coder-1", "data.json")

        // Assert
        val expectedBase = platform.getBasePathFor(BasePath.APP_ZONE)
        val sep = File.separatorChar
        assertEquals("$expectedBase${sep}agent${sep}data.json", path)
    }

    @Test
    fun `resolveAbsoluteSandboxPath with blank relative path returns sandbox root`() {
        // Act
        val path = platform.resolveAbsoluteSandboxPath("session", "")

        // Assert
        val expectedBase = platform.getBasePathFor(BasePath.APP_ZONE)
        val sep = File.separatorChar
        assertEquals("$expectedBase${sep}session", path)
    }

    @Test
    fun `resolveAbsoluteSandboxPath sanitizes special characters in handle`() {
        // Act
        val path = platform.resolveAbsoluteSandboxPath("bad/handle\\name", "file.txt")

        // Assert — '/' and '\' in the handle should be sanitized to '_'
        val expectedBase = platform.getBasePathFor(BasePath.APP_ZONE)
        val sep = File.separatorChar
        assertFalse(path.contains("bad/handle"),
            "Special characters in handle should be sanitized.")
        assertTrue(path.startsWith("$expectedBase$sep"),
            "Path should still be rooted under APP_ZONE.")
    }

    // === Tests for getFileName and getParentDirectory ===

    @Test
    fun `getFileName extracts file name from path`() {
        assertEquals("file.txt", platform.getFileName("/some/path/file.txt"))
        assertEquals("dir", platform.getFileName("/some/path/dir"))
    }

    @Test
    fun `getParentDirectory returns parent path`() {
        val parent = platform.getParentDirectory(File(tempDir, "child.txt").absolutePath)
        assertNotNull(parent)
        assertEquals(tempDir.absolutePath, parent)
    }

    // === Test for getLastModified ===

    @Test
    fun `getLastModified returns positive timestamp for existing file`() {
        // Arrange
        val file = File(tempDir, "lastmod.txt")
        platform.writeFileContent(file.absolutePath, "data")

        // Act
        val lastMod = platform.getLastModified(file.absolutePath)

        // Assert
        assertTrue(lastMod > 0, "lastModified should be a positive epoch millis value.")
    }
}