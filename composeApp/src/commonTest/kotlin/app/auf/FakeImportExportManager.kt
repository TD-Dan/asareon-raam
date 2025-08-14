package app.auf

import kotlinx.serialization.json.Json

/**
 * A fake, in-memory implementation of ImportExportManager for use in tests.
 *
 * ---
 * ## Mandate
 * This class is a proper test double that inherits from the `ImportExportManager` expect class.
 * It overrides all functions to prevent real file I/O during tests and uses "spy" properties
 * (e.g., `analyzeFolderCalledWith`) to allow tests to verify that the correct logic paths
 * were triggered in the ViewModel.
 *
 * ---
 * ## Dependencies
 * - `app.auf.ImportExportManager` (the `expect` class it inherits from)
 *
 * @version 2.0
 * @since 2025-08-14
 */
// --- FIX: This is now a regular class that INHERITS from the expect class. ---
class FakeImportExportManager : ImportExportManager(
    // These values are passed to the parent but are not used since we override all methods.
    frameworkBasePath = "",
    jsonParser = JsonProvider.appJson
) {
    // Spy properties
    var analyzeFolderCalledWith: Pair<String, List<HolonHeader>>? = null
    var executeExportCalledWith: Pair<String, List<HolonHeader>>? = null
    var executeImportCalledWith: Triple<ImportState, List<HolonHeader>, String>? = null

    // Control property
    var analysisResult: List<ImportItem> = emptyList()

    // --- FIX: Use `override` keyword, not `actual`. ---
    override fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        analyzeFolderCalledWith = sourcePath to currentGraph
        return analysisResult
    }

    override fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>) {
        executeExportCalledWith = destinationPath to holonsToExport
    }

    override fun executeImport(
        importState: ImportState,
        currentGraph: List<HolonHeader>,
        personaId: String,
        holonsBasePath: String
    ) {
        executeImportCalledWith = Triple(importState, currentGraph, personaId)
    }
}