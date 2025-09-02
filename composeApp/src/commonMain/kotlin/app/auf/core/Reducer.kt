package app.auf.core

import app.auf.model.SettingValue

/**
 * The root Reducer function for the Unidirectional Data Flow (UDF) architecture.
 * This reducer only handles core, app-wide state. Feature-specific
 * state changes are handled by their own dedicated reducers.
 *
 * Is part of CORE: Does not know anything of specific features!
 */
fun appReducer(state: AppState, action: AppAction): AppState {
    return when (action) {
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
            isProcessing = true
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
            isProcessing = false
        )
        is AppAction.DeleteMessage -> state.copy(
            chatHistory = state.chatHistory.filterNot { it.id == action.id }
        )
        is AppAction.RerunFromMessage -> {
            val messageIndex = state.chatHistory.indexOfFirst { it.id == action.id }
            if (messageIndex != -1) {
                val truncatedHistory = state.chatHistory.subList(0, messageIndex + 1)
                state.copy(chatHistory = truncatedHistory)
            } else {
                state
            }
        }

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
        is AppAction.ToggleMessageCollapsed -> {
            val updatedHistory = state.chatHistory.map { message ->
                if (message.id == action.id) {
                    message.copy(isCollapsed = !message.isCollapsed)
                } else {
                    message
                }
            }
            state.copy(chatHistory = updatedHistory)
        }

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
                        if (block is CodeBlock) {
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

        // --- Session Persistence ---
        is AppAction.LoadSessionSuccess -> state.copy(
            chatHistory = action.history
        )

        else -> state
    }
}