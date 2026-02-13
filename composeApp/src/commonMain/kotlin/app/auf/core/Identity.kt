package app.auf.core

import kotlinx.serialization.Serializable

/**
 * A universal, serializable data class representing a unique participant on the action bus.
 * Every addressable entity — features, users, agents, sessions, future scripts — is an Identity.
 *
 * Identities form a tree via [parentHandle], which is always the originator that registered
 * this identity. The tree builds itself naturally from the call chain:
 *
 *   "session" dispatches REGISTER_IDENTITY { localHandle = "chat1" }
 *     → handle = "session.chat1", parentHandle = "session"
 *
 *   "agent" dispatches REGISTER_IDENTITY { localHandle = "gemini-coder" }
 *     → handle = "agent.gemini-coder", parentHandle = "agent"
 *
 *   "agent.gemini-coder" dispatches REGISTER_IDENTITY { localHandle = "sub-task" }
 *     → handle = "agent.gemini-coder.sub-task", parentHandle = "agent.gemini-coder"
 *
 * This means:
 * - No feature can register identities outside its own namespace (enforced by design)
 * - The parent portion of the handle is immutable — only the registrant controls it
 * - The display name of any sender is always `identityRegistry[handle]?.name`
 * - The parent tree enables future permission inheritance without new concepts
 *
 * **Handle rules**:
 * - [localHandle]: only `[a-z][a-z0-9-]*` — must start with a letter, no dots
 * - [handle]: the full bus address, constructed as `parentHandle.localHandle` (or just
 *   `localHandle` for root identities). The dot is a hierarchy separator, never part
 *   of a localHandle.
 */
@Serializable
data class Identity(
    /**
     * Globally unique, system-assigned identifier.
     * Null for features — their handles are stable across restarts.
     * Generated for ephemeral entities (users, agents, sessions).
     */
    val uuid: String?,

    /**
     * The leaf-level handle for this identity, unique among siblings.
     * Only [a-z][a-z0-9-]* allowed (must start with a letter, no dots).
     * Examples: "session", "chat1", "gemini-coder-1", "alice"
     */
    val localHandle: String,

    /**
     * The full bus address, constructed as "parentHandle.localHandle" for child identities,
     * or just "localHandle" for root identities (features).
     * This is the registry key and what appears as action.originator.
     * Examples: "session", "session.chat1", "agent.gemini-coder-1", "core.alice"
     */
    val handle: String,

    /**
     * Display name shown in the UI. Full Unicode allowed.
     * Examples: "Session Manager", "Gemini Coder nr.1", "Alice"
     */
    val name: String,

    /**
     * Handle of the parent identity — always the originator that registered this identity.
     * Null for root identities (features, system).
     * Immutable after registration: no feature can change its parent.
     */
    val parentHandle: String? = null,

    /**
     * Epoch millis when this identity was registered.
     */
    val registeredAt: Long = 0

    // FUTURE: Permissions grants — paved, not implemented in v2.0.
    // val permissions: Map<String, Boolean>? = null
)