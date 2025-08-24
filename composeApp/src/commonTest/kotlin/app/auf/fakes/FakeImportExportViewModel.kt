package app.auf.fakes

import app.auf.service.ImportExportManager
import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A fake implementation of the ImportExportViewModel for use in unit tests.
 * This satisfies the StateManager's dependency graph without needing a real
 * UI or complex import/export logic for tests unrelated to that feature.
 */
class FakeImportExportViewModel : ImportExportViewModel(
    importExportManager = FakeImportExportManager(FakePlatformDependencies(), JsonProvider.appJson),
    coroutineScope = CoroutineScope(Dispatchers.Unconfined)
) {
    // This fake can be expanded with properties to track calls if needed for
    // tests that specifically target import/export behavior. For now, its
    // existence is enough to satisfy the dependency injection.
}