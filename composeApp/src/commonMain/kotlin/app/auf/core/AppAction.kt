package app.auf.core

import app.auf.model.SettingValue

/**
 * Defines all possible actions that can be dispatched to the Store to trigger a state change.
 * As of v2.0.0, this is a non-sealed interface to support an open, pluggable feature architecture,
 * allowing features in separate packages to define and dispatch their own action subtypes.
 */
interface AppAction {

    // --- DEPRECATED Chat & Gateway Actions ---
    // These are now handled by HkgAgentFeature and SessionFeature internally.

    // --- UI & View Actions ---
    data class ShowToast(val message: String) : AppAction
    data object ClearToast : AppAction
    data object ToggleSystemVisibility : AppAction // This will likely be deprecated soon.


    // --- Model Selection ---
    data class SelectModel(val modelName: String) : AppAction
    data class SetAvailableModels(val models: List<String>) : AppAction

    // --- DEPRECATED Action Manifest Actions ---

    // --- Settings Actions ---
    data class UpdateSetting(val setting: SettingValue) : AppAction

    // --- DEPRECATED Session Persistence Actions ---
}