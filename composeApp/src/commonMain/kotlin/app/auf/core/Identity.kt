package app.auf.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

// ============================================================================
// Typed ID Wrappers
//
// Zero-cost at runtime (value class erases to String). Compile-time type safety
// prevents passing a session handle where a resource UUID is expected.
//
// Serialization: kotlinx.serialization encodes value classes transparently as
// their underlying type — a plain JSON string, not a nested object. Existing
// persisted files are forward-compatible with no migration.
//
// NOTE: @JvmInline is required by the compiler for single-field value classes.
// It IS available in commonMain — kotlin.jvm.JvmInline is part of the Kotlin
// stdlib and compiles on all KMP targets (JVM, Native, JS/WASM).
// ============================================================================

/**
 * The full bus address of any registered identity.
 * Examples: "session", "session.chat1", "agent.gemini-coder-1", "core.alice"
 *
 * Used as the key in [AppState.identityRegistry] and as the stable reference
 * from any entity that needs to point to an identity without embedding a
 * full [Identity] struct.
 */
@JvmInline
@Serializable
value class IdentityHandle(val handle: String) {
    override fun toString(): String = handle
}

/**
 * A globally unique, system-assigned identifier for ephemeral entities
 * (users, agents, sessions). Features have null UUIDs — their handles are
 * stable across restarts.
 *
 * Used as the map key for agents (`AgentRuntimeState.agents`) and for
 * filesystem paths (`{uuid}/agent.json`), correlation IDs, and any
 * context where a stable, non-reassignable identifier is needed.
 */
@JvmInline
@Serializable
value class IdentityUUID(val uuid: String) {
    override fun toString(): String = uuid
}

// ============================================================================
// Identity String Validators
//
// Runtime guards for catching type mismatches (e.g. a handle string wrapped
// in an IdentityUUID). Place these at trust boundaries — reducer entry points,
// payload parsing, and cross-feature dispatch sites.
// ============================================================================

private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** True if [s] matches UUID v4 format: 8-4-4-4-12 lowercase hex with dashes. */
fun stringIsUUID(s: String): Boolean = s.length == 36 && UUID_REGEX.matches(s)

/** True if [s] looks like a dotted hierarchical handle (e.g. "session.chat1"). */
fun stringIsHandle(s: String): Boolean = '.' in s && !stringIsUUID(s)

/**
 * Wraps [s] as [IdentityUUID] after validating UUID format.
 * Throws [IllegalArgumentException] on mismatch — use at trust boundaries
 * where a non-UUID value indicates a wiring bug.
 */
fun requireUUID(s: String, context: String = ""): IdentityUUID {
    require(stringIsUUID(s)) {
        "Expected UUID but got '${s.take(60)}'${if (context.isNotEmpty()) " ($context)" else ""}. " +
                "This usually means a handle was passed where a UUID was expected."
    }
    return IdentityUUID(s)
}

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
    val registeredAt: Long = 0,

    /**
     * Permission grants for this identity, keyed by permission key.
     * Example: {"filesystem:workspace": {"level": "YES"}, "gateway:generate": {"level": "YES"}}
     *
     * Empty map means no explicit grants — inherits from parent, or deny-by-default
     * if no ancestor has grants.
     */
    val permissions: Map<String, PermissionGrant> = emptyMap()
) {
    /** Convenience accessor for typed handle. */
    val identityHandle: IdentityHandle get() = IdentityHandle(handle)

    /** Convenience accessor for typed UUID. Null for features (no system-assigned UUID). */
    val identityUUID: IdentityUUID? get() = uuid?.let { IdentityUUID(it) }
}

// ============================================================================
// Identity Registry Extensions
//
// The identity registry is Map<String, Identity> keyed by handle.
// These extensions provide lookup by any form of identity reference
// and close-match suggestions for error messages.
// ============================================================================

/**
 * Find an identity by its [IdentityUUID].
 * Returns null if no identity with this UUID exists in the registry.
 */
fun Map<String, Identity>.findByUUID(uuid: IdentityUUID): Identity? =
    values.find { it.uuid == uuid.uuid }

/**
 * Find an identity by its UUID string.
 */
fun Map<String, Identity>.findByUUID(uuid: String): Identity? =
    values.find { it.uuid == uuid }

/**
 * Universal identity resolution. Accepts any form of identity reference
 * and returns the matching Identity, or null.
 *
 * Resolution order (first match wins):
 *   1. Exact handle match (map key lookup — O(1))
 *   2. UUID match
 *   3. localHandle match
 *   4. Case-insensitive display name match
 *
 * This order prioritizes unambiguous identifiers over potentially
 * ambiguous ones (multiple identities could share a display name).
 */
fun Map<String, Identity>.resolve(raw: String): Identity? {
    // 1. Direct handle (O(1) map lookup)
    this[raw]?.let { return it }
    // 2. UUID
    values.find { it.uuid == raw }?.let { return it }
    // 3. localHandle
    values.find { it.localHandle == raw }?.let { return it }
    // 4. Display name (case-insensitive)
    values.find { it.name.equals(raw, ignoreCase = true) }?.let { return it }
    return null
}

/**
 * Scoped variant: resolves only among identities that are children of
 * the given [parentHandle] (e.g. "agent", "session").
 */
fun Map<String, Identity>.resolve(raw: String, parentHandle: String): Identity? {
    val scoped = values.filter { it.parentHandle == parentHandle }
    scoped.find { it.handle == raw }?.let { return it }
    scoped.find { it.uuid == raw }?.let { return it }
    scoped.find { it.localHandle == raw }?.let { return it }
    scoped.find { it.name.equals(raw, ignoreCase = true) }?.let { return it }
    return null
}

/**
 * Returns up to [limit] identities whose name, localHandle, or handle
 * contain the query string (case-insensitive). Useful for "did you mean?"
 * suggestions in error messages.
 *
 * Optionally scoped to a [parentHandle].
 */
fun Map<String, Identity>.suggestMatches(
    query: String,
    parentHandle: String? = null,
    limit: Int = 3
): List<Identity> {
    val lower = query.lowercase()
    return values
        .filter { identity ->
            (parentHandle == null || identity.parentHandle == parentHandle) &&
                    (identity.name.lowercase().contains(lower) ||
                            identity.localHandle.lowercase().contains(lower) ||
                            identity.handle.lowercase().contains(lower))
        }
        .take(limit)
}