package app.auf

import app.auf.core.GraphLoadResult
import app.auf.service.GraphLoader
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies

/**
 * ---
 * ## Mandate
 * A test-only, in-memory implementation of the `GraphLoader`. This "fake" allows us to
 * decouple the `StateManager` from the real file system during tests. Its primary function
 * is to provide a controllable `GraphLoadResult` on demand, enabling us to simulate
 * success, failure, and edge-case scenarios without any actual file I/O.
 *
 * ---
 * ## Dependencies
 * - Inherits from `app.auf.GraphLoader`.
 * - Requires a `PlatformDependencies` instance for its superclass constructor.
 *
 * @version 1.0
 * @since 2025-08-15
 */
class FakeGraphLoader(
    platform: PlatformDependencies
) : GraphLoader(platform, JsonProvider.appJson) { // Satisfy the parent constructor

    /**
     * The canned response that this fake will return when `loadGraph` is called.
     * Tests should set this property before calling the function under test.
     */
    var graphToReturn: GraphLoadResult = GraphLoadResult()

    /**
     * Overrides the real `loadGraph` to immediately return the pre-configured
     * `graphToReturn` object, bypassing all file system interaction.
     */
    override fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        return graphToReturn
    }
}