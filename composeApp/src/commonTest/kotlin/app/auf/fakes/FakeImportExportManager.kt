package app.auf.fakes

import app.auf.core.HolonHeader
import app.auf.service.ImportExportManager
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json

class FakeImportExportManager(
    platform: PlatformDependencies,
    json: Json
) : ImportExportManager(platform, json) {

    var executeExportCalled = false
    var executeImportCalled = false

    override fun executeExport(destinationPath: String, headersToExport: List<HolonHeader>) {
        executeExportCalled = true
    }

    override suspend fun executeImport(
        sourcePath: String,
        actions: Map<String, app.auf.core.ImportAction>,
        graph: List<HolonHeader>,
        personaId: String
    ): Result<String> {
        executeImportCalled = true
        return Result.success("Fake import complete.")
    }
}