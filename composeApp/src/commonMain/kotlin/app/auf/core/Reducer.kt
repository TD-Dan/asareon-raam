package app.auf.core

/**
 * The root Reducer function for the Unidirectional Data Flow (UDF) architecture.
 * As of v2.0, this reducer only handles core, app-wide state. Feature-specific
 * state changes are handled by their own dedicated reducers.
 */
fun appReducer(state: AppState, action: AppAction): AppState {
    return when (action) {
        is AppAction.ShowToast -> state.copy(
            toastMessage = action.message
        )
        is AppAction.ClearToast -> state.copy(
            toastMessage = null
        )
        is AppAction.ToggleSystemVisibility -> state.copy(
            isSystemVisible = !state.isSystemVisible
        )
        // All feature-specific logic has been removed.
        else -> state
    }
}