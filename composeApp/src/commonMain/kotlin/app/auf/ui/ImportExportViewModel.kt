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
import kotlinx.coroutines.withContext

/**
 * A dedicated ViewModel for managing the state and business logic of the import/export feature.
 */
open class ImportExportViewModel(
    val importExportManager: ImportExportManager,
    private val coroutineScope: CoroutineScope
) {

    private val _importState = MutableStateFlow<ImportState?>(null)
    open val importState = _importState.asStateFlow()

    private val _isRecursive = MutableStateFlow(true)
    open val isRecursive = _isRecursive.asStateFlow()


    var onImportComplete: () -> Unit = {}
    var onImportFailed: (String) -> Unit = {}

    fun startImport(sourcePath: String = "") {
        _importState.value = ImportState(sourcePath)
        _isRecursive.value = true
    }

    open fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>) {
        coroutineScope.launch(Dispatchers.Default) {
            val importItems = importExportManager.analyzeFolder(sourcePath, currentGraph, _isRecursive.value)
            _importState.update {
                it?.copy(
                    sourcePath = sourcePath,
                    items = importItems,
                    selectedActions = importItems.associate { item -> item.sourcePath to item.initialAction }
                )
            }
        }
    }

    open fun setRecursive(isRecursive: Boolean, currentGraph: List<HolonHeader>) {
        _isRecursive.value = isRecursive
        _importState.value?.sourcePath?.let {
            if (it.isNotBlank()) {
                analyzeFolder(it, currentGraph)
            }
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

    // --- MODIFICATION START: Handle the new ImportResult object ---
    open fun executeImport(currentGraph: List<HolonHeader>, personaId: String?) {
        val currentState = _importState.value ?: return
        coroutineScope.launch(Dispatchers.Default) {
            val result = importExportManager.executeImport(
                actions = currentState.selectedActions,
                graph = currentGraph,
                personaId = personaId
            )

            withContext(Dispatchers.Main) {
                // If there were failures, report them.
                if (result.failedImports.isNotEmpty()) {
                    val failedFiles = result.failedImports.keys.joinToString { it.substringAfterLast('/') }
                    val errorMessage = "Import completed with ${result.failedImports.size} errors: $failedFiles"
                    onImportFailed(errorMessage)
                }

                // If there were any successes, trigger the UI refresh.
                if (result.successfulImports.isNotEmpty()) {
                    onImportComplete()
                }
            }
        }
    }
    // --- MODIFICATION END ---

    open fun cancelImport() {
        _importState.value = null
    }
}