package app.auf.core

import kotlinx.serialization.Serializable

/**
 * The grant level for a single permission key.
 *
 * CRITICAL: Declaration order defines privilege ordering: NO < ASK < APP_LIFETIME < YES.
 * This ordering is load-bearing — [Store.resolveEffectivePermissions] uses enum ordinal
 * comparison for inheritance and escalation detection. Do not reorder.
 *
 * NOTE: ASK and APP_LIFETIME exist in the enum for forward-compatibility but are
 * treated as NO by the Store guard until the ASK approval system is implemented.
 * See: permissions-ask-system-task.md
 */
@Serializable
enum class PermissionLevel {
    /** Access denied. The action is silently blocked at the Store level. */
    NO,

    /** Reserved for ASK system. Currently treated as NO with WARN log. */
    ASK,

    /** Reserved for ASK system. Currently treated as NO with WARN log. */
    APP_LIFETIME,

    /** Access permanently granted. Persisted with the identity. */
    YES
}

/**
 * A single permission grant on an identity.
 *
 * @param level The access level (NO, ASK, APP_LIFETIME, YES).
 * @param resourceScope Optional path/prefix constraint for resource-scoped permissions.
 *   Used by `match_resource_scope` scope rules in the Store permission guard (Phase 3).
 *   When set, the guard verifies that the target payload field starts with this prefix.
 */
@Serializable
data class PermissionGrant(
    val level: PermissionLevel,
    val resourceScope: String? = null
)

/**
 * Severity indicator for permission declarations. Drives UI color-coding
 * in the Permission Manager view.
 *
 * Declared in `*.actions.json` manifests alongside each permission key.
 * Validated at build time by the code generator — invalid values fail the build.
 */
@Serializable
enum class DangerLevel {
    /** Safe operation within sandboxed boundaries. UI: green/default. */
    LOW,
    /** Operation with broader impact. UI: orange/warning. */
    CAUTION,
    /** Operation that can cause system-wide damage. UI: red/danger. */
    DANGER
}