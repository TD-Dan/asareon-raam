// FILE: composeApp/src/commonTest/kotlin/app/auf/StateManagerTest.kt
// VERDICT: UPDATE
// REASON: Replaced the non-existent `addDirectory` call with the correct,
// contract-defined `createDirectories` method from the PlatformDependencies interface.
// This brings the test into compliance with the dependency's actual API and
// resolves the compilation error in a robust, architecturally sound way.

package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for the StateManager, utilizing fake dependencies for a controlled test environment.
 *
 * @version 1.1
 * @since 2025-08-16
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePlatform: FakePlatformDependencies
    private lateinit var fakeGatewayManager: FakeGatewayManager
    private lateinit var fakeBackupManager: FakeBackupManager
    private lateinit var fakeImportExportManager: FakeImportExportManager
    private lateinit var fakeImportExportViewModel: ImportExportViewModel
    private lateinit var graphLoader: GraphLoader
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var stateManager: StateManager

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePlatform = FakePlatformDependencies()
        fakeGatewayManager = FakeGatewayManager()
        fakeBackupManager = FakeBackupManager(fakePlatform)
        fakeImportExportManager = FakeImportExportManager(fakePlatform)

        // Real components that use fakes
        graphLoader = GraphLoader(fakePlatform, JsonProvider.appJson)
        actionExecutor = ActionExecutor(fakePlatform, JsonProvider.appJson)

        // The ViewModel uses a real coroutine scope but a fake manager
        val testCoroutineScope = CoroutineScope(testDispatcher)
        fakeImportExportViewModel = ImportExportViewModel(fakeImportExportManager, testCoroutineScope)

        stateManager = StateManager(
            gatewayManager = fakeGatewayManager,
            backupManager = fakeBackupManager,
            graphLoader = graphLoader,
            actionExecutor = actionExecutor,
            importExportViewModel = fakeImportExportViewModel,
            platform = fakePlatform,
            initialSettings = UserSettings(),
            coroutineScope = testCoroutineScope
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize creates backup and loads graph correctly when persona exists`() = runTest {
        // Arrange: Create a fake persona file system
        val personaId = "test-persona-1"
        val personaPath = "holons/$personaId/$personaId.json"
        val personaContent = """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "summary": "" },
              "payload": {}
            }
        """.trimIndent()
        // Use writeFileContent which implicitly creates parent directories in the fake
        fakePlatform.writeFileContent(personaPath, personaContent)

        // Act
        stateManager.initialize()
        testDispatcher.scheduler.advanceUntilIdle() // Ensure all coroutines complete

        // Assert
        assertTrue(fakeBackupManager.createBackupCalled, "A backup should have been created on launch.")
        assertEquals("on-launch", fakeBackupManager.createBackupTrigger)
        assertEquals(GatewayStatus.OK, stateManager.state.value.gatewayStatus)
        assertEquals(personaId, stateManager.state.value.aiPersonaId)
        assertEquals(1, stateManager.state.value.holonGraph.size)
        assertEquals("Test Persona", stateManager.state.value.holonGraph.first().name)
    }

    @Test
    fun `loadHolonGraph sets error state when no personas are found`() = runTest {
        // Arrange: No files in the fake file system

        // Act
        stateManager.loadHolonGraph()
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        assertEquals(GatewayStatus.ERROR, stateManager.state.value.gatewayStatus)
        assertTrue(stateManager.state.value.errorMessage!!.contains("No AI_Persona_Root holons found"))
    }

    @Test
    fun `onHolonClicked inspects and toggles holon in CHAT mode`() = runTest {
        // Arrange
        val personaId = "test-persona-1"
        val childId = "child-1"
        val personaPath = "holons/$personaId/$personaId.json"
        val childDir = "holons/$personaId/$childId"
        val childPath = "$childDir/$childId.json"

        val personaContent = """
            {
              "header": { "id": "$personaId", "type": "AI_Persona_Root", "name": "Test Persona", "summary": "", "sub_holons": [{"id": "$childId", "type": "Log", "summary": ""}] },
              "payload": {}
            }
        """.trimIndent()
        val childContent = """
            {
              "header": { "id": "$childId", "type": "Log", "name": "Child Holon", "summary": "" },
              "payload": { "data": "test" }
            }
        """.trimIndent()

        // --- FIX IS HERE: Using the correct contract method `createDirectories` ---
        // This simulates the file system structure required for the GraphLoader to traverse.
        fakePlatform.createDirectories("holons/$personaId")
        fakePlatform.createDirectories(childDir)
        fakePlatform.writeFileContent(personaPath, personaContent)
        fakePlatform.writeFileContent(childPath, childContent)

        stateManager.loadHolonGraph()
        testDispatcher.scheduler.advanceUntilIdle()

        // Act
        stateManager.onHolonClicked(childId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        assertEquals(childId, stateManager.state.value.inspectedHolonId, "Inspected ID should be set.")
        assertTrue(stateManager.state.value.contextualHolonIds.contains(childId), "Holon should be added to context.")
        assertNotNull(stateManager.state.value.activeHolons[childId], "Holon content should be loaded into active map.")
        assertEquals("test", stateManager.state.value.activeHolons[childId]!!.payload["data"]?.jsonPrimitive?.content)

        // Act again (toggle off)
        stateManager.onHolonClicked(childId)

        // Assert
        assertTrue(!stateManager.state.value.contextualHolonIds.contains(childId), "Holon should be removed from context.")
    }
}