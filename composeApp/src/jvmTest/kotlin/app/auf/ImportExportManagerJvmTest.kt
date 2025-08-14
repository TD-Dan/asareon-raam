package app.auf

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * JVM-specific integration tests for the ImportExportManager.
 * This suite verifies the file system interactions of the `actual` implementation.
 *
 * It uses temporary directories to avoid interfering with the real holon graph.
 */
class ImportExportManagerJvmTest {

    private lateinit var tempDir: File
    private lateinit var manager: ImportExportManager
    private lateinit var graphWithPaths: List<HolonHeader>

    @BeforeTest
    fun setup() {
        // Create a temporary root directory for the test file system
        tempDir = createTempDirectory("auf-test-").toFile()

        // Setup a dummy framework structure
        val holonsDir = File(tempDir, "holons").apply { mkdirs() }
        val rootDir = File(holonsDir, "root-id").apply { mkdirs() }
        val childDir = File(rootDir, "child-id").apply { mkdirs() }
        val rootJson = File(rootDir, "root-id.json").apply { writeText("""{"header":{"id":"root-id", "type": "Root", "name": "R", "summary": "S", "sub_holons":[{"id":"child-id", "type": "Child", "summary": "CS"}]}}""") }
        val childJson = File(childDir, "child-id.json").apply { writeText("""{"header":{"id":"child-id", "type": "Child", "name": "C", "summary": "CS"}}""") }

        // Initialize the manager with the temp directory path
        manager = ImportExportManager(tempDir.absolutePath, JsonProvider.appJson)

        val rootHolon = HolonHeader("root-id", "Root", "Root", "", subHolons = listOf(SubHolonRef("child-id", "Child", "")))
        val childHolon = HolonHeader("child-id", "Child", "Child", "", parentId = "root-id")

        graphWithPaths = listOf(
            rootHolon.copy(filePath = rootJson.absolutePath),
            childHolon.copy(filePath = childJson.absolutePath)
        )
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `analyzeFolder correctly identifies an UPDATED holon`() {
        // Arrange: Create a source folder with a newer version of an existing holon
        val sourceFolder = File(tempDir, "import-source").apply { mkdirs() }
        val newerChildFile = File(sourceFolder, "child-id.json")
        newerChildFile.writeText("""{"header":{"id":"child-id","name":"Newer Child", "type": "Child", "summary": "S"}}""")
        val existingChildFile = File(graphWithPaths.find { it.id == "child-id" }!!.filePath)
        // Ensure the new file is 'newer'
        newerChildFile.setLastModified(existingChildFile.lastModified() + 1000)

        // Act
        val importItems = manager.analyzeFolder(sourceFolder.absolutePath, graphWithPaths)

        // Assert
        assertEquals(1, importItems.size)
        val item = importItems.first()
        assertEquals(newerChildFile.absolutePath, item.sourcePath)
        assertTrue(item.initialAction is Update, "Action should be Update")
        assertEquals("child-id", (item.initialAction as Update).targetHolonId)
    }

    @Test
    fun `analyzeFolder correctly identifies a NEW holon to be INTEGRATED`() {
        // Arrange
        val sourceFolder = File(tempDir, "import-source").apply { mkdirs() }
        // Let's pretend 'child-id' has a sub-holon we don't know about yet
        val newGrandchildFile = File(sourceFolder, "grandchild-id.json")
        newGrandchildFile.writeText("""{"header":{"id":"grandchild-id", "type":"gc", "name":"gc", "summary":"gc"}}""")

        // We need to tell the analyzer that 'grandchild-id' belongs to 'child-id'
        val graphWithSubHolonInfo = graphWithPaths.map {
            if (it.id == "child-id") {
                it.copy(subHolons = listOf(SubHolonRef("grandchild-id", "Grandchild", "")))
            } else {
                it
            }
        }

        // Act
        val importItems = manager.analyzeFolder(sourceFolder.absolutePath, graphWithSubHolonInfo)

        // Assert
        assertEquals(1, importItems.size)
        val item = importItems.first()
        assertTrue(item.initialAction is Integrate, "Action should be Integrate")
        assertEquals("child-id", (item.initialAction as Integrate).parentHolonId)
    }

    @Test
    fun `analyzeFolder correctly identifies a NEW holon that needs a PARENT assigned`() {
        // Arrange
        val sourceFolder = File(tempDir, "import-source").apply { mkdirs() }
        val unknownFile = File(sourceFolder, "unknown-id.json")
        unknownFile.writeText("""{"header":{"id":"unknown-id", "type":"u", "name":"u", "summary":"u"}}""")

        // Act
        val importItems = manager.analyzeFolder(sourceFolder.absolutePath, graphWithPaths)

        // Assert
        assertEquals(1, importItems.size)
        val item = importItems.first()
        assertTrue(item.initialAction is AssignParent, "Action should be AssignParent")
    }

    @Test
    fun `analyzeFolder correctly QUARANTINES malformed JSON`() {
        // Arrange
        val sourceFolder = File(tempDir, "import-source").apply { mkdirs() }
        val malformedFile = File(sourceFolder, "malformed.json")
        malformedFile.writeText("""{"header":{id:"bad-json"}}""") // Missing quotes on key

        // Act
        val importItems = manager.analyzeFolder(sourceFolder.absolutePath, graphWithPaths)

        // Assert
        assertEquals(1, importItems.size)
        val item = importItems.first()
        assertTrue(item.initialAction is Quarantine, "Action should be Quarantine")
    }
}