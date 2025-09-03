package app.auf.core

import app.auf.model.SettingValue
import app.auf.service.BackupManager
import app.auf.service.SettingsManager
import app.auf.service.SourceCodeService
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

open class StateManager(
    private val store: Store,
    private val backupManager: BackupManager,
    internal val sourceCodeService: SourceCodeService,
    val settingsManager: SettingsManager,
    private val platform: PlatformDependencies,
    private val coroutineScope: CoroutineScope,
    private val features: List<Feature>
) {

    open val state: StateFlow<AppState> = store.state

    /**
     * The single, generic entry point for all state changes.
     * The UI is responsible for creating the appropriate Action object.
     */
    fun dispatch(action: AppAction) {
        store.dispatch(action)
    }

    fun initialize() {
        backupManager.createBackup("on-launch")
    }

    // --- Complex Operations & Platform Access ---

    fun cancelMessage() {
        // TODO: This will need to dispatch a feature-specific cancel action.
    }

    fun formatDisplayTimestamp(timestamp: Long): String {
        return platform.formatDisplayTimestamp(timestamp)
    }

    fun openBackupFolder() {
        backupManager.createBackup("on-export-view")
        backupManager.openBackupFolder()
    }

    fun copyCodebaseToClipboard() {
        coroutineScope.launch {
            val codebaseString = sourceCodeService.collateKtFilesToString()
            if (codebaseString.startsWith("ERROR:")) {
                dispatch(AppAction.ShowToast(codebaseString))
            } else {
                platform.copyToClipboard(codebaseString)
                dispatch(AppAction.ShowToast("Source code copied to clipboard!"))
            }
        }
    }

    /**
     * A special helper that uses the delegation pattern to find the correct
     * feature to handle a generic setting update.
     */
    fun updateSetting(settingValue: SettingValue) {
        val actionToDispatch = features.firstNotNullOfOrNull {
            it.createActionForSetting(settingValue)
        }
        if (actionToDispatch != null) {
            dispatch(actionToDispatch)
        } else {
            println("WARNING: No feature handled setting update for key '${settingValue.key}'")
        }
    }
}