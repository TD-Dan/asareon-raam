package app.auf.model

import app.auf.core.FeatureState
import kotlinx.serialization.Serializable

@Serializable
data class UserSettings(
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val featureStates: Map<String, FeatureState> = emptyMap()
)