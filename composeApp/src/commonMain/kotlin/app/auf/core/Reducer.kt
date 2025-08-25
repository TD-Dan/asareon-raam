package app.auf.core

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
 * - `app.auf.core.AppState`: The state object it operates on.
 * - `app.auf.core.AppAction`: The actions it responds to.
 *
 * @version 2.8
 * @since 2025-08-25
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
            val personaHolon = result.holonGraph.find { it.header.id == result.determinedPersonaId }
            val initialActiveHolons = if (personaHolon != null) mapOf(personaHolon.header.id to personaHolon) else emptyMap()

            var newChatHistory = state.chatHistory

            if (result.parsingErrors.isNotEmpty()) {
                val errorContent = "The following ${result.parsingErrors.size} files could not be parsed and were excluded from the graph:\n\n" +
                        result.parsingErrors.joinToString("\n") { "- $it" }

                val errorMessage = ChatMessage.createSystem(
                    title = "Graph Parsing Warning",
                    rawContent = errorContent
                )
                newChatHistory = newChatHistory + errorMessage
            }

            state.copy(
                holonGraph = result.holonGraph,
                gatewayStatus = GatewayStatus.OK,
                availableAiPersonas = result.availableAiPersonas,
                aiPersonaId = result.determinedPersonaId,
                activeHolons = initialActiveHolons,
                chatHistory = newChatHistory,
                errorMessage = null
            )
        }
        is AppAction.LoadGraphFailure -> state.copy(
            gatewayStatus = GatewayStatus.ERROR,
            errorMessage = action.error
        )

        // --- Chat & Gateway ---
        is AppAction.AddUserMessage -> {
            val userMessage = ChatMessage.createUser(rawContent = action.rawContent)
            state.copy(
                chatHistory = state.chatHistory + userMessage
            )
        }
        is AppAction.AddSystemMessage -> {
            val systemMessage = ChatMessage.createSystem(
                title = action.title,
                rawContent = action.rawContent
            )
            state.copy(
                chatHistory = state.chatHistory + systemMessage
            )
        }
        is AppAction.SendMessageLoading -> state.copy(
            isProcessing = true,
            errorMessage = null
        )
        is AppAction.SendMessageSuccess -> {
            val aiMessage = ChatMessage.createAi(
                rawContent = action.response.rawContent ?: "Error: AI response was null.",
                usageMetadata = action.response.usageMetadata
            )
            state.copy(
                isProcessing = false,
                chatHistory = state.chatHistory + aiMessage
            )
        }
        is AppAction.SendMessageFailure -> {
            val errorChatMessage = ChatMessage.createSystem(
                title = "Gateway Error",
                rawContent = action.error
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
        is AppAction.DeleteMessage -> state.copy(
            chatHistory = state.chatHistory.filterNot { it.id == action.id }
        )
        is AppAction.RerunFromMessage -> {
            val messageIndex = state.chatHistory.indexOfFirst { it.id == action.id }
            if (messageIndex != -1) {
                // Truncate the history to include only messages up to the point of the rerun.
                val truncatedHistory = state.chatHistory.subList(0, messageIndex + 1)
                state.copy(chatHistory = truncatedHistory)
            } else {
                state // If message not found, do nothing.
            }
        }

        // --- UI & View ---
        is AppAction.SetViewMode -> {
            if (action.mode == ViewMode.EXPORT) {
                state.copy(
                    currentViewMode = action.mode,
                    holonIdsForExport = state.activeHolons.keys
                )
            } else {
                state.copy(
                    currentViewMode = action.mode,
                    holonIdsForExport = emptySet() // Clear list when leaving other modes
                )
            }
        }
        is AppAction.InspectHolon -> state.copy(
            inspectedHolonId = action.holonId
        )
        is AppAction.ToggleHolonActive -> {
            if (action.holonId == state.aiPersonaId) return state // Cannot deactivate persona

            val newContextIds: Set<String>
            val newActiveHolons: Map<String, Holon>

            if (state.contextualHolonIds.contains(action.holonId)) {
                // Remove from context
                newContextIds = state.contextualHolonIds - action.holonId
                newActiveHolons = state.activeHolons - action.holonId
            } else {
                // Add to context
                val holonToAdd = state.holonGraph.find { it.header.id == action.holonId }
                if (holonToAdd != null) {
                    newContextIds = state.contextualHolonIds + action.holonId
                    newActiveHolons = state.activeHolons + (action.holonId to holonToAdd)
                } else {
                    return state
                }
            }
            state.copy(contextualHolonIds = newContextIds, activeHolons = newActiveHolons)
        }
        is AppAction.SetCatalogueFilter -> state.copy(
            catalogueFilter = action.type
        )
        is AppAction.ShowToast -> state.copy(
            toastMessage = action.message
        )
        is AppAction.ClearToast -> state.copy(
            toastMessage = null
        )
        is AppAction.ToggleSystemVisibility -> state.copy(
            isSystemVisible = !state.isSystemVisible
        )
        is AppAction.ToggleHolonExport -> {
            if (action.holonId == state.aiPersonaId) return state // Cannot deselect persona
            val newExportIds = if (state.holonIdsForExport.contains(action.holonId)) {
                state.holonIdsForExport - action.holonId
            } else {
                state.holonIdsForExport + action.holonId
            }
            state.copy(holonIdsForExport = newExportIds)
        }


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

        // --- Action Manifest Execution ---
        is AppAction.ExecuteActionManifest -> state.copy(
            isProcessing = true
        )
        is AppAction.ExecuteActionManifestSuccess -> state.copy(
            isProcessing = false,
            toastMessage = action.summary
        )
        is AppAction.ExecuteActionManifestFailure -> state.copy(
            isProcessing = false,
            toastMessage = "ERROR: ${action.error}"
        )
        is AppAction.UpdateActionStatus -> {
            val updatedHistory = state.chatHistory.map { message ->
                if (message.timestamp == action.messageTimestamp) {
                    val updatedBlocks = message.contentBlocks.map { block ->
                        if (block is ActionBlock) {
                            block.copy(status = action.status)
                        } else {
                            block
                        }
                    }
                    message.copy(contentBlocks = updatedBlocks)
                } else {
                    message
                }
            }
            state.copy(chatHistory = updatedHistory)
        }
        // --- Settings ---
        is AppAction.UpdateSetting -> { // <<< ADDED
            val newCompilerSettings = when (action.setting.key) {
                "compiler.removeWhitespace" -> state.compilerSettings.copy(removeWhitespace = action.setting.value)
                "compiler.cleanHeaders" -> state.compilerSettings.copy(cleanHeaders = action.setting.value)
                "compiler.minifyJson" -> state.compilerSettings.copy(minifyJson = action.setting.value)
                else -> state.compilerSettings
            }
            state.copy(compilerSettings = newCompilerSettings)
        }
    }
}