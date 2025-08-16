package app.auf

import app.auf.core.GraphLoadResult
import app.auf.service.GraphLoader
import app.auf.service.GraphService
import app.auf.util.JsonProvider
import app.auf.fakes.FakePlatformDependencies
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * A fake implementation of GraphLoader for testing purposes.
 * It allows us to control the output of `loadGraph` and verify it was called correctly.
 */
class FakeGraphLoader : GraphLoader(FakePlatformDependencies(), JsonProvider.appJson) {
    var resultToReturn: GraphLoadResult = GraphLoadResult()
    var invokedWithPersonaId: String? = null
        private set
    var invokeCount = 0
        private set

    override fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        invokedWithPersonaId = currentPersonaId
        invokeCount++
        return resultToReturn
    }
}


/**
 * Unit tests for the GraphService.
 *
 * ---
 * ## Mandate
 * This suite verifies the business logic of the GraphService in isolation. It uses a
 * FakeGraphLoader to simulate the dependency, ensuring that the service correctly
 * orchestrates the graph loading process and passes data through as expected. This test
 * focuses on the service's role as an orchestrator, not on the underlying file I/O.
 *
 * ---
 * ## Test Strategy
 * - **Arrange: ** A `FakeGraphLoader` is instantiated and configured to return a specific, predefined `GraphLoadResult`.
 * - **Act: ** The `graphService.loadGraph()` method is called.
 * - **Assert: ** Verify that the `GraphService` returned the exact result from the fake loader and that the fake loader was called with the correct parameters.
 *
 * @version 1.0
 * @since 2025-08-16
 */
class GraphServiceTest {

    private lateinit var fakeGraphLoader: FakeGraphLoader
    private lateinit var graphService: GraphService

    @BeforeTest
    fun setup() {
        // Instantiate the fake dependency and the class under test
        fakeGraphLoader = FakeGraphLoader()
        graphService = GraphService(fakeGraphLoader)
    }

    @Test
    fun `loadGraph should call loader and return its success result`() = runTest {
        // Arrange: Prepare a successful result object for the fake loader to return.
        val expectedResult = GraphLoadResult(
            holonGraph = listOf(FakeHolon.header),
            determinedPersonaId = FakeHolon.header.id
        )
        fakeGraphLoader.resultToReturn = expectedResult
        val testPersonaId = "test-persona-id"

        // Act: Call the method on the service we are testing.
        val actualResult = graphService.loadGraph(testPersonaId)

        // Assert: Verify the results.
        assertEquals(1, fakeGraphLoader.invokeCount, "GraphLoader's loadGraph should be called exactly once.")
        assertEquals(testPersonaId, fakeGraphLoader.invokedWithPersonaId, "GraphLoader should be called with the correct persona ID.")
        assertEquals(expectedResult, actualResult, "GraphService should return the exact result from the GraphLoader.")
    }

    @Test
    fun `loadGraph should call loader and return its failure result`() = runTest {
        // Arrange: Prepare a failure result object.
        val expectedResult = GraphLoadResult(
            fatalError = "Test fatal error"
        )
        fakeGraphLoader.resultToReturn = expectedResult
        val testPersonaId = "test-persona-id"

        // Act
        val actualResult = graphService.loadGraph(testPersonaId)

        // Assert
        assertEquals(1, fakeGraphLoader.invokeCount)
        assertEquals(testPersonaId, fakeGraphLoader.invokedWithPersonaId)
        assertEquals(expectedResult, actualResult, "GraphService should correctly pass through the failure result.")
        assertEquals("Test fatal error", actualResult.fatalError)
    }

    @Test
    fun `loadGraph with null personaId should pass null to the loader`() = runTest {
        // Arrange
        fakeGraphLoader.resultToReturn = GraphLoadResult()

        // Act
        graphService.loadGraph(null)

        // Assert
        assertEquals(1, fakeGraphLoader.invokeCount)
        assertNull(fakeGraphLoader.invokedWithPersonaId, "GraphLoader should be called with null when the service is given null.")
    }
}

// A simple object to provide fake data for tests, avoiding magic strings.
object FakeHolon {
    val header = app.auf.core.HolonHeader(
        id = "test-holon-1",
        type = "Test_Type",
        name = "Test Holon",
        summary = "A test holon"
    )
}