package app.auf.model

import app.auf.feature.systemclock.SystemClockState
import kotlinx.serialization.Serializable

@Serializable
data class CompilerSettings(
    val removeWhitespace: Boolean = true,
    val cleanHeaders: Boolean = true,
    val minifyJson: Boolean = false
)

@Serializable
data class UserSettings(
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val selectedModel: String = "gemini-1.5-flash-latest",
    val selectedAiPersonaId: String? = null,
    val activeContextualHolonIds: Set<String> = emptySet(),
    val compilerSettings: CompilerSettings = CompilerSettings(),
    val systemClockState: SystemClockState = SystemClockState()
)