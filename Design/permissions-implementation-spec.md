# AUF Permissions System — Implementation Specification

**Version:** 3.0  
**Date:** 2026-03-01  
**Status:** Decisions locked. Ready for implementation.  
**Companion:** `permissions-ask-system-task.md` (deferred ASK/APP_LIFETIME approval protocol)

---

## 1. Design Pillars

**Pillar 1 — Core Agnosticism.** `app.auf.core` and `app.auf.feature.core` know nothing about feature internals. Core provides mechanisms (actions, identities, permissions, routing). It never interprets feature-specific semantics. Any permission enforcement that requires domain knowledge must be expressible declaratively in the manifest.

**Pillar 2 — Core as Gatekeeper.** The Store is the single security checkpoint. Features — including compiled ones — cannot be trusted to self-enforce security. Every dispatched action passes through the Store's permission guard. There is no bypass.

**Pillar 3 — Universal Infrastructure.** Common cross-cutting concerns are provided by Core so that feature development is simple and safe. Identity management, action validation, and permission checking are Core-provided services.

**Pillar 4 — Manifest-Driven Contracts.** `*.actions.json` files are the single source of truth for what actions exist, what they require, and what permissions they demand. If it's not in the manifest, it doesn't exist.

**Pillar 5 — Deny by Default.** An unmentioned permission is `NO`. An unrecognized action is rejected. The system fails closed, never open.

**Pillar 6 — Auditability.** Every permission check, grant, denial, and escalation is logged through the existing Store logging pipeline.

**Pillar 7 — Controlled Escalation.** A parent feature may have a default policy of denying a capability globally but grant it to a specific child. For example, the `agent` feature defaults to `filesystem:write = NO` but grants it to `agent.coder-1`. Without this, the entire `agent` feature would need `filesystem:write` just to enable one agent, defeating granularity. Escalations are: (a) logged at WARN level, (b) flagged in the Permission Manager UI with a warning indicator (orange background, ⚠ icon), and (c) tracked by `resolveEffectivePermissions()` for audit.

---

## 2. Terminology

| Term | Definition |
|---|---|
| **Permission Key** | A colon-separated string identifying an atomic capability. Format: `domain:capability`. Examples: `filesystem:workspace`, `session:write`. |
| **Permission Level** | The grant level for a permission: `NO`, `ASK`, `APP_LIFETIME`, `YES`. Ordered by privilege. `ASK` and `APP_LIFETIME` are reserved for the deferred ASK system — this spec treats both as `NO`. |
| **Permission Grant** | A `(level, resourceScope?)` pair associated with a permission key on an identity. |
| **Required Permissions** | The list of permission keys an action demands from its originator, declared in the action manifest. ALL listed permissions must be satisfied (AND logic). |
| **Danger Level** | A severity indicator on each permission declaration: `LOW`, `CAUTION`, or `DANGER`. Drives UI color-coding in the Permission Manager. Validated at build time. |
| **Effective Permissions** | The resolved set of grants for an identity after applying inheritance. |
| **Permission Scope** | A declarative rule in the manifest that constrains a permission to a subset of targets (Phase 4). |

### 2.1 Permission Key Naming Convention

The colon (`:`) separator is an industry standard (OAuth2 scopes, AWS IAM, Android permissions). It does not collide with dot-delimited handles (`session.chat1`) or action names (`filesystem.LIST`).

**Format:** `<domain>:<capability>`

**Rules:**
- `domain` matches a feature name or a cross-cutting concern (e.g., `filesystem`, `core`, `gateway`, `session`, `agent`).
- `capability` is a lowercase, hyphen-separated token describing an atomic capability (e.g., `workspace`, `system-files-read`, `write`, `generate`).
- Permission keys are case-sensitive, lowercase only.
- Maximum length: 64 characters.
- The code generator **must reject** keys that do not contain exactly one colon at build time.

**Separator convention (document as naming standard):**
- Dot (`.`) for hierarchy: handles (`core.alice`), actions (`filesystem.LIST`)
- Colon (`:`) for capability: permissions (`filesystem:workspace`)

### 2.2 Collapsed Capability Model

Permissions are **collapsed capabilities** that express what an identity can do, without a separate operation-type × resource-type decomposition. Each permission key is self-contained.

This replaces the two-dimensional compounding model from v2. The previous model could not express "read/write in workspace, but only read system-files" because `:write` was a single toggle applying to all resource types. The collapsed model solves this by making each permission key independently grantable.

Actions still use AND-logic: an action's `required_permissions` lists all capabilities the originator must hold. The originator must satisfy ALL of them.

**Examples:**

| Action | Required Permissions | Meaning |
|---|---|---|
| `filesystem.LIST` | `["filesystem:workspace"]` | Can list files in own sandbox/workspace |
| `filesystem.WRITE` | `["filesystem:workspace"]` | Can write files in workspace |
| `filesystem.DELETE_FILE` | `["filesystem:workspace"]` | Can delete files in workspace |
| `filesystem.REQUEST_SCOPED_READ_UI` | `["filesystem:system-files-read"]` | Can read system-wide files (with UI confirmation) |
| `session.POST` | `["session:write"]` | Can post messages |
| `session.CREATE` | `["session:manage"]` | Can create sessions |
| `session.DELETE` | `["session:manage"]` | Can delete sessions |
| `gateway.GENERATE_CONTENT` | `["gateway:generate"]` | Can request AI generation |
| `core.REMOVE_USER_IDENTITY` | `["core:identity"]` | Can modify identity records |
| `agent.DELETE` | `["agent:manage"]` | Can delete agents |
| `knowledgegraph.EXECUTE_IMPORT` | `["knowledgegraph:write"]` | Can modify holon graphs |
| `filesystem.NAVIGATE` | (empty) | UI-only — no permission required |
| `filesystem.TOGGLE_ITEM_EXPANDED` | (empty) | UI-only — no permission required |
| `session.SET_ACTIVE` | (empty) | UI-only — no permission required |

**Key principle:** Permissions protect *data operations and cross-feature effects*, not UI state. Actions that only mutate transient feature-internal state (`TOGGLE_ITEM_EXPANDED`, `NAVIGATE`, `SET_EDITING_SESSION`, `EXPAND_ALL`, `COLLAPSE_ALL`, etc.) have empty `required_permissions`.

### 2.3 Permission Key Catalog

| Permission Key | Description | Danger Level |
|---|---|---|
| `filesystem:workspace` | Read, write, and delete files in the sandboxed workspace | LOW |
| `filesystem:system-files-read` | Read any file on the operating system | CAUTION |
| `filesystem:system-files-modify` | Write and delete any file on the operating system | DANGER |
| `session:read` | Read session transcripts and message content | LOW |
| `session:write` | Post and modify messages in sessions | LOW |
| `session:manage` | Create, delete, clone, and configure sessions | CAUTION |
| `gateway:generate` | Request AI content generation from providers | CAUTION |
| `gateway:preview` | Request turn preview / token estimation | LOW |
| `core:read` | Read core state and identity information | LOW |
| `core:identity` | Modify identity records | CAUTION |
| `core:administrator` | Full administrative access to core operations | DANGER |
| `agent:manage` | Create, delete, and configure agents | CAUTION |
| `agent:execute` | Initiate and control agent turns | CAUTION |
| `knowledgegraph:read` | Read HKG context and persona data | LOW |
| `knowledgegraph:write` | Modify holons, import data, manage personas | CAUTION |

This catalog is illustrative. The definitive set is the union of all `permissions` entries across all `*.actions.json` manifests. Core discovers these automatically.

### 2.4 Permission Declarations in Manifests

Each `*.actions.json` file declares its permission keys with descriptions and danger levels:

```json
{
   "feature_name": "filesystem",
   "permissions": [
      { "key": "filesystem:workspace",           "description": "Read, write, and delete files in the sandboxed workspace", "dangerLevel": "LOW" },
      { "key": "filesystem:system-files-read",   "description": "Read any file on the operating system",                   "dangerLevel": "CAUTION" },
      { "key": "filesystem:system-files-modify", "description": "Write and delete any file on the operating system",        "dangerLevel": "DANGER" }
   ],
   "actions": [ ... ]
}
```

The code generator:
1. Validates that `dangerLevel` is one of `LOW`, `CAUTION`, `DANGER` — **build fails on invalid value**.
2. Validates that every key in every action's `required_permissions` is declared in some feature's `permissions` array — **build fails on undeclared key**.
3. Validates that every permission key contains exactly one colon — **build fails on malformed key**.
4. Collects all entries into `ActionRegistry.permissionDeclarations`.

---

## 3. Data Model

### 3.1 Permission Level Enum

```kotlin
// File: app.auf.core.Permission.kt (new file)

package app.auf.core

import kotlinx.serialization.Serializable

/**
 * The grant level for a single permission key.
 *
 * CRITICAL: Declaration order defines privilege ordering: NO < ASK < APP_LIFETIME < YES.
 * This ordering is load-bearing — [resolveEffectivePermissions] uses enum ordinal
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
```

### 3.2 Permission Grant

```kotlin
/**
 * A single permission grant on an identity.
 *
 * @param level The access level (NO, ASK, APP_LIFETIME, YES).
 * @param resourceScope Optional path/prefix constraint for resource-scoped permissions.
 *   Phase 4 feature — ignored until then.
 */
@Serializable
data class PermissionGrant(
   val level: PermissionLevel,
   val resourceScope: String? = null
)
```

### 3.3 Danger Level Enum

```kotlin
/**
 * Severity indicator for permission declarations. Drives UI color-coding
 * in the Permission Manager view.
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
```

### 3.4 Identity Extension

The existing paved field in `Identity.kt`:
```kotlin
// val permissions: Map<String, Boolean>? = null  // OLD (paved but unused)
```

Becomes:
```kotlin
/**
 * Permission grants for this identity, keyed by permission key.
 * Example: {"filesystem:workspace": {"level": "YES"}, "gateway:generate": {"level": "YES"}}
 *
 * Empty map means no explicit grants — inherits from parent, or deny-by-default
 * if no ancestor has grants.
 */
val permissions: Map<String, PermissionGrant> = emptyMap()
```

### 3.5 Manifest Extensions to `*.actions.json`

**Action-level `required_permissions`:**

```json
{
   "action_name": "filesystem.WRITE",
   "required_permissions": ["filesystem:workspace"],
   ...
},
{
"action_name": "session.POST",
"required_permissions": ["session:write"],
...
},
{
"action_name": "filesystem.NAVIGATE",
"required_permissions": [],
...
}
```

Actions with no `required_permissions` field (or an empty array) are unrestricted during migration. **Post-migration, the code generator MUST reject actions with a missing `required_permissions` field at build time** (§12.1). Actions that genuinely require no permissions declare `"required_permissions": []` explicitly.

### 3.6 ActionDescriptor Extension

In the generated `ActionRegistry`, `ActionDescriptor` gains:

```kotlin
data class ActionDescriptor(
   // ... existing fields ...
   val requiredPermissions: List<String> = emptyList()
)
```

New data class and registry-level additions:

```kotlin
object ActionRegistry {
   data class PermissionDeclaration(
      val key: String,
      val description: String,
      val dangerLevel: DangerLevel
   )

   /** All declared permission keys with their descriptions and danger levels. */
   val permissionDeclarations: Map<String, PermissionDeclaration> = mapOf(
      "filesystem:workspace" to PermissionDeclaration(
         "filesystem:workspace",
         "Read, write, and delete files in the sandboxed workspace",
         DangerLevel.LOW
      ),
      // ... generated from *.actions.json "permissions" arrays
   )
}
```

### 3.7 Permission Scopes in Manifests (Phase 4)

Deferred to Phase 4. The manifest schema supports this now for forward-compatibility, but the Store ignores it until Phase 4 implementation. See v2 §3.6 for the full scope type definitions.

---

## 4. Implementation Phases

### Pre-Phase 1 — Foundation Fixes

These changes are prerequisites that must land before the permission guard is implemented. They fix existing bugs and inconsistencies that would undermine the guard.

**Deliverables:**

1. **Fix `Store.handleFeatureException` originator.** Currently dispatches as `"Store.ExceptionHandler"` (not registered). Change to `"core"` so the toast dispatch passes strict originator validation.

2. **Fix agent dispatch originator in `AgentRuntimeFeature.kt`.** Line 598 dispatches domain actions as `identity.handle` (`"agent"`, the feature handle). Must dispatch as the agent's identity handle (`agent.identityHandle.handle`, e.g., `"agent.coder-1"`) so the permission guard evaluates the correct identity. Same fix needed for any feature that dispatches on behalf of a sub-identity.

3. **Fix `FileSystemFeature.getSandboxPathFor` to resolve at feature level.** When the agent originator changes from `"agent"` to `"agent.coder-1"`, the sandbox root would change from `APP_ZONE/agent` to `APP_ZONE/agent_coder-1`, breaking existing data. Fix:
   ```kotlin
   private fun getSandboxPathFor(originator: String): String {
       // Resolve to feature-level prefix to preserve storage layout.
       // "agent.coder-1" → "agent", "session.chat1" → "session", "settings" → "settings"
       val featureHandle = originator.substringBefore('.')
       val safeOriginator = featureHandle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
       return "$appZoneRoot${platformDependencies.pathSeparator}$safeOriginator"
   }
   ```
   Verify: all non-agent features (settings, session, knowledgegraph) already dispatch filesystem ops as their feature handle, so this change is backward-compatible.

4. **Replace all ad-hoc UI originator strings with feature handles.** 14 call sites across UI composables use unregistered strings (`"core.ui"`, `"session.ui"`, `"settings.ui"`, `"ui.agent"`, `"ui.ribbon"`, `"filesystem.ui"`). Replace all with the owning feature's handle:

   | Current | Replacement | Files |
      |---|---|---|
   | `"core.ui"` | `"core"` | IdentityManagerView.kt, CoreFeature.kt composables |
   | `"session.ui"` | `"session"` | SessionFeature.kt composables |
   | `"settings.ui"` | `"settings"` | SettingsFeature.kt composables |
   | `"ui.agent"` | `"agent"` | AgentRuntimeFeature.kt composables |
   | `"ui.ribbon"` | `"agent"` | AgentRuntimeFeature.kt ribbon |
   | `"filesystem.ui"` | `"filesystem"` | FileSystemFeature.kt composables |

### Phase 1 — YES/NO Permission Enforcement

**Goal:** Binary permission enforcement in the Store. `YES` allows, everything else denies. `ASK` and `APP_LIFETIME` are recognized in the enum and data model but treated as `NO` with a warning log.

**Deliverables:**

1. **`Permission.kt`** — New file in `app.auf.core` with `PermissionLevel`, `PermissionGrant`, `DangerLevel`.
2. **`Identity.kt` update** — Replace paved `permissions` field with `Map<String, PermissionGrant>`.
3. **Code generator update** — Parse `required_permissions` and `permissions` from `*.actions.json`. Generate `ActionRegistry.permissionDeclarations`. Validate `dangerLevel` enum values, colon format in keys, and cross-reference declared keys. Build fails on violations.
4. **Manifest annotations** — Add `permissions` declarations (with `key`, `description`, `dangerLevel`) and `required_permissions` to all actions across all manifests. UI-only actions get `"required_permissions": []`.
5. **Store guard** — Insert permission check in `processAction()` as Step 2b. See §5.
6. **Strict originator validation** — Step 1c in `processAction()`. Reject originators not in the identity registry and not resolvable to a feature handle. See §9.1.
7. **Inheritance resolution** — `resolveEffectivePermissions()` with controlled escalation (Pillar 7). See §5.3.
8. **Default permissions via registration hook** — When `core.REGISTER_IDENTITY` processes a new identity, CoreFeature reads the compiled-in defaults from `DefaultPermissions.defaultGrants`, matches the new identity against patterns, and applies grants. See §8.2.
9. **`core.SET_PERMISSION` secured** — `public: false`. Only CoreFeature can dispatch. The Permission Manager UI path operates under Core's trusted handle.
10. **Test suite** — `StoreT1PermissionGuardTest.kt` with the 9 test scenarios in §10.1. Test infrastructure extended with permission-aware helpers.

### Phase 2 — Permission Manager UI + CommandBot Integration

#### Phase 2.A — Permission Manager View

1. **`PermissionManagerView.kt`** — Matrix UI in `app.auf.feature.core`. Column headers show `:capability` within each feature group, with full key and description in tooltips. Danger level drives column header color.
2. **Tab integration** — "Permissions" tab in the Identity Manager view.
3. **Escalation indicators** — Grants exceeding parent level shown with ⚠ icon and orange background.
4. **Actions** — `core.SET_PERMISSION` (non-public), `core.SET_PERMISSIONS_BATCH` (non-public), `core.PERMISSIONS_UPDATED` (broadcast event).
5. **Persistence** — Permissions persist as part of the `Identity` data class via existing serialization.

#### Phase 2.B — Permissions-Based CommandBot Exposure

**Goal:** Replace `ActionRegistry.agentAllowedNames` and `ActionRegistry.agentRequiresApproval` with permission checks.

**Migration safety:** The old allowlist (`agentAllowedNames` check in `CommandBotFeature.processCommandBlock()`) is kept as a second layer during Phase 2.B. The Store permission guard runs first (authoritative). The CommandBot allowlist runs second (belt-and-suspenders). The allowlist is removed only after integration tests confirm the guard works correctly for all agent action flows.

**New CommandBot flow:**
```
CommandBot.processCommandBlock()
  → resolve originator identity from senderId
  → resolve effective permissions for originator
  → check required_permissions for target action
    → all YES → publishActionCreated()
    → any NO or missing → post error feedback to session
  → (CAG-007 Auto-Fill and sandbox rules still apply)
```

**`agent_exposure` field deprecation:** Remove `agentAllowedNames` and `agentRequiresApproval` derived views from ActionRegistry. **Preserve** `agentSandboxRules` and `agentAutoFillRules` — these are orthogonal to permissions and remain needed.

**Code generator cleanup:** Stop emitting `agentExposure` field on ActionDescriptor after allowlist removal. Sandbox rules and auto-fill rules get their own top-level fields.

### Phase 3 — Permission Scopes

**Goal:** Declarative, payload-based scoping enforced by the Store.

1. **`PermissionScopeRule`** data class.
2. **Code generator update** — Parse `permission_scopes` from manifests.
3. **`evaluateScope()` in Store** — Mechanical scope checker (see §5.4).
4. **Manifest annotations** — Add `permission_scopes` to actions that need them.
5. **UI extension** — Show resource scope values in the permission matrix.

### Future — ASK Approval System

The interactive approval protocol (ASK level, APP_LIFETIME transient grants, presenter routing) is specified in a separate task document: `permissions-ask-system-task.md`. It builds on the foundation delivered by Phases 1–3 of this spec.

---

## 5. Store Guard — Detailed Implementation

### 5.1 Insertion Point

The permission guard inserts into `Store.processAction()` as new steps:

```
Step 1:  Schema lookup (existing)
Step 1b: Targeted validation (existing)
Step 1c: ORIGINATOR VALIDATION ← new (Pre-Phase 1)
Step 2:  Authorization — public flag (existing)
Step 2b: PERMISSION GUARD ← new (Phase 1)
Step 3:  Lifecycle guard (existing)
Step 4:  Route, reduce, side-effects (existing)
```

### 5.2 Guard Implementation (Phase 1: YES/NO only)

```kotlin
// Inside processAction(), after Step 2 authorization passes:

// --- STEP 2b: PERMISSION GUARD ---
val requiredPerms = descriptor.requiredPermissions
if (requiredPerms.isNotEmpty()) {
    val originatorIdentity = action.originator?.let {
        _state.value.identityRegistry[it]
    }

    // Feature identities (uuid == null) are trusted — skip permission check.
    if (originatorIdentity != null && originatorIdentity.uuid != null) {
        val effective = resolveEffectivePermissions(originatorIdentity)

        for (permKey in requiredPerms) {
            val grant = effective[permKey]
            val level = grant?.level ?: PermissionLevel.NO  // deny by default (Pillar 5)

            when (level) {
                PermissionLevel.NO -> {
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION DENIED: '${action.originator}' lacks '$permKey' " +
                        "for action '${action.name}'. Action blocked."
                    )
                    return
                }
                PermissionLevel.ASK -> {
                    // ASK system not yet implemented. Deny with warning.
                    // See: permissions-ask-system-task.md
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION ASK not yet implemented (treating as NO): " +
                        "'${action.originator}' has ASK for '$permKey' " +
                        "on action '${action.name}'. Action blocked."
                    )
                    return
                }
                PermissionLevel.APP_LIFETIME -> {
                    // APP_LIFETIME system not yet implemented. Deny with warning.
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION APP_LIFETIME not yet implemented (treating as NO): " +
                        "'${action.originator}' has APP_LIFETIME for '$permKey' " +
                        "on action '${action.name}'. Action blocked."
                    )
                    return
                }
                PermissionLevel.YES -> {
                    // Permitted — continue to next required permission.
                }
            }
        }
        // Phase 3: Evaluate permission scopes here.
    }
    // Unknown originator with required permissions: check if resolvable to feature.
    else if (originatorIdentity == null && action.originator != null) {
        val featureHandle = extractFeatureHandle(action.originator)
        val isFeature = features.any { it.identity.handle == featureHandle }
        if (!isFeature) {
            platformDependencies.log(
                LogLevel.ERROR, "Store",
                "PERMISSION DENIED: originator '${action.originator}' not found in " +
                "identity registry and action '${action.name}' requires permissions. Blocked."
            )
            return
        }
        // Resolvable to a trusted feature — allowed (feature trust exemption).
    }
}
```

### 5.3 Permission Inheritance Resolution

```kotlin
/**
 * Resolves the effective permissions for an identity by walking the
 * parent chain. Applies controlled escalation policy (Pillar 7):
 * child grants may override parent grants freely, but escalations
 * (child level > parent level) are logged at WARN for audit.
 *
 * Resolution: accumulate from root to leaf. Each layer overrides
 * the inherited value.
 */
private fun resolveEffectivePermissions(
    identity: Identity
): Map<String, PermissionGrant> {
    val registry = _state.value.identityRegistry

    // Collect the chain: [self, parent, grandparent, ...]
    val chain = mutableListOf(identity)
    var current = identity
    while (current.parentHandle != null) {
        val parent = registry[current.parentHandle] ?: break
        chain.add(parent)
        current = parent
    }

    // Merge root-first: each child layer overrides
    val effective = mutableMapOf<String, PermissionGrant>()

    for (ancestor in chain.reversed()) {
        for ((key, grant) in ancestor.permissions) {
            val parentGrant = effective[key]
            if (parentGrant == null) {
                // First in chain to declare this key
                effective[key] = grant
            } else {
                // Controlled escalation: allow but log
                if (grant.level > parentGrant.level) {
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION ESCALATION: '${ancestor.handle}' has " +
                        "'${grant.level}' for '$key' but parent effective is " +
                        "'${parentGrant.level}'. Allowed under controlled escalation."
                    )
                }
                effective[key] = grant
            }
        }
    }

    return effective
}
```

### 5.4 Scope Evaluation (Phase 3)

```kotlin
/**
 * Evaluates a declarative permission scope rule against an action.
 * Returns true if the scope constraint is satisfied.
 */
private fun evaluateScope(
    rule: PermissionScopeRule,
    action: Action,
    originator: Identity,
    grant: PermissionGrant
): Boolean {
    val payloadValue = resolvePayloadField(action.payload, rule.payloadField)
        ?: return false  // Missing field = scope violation (fail closed)

    return when (rule.scopeType) {
        "match_originator" -> {
            val originatorValue = when (rule.originatorProperty) {
                "uuid" -> originator.uuid
                "handle" -> originator.handle
                "localHandle" -> originator.localHandle
                else -> null
            }
            payloadValue == originatorValue
        }
        "match_namespace" -> {
            payloadValue == originator.handle ||
            payloadValue.startsWith("${originator.handle}.")
        }
        "match_resource_scope" -> {
            val prefix = grant.resourceScope
            prefix != null && payloadValue.startsWith(prefix)
        }
        else -> false  // Unknown scope type = fail closed
    }
}
```

---

## 6. New Actions

Add to `core_actions.json`:

```json
{
  "action_name": "core.SET_PERMISSION",
  "summary": "Sets a single permission grant on an identity. Only dispatchable by CoreFeature (non-public). The Permission Manager UI operates through Core's trusted handle.",
  "public": false,
  "broadcast": false,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "identityHandle": { "type": "string", "description": "The handle of the identity to modify." },
      "permissionKey": { "type": "string", "description": "The permission key (e.g., 'filesystem:workspace')." },
      "level": { "type": "string", "enum": ["NO", "YES"], "description": "The permission level. ASK and APP_LIFETIME are reserved for the ASK system." }
    },
    "required": ["identityHandle", "permissionKey", "level"]
  }
},
{
  "action_name": "core.SET_PERMISSIONS_BATCH",
  "summary": "Bulk-sets multiple permission grants. Used by the matrix UI and initial config loading. Non-public — only dispatchable by CoreFeature.",
  "public": false,
  "broadcast": false,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "grants": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "identityHandle": { "type": "string" },
            "permissionKey": { "type": "string" },
            "level": { "type": "string", "enum": ["NO", "YES"] }
          },
          "required": ["identityHandle", "permissionKey", "level"]
        }
      }
    },
    "required": ["grants"]
  }
},
{
  "action_name": "core.PERMISSIONS_UPDATED",
  "summary": "Broadcast after any permission change. Features that cache permission state should refresh.",
  "public": false,
  "broadcast": true,
  "targeted": false
},
{
  "action_name": "core.PERMISSION_DENIED",
  "summary": "Targeted notification sent to the originator's feature when an action is blocked by the permission guard.",
  "public": false,
  "broadcast": false,
  "targeted": true,
  "payload_schema": {
    "type": "object",
    "properties": {
      "blockedAction": { "type": "string" },
      "originatorHandle": { "type": "string" },
      "missingPermissions": { "type": "array", "items": { "type": "string" } },
      "sessionId": { "type": ["string", "null"], "description": "Session context for feedback, if available." }
    },
    "required": ["blockedAction", "originatorHandle", "missingPermissions"]
  }
}
```

---

## 7. Permission Inheritance Model

### 7.1 Tree Structure

```
core (feature — trusted, exempt from checks)
├── core.alice (user — has own grants)
└── core.bob   (user — has own grants)

agent (feature — trusted, exempt from checks)
├── agent.coder-1  (agent — has grants, may escalate beyond feature defaults)
│   └── agent.coder-1.sub-task (inherits from coder-1)
└── agent.reviewer  (agent — own grants)

session (feature — trusted, exempt from checks)
├── session.chat1
└── session.chat2
```

### 7.2 Inheritance Rules

1. **Features are exempt.** Any identity with `uuid == null` skips the permission check entirely.
2. **Explicit grants override inherited.** If an identity has an explicit grant for a permission key, that takes precedence over the parent's grant for the same key.
3. **Controlled escalation (Pillar 7).** Child grants that exceed the parent's effective level are allowed but logged at WARN and flagged in the Permission Manager UI.
4. **Missing grants inherit.** If an identity has no explicit grant for a key, it inherits the parent's effective grant.
5. **No grant anywhere = NO.** If neither the identity nor any ancestor has a grant for the key, the effective level is `NO` (Pillar 5).

### 7.3 Registration Propagation

When `core.REGISTER_IDENTITY` processes a new identity, CoreFeature:

1. Reads the compiled-in defaults from `DefaultPermissions.defaultGrants` (no file I/O needed).
2. Matches the new identity's handle against `identityPattern` glob patterns.
3. Applies matching grants to the new identity via the same code path as `core.SET_PERMISSIONS_BATCH`.

If the registration payload includes `requestedPermissions`, those are merged on top of the defaults.

---

## 8. Persistence

### 8.1 Identity Persistence

Permissions are persisted as part of the `Identity` data class. Since `identities.json` already stores full `Identity` objects, adding the `permissions` field is a non-breaking schema extension via `ignoreUnknownKeys`.

### 8.2 Default Permissions — Compile-Time Constants

A `DefaultPermissions.kt` object in `app.auf.core` embeds the default grants directly in the binary. This eliminates file I/O, avoids resource-loading race conditions, and makes defaults available immediately at process start.

```kotlin
// File: app.auf.core.DefaultPermissions.kt

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

        // ── Agent identities (agent.*) ────────────────────────────────
        DefaultGrant("agent.*", "filesystem:workspace",         PermissionLevel.YES),
        DefaultGrant("agent.*", "session:read",                 PermissionLevel.YES),
        DefaultGrant("agent.*", "session:write",                PermissionLevel.YES),
        DefaultGrant("agent.*", "gateway:generate",             PermissionLevel.YES),
        DefaultGrant("agent.*", "gateway:preview",              PermissionLevel.YES),
        DefaultGrant("agent.*", "knowledgegraph:read",          PermissionLevel.YES),
    )
}
```

**Loading mechanism:** CoreFeature accesses `DefaultPermissions.defaultGrants` directly — no file reading or parsing required. The grants list is cached in `CoreState` at startup (during `system.STARTING`) for pattern matching. When `core.REGISTER_IDENTITY` processes a new identity, CoreFeature matches it against the cached patterns and applies matching grants. This eliminates the race condition where agents may not be registered when startup runs.

For identities that already exist at startup (loaded from `identities.json`), their persisted `permissions` field is authoritative. Defaults only apply to newly registered identities that lack explicit grants.

---

## 9. Edge Cases and Guard Rails

### 9.1 Strict Originator Validation

```kotlin
// In processAction(), before Step 2:
// --- STEP 1c: ORIGINATOR VALIDATION ---
if (action.originator != null) {
    val originatorInRegistry = _state.value.identityRegistry.containsKey(action.originator)
    val originatorIsFeature = features.any { it.identity.handle == action.originator }
    if (!originatorInRegistry && !originatorIsFeature) {
        val featureHandle = extractFeatureHandle(action.originator)
        val parentIsFeature = features.any { it.identity.handle == featureHandle }
        if (!parentIsFeature) {
            platformDependencies.log(
                LogLevel.ERROR, "Store",
                "INVALID ORIGINATOR: '${action.originator}' is not a registered identity " +
                "or feature. Action '${action.name}' rejected."
            )
            return
        }
    }
}
```

### 9.2 `extractFeatureHandle` and Hierarchical Resolution

`extractFeatureHandle("agent.coder-1")` → `"agent"`. This is correct for:
- **Public flag check (Step 2):** Any identity in the `agent` namespace can dispatch internal agent actions.
- **Sandbox path resolution:** `getSandboxPathFor` uses the feature prefix, preserving `APP_ZONE/agent/`.
- **Feature trust exemption:** Only exact feature handles (`"agent"`, not `"agent.coder-1"`) skip permissions.

For identities with deeper hierarchy (`"agent.coder-1.sub-task"`), `extractFeatureHandle` returns `"agent"` (first segment only). This is intentional — document it explicitly.

### 9.3 In-Flight Actions During Permission Changes

Actions already past the guard are unaffected. Actions in the deferred queue are checked when processed and may be denied. No special handling needed.

### 9.4 Cross-Feature Dispatch Attribution

Features dispatching with their own handle as originator are trusted and exempt. Features dispatching on behalf of a user/agent identity use `store.deferredDispatch(userHandle, action)` — the permission guard checks the user's permissions.

**Critical invariant:** All agent-dispatchable domain actions (`filesystem.LIST`, `filesystem.WRITE`, `session.POST`, `gateway.GENERATE_CONTENT`, etc.) must be declared `public: true` in the manifest, because the agent's handle (`"agent.coder-1"`) resolves to feature `"agent"`, which differs from the target action's feature. The `public` flag permits cross-feature dispatch; the permission guard then checks capability.

### 9.5 Empty vs Missing `requiredPermissions`

- **Missing field in JSON:** `requiredPermissions` defaults to empty list. No permission check. Backward-compatible during migration.
- **Empty array `[]`:** Equivalent to missing. No permission check. Explicit "this action is unrestricted."
- **Non-empty array:** Permission check required. All listed permissions must be `YES`.

---

## 10. Testing Strategy

### 10.1 Unit Tests — `StoreT1PermissionGuardTest.kt`

Using the existing `RecordingStore` and `TestEnvironment` patterns:

1. **Deny by default:** Identity with no grants dispatches action with required permissions → blocked.
2. **YES allows:** Identity has `YES` for all required permissions → allowed.
3. **Partial grants deny:** Identity has `session:write` but not `session:manage` for `session.CREATE` → blocked.
4. **Feature exemption:** Dispatch from feature handle (uuid == null) → allowed regardless of permissions.
5. **Inheritance:** Child with no explicit grants, parent has `YES` → allowed.
6. **Controlled escalation:** Child has `YES`, parent has `NO` → allowed (WARN logged).
7. **Unknown originator:** Originator not in registry and not resolvable to feature → blocked.
8. **Invalid UI originator:** `"core.ui"` dispatching → ERROR and blocked.
9. **No permissions required:** Action with empty `required_permissions` → allowed for any originator.
10. **ASK treated as NO:** Identity has `ASK` for a required permission → blocked with WARN log.

### 10.2 Test Infrastructure Extensions

```kotlin
// In TestEnvironment.kt:

/** Creates descriptors with specific required permissions. */
fun permissionTestDescriptor(
    actionName: String,
    requiredPermissions: List<String>,
    public: Boolean = true,
    broadcast: Boolean = true
): Pair<String, ActionRegistry.ActionDescriptor>

/** Creates an identity with specific permission grants in the registry. */
fun TestEnvironment.withIdentityAndPermissions(
    handle: String,
    parentHandle: String,
    permissions: Map<String, PermissionGrant>
): TestEnvironment
```

### 10.3 Integration Tests

1. **Agent lifecycle:** Create agent → default permissions applied via registration hook → allowed actions work → disallowed actions blocked.
2. **CommandBot integration:** Agent posts `auf_session.POST` → Store guard checks permissions → allowed/denied.
3. **Permission change at runtime:** Modify via `core.SET_PERMISSION` → subsequent dispatches respect new level.
4. **Matrix UI round-trip:** Change permission in UI → persisted → survives restart.

---

## 11. File Inventory

### New Files
| File | Package | Description |
|---|---|---|
| `Permission.kt` | `app.auf.core` | `PermissionLevel` enum, `PermissionGrant`, `DangerLevel` |
| `PermissionManagerView.kt` | `app.auf.feature.core` | Matrix UI (Phase 2.A) |
| `DefaultPermissions.kt` | `app.auf.core` | Compile-time default permission grants |
| `StoreT1PermissionGuardTest.kt` | test | Guard unit tests |

### Modified Files
| File | Change |
|---|---|
| `Identity.kt` | Replace paved `permissions` field with `Map<String, PermissionGrant>` |
| `Store.kt` | Add Step 1c originator validation, Step 2b permission guard, `resolveEffectivePermissions()`, `evaluateScope()` (Phase 3) |
| `CoreFeature.kt` | Handle `SET_PERMISSION`, `SET_PERMISSIONS_BATCH`, default-permissions loading from `DefaultPermissions.kt` via registration hook, permission persistence |
| `AgentRuntimeFeature.kt` | **Fix dispatch originator** to use agent identity handle (Pre-Phase 1) |
| `FileSystemFeature.kt` | **Fix `getSandboxPathFor`** to resolve at feature level (Pre-Phase 1) |
| `CommandBotFeature.kt` | Replace allowlist with permission checks (Phase 2.B) |
| `core_actions.json` | Add permission actions (non-public); add `permissions` declarations |
| `*.actions.json` (all) | Add `permissions` declarations with `dangerLevel`; add `required_permissions` |
| `build.gradle.kts` | Parse new `permissions` format; validate `dangerLevel` enum, colon format, key cross-references; generate `permissionDeclarations` |
| `IdentityManagerView.kt` | Add "Permissions" tab (Phase 2.A) |
| All UI Composables | Replace ad-hoc originator strings with feature handles (Pre-Phase 1) |
| `TestEnvironment.kt` | Add permission-aware test helpers |
| `ActionRegistry.kt` (generated) | Add `PermissionDeclaration`, `permissionDeclarations` map; eventually remove `agentAllowedNames`/`agentRequiresApproval` |

---

## 12. Migration & Open Items

### 12.1 Compile-Time Enforcement of `required_permissions`

**During migration:** Empty `required_permissions` means "no permissions required" — backward-compatible.

**Post-migration (end of Phase 2):** The code generator **must fail the build** if any action lacks a `required_permissions` field. Actions that genuinely require no permissions declare `"required_permissions": []` explicitly.

### 12.2 Agent Permission Escalation Requests

Pave `core.REQUEST_PERMISSION_ESCALATION` action name but don't implement until a use case emerges. Analogous to Android's runtime permission request flow.

### 12.3 User Notification on Denial

Phase 1 blocks denied actions with logging. Phase 2 introduces `core.PERMISSION_DENIED` targeted to the originator's feature, enabling features to show meaningful feedback.

### 12.4 `agentExposure` Removal Checklist

When removing the old allowlist system (Phase 2.B, after Store guard validation):
1. Remove `agentAllowedNames` and `agentRequiresApproval` derived views from ActionRegistry.
2. Remove `agentExposure` property from `ActionDescriptor`.
3. **Keep** `agentSandboxRules` and `agentAutoFillRules` as standalone derived views.
4. Update code generator to stop parsing `agent_exposure.requires_approval`.
5. **Keep** `agent_exposure.sandbox_rule` and `agent_exposure.auto_fill_rules` parsing (or migrate to top-level fields).
6. Update `CommandBotFeature.processCommandBlock()` to remove allowlist check.