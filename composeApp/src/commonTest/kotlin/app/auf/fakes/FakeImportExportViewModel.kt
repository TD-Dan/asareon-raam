package app.auf.fakes

import app.auf.ui.ImportExportViewModel
import app.auf.util.JsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A fake implementation of the ImportExportViewModel for use in unit tests.
 */
class FakeImportExportViewModel(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
) : ImportExportViewModel(
    importExportManager = FakeImportExportManager(FakePlatformDependencies(), JsonProvider.appJson),
    coroutineScope = coroutineScope // Pass the scope to the super constructor
) {
    // This fake can be expanded with properties to track calls if needed.
}