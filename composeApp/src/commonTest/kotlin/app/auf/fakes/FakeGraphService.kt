package app.auf.fakes

import app.auf.core.GraphLoadResult
import app.auf.service.GraphService

/**
 * A fake implementation of GraphService for use in unit tests.
 */
class FakeGraphService : GraphService(FakeGraphLoader()) {
    var loadGraphCalled = 0
    var nextResult: GraphLoadResult = GraphLoadResult()

    override suspend fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        loadGraphCalled++
        return nextResult
    }
}