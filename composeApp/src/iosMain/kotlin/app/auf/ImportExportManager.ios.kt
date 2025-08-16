package app.auf

import app.auf.core.HolonHeader
import app.auf.core.ImportItem
import app.auf.core.ImportState
import kotlinx.serialization.json.Json

/**
 * Placeholder iOS implementation of ImportExportManager.
 */
actual open class ImportExportManager actual constructor(
    frameworkBasePath: String,
    jsonParser: Json
) {
    actual open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        println("iOS analyzeFolder not implemented.")
        return emptyList()
    }

    actual open fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>) {
        println("iOS executeExport not implemented.")
    }

    actual open fun executeImport(
        importState: ImportState,
        currentGraph: List<HolonHeader>,
        personaId: String,
        holonsBasePath: String
    ) {
        println("iOS executeImport not implemented.")
    }
}