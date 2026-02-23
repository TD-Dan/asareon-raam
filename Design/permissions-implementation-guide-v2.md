# AUF Permissions System — Implementation Guide

**Version:** 2.0-draft  
**Date:** 2026-02-23  
**Status:** Pending thorough implementation analysis and devil's advocate / red team analysis.

---

## 1. Design Pillars

The permissions system is constrained by the following non-negotiable architectural pillars:

**Pillar 1 — Core Agnosticism.** `app.auf.core` and `app.auf.feature.core` know nothing about feature internals. Core provides the mechanisms (actions, identities, permissions, routing). It never interprets feature-specific semantics. Any permission enforcement that requires domain knowledge must be expressible declaratively in the manifest.

**Pillar 2 — Core as Gatekeeper.** The Store is the single security checkpoint. Features — including compiled ones — cannot be trusted to self-enforce security. Every dispatched action passes through the Store's permission guard. There is no bypass.

**Pillar 3 — Universal Infrastructure.** Common cross-cutting concerns are provided by Core so that feature development is simple and safe. Identity management, action validation, permission checking, and approval flows are all Core-provided services.

**Pillar 4 — Manifest-Driven Contracts.** `*.actions.json` files are the single source of truth for what actions exist, what they require, and what permissions they demand. If it's not in the manifest, it doesn't exist.

**Pillar 5 — Deny by Default.** An unmentioned permission is `NO`. An unrecognized action is rejected. The system fails closed, never open.

**Pillar 6 — Auditability.** Every permission check, grant, denial, and escalation is logged through the existing Store logging pipeline. A future in-app log ticker can surface these to users who want full visibility.

**Pillar 7 — Escalation Policy (Under Evaluation).**

Two competing positions:

*Position A — No Escalation:* No entity can grant permissions it doesn't itself hold. Children inherit at most what their parent has. This provides absolute security peace of mind and a simple mental model.

*Position B — Controlled Escalation:* A parent feature (e.g., `agent`) may have a default policy of denying `filesystem:write` globally, but grant it to a specific child (e.g., `agent.coder-1`). Without this, the entire `agent` feature would need `filesystem:write` just to enable one agent, which is wasteful and weakens the default posture.

*Current decision:* **Defer to implementation analysis.** Both positions have merit. If escalation is allowed, it must be: (a) strongly emphasized in the UI (visual warning, different color), (b) logged at WARN level, and (c) the `resolveEffectivePermissions()` function must flag rather than silently cap. If no escalation is chosen, encountering a child grant exceeding its parent's level is an ERROR and should be corrected at write-time (i.e., `core.SET_PERMISSION` rejects escalating grants).

---

## 2. Terminology

| Term | Definition |
|---|---|
| **Permission Key** | A colon-separated string identifying an atomic capability. Format: `domain:capability`. Examples: `filesystem:read`, `session:message`. |
| **Permission Level** | The grant level for a permission: `NO`, `ASK`, `APP_LIFETIME`, `YES`. Ordered by privilege — this ordering is load-bearing for inheritance resolution (see §3.1). |
| **Permission Grant** | A `(level, resourceScope?)` pair associated with a permission key on an identity. |
| **Required Permissions** | The list of permission keys an action demands from its originator, declared in the action manifest. ALL listed permissions must be satisfied (AND logic). |
| **Permission Description** | A human-readable description of a permission key, declared in the manifest alongside the key. Displayed as hover tooltips in the UI. |
| **Compounding Permissions** | The principle that an action's security posture is defined by the combination of multiple atomic permissions. E.g., `session.POST` requires both `session:write` (operation type) AND `session:message` (resource type). Neither alone is sufficient. |
| **Permission Scope** | A declarative rule in the manifest that constrains a permission to a subset of targets (Phase 4). |
| **Effective Permissions** | The resolved set of grants for an identity after applying inheritance. |

### 2.1 Permission Key Naming Convention

The colon (`:`) separator is an industry standard (OAuth2 scopes, AWS IAM, Android permissions). It is unambiguous in the AUF codebase — it does not collide with dot-delimited handles (`session.chat1`) or action names (`filesystem.LIST`).

**Format:** `<domain>:<capability>`

**Rules:**
- `domain` matches a feature name or a cross-cutting concern (e.g., `filesystem`, `core`, `gateway`, `session`, `agent`).
- `capability` is a lowercase, hyphen-separated token describing an atomic capability (e.g., `read`, `write`, `delete`, `message`, `workspace`, `manage`, `generate`).
- Permission keys are case-sensitive, lowercase only.
- Maximum length: 64 characters.
- Including the feature domain in the permission key enables future cross-cutting permissions like `core:administrator` for severely privileged operations.

### 2.2 Action and Handle Disambiguation

**Current convention:**
- Actions: `filesystem.LIST` (dot + UPPERCASE) 
- Handles: `core.alice` (dot + lowercase)
- Permission keys: `filesystem:read` (colon + lowercase)

**Discussion:** Should actions and handles use distinct separators to eliminate ambiguity? Candidates:

| Entity | Current | Alt A | Alt B |
|---|---|---|---|
| Actions | `filesystem.LIST` | `filesystem@LIST` | `filesystem>LIST` |
| Handles | `core.alice` | `core.alice` | `core.alice` |
| Permissions | `filesystem:read` | `filesystem:read` | `filesystem:read` |

The UPPERCASE convention already distinguishes actions from handles in practice, and the codebase is deeply committed to dot-separated action names. Changing the separator would require modifying the code generator, all `*.actions.json` files, `ActionRegistry`, the `auf_` command block parser in CommandBot, and every feature that constructs or parses action names. The ROI is low given that the UPPERCASE convention works. **Recommendation: keep current convention.** Document the three separator convention as a naming standard: dot for hierarchy (handles, actions), colon for capability (permissions).

### 2.3 Compounding Permission Model

Permissions are **atomic capabilities** that **compound via AND-logic** on each action. An action's `required_permissions` array lists all the atomic capabilities the originator must hold. The originator must satisfy ALL of them.

This decomposition creates two orthogonal dimensions:

**Operation type** — what the entity wants to do:
- `:read` — retrieve/view data
- `:write` — create or modify data
- `:delete` — remove data
- `:execute` — trigger a process or computation
- `:manage` — lifecycle operations (create/delete entities)

**Resource type** — what the entity wants to do it to:
- `:message` — session messages
- `:workspace` — shared workspace files
- `:system-files` — system-wide file access beyond own sandbox
- `:identity` — identity records
- `:config` — configuration data

**Examples of compounding:**

| Action | Required Permissions | Meaning |
|---|---|---|
| `session.POST` | `["session:write", "session:message"]` | Write + message = can post messages |
| `filesystem.LIST` | `["filesystem:read"]` | Read = can list directory contents (in own sandbox by default) |
| `filesystem.WRITE` to workspace | `["filesystem:write", "filesystem:workspace"]` | Write + workspace = can write to shared workspace |
| `filesystem.READ` system-wide | `["filesystem:read", "filesystem:system-files"]` | Read + system-files = can read beyond own sandbox |
| `filesystem.DELETE_FILE` | `["filesystem:delete"]` | Delete = can remove files |
| `gateway.GENERATE_CONTENT` | `["gateway:generate"]` | Generate = can request AI generation |
| `core.REMOVE_USER_IDENTITY` | `["core:write", "core:identity"]` | Write + identity = can modify identity records |
| `agent.DELETE` | `["agent:manage"]` | Manage = can delete agents |

**Discussion:** This is propably not the final architecture. Is this system flexible and expressive enough for multiple cases eg. the case 'agent can read, write and delete in workspace but only read on system-files' is not possible with this? A smarter way would just to have 'workspace'-"read,write and delete files in a sandboxed private folder", 'system-files-read'-"read any OS file anywhere in the system **CAUTION ZONE**", 'system-files-modify'-"write and delete ANY file on the OS! **DANGER ZONE**"

### 2.4 Initial Permission Key Catalog

**Operation-type permissions:**

| Permission Key | Description (for UI tooltips) |
|---|---|
| `filesystem:read` | Read files and list directories |
| `filesystem:write` | Create and modify files |
| `filesystem:delete` | Remove files and directories |
| `session:read` | Read session transcripts and message content |
| `session:write` | Post and modify messages in sessions |
| `session:manage` | Create, delete, clone, and configure sessions |
| `gateway:generate` | Request AI content generation from providers |
| `gateway:preview` | Request turn preview / token estimation |
| `core:read` | Read core state and identity information |
| `core:write` | Modify core state and identity records |
| `agent:manage` | Create, delete, and configure agents |
| `agent:execute` | Initiate and control agent turns |
| `knowledgegraph:read` | Read HKG context and persona data |
| `knowledgegraph:write` | Modify holons, import data, manage personas |

**Resource-type permissions (for compounding):**

| Permission Key | Description (for UI tooltips) |
|---|---|
| `filesystem:workspace` | Access the shared workspace area (beyond own sandbox) |
| `filesystem:system-files` | Access system-wide files (beyond workspace) |
| `session:message` | Operate on session messages specifically |
| `core:identity` | Operate on identity records specifically |
| `core:administrator` | Full administrative access to core operations |

**This catalog is illustrative.** The definitive set is the union of all `required_permissions` and `permissions` entries across all `*.actions.json` manifests. Core discovers these automatically.

### 2.5 Permission Descriptions in Manifests

Each `*.actions.json` file declares its permission keys with descriptions. This enables the UI to show tooltips without Core needing hardcoded knowledge:

```json
{
  "feature_name": "filesystem",
  "permissions": [
    { "key": "filesystem:read",         "description": "Read files and list directories" },
    { "key": "filesystem:write",        "description": "Create and modify files" },
    { "key": "filesystem:delete",       "description": "Remove files and directories" },
    { "key": "filesystem:workspace",    "description": "Access the shared workspace area" },
    { "key": "filesystem:system-files", "description": "Access system-wide files" }
  ],
  "actions": [ ... ]
}
```

The code generator collects all `permissions` entries into a global `ActionRegistry.permissionDescriptions: Map<String, String>` lookup. The Permission Manager UI reads from `AppState.actionDescriptors` (or a parallel registry) to populate column headers and tooltips.

**Discussion:** Additional enum dangerLevel(LOW|CAUTION|DANGER) for each permission entry?

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
 * comparison via [minOf] for inheritance narrowing. Do not reorder.
 */
@Serializable
enum class PermissionLevel {
    /** Access denied. The action is silently blocked at the Store level. */
    NO,

    /** Access requires explicit user approval each time. The Store suspends
     *  the action and presents an approval card. */
    ASK,

    /** Access approved by the user for the duration of the current app run.
     *  Stored in transient state only — resets to ASK on restart. */
    APP_LIFETIME,

    /** Access permanently granted. Persisted with the identity. */
    YES;

    /** True if this level permits the action (possibly after asking). */
    fun isPermitted(): Boolean = this != NO

    /** True if this level requires user interaction before proceeding. */
    fun requiresApproval(): Boolean = this == ASK
}
```

### 3.2 Permission Grant

```kotlin
/**
 * A single permission grant on an identity.
 *
 * @param level The access level (NO, ASK, APP_LIFETIME, YES).
 * @param resourceScope Optional path/prefix constraint for resource-scoped permissions.
 *   Example: "/workspace/agent-alice/" for filesystem:write.
 *   Null means unrestricted within the permission's domain.
 *   Phase 4 feature — ignored in Phases 1–3.
 */
@Serializable
data class PermissionGrant(
    val level: PermissionLevel,
    val resourceScope: String? = null
)
```

### 3.3 Identity Extension

The existing paved field in `Identity.kt`:
```kotlin
// val permissions: Map<String, Boolean>? = null  // OLD (paved but unused)
```

Becomes:
```kotlin
/**
 * Permission grants for this identity, keyed by permission key.
 * Example: {"filesystem:read": {"level": "YES"}, "gateway:generate": {"level": "ASK"}}
 *
 * Empty map means no explicit grants — inherits from parent, or deny-by-default
 * if no ancestor has grants.
 */
val permissions: Map<String, PermissionGrant> = emptyMap()
```

### 3.4 Manifest Extensions to `*.actions.json`

**Feature-level permissions declaration:**

```json
{
  "feature_name": "filesystem",
  "permissions": [
    { "key": "filesystem:read",         "description": "Read files and list directories" },
    { "key": "filesystem:write",        "description": "Create and modify files" },
    { "key": "filesystem:delete",       "description": "Remove files and directories" },
    { "key": "filesystem:workspace",    "description": "Access the shared workspace area" },
    { "key": "filesystem:system-files", "description": "Access system-wide files" }
  ],
  "actions": [ ... ]
}
```

**Action-level required_permissions:**

```json
{
  "action_name": "filesystem.LIST",
  "required_permissions": ["filesystem:read"],
  ...
},
{
  "action_name": "filesystem.WRITE",
  "required_permissions": ["filesystem:write"],
  ...
},
{
  "action_name": "filesystem.DELETE_FILE",
  "required_permissions": ["filesystem:delete"],
  ...
},
{
  "action_name": "session.POST",
  "required_permissions": ["session:write", "session:message"],
  ...
}
```

Including the feature domain in the permission key (e.g., `filesystem:read` not just `read`) enables future cross-domain permissions. For example, `core:administrator` could be required by severely dangerous operations across any feature, and the Store enforces it identically.

Actions with no `required_permissions` field (or an empty array) are unrestricted during migration. **Post-migration (see §12.1), the code generator MUST reject actions with missing `required_permissions` at compile time.**

### 3.5 ActionDescriptor Extension

In the generated `ActionRegistry`, `ActionDescriptor` gains:

```kotlin
data class ActionDescriptor(
    val actionName: String,
    val featureName: String,
    val public: Boolean,
    val broadcast: Boolean,
    val targeted: Boolean,
    val requiredPermissions: List<String> = emptyList(),
    // Phase 4:
    val permissionScopes: Map<String, PermissionScopeRule>? = null
)
```

New registry-level additions:

```kotlin
object ActionRegistry {
    /** All declared permission keys with their descriptions, collected from all manifests. */
    val permissionDescriptions: Map<String, String> = mapOf(
        "filesystem:read" to "Read files and list directories",
        // ... generated from *.actions.json "permissions" arrays
    )
}
```

### 3.6 Permission Scopes in Manifests (Phase 4)

Deferred to Phase 4. The manifest schema supports this now for forward-compatibility, but the Store ignores it until Phase 4 implementation.

```json
{
  "action_name": "core.REMOVE_USER_IDENTITY",
  "required_permissions": ["core:write", "core:identity"],
  "permission_scopes": {
    "core:identity": {
      "scope_type": "match_originator",
      "payload_field": "id",
      "originator_property": "uuid"
    }
  }
}
```

**Scope types (Phase 4):**

| scope_type | Behavior | Use Case |
|---|---|---|
| `match_originator` | `payload[field] == originator[property]` | "Only self" operations |
| `match_namespace` | `payload[field].startsWith(originator.handle)` | "Only own children" |
| `match_resource_scope` | `payload[field].startsWith(grant.resourceScope)` | Filesystem sandboxing |

**Originator properties available for matching:** `uuid`, `handle`, `localHandle`.

**Nested payload fields** use dot-path notation: `"payload_field": "target.identity.id"`.

---

## 4. Implementation Phases

### Phase 1 — Foundation: NO / YES + Store Guard

**Goal:** Binary permission enforcement in the Store. Permissions set via initial config files and programmatic grants only.

**Deliverables:**

1. **`Permission.kt`** — New file in `app.auf.core` with `PermissionLevel`, `PermissionGrant`.
2. **`Identity.kt` update** — Replace paved `permissions` field with `Map<String, PermissionGrant>`.
3. **`ActionDescriptor` update** — Add `requiredPermissions: List<String>` to the generated descriptor.
4. **Code generator update** — Parse `required_permissions` from `*.actions.json` into `ActionDescriptor`. Parse `permissions` array into `ActionRegistry.permissionDescriptions`.
5. **Manifest annotations** — Add `permissions` declarations and `required_permissions` to all actions. Start with `filesystem.*` and `gateway.*` as they are the most security-sensitive.
6. **Store guard** — Insert permission check in `processAction()` between Step 2 (authorization) and Step 3 (lifecycle guard). See §5.
7. **Strict originator validation** — Tighten Store guards to reject unregistered originators. Ad-hoc UI originators (`"core.ui"`, `"ui.agentManager"`) are now ERROR. All user-initiated UI dispatches use the active user identity handle as originator. See §9.1.
8. **Inheritance resolution** — Implement `resolveEffectivePermissions()` that walks the identity tree via `parentHandle`. See §5.3.
9. **Initial permissions config** — Ship `default-permissions.json` alongside default agent configs. Core loads this during `system.STARTING` and applies grants.
10. **Registration grant propagation** — When `core.REGISTER_IDENTITY` processes a new identity, inherit parent permissions (narrowing applies per Pillar 7 policy).

**Phase 1 treats `ASK` and `APP_LIFETIME` as `NO`.** This avoids approval UI complexity. The enum values exist in code but the Store short-circuits: `ASK` → denied with log message "ASK not yet implemented, treating as NO."

### Phase 2 — Permission Manager UI + CommandBot Integration

**Goal:** A matrix-style permission editor and permissions-based action exposure through CommandBot.

#### Phase 2.A — Permission Manager View

**Deliverables:**

1. **`PermissionManagerView.kt`** — New Composable in `app.auf.feature.core`.
2. **Tab integration** — Add a "Permissions" tab to the Identity Manager view, following the `TabRow` pattern established in `AgentManagerView.kt`.
3. **Matrix UI** — One matrix **per feature's permissions** to keep it readable on smaller screens:

```
┌──────────────────────────────────────────────────────────────────┐
│ Identity Manager                                                  │
│ ┌─────────────┬──────────────┐                                   │
│ │ Identities  │ Permissions  │                                   │
│ └─────────────┴──────────────┘                                   │
│                                                                   │
│  ┌─ Filesystem ──────────────────────────────────────────────┐   │
│  │ Identity       │ :read  │ :write │ :delete │ :workspace  │   │
│  ├────────────────┼────────┼────────┼─────────┼─────────────┤   │
│  │ core.alice     │  [YES] │  [YES] │  [YES]  │   [YES]     │   │
│  │ core.bob       │  [YES] │  [ NO] │  [ NO]  │   [ NO]     │   │
│  │ agent.coder    │  [YES] │  [ASK] │  [ NO]  │   [ASK]     │   │
│  │ agent.reviewer │  [YES] │  [ NO] │  [ NO]  │   [ NO]     │   │
│  └────────────────┴────────┴────────┴─────────┴─────────────┘   │
│                                                                   │
│  ┌─ Session ─────────────────────────────────────────────────┐   │
│  │ Identity       │ :read  │ :write │ :manage │ :message    │   │
│  ├────────────────┼────────┼────────┼─────────┼─────────────┤   │
│  │ core.alice     │  [YES] │  [YES] │  [YES]  │   [YES]     │   │
│  │ agent.coder    │  [YES] │  [YES] │  [ NO]  │   [YES]     │   │
│  └────────────────┴────────┴────────┴─────────┴─────────────┘   │
│                                                                   │
│  ┌─ Gateway ─────────────────────────────────────────────────┐   │
│  │ Identity       │ :generate │ :preview                     │   │
│  ├────────────────┼───────────┼──────────────────────────────┤   │
│  │ agent.coder    │   [YES]   │   [YES]                      │   │
│  └────────────────┴───────────┴──────────────────────────────┘   │
│                                                                   │
│  Legend: [YES] ● [ASK] ◐ [APP] ◑ [NO] ○                         │
│  Click to cycle: NO → YES → ASK → NO                            │
│  Hover column header for permission description tooltip          │
│  (APP_LIFETIME is set at runtime via approval dialogs only)      │
└──────────────────────────────────────────────────────────────────┘
```

**Column headers** are shortened to `:capability` within each feature group (the domain is the group header). Full key shown in tooltip alongside the description from `ActionRegistry.permissionDescriptions`.

**Column discovery:** Group `appState.actionDescriptors.values.flatMap { it.requiredPermissions }.distinct()` by domain (before the colon). New features automatically appear as groups when they declare permissions — zero Core changes needed.

**Row grouping:** Identities grouped by `parentHandle`. Feature-level identities (uuid == null) shown as section headers, not editable (features are trusted).

If escalation is allowed (Pillar 7, Position B), grants that exceed the parent's level are displayed with a warning indicator (e.g., orange background, ⚠ icon).

4. **Actions** — New actions in `core_actions.json`:
   - `core.SET_PERMISSION` — Sets a single permission grant on an identity.
   - `core.SET_PERMISSIONS_BATCH` — Bulk update for efficient matrix editing.
   - `core.PERMISSIONS_UPDATED` — Broadcast after any permission change.
5. **Persistence** — Permissions are persisted as part of the `Identity` data class. The new `permissions` field serializes naturally with `kotlinx.serialization`. The `ignoreUnknownKeys` flag already used throughout handles forward-compatibility.

#### Phase 2.B — Permissions-Based CommandBot Exposure

**Goal:** Replace the current `ActionRegistry.agentAllowedNames` and `ActionRegistry.agentRequiresApproval` allowlists with the permissions system. Any identity (user or agent) can invoke any action through CommandBot `auf_` code blocks, **provided they have the required permissions.**

**Current flow (to be replaced):**
```
CommandBot.processCommandBlock()
  → is sender an agent?
    → yes → is actionName in agentAllowedNames? (CAG-004)
      → no  → BLOCKED
      → yes → is actionName in agentRequiresApproval? (CAG-006)
        → yes → stageApproval()
        → no  → publishActionCreated()
    → no (human) → publishActionCreated() (unrestricted)
```

**New flow:**
```
CommandBot.processCommandBlock()
  → resolve originator identity from senderId
  → resolve effective permissions for originator
  → check required_permissions for target action
    → all YES or APP_LIFETIME → publishActionCreated()
    → any ASK → integrate with Phase 3 approval flow
    → any NO or missing → post error feedback to session
  → (CAG-007 Auto-Fill still applies)
```

**Key changes in `CommandBotFeature.kt`:**
- Remove references to `ActionRegistry.agentAllowedNames` and `ActionRegistry.agentRequiresApproval`.
- Remove the `if (isAgent)` branch — permission checks are identity-based, not role-based. Users and agents go through the same code path.
- CommandBot reads `ActionRegistry.byActionName[actionName]?.requiredPermissions` and evaluates them against the originator's effective permissions.
- The existing approval card UI is reused for `ASK` permissions (unified with Phase 3).

**Backward compatibility:** During migration, if an action has empty `required_permissions`, CommandBot falls back to the current allowlist behavior. After migration is complete and the compile-time guard is active (§12.1), this fallback is removed.

### Phase 3 — ASK + APP_LIFETIME Approval Flow

**Goal:** Interactive approval dialogs when an identity has `ASK` level for a required permission.

**Deliverables:**

1. **Pending action queue in Store** — When the guard encounters `ASK`, the action is suspended in a `pendingPermissionActions` map keyed by a generated approval ID.

2. **Approval card** — Posted to the appropriate session via the existing CommandBot approval card infrastructure (`PartialView` with `commandbot.approval`). The card shows:
   - Who is requesting: identity name and handle
   - What they want to do: action name and human-readable description
   - Which permissions triggered ASK: listed with descriptions
   - Four response options:
     - **"Deny"** → action dropped, no level change
     - **"Allow once"** → action resumes, no level change
     - **"Allow for this app run"** → action resumes, transient `APP_LIFETIME` overlay set
     - **"Always allow"** → action resumes, persisted grant upgraded to `YES`

3. **Session targeting** — The approval card is posted to:
   - Priority 1: The session specified in the action's payload (if present — e.g., `session.POST` has a `session` field)
   - Priority 2: The session where the CommandBot command originated (if the action came through CommandBot)
   - Priority 3: The currently active/viewed session
   - Fallback: Queue the approval and block until a session is available

4. **`APP_LIFETIME` transient overlay** — Stored in a map on the Store (not persisted):
   ```kotlin
   val transientPermissionOverrides: MutableMap<String, MutableMap<String, PermissionGrant>> = mutableMapOf()
   ```
   Consulted by `resolveEffectivePermissions()` after persistent grants, before inheritance. Cleared on app restart.

5. **"Always allow" persistence** — Dispatches `core.SET_PERMISSION` to upgrade the persisted grant.

6. **Unification with CommandBot approval flow** — **Requires further analysis.** The current CommandBot approval flow (CAG-006) and the permission ASK flow serve overlapping purposes at different trust layers. Options:
   - **Unify completely:** CommandBot's approval gate is removed. All approval flows go through the permission system. CommandBot's `stageApproval()` is replaced by the Store's permission-suspended-action mechanism, and the existing approval card UI is reused.
   - **Layer separately:** Permission ASK runs first in the Store (security gate). CommandBot approval runs later (business policy). An action could pass permission ASK but still require CommandBot approval.
   - **Recommendation:** Unify. The current `agentRequiresApproval` list is a proto-permissions system. With real permissions in place, there's no reason for a second approval mechanism. The CommandBot approval card UI and UX are preserved — only the trigger point changes (Store guard instead of CommandBot's `processCommandBlock`).

### Phase 4 — Permission Scopes

**Goal:** Declarative, payload-based scoping enforced by the Store.

**Deliverables:**

1. **`PermissionScopeRule`** data class (§3.6).
2. **Code generator update** — Parse `permission_scopes` from manifests.
3. **`evaluateScope()` in Store** — Mechanical scope checker (see §5.4).
4. **Manifest annotations** — Add `permission_scopes` to actions that need them (self-only operations, namespace-scoped operations, filesystem paths).
5. **UI extension** — Show resource scope values in the permission matrix where applicable.

---

## 5. Store Guard — Detailed Implementation

### 5.1 Insertion Point

The permission guard inserts into `Store.processAction()` as a new **Step 2b**, between Step 2 (authorization / `public` flag) and Step 3 (lifecycle guard):

```
Step 1:  Schema lookup
Step 1b: Targeted validation
Step 2:  Authorization (public flag — structural access)
Step 2b: PERMISSION GUARD (capability access) ← new
Step 3:  Lifecycle guard
Step 4:  Route, reduce, side-effects
```

**Rationale:** The `public` flag answers "is this originator *structurally* allowed to dispatch this action type?" Permissions answer "does this specific identity have *capability* clearance?" Both must pass. Permissions come second because identity resolution is more expensive than the string-prefix check of the `public` flag.

### 5.2 Guard Implementation (Phase 1: NO/YES only)

```kotlin
// Inside processAction(), after Step 2 authorization passes:

// --- STEP 2b: PERMISSION GUARD ---
val requiredPerms = descriptor.requiredPermissions
if (requiredPerms.isNotEmpty()) {
    // Resolve the originator identity from the registry.
    val originatorIdentity = action.originator?.let {
        _state.value.identityRegistry[it]
    }

    // Feature identities (uuid == null) are trusted — skip permission check.
    // This covers all compiled feature handles: "core", "session", "agent", etc.
    if (originatorIdentity != null && originatorIdentity.uuid != null) {
        val effective = resolveEffectivePermissions(originatorIdentity)

        for (permKey in requiredPerms) {
            val grant = effective[permKey]
            val level = grant?.level ?: PermissionLevel.NO  // deny by default

            when (level) {
                PermissionLevel.NO -> {
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION DENIED: '${action.originator}' lacks '$permKey' " +
                        "for action '${action.name}'. Action blocked."
                    )
                    // Phase 3: dispatch core.PERMISSION_DENIED targeted notification
                    return
                }
                PermissionLevel.ASK -> {
                    // Phase 1: treat as NO (approval UI not yet implemented)
                    platformDependencies.log(
                        LogLevel.WARN, "Store",
                        "PERMISSION ASK (not yet implemented, treating as NO): " +
                        "'${action.originator}' has ASK for '$permKey' on '${action.name}'."
                    )
                    return
                }
                PermissionLevel.APP_LIFETIME,
                PermissionLevel.YES -> {
                    // Permitted — continue to next required permission.
                }
            }
        }

        // Phase 4: Evaluate permission scopes here.
    }
    // Unknown originator with required permissions: deny.
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
        // If resolvable to a trusted feature, allow (feature trust exemption).
    }
}
```

### 5.3 Permission Inheritance Resolution

```kotlin
/**
 * Resolves the effective permissions for an identity by walking the
 * parent chain. Child grants can override parent grants but are subject
 * to the escalation policy (Pillar 7).
 *
 * If no-escalation policy is active:
 *   - Child grants are capped at the parent's effective level.
 *   - Encountering a child grant > parent grant is logged as ERROR
 *     (indicates a data integrity issue that should have been caught
 *     at write-time by core.SET_PERMISSION validation).
 *
 * If controlled-escalation is active:
 *   - Child grants override parent grants without capping.
 *   - Escalations are logged at WARN level for audit.
 *
 * Resolution: accumulate from root to leaf. Each level overrides
 * the inherited value, subject to policy.
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

    // Merge root-first: each child layer overrides (with policy enforcement)
    val effective = mutableMapOf<String, PermissionGrant>()

    for (ancestor in chain.reversed()) {
        for ((key, grant) in ancestor.permissions) {
            val parentGrant = effective[key]
            if (parentGrant == null) {
                // First in chain to declare this key — use as-is
                effective[key] = grant
            } else {
                // NO_ESCALATION policy: cap at parent level
                if (grant.level > parentGrant.level) {
                    platformDependencies.log(
                        LogLevel.ERROR, "Store",
                        "PERMISSION ESCALATION detected: '${ancestor.handle}' has " +
                        "'${grant.level}' for '$key' but parent effective is " +
                        "'${parentGrant.level}'. Capping to parent level."
                    )
                    effective[key] = grant.copy(level = parentGrant.level)
                } else {
                    effective[key] = grant
                }
            }
        }
    }

    // Phase 3: Apply transient APP_LIFETIME overrides
    // val overrides = transientPermissionOverrides[identity.handle]
    // overrides?.forEach { (key, grant) -> effective[key] = grant }

    return effective
}
```

### 5.4 Scope Evaluation (Phase 4)

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

/**
 * Resolves a dot-path field from a JsonObject.
 * "target.identity.id" → payload["target"]["identity"]["id"]
 */
private fun resolvePayloadField(payload: JsonObject?, path: String): String? {
    if (payload == null) return null
    val parts = path.split(".")
    var current: JsonElement = payload
    for (part in parts) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull
}
```

---

## 6. New Actions

### 6.1 Core Actions for Permission Management

Add to `core_actions.json`:

```json
{
  "action_name": "core.SET_PERMISSION",
  "summary": "Sets a single permission grant on an identity. Enforces escalation policy: if no-escalation is active, rejects grants that exceed the parent's effective level. Namespace enforcement: originators can only modify identities within their namespace, except the Permission Manager UI which operates as a god-level entity via Core's own handle.",
  "public": true,
  "broadcast": false,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "identityHandle": {
        "type": "string",
        "description": "The handle of the identity to modify."
      },
      "permissionKey": {
        "type": "string",
        "description": "The permission key to set (e.g., 'filesystem:read')."
      },
      "level": {
        "type": "string",
        "enum": ["NO", "ASK", "APP_LIFETIME", "YES"],
        "description": "The permission level to grant."
      },
      "resourceScope": {
        "type": ["string", "null"],
        "description": "Optional resource scope constraint (Phase 4)."
      }
    },
    "required": ["identityHandle", "permissionKey", "level"]
  }
},
{
  "action_name": "core.SET_PERMISSIONS_BATCH",
  "summary": "Bulk-sets multiple permission grants. Used by the matrix UI for efficient editing and by initial config loading.",
  "public": true,
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
            "level": { "type": "string", "enum": ["NO", "ASK", "APP_LIFETIME", "YES"] },
            "resourceScope": { "type": ["string", "null"] }
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
  "summary": "Targeted notification sent to the originator's feature when an action is blocked by the permission guard. Enables features to show meaningful error messages or feedback to the user/agent.",
  "public": false,
  "broadcast": false,
  "targeted": true,
  "payload_schema": {
    "type": "object",
    "properties": {
      "blockedAction": { "type": "string" },
      "originatorHandle": { "type": "string" },
      "missingPermissions": {
        "type": "array",
        "items": { "type": "string" }
      },
      "sessionId": {
        "type": ["string", "null"],
        "description": "The session context where the denied action originated, if available. Enables the receiving feature to post feedback."
      }
    },
    "required": ["blockedAction", "originatorHandle", "missingPermissions"]
  }
}
```

---

## 7. Permission Inheritance Model

### 7.1 Tree Structure

Permissions follow the identity tree via `parentHandle`:

```
core (feature — trusted, exempt from checks)
├── core.alice (user — has own grants)
└── core.bob   (user — has own grants)

agent (feature — trusted, exempt from checks)
├── agent.coder-1  (agent — has grants, subject to escalation policy)
│   └── agent.coder-1.sub-task (inherits from coder-1, further narrowed)
└── agent.reviewer  (agent — own grants)

session (feature — trusted, exempt from checks)
├── session.chat1
└── session.chat2
```

### 7.2 Inheritance Rules

1. **Features are exempt.** Any identity with `uuid == null` skips the permission check entirely.
2. **Explicit grants override inherited.** If an identity has an explicit grant for a permission key, that takes precedence over the parent's grant for the same key (subject to escalation policy).
3. **Escalation policy.** See Pillar 7. Under no-escalation: explicit grants are capped at the parent's effective level. Under controlled-escalation: explicit grants override freely but are flagged.
4. **Missing grants inherit.** If an identity has no explicit grant for a key, it inherits the parent's effective grant.
5. **No grant anywhere = NO.** If neither the identity nor any ancestor has a grant for the key, the effective level is `NO`.

### 7.3 Registration Propagation

When `core.REGISTER_IDENTITY` processes a new identity:

- If the payload includes a `requestedPermissions` map, CoreFeature validates each requested level against the parent's effective permissions and the escalation policy.
- Under no-escalation: each requested level is capped at the parent's level.
- The approved permissions are stored on the new identity.
- If no `requestedPermissions` is provided, the identity starts with an empty permissions map (inherits entirely from parent).

---

## 8. Persistence

### 8.1 Identity Persistence

Permissions are persisted as part of the `Identity` data class. Since `identities.json` already stores full `Identity` objects, adding the `permissions` field is a non-breaking schema extension via `ignoreUnknownKeys`.

### 8.2 Transient Overrides (Phase 3)

`APP_LIFETIME` grants are stored in a transient map on the Store, not in `identities.json`. Cleared on app restart.

```kotlin
// In Store:
private val transientPermissionOverrides: MutableMap<String, MutableMap<String, PermissionGrant>> =
    mutableMapOf()
```

### 8.3 Initial Config Loading

A `default-permissions.json` ships with the app:

```json
{
  "grants": [
    { "identityPattern": "core.*",    "permissionKey": "filesystem:read",    "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "filesystem:write",   "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "filesystem:delete",  "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "session:read",       "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "session:write",      "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "session:message",    "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "session:manage",     "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "gateway:generate",   "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "gateway:preview",    "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "core:read",          "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "core:write",         "level": "YES" },
    { "identityPattern": "core.*",    "permissionKey": "core:identity",      "level": "YES" },

    { "identityPattern": "agent.*",   "permissionKey": "filesystem:read",    "level": "YES" },
    { "identityPattern": "agent.*",   "permissionKey": "filesystem:write",   "level": "ASK" },
    { "identityPattern": "agent.*",   "permissionKey": "filesystem:delete",  "level": "ASK" },
    { "identityPattern": "agent.*",   "permissionKey": "session:read",       "level": "YES" },
    { "identityPattern": "agent.*",   "permissionKey": "session:write",      "level": "YES" },
    { "identityPattern": "agent.*",   "permissionKey": "session:message",    "level": "YES" },
    { "identityPattern": "agent.*",   "permissionKey": "gateway:generate",   "level": "YES" },
    { "identityPattern": "agent.*",   "permissionKey": "gateway:preview",    "level": "YES" }
  ]
}
```

CoreFeature reads this during `system.STARTING` and applies grants via `core.SET_PERMISSIONS_BATCH` to matching identities. The `identityPattern` supports glob-style matching: `core.*` matches all direct children of the `core` identity.

---

## 9. Edge Cases and Guard Rails

### 9.1 Strict Originator Validation — Eliminating "UI Originators"

**Previous behavior:** The codebase used ad-hoc originator strings for UI dispatches (`"core.ui"`, `"ui.agentManager"`). These are not registered identities.

**New behavior (Phase 1):** The Store rejects any originator that is not either:
- A registered identity in `AppState.identityRegistry`, OR
- The exact handle of a registered Feature

Unregistered originators like `"core.ui"` or `"ui.agentManager"` are **ERROR**:

```kotlin
// In processAction(), before Step 2:
// --- STEP 1c: ORIGINATOR VALIDATION ---
if (action.originator != null) {
    val originatorInRegistry = _state.value.identityRegistry.containsKey(action.originator)
    val originatorIsFeature = features.any { it.identity.handle == action.originator }
    if (!originatorInRegistry && !originatorIsFeature) {
        // Check if it resolves to a feature via prefix
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

**Migration:** All `store.dispatch("core.ui", ...)` and `store.dispatch("ui.agentManager", ...)` calls must be updated to use either the feature handle (e.g., `"core"`) for feature-level operations, or the active user identity handle for user-initiated operations. This is a mechanical find-and-replace across UI Composables.

### 9.2 In-Flight Actions During Permission Changes

Actions already past the guard are unaffected (they've cleared security). Actions in the deferred queue are checked when processed and may be denied. This is acceptable behavior — no special handling needed.

### 9.3 CommandBot ACTION_CREATED Attribution

When CommandBot publishes `commandbot.ACTION_CREATED`, the originating feature dispatches the domain action with the originator set to the entity that typed the command. The permission check evaluates against the actual originator's grants. This is correct.

Post-Phase 2.B, CommandBot itself performs a pre-check before publishing `ACTION_CREATED`, providing immediate feedback to the session if permissions are insufficient. The Store guard remains the authoritative enforcement point.

### 9.4 Cross-Feature Dispatch Patterns

Features dispatching with their own handle as originator (e.g., CoreFeature dispatches `filesystem.READ` as `"core"`) are trusted and exempt. Features dispatching on behalf of a user/agent identity use `store.deferredDispatch(userHandle, action)` — the permission guard checks the user's permissions. This is correct and intentional.

### 9.5 Empty `requiredPermissions` vs Missing Field

- **Missing field in JSON:** `requiredPermissions` defaults to empty list. No permission check. Backward-compatible.
- **Empty array `[]`:** Equivalent to missing. No permission check.
- **Non-empty array:** Permission check required. All listed permissions must be `> NO`.

This ensures backward compatibility during migration. **After migration, the compile-time guard (§12.1) makes this a build error.**

---

## 10. Testing Strategy

### 10.1 Unit Tests (Store Guard)

Using the existing `RecordingStore` pattern:

1. **Deny by default:** Dispatch action with required permissions from identity with no grants → blocked.
2. **YES allows:** Identity has `YES` for all required permissions → allowed.
3. **Partial grants deny (compounding):** Identity has `session:write` but not `session:message` for `session.POST` → blocked.
4. **Feature exemption:** Dispatch from feature handle (uuid == null) → allowed regardless of permissions.
5. **Inheritance:** Child with no explicit grants, parent has `YES` → allowed.
6. **Narrowing:** Child has `YES`, parent has `ASK` → effective is `ASK` (capped). Escalation logged as ERROR.
7. **Unknown originator:** Originator not in registry → blocked (new strict validation).
8. **Invalid UI originator:** `"core.ui"` dispatching → ERROR and blocked.
9. **No permissions required:** Action without `required_permissions` → allowed for any originator.

### 10.2 Integration Tests

1. **Agent lifecycle:** Create agent → default permissions applied → allowed actions work → disallowed actions blocked.
2. **CommandBot integration:** Agent posts `auf_session.POST` → CommandBot checks permissions → allowed/denied based on grants.
3. **Permission change at runtime:** Modify via `core.SET_PERMISSION` → subsequent dispatches respect new level.
4. **Matrix UI round-trip:** Change permission in UI → persisted → survives restart.

---

## 11. File Inventory

> **Note:** This inventory is based on partial codebase visibility and is likely incomplete. Additional modifications may be needed in platform-specific code, test infrastructure, and feature-specific files not yet reviewed.

### New Files
| File | Package | Description |
|---|---|---|
| `Permission.kt` | `app.auf.core` | `PermissionLevel` enum, `PermissionGrant`, `PermissionScopeRule` (paved) |
| `PermissionManagerView.kt` | `app.auf.feature.core` | Matrix UI for viewing/editing permissions (Phase 2) |
| `default-permissions.json` | resources | Initial permission grants for shipped identities |

### Modified Files (known)
| File | Change |
|---|---|
| `Identity.kt` | Replace paved `permissions` field with `Map<String, PermissionGrant>` |
| `Store.kt` | Add Step 1c originator validation, Step 2b permission guard, `resolveEffectivePermissions()`, `evaluateScope()` (Phase 4), transient overrides map |
| `CoreFeature.kt` | Handle `core.SET_PERMISSION`, `core.SET_PERMISSIONS_BATCH`, initial config loading, permission persistence, escalation validation |
| `CommandBotFeature.kt` | Replace `agentAllowedNames`/`agentRequiresApproval` with permission checks (Phase 2.B) |
| `core_actions.json` | Add `core.SET_PERMISSION`, `core.SET_PERMISSIONS_BATCH`, `core.PERMISSIONS_UPDATED`, `core.PERMISSION_DENIED`; add `permissions` array |
| `*.actions.json` (all) | Add `permissions` declarations and `required_permissions` arrays |
| `IdentityManagerView.kt` | Add "Permissions" tab |
| Code generator | Parse `required_permissions`, `permissions`, `permission_scopes`; generate `permissionDescriptions` map; remove `agentAllowedNames`/`agentRequiresApproval` generation (Phase 2.B) |
| All UI Composables | Replace ad-hoc originator strings with registered identity handles |

---

## 12. Open Questions

### 12.1 Compile-Time Enforcement of `required_permissions`

**Current (migration):** Empty `required_permissions` means "no permissions required" — backward-compatible.

**Future (mandatory):** The `build.gradle.kts` code generation phase that compiles `*.actions.json` into `ActionNames.kt` must **fail the build** if any action lacks a `required_permissions` field. This closes the vulnerability where a developer forgets to declare permissions on a new action, leaving it unguarded. Actions that genuinely require no permissions should declare `"required_permissions": []` explicitly (the generator distinguishes absent-field from empty-array).

**Timeline:** Enforce after all existing manifests have been annotated (end of Phase 2).

### 12.2 Agent Permission Escalation Requests

An agent could dispatch a `core.REQUEST_PERMISSION_ESCALATION` action, which presents a dialog to the user asking for additional capabilities. **Pave the action name but don't implement** until a real use case emerges. This is analogous to Android's runtime permission request flow.

### 12.3 User Notification on Denial

Phase 1 silently drops denied actions (with logging). Phase 3 introduces `core.PERMISSION_DENIED` targeted to the originator's feature. Additionally, a future in-app log ticker can surface all permission events (grants, denials, escalation warnings) to power users who want full visibility. Not blocking for any phase.

### 12.4 Filesystem Delete Permission

Yes — separate `filesystem:delete` from `filesystem:write`. Linux distinguishes write and unlink permissions at the directory level, and the AUF codebase already separates `DELETE_FILE` and `DELETE_DIRECTORY` from `WRITE`. Delete is more destructive and warrants separate control.

### 12.5 Who Guards the Permission Manager?

`core.SET_PERMISSION` itself does not require permissions (circular dependency). It is gated by the `public` flag + namespace enforcement. The Permission Manager UI dispatches as `"core"` (the feature handle), which is trusted. This makes the Permission Manager a **god-level entity** by design — it is the only place where arbitrary permission grants can be set, and it operates under Core's own trusted identity.

### 12.6 CommandBot + ASK Unification Analysis

The relationship between CommandBot's existing approval flow and the permission ASK flow requires deeper analysis before Phase 3 implementation. Key questions:
- Does the unified flow need to support non-CommandBot ASK triggers (e.g., an agent dispatching directly via `store.deferredDispatch` without going through CommandBot)?
- Should the approval card UI live in Core (as a system-level component) or remain in CommandBot (as a feature-level component that Core delegates to)?
- How do the existing `PendingApproval` / `ApprovalResolution` state structures map to the new `pendingPermissionActions` concept?

This analysis should be completed before Phase 3 development begins.
