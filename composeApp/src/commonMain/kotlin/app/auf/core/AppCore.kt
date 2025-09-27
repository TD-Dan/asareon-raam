package app.auf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// --- VERSION ---
object Version {
    const val APP_VERSION = "2.0.0-alpha"
}

// --- CORE STATE ---

interface FeatureState

data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap()
)

/**
 * The single, universal action class for the entire application.
 * All communication, whether intent (Command) or result (Event), flows through this class.
 * It is based on a string name for routing and a serializable JSON string for the payload.
 *
 * This design enforces the 'Absolute Decoupling' and 'Manifest-Driven Contracts' principles.
 */
@Serializable
data class Action(val name: String, val payload: JsonObject? = null)
