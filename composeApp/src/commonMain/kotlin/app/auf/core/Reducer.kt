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
        // is AppAction.AddUserMessage -> ...
        // is AppAction.AddSystemMessage -> ...
        is AppAction.SendMessageLoading -> state.copy(
            isProcessing = true
        )
        is AppAction.SendMessageSuccess -> state.copy(
            isProcessing = false
            // The chatHistory update is now handled by SessionFeature
        )
        is AppAction.SendMessageFailure -> state.copy(
            isProcessing = false
            // The chatHistory update is now handled by SessionFeature
        )
        is AppAction.CancelMessage -> state.copy(
            isProcessing = false
        )
        // is AppAction.DeleteMessage -> ...
        // is AppAction.RerunFromMessage -> ...

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
            val newCompilerSettings = when (action.setting.key) {
                "compiler.removeWhitespace" -> state.compilerSettings.copy(removeWhitespace = action.setting.value as? Boolean ?: state.compilerSettings.removeWhitespace) // Needs updating: core should not know about feature specifics
                "compiler.cleanHeaders" -> state.compilerSettings.copy(cleanHeaders = action.setting.value as? Boolean ?: state.compilerSettings.cleanHeaders) // Needs updating: core should not know about feature specifics
                "compiler.minifyJson" -> state.compilerSettings.copy(minifyJson = action.setting.value as? Boolean ?: state.compilerSettings.minifyJson) // Needs updating: core should not know about feature specifics
                else -> state.compilerSettings
            }
            if (newCompilerSettings != state.compilerSettings) {
                state.copy(compilerSettings = newCompilerSettings)  // Needs updating: core should not know about feature specifics
            } else {
                state
            }
        }

        // --- DEPRECATED SESSION PERSISTENCE ACTIONS ---
        // is AppAction.LoadSessionSuccess -> ...

        else -> state
    }
}