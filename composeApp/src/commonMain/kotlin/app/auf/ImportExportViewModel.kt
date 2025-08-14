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
 * @version 1.0
 * @since 2025-08-14
 */
open class ImportExportViewModel(
    private val importExportManager: ImportExportManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onImportComplete: () -> Unit
) {

    private val _importState = MutableStateFlow<ImportState?>(null)
    open val importState = _importState.asStateFlow()

    fun startImport(sourcePath: String = "") {
        _importState.value = ImportState(sourcePath)
    }

    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>) {
        coroutineScope.launch(Dispatchers.IO) {
            val importItems = importExportManager.analyzeFolder(sourcePath, currentGraph)
            _importState.update { it?.copy(sourcePath = sourcePath, items = importItems) }
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
        coroutineScope.launch(Dispatchers.IO) {
            importExportManager.executeImport(currentState, currentGraph, personaId, holonsBasePath)
            // Signal completion to the parent manager
            onImportComplete()
        }
    }

    open fun cancelImport() {
        _importState.value = null
    }
}