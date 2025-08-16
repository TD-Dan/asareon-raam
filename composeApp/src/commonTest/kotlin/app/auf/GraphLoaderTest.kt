// FILE: composeApp/src/commonTest/kotlin/app/auf/GraphLoaderTest.kt
package app.auf

import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the GraphLoader, the core service for reading the Holon Knowledge Graph.
 *
 * ---
 * ## Mandate
 * This suite verifies the business logic of the GraphLoader in isolation. It uses a
 * FakePlatformDependencies object to simulate a file system, allowing for fast,
 * deterministic, and comprehensive testing of all loading scenarios without
 * performing any real file I/O.
 *
 * ---
 * ## Test Strategy
 * - **Arrange:** A virtual file system is constructed using the `fakePlatform` object.
 * - **Act:** The `graphLoader.loadGraph()` method is called.
 * - **Assert:** The returned `GraphLoadResult` is inspected to verify correctness.
 *
 * @version 1.1
 * @since 2025-08-16
 */
class GraphLoaderTest {

    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var graphLoader: GraphLoader

    // --- Test Persona and Holon IDs ---
    private val personaId = "sage-20250726T213010Z"
    private val projectId = "auf-20250707T091000Z"

    @BeforeTest
    fun setup() {
        // Instantiate the fake dependency and the class under test
        fakePlatform = FakePlatformDependencies()
        graphLoader = GraphLoader(fakePlatform, JsonProvider.appJson)
    }

    // --- Happy Path Tests ---

    @Test
    fun `loadGraph with a valid, multi-level graph should load successfully`() {
        // Arrange: Create a virtual file system with a persona and a sub-holon
        val personaDir = "holons/$personaId"
        val projectDir = "$personaDir/$projectId"
        val personaPath = "$personaDir/$personaId.json"
        val projectPath = "$projectDir/$projectId.json"

        val personaContent = """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "summary": "", "sub_holons": [{"id": "$projectId", "type": "Project", "summary": ""}] },
              "payload": {}
            }
        """.trimIndent()
        val projectContent = """
            {
              "header": { "id": "$projectId", "type": "Project", "name": "Test Project", "summary": "" },
              "payload": {}
            }
        """.trimIndent()

        // Use the fake platform to "create" the files and directories
        fakePlatform.createDirectories(projectDir) // This implicitly creates parent dirs
        fakePlatform.writeFileContent(personaPath, personaContent)
        fakePlatform.writeFileContent(projectPath, projectContent)

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = personaId)

        // Assert
        assertNull(result.fatalError, "There should be no fatal error for a valid graph.")
        assertTrue(result.parsingErrors.isEmpty(), "There should be no parsing errors.")
        assertEquals(2, result.holonGraph.size, "The graph should contain two holons.")

        val personaHeader = result.holonGraph.find { it.id == personaId }
        val projectHeader = result.holonGraph.find { it.id == projectId }

        assertNotNull(personaHeader, "Persona header should be loaded.")
        assertNotNull(projectHeader, "Project header should be loaded.")

        assertEquals(0, personaHeader.depth, "Root persona should be at depth 0.")
        assertNull(personaHeader.parentId, "Root persona should have no parent.")

        assertEquals(1, projectHeader.depth, "Child project should be at depth 1.")
        assertEquals(personaId, projectHeader.parentId, "Child's parentId should be correctly set.")
    }

    // --- Edge Case Tests ---

    @Test
    fun `loadGraph with multiple personas should discover all of them`() {
        // Arrange: Create two valid persona files
        val persona2Id = "other-persona-2"
        fakePlatform.writeFileContent("holons/$personaId/$personaId.json", """{"header": {"id": "$personaId", "type": "AI_Persona_Root", "name": "Persona 1", "summary": ""}, "payload": {}}""")
        fakePlatform.writeFileContent("holons/$persona2Id/$persona2Id.json", """{"header": {"id": "$persona2Id", "type": "AI_Persona_Root", "name": "Persona 2", "summary": ""}, "payload": {}}""")

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = null)

        // Assert
        assertEquals(2, result.availableAiPersonas.size, "Should discover two available personas.")
        assertNull(result.determinedPersonaId, "Should not determine a persona when it's ambiguous.")
        assertTrue(result.fatalError!!.contains("Please select an Active Agent"), "Should return a user-facing error.")
    }

    @Test
    fun `loadGraph with a single persona and null currentId should auto-determine the persona`() {
        // Arrange
        fakePlatform.writeFileContent("holons/$personaId/$personaId.json", """{"header": {"id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "summary": ""}, "payload": {}}""")

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = null)

        // Assert
        assertNull(result.fatalError)
        assertEquals(personaId, result.determinedPersonaId, "Should automatically select the single available persona.")
        assertEquals(1, result.holonGraph.size)
    }

    // --- Failure Mode Tests ---

    @Test
    fun `loadGraph with a missing child file should report a parsing error`() {
        // Arrange: Parent references a child, but the child file does not exist.
        val nonExistentChildId = "non-existent-child"
        val personaDir = "holons/$personaId"
        val personaPath = "$personaDir/$personaId.json"
        val personaContent = """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "summary": "", "sub_holons": [{"id": "$nonExistentChildId", "type": "Log", "summary": ""}] },
              "payload": {}
            }
        """.trimIndent()
        fakePlatform.writeFileContent(personaPath, personaContent)

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = personaId)

        // Assert
        assertEquals(1, result.holonGraph.size, "Parent holon should still be loaded.")
        assertEquals(1, result.parsingErrors.size, "Should report one parsing error.")
        // --- FIX: Make the assertion less brittle. Check for the key info (the missing ID), not the exact sentence. ---
        assertTrue(result.parsingErrors.first().contains(nonExistentChildId), "Error message should contain the ID of the missing child.")
    }

    @Test
    fun `loadGraph with malformed JSON in a file should report a parsing error`() {
        // Arrange: Create a file with invalid JSON (e.g., a trailing comma).
        val personaDir = "holons/$personaId"
        val personaPath = "$personaDir/$personaId.json"
        val malformedContent = """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Malformed", "summary": "" },
              "payload": {},
            }
        """.trimIndent()
        fakePlatform.writeFileContent(personaPath, malformedContent)

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = personaId)

        // Assert
        assertTrue(result.holonGraph.isEmpty(), "No holons should be loaded if the root fails.")
        assertEquals(1, result.parsingErrors.size, "Should report one parsing error.")
        // --- FIX: Make the assertion less brittle. Check for the key info (the persona ID), not the exact sentence. ---
        assertTrue(result.parsingErrors.first().contains(personaId), "Error message should contain the ID of the malformed persona.")
    }

    @Test
    fun `loadGraph when 'holons' directory does not exist should return a fatal error`() {
        // Arrange: The fakePlatform is empty, so the "holons" directory won't exist.

        // Act
        val result = graphLoader.loadGraph(currentPersonaId = "any-id")

        // Assert
        assertNotNull(result.fatalError, "A fatal error should be reported.")
        assertTrue(result.fatalError!!.contains("Holon directory not found"), "Error message should indicate the missing directory.")
        assertTrue(result.holonGraph.isEmpty(), "The holon graph should be empty.")
    }
}