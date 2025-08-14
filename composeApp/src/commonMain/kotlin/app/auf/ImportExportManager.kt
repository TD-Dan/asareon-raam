package app.auf

import kotlinx.serialization.json.Json

/**
 * Defines the contract for a service that handles all business logic for import and export operations.
 *
 * ---
 * ## Mandate
 * This expect class defines a platform-agnostic contract for interacting with the file system
 * for the purpose of importing and exporting holons. Its responsibility is to provide a stable API
 * that the shared ViewModel/State Management logic can use, while delegating the platform-specific
 * file I/O implementation to the `actual` class on each target platform.
 *
 * ---
 * ## Dependencies
 * - `app.auf.HolonHeader`
 * - `app.auf.ImportItem`
 * - `app.auf.ImportState`
 * - `kotlinx.serialization.json.Json`
 *
 * @version 1.0
 * @since 2025-08-14
 */
expect class ImportExportManager(
    frameworkBasePath: String,
    jsonParser: Json
) {
    /**
     * Analyzes a source folder against the current knowledge graph to determine
     * the initial proposed action for each file.
     */
    fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem>

    /**
     * Executes the file copy operations for the export feature.
     */
    fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>)

    /**
     * Executes all file modifications for the import feature based on the finalized actions.
     */
    fun executeImport(
        importState: ImportState,
        currentGraph: List<HolonHeader>,
        personaId: String,
        holonsBasePath: String
    )
}