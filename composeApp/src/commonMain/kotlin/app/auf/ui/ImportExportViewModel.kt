package app.auf.ui

import app.auf.core.HolonHeader
import app.auf.core.ImportAction
import app.auf.core.ImportState
import app.auf.service.ImportExportManager
import app.auf.service.ImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A dedicated ViewModel for managing the state and business logic of the import/export feature.
 *
 * @version 1.7
 * @since 2025-08-28
 */
open class ImportExportViewModel(
    val importExportManager: ImportExportManager,
    private val coroutineScope: CoroutineScope
) {

    private val _importState = MutableStateFlow<ImportState?>(null)
    open val importState = _importState.asStateFlow()

    private val _isRecursive = MutableStateFlow(true)
    open val isRecursive = _isRecursive.asStateFlow()

    // --- MODIFICATION START: Add state for the filter toggle ---
    private val _showOnlyChanged = MutableStateFlow(false)
    open val showOnlyChanged = _showOnlyChanged.asStateFlow()
    // --- MODIFICATION END ---


    var onImportComplete: () -> Unit = {}
    var onImportFailed: (String) -> Unit = {}

    fun startImport(sourcePath: String = "") {
        _importState.value = ImportState(sourcePath)
        _isRecursive.value = true
        _showOnlyChanged.value = false // Reset on start
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

    // --- MODIFICATION START: Add function to handle filter toggle ---
    open fun toggleShowOnlyChanged() {
        _showOnlyChanged.value = !_showOnlyChanged.value
    }
    // --- MODIFICATION END ---

    open fun updateImportAction(sourceFilePath: String, newAction: ImportAction) {
        _importState.update { currentImportState ->
            currentImportState?.let {
                val updatedActions = it.selectedActions.toMutableMap()
                updatedActions[sourceFilePath] = newAction
                it.copy(selectedActions = updatedActions)
            }
        }
    }

    open fun executeImport(currentGraph: List<HolonHeader>, personaId: String?) {
        val currentState = _importState.value ?: return
        coroutineScope.launch(Dispatchers.Default) {
            val result = importExportManager.executeImport(
                actions = currentState.selectedActions,
                graph = currentGraph,
                personaId = personaId
            )

            withContext(Dispatchers.Main) {
                if (result.failedImports.isNotEmpty()) {
                    val failedFiles = result.failedImports.keys.joinToString { it.substringAfterLast('/') }
                    val errorMessage = "Import completed with ${result.failedImports.size} errors: $failedFiles"
                    onImportFailed(errorMessage)
                }

                if (result.successfulImports.isNotEmpty()) {
                    onImportComplete()
                }
            }
        }
    }

    open fun cancelImport() {
        _importState.value = null
    }
}