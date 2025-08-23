package app.auf.core

/**
 * Defines all possible actions that can be dispatched to the Store to trigger a state change.
 *
 * ---
 * ## Mandate
 * This file contains the sealed interface `AppAction` and all data classes that implement it.
 * Each class represents a single, specific, serializable user intent or asynchronous event
 * completion. This strict contract is the foundation of the Unidirectional Data Flow (UDF)
 * architecture, ensuring that state changes are predictable and traceable.
 *
 * ---
 * ## Dependencies
 * - `app.auf.core.AppState`: Many actions carry payload data defined in AppState.kt.
 *
 * @version 1.9
 * @since 2025-08-17
 */
sealed interface AppAction {

    // --- Graph Loading Actions ---
    data object LoadGraph : AppAction
    data class LoadGraphSuccess(val result: GraphLoadResult, val timestamp: Long) : AppAction
    data class LoadGraphFailure(val error: String) : AppAction

    // --- Chat & Gateway Actions ---
    data class AddUserMessage(val contentBlocks: List<ContentBlock>, val timestamp: Long) : AppAction
    data class AddSystemMessage(val contentBlocks: List<ContentBlock>, val timestamp: Long) : AppAction
    data object SendMessageLoading : AppAction
    data class SendMessageSuccess(val response: GatewayResponse, val timestamp: Long) : AppAction
    data class SendMessageFailure(val error: String, val timestamp: Long) : AppAction
    data object CancelMessage : AppAction
    data class DeleteMessage(val timestamp: Long) : AppAction

    // --- UI & View Actions ---
    data class SetViewMode(val mode: ViewMode) : AppAction
    data class InspectHolon(val holonId: String?) : AppAction
    data class ToggleHolonActive(val holonId: String) : AppAction
    data class SetCatalogueFilter(val type: String?) : AppAction
    data class ShowToast(val message: String) : AppAction
    data object ClearToast : AppAction
    data object ToggleSystemVisibility : AppAction
    data class ToggleHolonExport(val holonId: String) : AppAction

    // --- Persona & Model Selection ---
    data class SelectAiPersona(val holonId: String?) : AppAction
    data class SelectModel(val modelName: String) : AppAction
    data class SetAvailableModels(val models: List<String>) : AppAction

    // --- Action Manifest Execution ---
    data class ExecuteActionManifest(val messageTimestamp: Long) : AppAction
    data class ExecuteActionManifestSuccess(val summary: String, val messageTimestamp: Long) : AppAction
    data class ExecuteActionManifestFailure(val error: String) : AppAction
    data class ResolveActionInMessage(val messageTimestamp: Long) : AppAction
}