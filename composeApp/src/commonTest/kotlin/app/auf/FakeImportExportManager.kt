package app.auf

import kotlinx.serialization.json.Json

/**
 * A fake, in-memory implementation of ImportExportManager for use in tests.
 *
 * ---
 * ## Mandate
 * This class provides a test double for the ImportExportManager. It conforms to the `expect`
 * contract but overrides all functions to prevent real file I/O during tests. It uses
 * "spy" properties (e.g., `analyzeFolderCalled`) to allow tests to verify that the
 * correct logic paths were triggered in the ViewModel.
 *
 * ---
 * ## Dependencies
 * - `app.auf.ImportExportManager` (the `expect` class it implements)
 *
 * @version 1.0
 * @since 2025-08-14
 */
actual class ImportExportManager actual constructor(
    frameworkBasePath: String,
    jsonParser: Json
) {
    // Spy properties
    var analyzeFolderCalledWith: Pair<String, List<HolonHeader>>? = null
    var executeExportCalledWith: Pair<String, List<HolonHeader>>? = null
    var executeImportCalledWith: Triple<ImportState, List<HolonHeader>, String>? = null

    // Control property
    var analysisResult: List<ImportItem> = emptyList()

    actual fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        analyzeFolderCalledWith = sourcePath to currentGraph
        return analysisResult
    }

    actual fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>) {
        executeExportCalledWith = destinationPath to holonsToExport
    }

    actual fun executeImport(
        importState: ImportState,
        currentGraph: List<HolonHeader>,
        personaId: String,
        holonsBasePath: String
    ) {
        executeImportCalledWith = Triple(importState, currentGraph, personaId)
    }
}