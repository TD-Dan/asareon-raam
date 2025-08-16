package app.auf.service

import app.auf.core.GraphLoadResult

/**
 * Service dedicated to handling all business logic related to the Holon Knowledge Graph.
 *
 * ---
 * ## Mandate
 * This class is the single entry point for graph-related operations. It orchestrates
 * the loading and parsing of holons by delegating to lower-level managers like GraphLoader.
 * It is designed to be a testable, platform-agnostic component that contains all
 * asynchronous logic and business rules for the knowledge graph.
 *
 * ---
 * ## Dependencies
 * - `app.auf.service.GraphLoader`: The component responsible for file-system-level discovery and parsing.
 *
 * @version 1.0
 * @since 2025-08-16
 */
class GraphService(
    private val graphLoader: GraphLoader
) {
    /**
     * Loads the entire Holon Knowledge Graph based on the currently selected AI persona.
     * This is an asynchronous, suspendable function.
     *
     * @param currentPersonaId The ID of the persona to load. Can be null.
     * @return A [GraphLoadResult] containing the outcome of the load operation.
     */
    suspend fun loadGraph(currentPersonaId: String?): GraphLoadResult {
        return graphLoader.loadGraph(currentPersonaId)
    }
}