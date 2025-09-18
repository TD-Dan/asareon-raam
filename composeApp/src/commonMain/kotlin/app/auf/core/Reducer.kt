package app.auf.core

/**
 * The root Reducer function for the Unidirectional Data Flow (UDF) architecture.
 * As of v2.0, this reducer only handles core, app-wide state. Feature-specific
 * state changes are handled by their own dedicated reducers.
 */
fun appReducer(state: AppState, action: AppAction): AppState {
    return when (action) {
        is ShowToast -> state.copy(
            toastMessage = action.message
        )
        is ClearToast -> state.copy(
            toastMessage = null
        )
        is SetActiveView -> state.copy(
            activeViewKey = action.key
        )
        else -> state
    }
}