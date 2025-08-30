package app.auf.fakes

import app.auf.core.HolonHeader
import app.auf.core.ImportAction
import app.auf.service.ImportExportManager
import app.auf.service.ImportResult
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json

class FakeImportExportManager(
    platform: PlatformDependencies,
    json: Json
) : ImportExportManager(platform, json) {

    var executeExportCalled = false
    var executeImportCalled = false
    var lastImportActions: Map<String, ImportAction>? = null

    override fun executeExport(destinationPath: String, headersToExport: List<HolonHeader>) {
        executeExportCalled = true
    }

    override suspend fun executeImport(
        actions: Map<String, ImportAction>,
        graph: List<HolonHeader>,
        personaId: String?
    ): ImportResult {
        executeImportCalled = true
        lastImportActions = actions
        // Return a default success result for testing purposes
        return ImportResult(successfulImports = actions.keys.toList(), failedImports = emptyMap())
    }
}