// FILE: composeApp/src/commonMain/kotlin/app/auf/ImportExportViewModel.kt

package app.auf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A dedicated ViewModel for managing the state and business logic of the import/export feature.
 *
 * ---
 * ## Mandate
 * This class's sole responsibility is to handle all operations related to importing and exporting
 * holons. It encapsulates the `ImportState` and orchestrates calls to the `ImportExportManager`
 * for file system interactions. This adheres to the Single Responsibility Principle by decoupling
 * this specific feature's logic from the main StateManager.
 *
 * ---
 * ## Dependencies
 * - `app.auf.ImportExportManager`
 * - `app.auf.ImportState`
 * - `app.auf.HolonHeader`
 *
 * @version 1.2
 * @since 2025-08-15
 */
open class ImportExportViewModel(
    val importExportManager: ImportExportManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _importState = MutableStateFlow<ImportState?>(null)
    open val importState = _importState.asStateFlow()

    var onImportComplete: () -> Unit = {}

    fun startImport(sourcePath: String) {
        _importState.value = ImportState(sourcePath)
    }

    // --- MODIFIED: New function to update the path in the state ---
    fun setImportPath(sourcePath: String) {
        _importState.update { it?.copy(sourcePath = sourcePath) }
    }


    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>) {
        // Update the path in the state before analyzing
        setImportPath(sourcePath)
        coroutineScope.launch(Dispatchers.Default) {
            val importItems = importExportManager.analyzeFolder(sourcePath, currentGraph)
            _importState.update { it?.copy(items = importItems) }
        }
    }

    open fun updateImportAction(sourceFilePath: String, newAction: ImportAction) {
        _importState.update { currentImportState ->
            currentImportState?.let {
                val updatedActions = it.selectedActions.toMutableMap()
                updatedActions[sourceFilePath] = newAction
                it.copy(selectedActions = updatedActions)
            }
        }
    }

    open fun executeImport(currentGraph: List<HolonHeader>, personaId: String, holonsBasePath: String) {
        val currentState = _importState.value ?: return
        coroutineScope.launch(Dispatchers.Default) {
            importExportManager.executeImport(currentState, currentGraph, personaId, holonsBasePath)
            onImportComplete()
        }
    }

    open fun cancelImport() {
        _importState.value = null
    }
}