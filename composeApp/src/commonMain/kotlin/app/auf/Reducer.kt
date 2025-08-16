package app.auf

/**
 * The Reducer function for the Unidirectional Data Flow (UDF) architecture.
 *
 * ---
 * ## Mandate
 * This file contains the primary reducer for the application. The reducer is a PURE function
 * that takes the current `AppState` and an `AppAction` and returns a new `AppState`. It is
 * the only place in the application where the state is mutated. It must be synchronous and
 * completely free of side effects (e.g., no network calls, no file I/O).
 *
 * ---
 * ## Dependencies
 * - `app.auf.AppState`: The state object it operates on.
 * - `app.auf.AppAction`: The actions it responds to.
 *
 * @version 1.0
 * @since 2025-08-16
 */
fun appReducer(state: AppState, action: AppAction): AppState {
    return when (action) {

        // --- Graph Loading ---
        is AppAction.LoadGraph -> state.copy(
            gatewayStatus = GatewayStatus.LOADING,
            errorMessage = null,
            holonGraph = emptyList()
        )
        is AppAction.LoadGraphSuccess -> {
            val result = action.result
            val finalParsingErrors = result.parsingErrors.toMutableList()
            // This logic for loading active holons on graph load success would be
            // handled by a service, but for now, we'll represent the state change here.
            state.copy(
                holonGraph = result.holonGraph,
                gatewayStatus = GatewayStatus.OK,
                availableAiPersonas = result.availableAiPersonas,
                aiPersonaId = result.determinedPersonaId,
                // activeHolons will be loaded by a service that dispatches its own action.
                errorMessage = if (finalParsingErrors.isNotEmpty()) "Warning: ${finalParsingErrors.size} holons failed to parse." else null
            )
        }
        is AppAction.LoadGraphFailure -> state.copy(
            gatewayStatus = GatewayStatus.ERROR,
            errorMessage = action.error
        )

        // --- Chat & Gateway ---
        is AppAction.AddUserMessage -> {
            val userMessage = ChatMessage(
                author = Author.USER,
                timestamp = action.timestamp, // Use timestamp from action
                contentBlocks = listOf(TextBlock(action.message))
            )
            state.copy(chatHistory = state.chatHistory + userMessage)
        }
        is AppAction.SendMessageLoading -> state.copy(
            isProcessing = true,
            errorMessage = null
        )
        is AppAction.SendMessageSuccess -> {
            val aiMessage = ChatMessage(
                author = Author.AI,
                title = "AI",
                contentBlocks = action.response.contentBlocks,
                usageMetadata = action.response.usageMetadata,
                rawContent = action.response.rawContent,
                timestamp = action.timestamp // Use timestamp from action
            )
            state.copy(
                isProcessing = false,
                chatHistory = state.chatHistory + aiMessage
            )
        }
        is AppAction.SendMessageFailure -> {
            val errorChatMessage = ChatMessage(
                author = Author.SYSTEM,
                title = "Gateway Error",
                contentBlocks = listOf(TextBlock(action.error)),
                timestamp = action.timestamp // Use timestamp from action
            )
            state.copy(
                isProcessing = false,
                chatHistory = state.chatHistory + errorChatMessage
            )
        }
        is AppAction.CancelMessage -> state.copy(
            isProcessing = false,
            errorMessage = "Request cancelled by user."
        )


        // --- UI & View ---
        is AppAction.SetViewMode -> state.copy(
            currentViewMode = action.mode,
            // Reset export selection when moving back to chat
            holonIdsForExport = if (action.mode == ViewMode.CHAT) emptySet() else state.holonIdsForExport
        )
        is AppAction.InspectHolon -> state.copy(
            inspectedHolonId = action.holonId
        )
        is AppAction.HolonInspectionSuccess -> state.copy(
            activeHolons = state.activeHolons + (action.holon.header.id to action.holon)
        )
        is AppAction.HolonInspectionFailure -> {
            // Future enhancement: show a specific error for the failed holon.
            println("Reducer handled HolonInspectionFailure: ${action.error}")
            state
        }
        is AppAction.ToggleHolonActive -> {
            if (action.holonId == state.aiPersonaId) return state // Cannot deactivate persona
            val newContextIds = if (state.contextualHolonIds.contains(action.holonId)) {
                state.contextualHolonIds - action.holonId
            } else {
                state.contextualHolonIds + action.holonId
            }
            state.copy(contextualHolonIds = newContextIds)
        }
        is AppAction.SetCatalogueFilter -> state.copy(
            catalogueFilter = action.type
        )

        // --- Persona & Model ---
        is AppAction.SelectAiPersona -> {
            if (action.holonId == null) {
                state.copy(
                    aiPersonaId = null,
                    holonGraph = emptyList(),
                    activeHolons = emptyMap(),
                    inspectedHolonId = null,
                    contextualHolonIds = emptySet(),
                    gatewayStatus = GatewayStatus.ERROR,
                    errorMessage = "Please select an Active Agent to begin."
                )
            } else {
                state.copy(aiPersonaId = action.holonId)
            }
        }
        is AppAction.SelectModel -> state.copy(
            selectedModel = action.modelName
        )
    }
}