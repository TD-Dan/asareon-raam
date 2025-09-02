package app.auf.core

import app.auf.model.SettingValue

/**
 * The root Reducer function for the Unidirectional Data Flow (UDF) architecture.
 * As of v2.0, this reducer only handles core, app-wide state. Feature-specific
 * state changes are handled by their own dedicated reducers.
 */
fun appReducer(state: AppState, action: AppAction): AppState {
    return when (action) {
        // --- DEPRECATED CHAT & GATEWAY ACTIONS ---
        // These have been removed as they are now handled by SessionFeature and HkgAgentFeature.

        // --- UI & View ---
        is AppAction.ShowToast -> state.copy(
            toastMessage = action.message
        )
        is AppAction.ClearToast -> state.copy(
            toastMessage = null
        )
        is AppAction.ToggleSystemVisibility -> state.copy(
            isSystemVisible = !state.isSystemVisible
        )
        // is AppAction.ToggleMessageCollapsed -> ...

        // --- Model ---
        is AppAction.SelectModel -> state.copy(
            selectedModel = action.modelName
        )
        is AppAction.SetAvailableModels -> {
            val defaultModel = "gemini-1.5-flash-latest"
            val newSelectedModel = if (state.selectedModel in action.models) {
                state.selectedModel
            } else if (defaultModel in action.models) {
                defaultModel
            } else {
                action.models.firstOrNull() ?: state.selectedModel
            }
            state.copy(
                availableModels = action.models,
                selectedModel = newSelectedModel
            )
        }

        // --- DEPRECATED ACTION MANIFEST ACTIONS ---
        // is AppAction.ExecuteActionManifest -> ...
        // is AppAction.ExecuteActionManifestSuccess -> ...
        // is AppAction.ExecuteActionManifestFailure -> ...
        // is AppAction.UpdateActionStatus -> ...

        // --- Settings ---
        is AppAction.UpdateSetting -> {
            // This logic is now partially delegated to feature reducers.
            // The core reducer handles settings it knows about, like compiler settings.
            // A more advanced implementation might use a dedicated SettingsFeature.
            val newCompilerSettings = when (action.setting.key) {
                "compiler.removeWhitespace" -> state.compilerSettings.copy(removeWhitespace = action.setting.value as? Boolean ?: state.compilerSettings.removeWhitespace)
                "compiler.cleanHeaders" -> state.compilerSettings.copy(cleanHeaders = action.setting.value as? Boolean ?: state.compilerSettings.cleanHeaders)
                "compiler.minifyJson" -> state.compilerSettings.copy(minifyJson = action.setting.value as? Boolean ?: state.compilerSettings.minifyJson)
                else -> state.compilerSettings
            }
            if (newCompilerSettings != state.compilerSettings) {
                state.copy(compilerSettings = newCompilerSettings)
            } else {
                state
            }
        }

        // --- DEPRECATED SESSION PERSISTENCE ACTIONS ---
        // is AppAction.LoadSessionSuccess -> ...

        else -> state
    }
}