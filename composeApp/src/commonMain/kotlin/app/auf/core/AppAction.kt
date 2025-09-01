package app.auf.core

import app.auf.model.SettingValue

/**
 * Defines all possible actions that can be dispatched to the Store to trigger a state change.
 * As of v2.0.0, this is a non-sealed interface to support an open, pluggable feature architecture,
 * allowing features in separate packages to define and dispatch their own action subtypes.
 */
interface AppAction {

    // --- Graph Loading Actions ---
    data object LoadGraph : AppAction
    data class LoadGraphSuccess(val result: GraphLoadResult) : AppAction
    data class LoadGraphFailure(val error: String) : AppAction

    // --- Chat & Gateway Actions ---
    data class AddUserMessage(val rawContent: String) : AppAction
    data class AddSystemMessage(val title: String, val rawContent: String) : AppAction
    data object SendMessageLoading : AppAction
    data class SendMessageSuccess(val response: GatewayResponse) : AppAction
    data class SendMessageFailure(val error: String) : AppAction
    data object CancelMessage : AppAction
    data class DeleteMessage(val id: Long) : AppAction
    data class RerunFromMessage(val id: Long) : AppAction

    // --- UI & View Actions ---
    data class SetViewMode(val mode: ViewMode) : AppAction
    data class InspectHolon(val holonId: String?) : AppAction
    data class ToggleHolonActive(val holonId: String) : AppAction
    data class SetCatalogueFilter(val type: String?) : AppAction
    data class ShowToast(val message: String) : AppAction
    data object ClearToast : AppAction
    data object ToggleSystemVisibility : AppAction
    data class ToggleHolonExport(val holonId: String) : AppAction
    data object SelectAllForExport : AppAction
    data object DeselectAllForExport : AppAction
    data class ToggleMessageCollapsed(val id: Long) : AppAction


    // --- Persona & Model Selection ---
    data class SelectAiPersona(val holonId: String?) : AppAction
    data class SelectModel(val modelName: String) : AppAction
    data class SetAvailableModels(val models: List<String>) : AppAction

    // --- Action Manifest Execution ---
    data class ExecuteActionManifest(val messageTimestamp: Long) : AppAction
    data class ExecuteActionManifestSuccess(val summary: String, val messageTimestamp: Long) : AppAction
    data class ExecuteActionManifestFailure(val error: String, val messageTimestamp: Long) : AppAction
    data class UpdateActionStatus(val messageTimestamp: Long, val status: ActionStatus) : AppAction

    // --- Settings Actions ---
    data class UpdateSetting(val setting: SettingValue) : AppAction

    // --- Session Persistence Actions ---
    data class LoadSessionSuccess(val history: List<ChatMessage>) : AppAction
}