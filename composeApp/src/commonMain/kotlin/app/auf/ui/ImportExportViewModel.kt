package app.auf.ui

import app.auf.core.HolonHeader
import app.auf.core.ImportAction
import app.auf.core.ImportState
import app.auf.service.ImportExportManager
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
 * - `app.auf.service.ImportExportManager`
 * - `app.auf.core.ImportState`
 * - `app.auf.core.HolonHeader`
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

    fun startImport(sourcePath: String = "") {
        _importState.value = ImportState(sourcePath)
    }

    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>) {
        coroutineScope.launch(Dispatchers.Default) {
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

    open fun executeImport(currentGraph: List<HolonHeader>, personaId: String) {
        val currentState = _importState.value ?: return
        coroutineScope.launch(Dispatchers.Default) {
            // --- FIX IS HERE: `holonsBasePath` argument removed from the call. ---
            importExportManager.executeImport(currentState, currentGraph, personaId)
            onImportComplete()
        }
    }

    open fun cancelImport() {
        _importState.value = null
    }
}