package app.auf.core

import app.auf.core.generated.ActionRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// --- VERSION ---
object Version {
    const val APP_NAME = "Asareon Raam"
    const val APP_VERSION = "1.0.0-alpha"
    const val APP_VERSION_MAJOR = "v1"
    const val APP_VERSION_MAJOR_MINOR = "v1.0"
    const val APP_TOOL_PREFIX = "raam_"
}

// --- CORE STATE ---

/**
 * The root state container for the entire application.
 * Holds feature state slices plus application infrastructure registries.
 *
 * Both registries are application infrastructure, not feature state:
 * - [actionDescriptors]: "what can be done" — validated against by the Store for every dispatch
 * - [identityRegistry]: "who is doing it" — used by the Store for originator resolution
 *
 * They live here (not in any FeatureState) because the Store needs them before any
 * feature reducer runs — a chicken-and-egg problem if they were in feature state.
 */
data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap(),

    /**
     * The universal action catalog. Pre-populated from the generated ActionRegistry
     * at construction time. Extended at runtime by Phase 5's REGISTER_ACTION.
     * The Store validates every dispatched action against this map.
     */
    val actionDescriptors: Map<String, ActionRegistry.ActionDescriptor> = ActionRegistry.byActionName,

    /**
     * The universal identity registry. Every participant on the action bus has an
     * entry here: features, users, agents, sessions, scripts.
     * Keyed by Identity.handle (the bus address).
     *
     * Seeded with feature identities during initFeatureLifecycles().
     * Mutated at runtime via Store.updateIdentityRegistry(), called by CoreFeature
     * from handleSideEffects in response to core.REGISTER_IDENTITY / core.UNREGISTER_IDENTITY.
     *
     * AppState is the single source of truth for identity data. CoreFeature retains
     * the business logic (validation, deduplication, namespace enforcement); the Store
     * owns the state. This parallels actionDescriptors.
     */
    val identityRegistry: Map<String, Identity> = emptyMap()
)

/**
 * A marker interface for all feature-specific state objects.
 */
interface FeatureState

/**
 * The single, universal action class for the entire application.
 * All communication, whether intent (Command) or result (Event), flows through this class.
 * It is based on a string name for routing and a serializable JSON object for the payload.
 * The originator can contain a hierarchical handle (e.g., "agent.gemini-flash-abc123")
 * for authorization resolution via extractFeatureHandle().
 * This design enforces the 'Absolute Decoupling' and 'Manifest-Driven Contracts' principles.
 */
@Serializable
data class Action(
    val name: String,
    val payload: JsonObject? = null,
    val originator: String? = null,
    /**
     * If non-null, the Store delivers this action only to the feature identified by this handle.
     * The Store resolves at the feature level: "session.chat1" delivers to the "session" feature.
     * ONLY valid when the action's schema descriptor has `targeted = true`.
     * The Store rejects targetRecipient on non-targeted actions, and targeted actions without it.
     */
    val targetRecipient: String? = null
) {

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
                targetRecipient?.let { " → '$it'" }.orEmpty() +
                payloadString?.let { " with $it" }.orEmpty() +
                ">"
    }
}
