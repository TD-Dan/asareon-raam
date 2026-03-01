package app.auf.core

/**
 * Compile-time default permission grants applied to newly registered identities.
 *
 * When [core.REGISTER_IDENTITY] processes a new identity, CoreFeature matches
 * the identity's handle against [defaultGrants] patterns and applies matching
 * grants. These defaults only apply to identities that lack explicit grants
 * (i.e., not already persisted in identities.json).
 *
 * To change defaults, modify this file and rebuild. This is intentional —
 * default permissions are a security-critical compile-time decision, not a
 * runtime configuration knob.
 */
object DefaultPermissions {

    /**
     * A single default grant rule.
     *
     * @param identityPattern Glob pattern matched against identity handles.
     *   Supports `*` as a single-segment wildcard (e.g., `core.*` matches
     *   `core.alice` but not `core.alice.sub`).
     * @param permissionKey The permission key to grant.
     * @param level The default grant level.
     */
    data class DefaultGrant(
        val identityPattern: String,
        val permissionKey: String,
        val level: PermissionLevel
    )

    /**
     * Matches an identity handle against a glob pattern.
     * `*` matches exactly one segment (no dots).
     * Example: "core.*" matches "core.alice" but not "core.alice.sub"
     */
    fun matchesPattern(handle: String, pattern: String): Boolean {
        // Split both into segments
        val handleParts = handle.split('.')
        val patternParts = pattern.split('.')

        if (handleParts.size != patternParts.size) return false

        return handleParts.zip(patternParts).all { (h, p) ->
            p == "*" || h == p
        }
    }

    /**
     * Returns the default permission grants for a given identity handle.
     * Only includes grants from matching patterns.
     */
    fun grantsFor(handle: String): Map<String, PermissionGrant> {
        val result = mutableMapOf<String, PermissionGrant>()
        for (grant in defaultGrants) {
            if (matchesPattern(handle, grant.identityPattern)) {
                result[grant.permissionKey] = PermissionGrant(level = grant.level)
            }
        }
        return result
    }

    val defaultGrants: List<DefaultGrant> = listOf(
        // ── Human users (core.*) ──────────────────────────────────────
        DefaultGrant("core.*", "filesystem:workspace",          PermissionLevel.YES),
        DefaultGrant("core.*", "filesystem:system-files-read",  PermissionLevel.YES),
        DefaultGrant("core.*", "session:read",                  PermissionLevel.YES),
        DefaultGrant("core.*", "session:write",                 PermissionLevel.YES),
        DefaultGrant("core.*", "session:manage",                PermissionLevel.YES),
        DefaultGrant("core.*", "gateway:generate",              PermissionLevel.YES),
        DefaultGrant("core.*", "gateway:preview",               PermissionLevel.YES),
        DefaultGrant("core.*", "core:read",                     PermissionLevel.YES),
        DefaultGrant("core.*", "core:identity",                 PermissionLevel.YES),
        DefaultGrant("core.*", "knowledgegraph:read",           PermissionLevel.YES),
        DefaultGrant("core.*", "knowledgegraph:write",          PermissionLevel.YES),
        DefaultGrant("core.*", "agent:manage",                  PermissionLevel.YES),
        DefaultGrant("core.*", "agent:execute",                 PermissionLevel.YES),

        // ── Agent identities (agent.*) ────────────────────────────────
        DefaultGrant("agent.*", "filesystem:workspace",         PermissionLevel.YES),
        DefaultGrant("agent.*", "session:read",                 PermissionLevel.YES),
        DefaultGrant("agent.*", "session:write",                PermissionLevel.YES),
        DefaultGrant("agent.*", "knowledgegraph:read",          PermissionLevel.YES),
    )
}