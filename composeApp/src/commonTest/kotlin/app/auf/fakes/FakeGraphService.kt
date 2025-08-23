package app.auf.fakes

import app.auf.core.GraphLoadResult
import app.auf.service.GraphService

class FakeGraphService : GraphService(mock()) { // Depends on GraphLoader, which we don't need to fake here
    var loadGraphCalled = 0
    var nextResult: GraphLoadResult = GraphLoadResult()

    override fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        loadGraphCalled++
        return nextResult
    }

    // Helper for mocking, can be replaced with a proper library
    private inline fun <reified T> mock(): T = TODO("Add MockK")
}