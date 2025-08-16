package app.auf.model

import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val selectedModel: String = "gemini-1.5-flash-latest",
    val selectedAiPersonaId: String? = null,
    val activeContextualHolonIds: Set<String> = emptySet()
)