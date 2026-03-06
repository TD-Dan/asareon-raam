package app.auf.core

/**
 * Compile-time default permission grants applied to feature identities at boot.
 *
 * These grants are applied to feature identities (e.g., "core", "agent") during
 * [Store.initFeatureLifecycles]. Child identities (e.g., "core.alice", "agent.mercury")
 * inherit permissions from their parent feature via [Store.resolveEffectivePermissions].
 *
 * This design means:
 * - Feature identities define the permission baseline for their namespace
 * - Children inherit naturally — no per-child grant application needed
 * - The Permission Manager shows features as editable rows, making the
 *   inheritance hierarchy visible and configurable
 * - Escalation detection works correctly (child > parent = escalation)
 *
 * To change defaults, modify this file and rebuild. This is intentional —
 * default permissions are a security-critical compile-time decision, not a
 * runtime configuration knob.
 *
 * Persisted permission edits (from the Permission Manager UI) take precedence
 * over these compile-time defaults. See [CoreFeature.restoreFeaturePermissions].
 */
object DefaultPermissions {

    /**
     * A single default grant rule.
     *
     * @param identityPattern Pattern matched against identity handles.
     *   Supports exact matches (e.g., "core") and glob patterns with `*`
     *   as a single-segment wildcard (e.g., "agent.*" matches "agent.sub"
     *   but not "agent.sub.deep").
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
     * Exact matches are supported naturally (e.g., "core" matches "core").
     */
    fun matchesPattern(handle: String, pattern: String): Boolean {
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
        // ── Core feature — baseline for human users (core.alice etc.) ──
        DefaultGrant("core", "filesystem:workspace",          PermissionLevel.YES),
        DefaultGrant("core", "filesystem:system-files-read",  PermissionLevel.YES),
        DefaultGrant("core", "session:read",                  PermissionLevel.YES),
        DefaultGrant("core", "session:write",                 PermissionLevel.YES),
        DefaultGrant("core", "session:manage",                PermissionLevel.YES),
        DefaultGrant("core", "gateway:generate",              PermissionLevel.YES),
        DefaultGrant("core", "gateway:preview",               PermissionLevel.YES),
        DefaultGrant("core", "core:read",                     PermissionLevel.YES),
        DefaultGrant("core", "core:identity",                 PermissionLevel.YES),
        DefaultGrant("core", "knowledgegraph:read",           PermissionLevel.YES),
        DefaultGrant("core", "knowledgegraph:write",          PermissionLevel.YES),
        DefaultGrant("core", "agent:manage",                  PermissionLevel.YES),
        DefaultGrant("core", "agent:execute",                 PermissionLevel.YES),

        // ── Agent feature — baseline for agents (agent.mercury etc.) ──
        DefaultGrant("agent", "filesystem:workspace",         PermissionLevel.YES),
        DefaultGrant("agent", "session:read",                 PermissionLevel.YES),
        DefaultGrant("agent", "session:write",                PermissionLevel.YES),
        DefaultGrant("agent", "knowledgegraph:read",          PermissionLevel.YES),
    )
}