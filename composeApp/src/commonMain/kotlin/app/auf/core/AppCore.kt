package app.auf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// --- VERSION ---
object Version {
    const val APP_VERSION = "2.0.0-alpha"
    const val APP_VERSION_MAJOR = "v2"
}

// --- CORE STATE ---

/**
 * The root state container for the entire application.
 * Its sole responsibility is to hold the state slices for each registered feature.
 */
data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap()
)

/**
 * A marker interface for all feature-specific state objects.
 */
interface FeatureState

/**
 * The single, universal action class for the entire application.
 * All communication, whether intent (Command) or result (Event), flows through this class.
 * It is based on a string name for routing and a serializable JSON object for the payload.
 * The originator can contain an uuid or feature name for debugging or other purposes.
 * This design enforces the 'Absolute Decoupling' and 'Manifest-Driven Contracts' principles.
 */
@Serializable
data class Action(val name: String, val payload: JsonObject? = null, val originator: String? = null) {

    override fun toString(): String {
        val payloadString = payload?.let {
            val raw = it.toString()
            if (raw.length > 130) {
                "'${raw.take(130)}...'"
            } else {
                "'$raw'"
            }
        }
        return "<'${name}'" +
                originator?.let { " from '$it'" }.orEmpty() +
                payloadString?.let { " with $it" }.orEmpty() +
                ">"
    }
}