package app.auf.feature.knowledgegraph

import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeGraphServiceTest {

    private lateinit var service: KnowledgeGraphService
    private lateinit var platform: FakePlatformDependencies

    // --- FAKE HOLON CONTENT ---
    private val personaContent = """
    {
      "header": {
        "id": "persona-1",
        "type": "AI_Persona_Root",
        "name": "Test Persona",
        "summary": "A test persona.",
        "sub_holons": [
          { "id": "project-a", "type": "Project", "summary": "A sub-project." }
        ]
      }, "payload": {}
    }
    """.trimIndent()

    private val projectContent = """
    {
      "header": {
        "id": "project-a",
        "type": "Project",
        "name": "Project A",
        "summary": "A test project.",
        "sub_holons": []
      }, "payload": {}
    }
    """.trimIndent()

    @BeforeTest
    fun setup() {
        platform = FakePlatformDependencies()
        service = KnowledgeGraphService(platform)
    }

    @Test
    fun `loadGraph with a valid hierarchy should succeed`() = runTest {
        // Arrange: Set up a fake file system with a valid persona and project
        val personaDir = "/fake/holons/persona-1"
        val projectDir = "$personaDir/project-a"
        platform.writeFileContent("$personaDir/persona-1.json", personaContent)
        platform.writeFileContent("$projectDir/project-a.json", projectContent)

        // Act
        val result = service.loadGraph("persona-1")

        // Assert
        assertNull(result.fatalError, "There should be no fatal error.")
        assertEquals(2, result.holonGraph.size, "The graph should contain two holons.")

        val persona = result.holonGraph.find { it.header.id == "persona-1" }
        assertNotNull(persona)
        assertEquals(0, persona.header.depth)
        assertNull(persona.header.parentId)

        val project = result.holonGraph.find { it.header.id == "project-a" }
        assertNotNull(project)
        assertEquals(1, project.header.depth, "Project should be at depth 1.")
        assertEquals("persona-1", project.header.parentId, "Project's parent should be the persona.")
    }

    @Test
    fun `loadGraph with unparsable JSON should report errors but not fail catastrophically`() = runTest {
        // Arrange
        val personaDir = "/fake/holons/persona-1"
        platform.writeFileContent("$personaDir/persona-1.json", personaContent)
        platform.writeFileContent("$personaDir/project-a/project-a.json", "{ not json }")

        // Act
        val result = service.loadGraph("persona-1")

        // Assert
        assertNull(result.fatalError)
        assertEquals(1, result.holonGraph.size, "Should load the valid persona holon.")
        assertEquals(1, result.parsingErrors.size, "Should have one parsing error.")
        assertTrue(result.parsingErrors.first().contains("Parse failed for project-a"), "Error message should identify the corrupt file.")
    }

    @Test
    fun `loadGraph with no persona selected and one available should auto-select it`() = runTest {
        // Arrange
        val personaDir = "/fake/holons/persona-1"
        platform.writeFileContent("$personaDir/persona-1.json", personaContent)

        // Act
        val result = service.loadGraph(null) // No persona pre-selected

        // Assert
        assertNull(result.fatalError)
        assertEquals("persona-1", result.determinedPersonaId, "Should have auto-determined the only available persona.")
        assertEquals(1, result.holonGraph.size)
    }

    @Test
    fun `loadGraph with no persona selected and multiple available should return a fatal error`() = runTest {
        // Arrange
        platform.writeFileContent("/fake/holons/persona-1/persona-1.json", personaContent)
        platform.writeFileContent("/fake/holons/persona-2/persona-2.json", personaContent.replace("persona-1", "persona-2"))

        // Act
        val result = service.loadGraph(null) // No persona pre-selected

        // Assert
        assertNotNull(result.fatalError)
        assertTrue(result.fatalError.contains("Please select an Active Agent"), "Error should prompt user to select a persona.")
        assertEquals(0, result.holonGraph.size, "Should not load any graph.")
        assertEquals(2, result.availableAiPersonas.size, "Should report two available personas.")
    }

    @Test
    fun `analyzeFolder should correctly identify Update, Integrate, and AssignParent actions`() = runTest {
        // Arrange: Setup existing graph in the fake platform
        platform.currentTime = 2000L // Set the "old" timestamp
        val existingPersonaPath = "/fake/holons/persona-1/persona-1.json"
        platform.writeFileContent(existingPersonaPath, personaContent)

        // Arrange: Setup source folder with new/updated files
        platform.currentTime = 3000L // Set the "new" timestamp before writing new files
        val sourcePath = "/fake/import_source"
        val newerPersonaContent = personaContent.replace("A test persona.", "An updated persona.")
        val newProjectContent = projectContent.replace("project-a", "project-b")
        val orphanContent = projectContent.replace("project-a", "orphan-1")

        platform.writeFileContent("$sourcePath/persona-1.json", newerPersonaContent)
        platform.writeFileContent("$sourcePath/project-b.json", newProjectContent) // Not in original persona's sub_holons
        platform.writeFileContent("$sourcePath/project-a.json", projectContent) // In original persona's sub_holons
        platform.writeFileContent("$sourcePath/orphan-1.json", orphanContent)

        val currentGraph = service.loadGraph("persona-1").holonGraph.map { it.header }

        // Act
        val analysisResult = service.analyzeFolder(sourcePath, currentGraph, false)
        val actions = analysisResult.associate { it.sourcePath.substringAfterLast('/') to it.initialAction }

        // Assert
        assertIs<Update>(actions["persona-1.json"], "persona-1 should be an Update action.")
        assertIs<Integrate>(actions["project-a.json"], "project-a should be an Integrate action.")
        assertIs<AssignParent>(actions["project-b.json"], "project-b should require parent assignment.")
        assertIs<AssignParent>(actions["orphan-1.json"], "orphan-1 should require parent assignment.")
    }
}