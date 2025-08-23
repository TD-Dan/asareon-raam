package app.auf.fakes

import app.auf.core.GraphLoadResult
import app.auf.service.GraphLoader
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json

/**
 * A minimal fake implementation of GraphLoader to satisfy the GraphService constructor in tests.
 */
class FakeGraphLoader(
    platform: PlatformDependencies = FakePlatformDependencies(),
    jsonParser: Json = Json
) : GraphLoader(platform, jsonParser) {
    override fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        return GraphLoadResult(fatalError = "This is a fake loader.")
    }
}