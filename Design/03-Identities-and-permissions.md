# ASAREON RAAM — Identities and Permissions

**Version 1.0.0-alpha** · Companion to 01-System-architecture, 02-Unit-testing

---

## Overview

Every participant on the action bus — features, human users, AI agents, sessions — is an **Identity**. Every capability an identity can exercise is governed by **Permissions**. Together, these two systems answer two questions for every dispatched action: *"Who is asking?"* and *"Are they allowed?"*

This document explains how identities and permissions are structured, how they interact with the Store pipeline, how they're managed through the UI, and what you need to know when adding new features, new actions, or new permission-protected capabilities.


## The Identity Tree

Identities form a hierarchy. Feature identities sit at the root, and everything they create — users, agents, sessions — becomes a child beneath them.

```
  identityRegistry (Map<String, Identity>)
  ──────────────────────────────────────────────────────────
  core                              feature (root)
  ├── core.alice                    user
  └── core.default-user             user

  session                           feature (root)
  ├── session.chat1                 session
  └── session.private-notes         session

  agent                             feature (root)
  ├── agent.gemini-coder-1          agent
  │   └── agent.gemini-coder-1.sub-task   sub-agent
  └── agent.mercury                 agent

  settings                          feature (root)
  filesystem                        feature (root)
  gateway                           feature (root)
  knowledgegraph                    feature (root)
```

The tree emerges naturally from registration. When `"agent"` dispatches `core.REGISTER_IDENTITY` with `name = "Gemini Coder"`, Core slugifies the name to `"gemini-coder"`, constructs the full handle as `"agent.gemini-coder"`, and sets `parentHandle = "agent"`. No feature can register identities outside its own namespace — the parent is always the originator.


### Anatomy of an Identity

An `Identity` (defined in `Identity.kt`) carries everything the system needs to identify, display, and authorize a participant:

```
Identity
├── uuid            System-assigned UUID. Null for features (their handles are stable).
├── localHandle     Leaf segment: "gemini-coder-1"
├── handle          Full bus address: "agent.gemini-coder-1"  ← registry key
├── name            Display name: "Gemini Coder nr.1"  ← full Unicode
├── parentHandle    "agent"  ← who registered this identity
├── registeredAt    Epoch millis
├── permissions     Map<String, PermissionGrant>  ← explicit grants
├── displayColor    "#RRGGBB" hex (nullable)
├── displayIcon     Material icon key (nullable)
└── displayEmoji    Emoji override (nullable, takes precedence over displayIcon)
```

Two typed wrappers provide compile-time safety: `IdentityHandle` wraps the full bus address, `IdentityUUID` wraps the UUID. Both are Kotlin value classes — zero cost at runtime, type-safe at compile time. Use `requireUUID()` at trust boundaries to catch wiring bugs where a handle string is accidentally passed as a UUID.


### Two Kinds of Identity

The `uuid` field is the dividing line:

```
  uuid == null  →  FEATURE identity
                   Stable handle across restarts. Trusted.
                   Exempt from permission checks.
                   Examples: "core", "agent", "session"

  uuid != null  →  EPHEMERAL identity
                   System-assigned UUID. Created at runtime.
                   Subject to permission checks.
                   Examples: "core.alice", "agent.gemini-coder-1"
```

This distinction matters because **feature identities bypass the permission guard entirely**. When the `agent` feature itself dispatches an action, it's trusted infrastructure. When `agent.gemini-coder-1` dispatches the same action, the Store checks its permissions.


### Handle Rules

Handles use a dot-separated hierarchy where each segment is a `localHandle`:

- **localHandle format**: `[a-z][a-z0-9-]*` — must start with a letter, no dots allowed
- **Full handle**: `parentHandle.localHandle` (or just `localHandle` for root features)
- **Dot** (`.`) is the hierarchy separator — it never appears inside a localHandle
- **Colon** (`:`) is reserved for permission keys — never appears in handles

This gives three distinct namespaces with no collisions: `agent.gemini-coder-1` (handle), `filesystem.WRITE` (action name), `filesystem:workspace` (permission key).


### Identity Resolution

The registry (`AppState.identityRegistry`) is a `Map<String, Identity>` keyed by full handle. The `resolve()` extension function accepts any form of identity reference and tries them in order:

```
  resolve("agent.gemini-coder-1")   →  1. Exact handle match (O(1) map lookup)
  resolve("a1b2c3d4-...")           →  2. UUID match
  resolve("gemini-coder-1")         →  3. localHandle match
  resolve("Gemini Coder nr.1")      →  4. Case-insensitive display name match
```

A scoped variant `resolve(raw, parentHandle)` limits the search to children of a specific feature. There's also `suggestMatches()` for "did you mean?" suggestions in error messages.


### Registration and Unregistration

Registration flows through the action bus. Only `name` is required — the handle is derived automatically:

```
  Feature dispatches
    core.REGISTER_IDENTITY { name: "Gemini Coder" }
                │
                ▼
  CoreFeature processes
    ├── localHandle provided?
    │     yes → validate format ([a-z][a-z0-9-]*)
    │     no  → slugify name: "Gemini Coder" → "gemini-coder"
    ├── Deduplicate among siblings: "gemini-coder" taken? → "gemini-coder-2"
    ├── UUID collision? (idempotent — returns existing if same UUID)
    └── Build full handle: originator + "." + localHandle
          "agent" + "." + "gemini-coder" → "agent.gemini-coder"
                │
                ▼
  Store.updateIdentityRegistry()    ← direct state mutation (architectural exception)
                │
                ▼
  core.RETURN_REGISTER_IDENTITY     ← targeted response back to the registrant
  core.IDENTITY_REGISTRY_UPDATED    ← broadcast so all features see the change
```

The slugification rules: lowercase, replace non-alphanumeric characters with hyphens, collapse runs of hyphens, ensure the result starts with a letter, fall back to `"unnamed"` if empty. If an explicit `localHandle` is provided in the payload, it's validated but used as-is (no slugification).

Unregistration via `core.UNREGISTER_IDENTITY` cascades — removing `"agent.gemini-coder"` also removes `"agent.gemini-coder.sub-task"` and any deeper descendants.


## The Permission System

Permissions control what ephemeral identities (users, agents) can do. The system is **deny-by-default**: if a permission isn't explicitly granted, it's `NO`.


### Permission Keys

A permission key is a `domain:capability` string identifying an atomic capability:

```
  filesystem:workspace          Read/write files in sandboxed workspace
  filesystem:system-files-read  Read any file on the OS
  filesystem:system-files-modify  Write/delete any file on the OS
  session:read                  Read session transcripts
  session:write                 Post messages
  session:manage                Create/delete/configure sessions
  gateway:generate              Request AI content generation
  gateway:preview               Request token estimation
  core:read                     Read core state and identity info
  core:identity                 Modify identity records
  core:admin                    Full administrative access
  agent:manage                  Create/delete/configure agents
  agent:execute                 Initiate agent turns
  knowledgegraph:read           Read HKG context
  knowledgegraph:write          Modify holons, import data
```

The `domain` typically maps to a feature name. The `capability` is a lowercase, hyphen-separated token. Keys are case-sensitive, max 64 characters, and must contain exactly one colon — all enforced at build time by the code generator.


### Permission Levels

Four levels, ordered by privilege:

```
  NO  <  ASK  <  APP_LIFETIME  <  YES

  NO             Access denied. Action silently blocked.
  ASK            Reserved for future approval system. Currently treated as NO.
  APP_LIFETIME   Reserved for future approval system. Currently treated as NO.
  YES            Access granted. Persisted with the identity.
```

The enum declaration order is load-bearing — `resolveEffectivePermissions()` uses ordinal comparison for inheritance and escalation detection. Do not reorder the enum.


### Danger Levels

Each permission key carries a danger level declared in the manifest. This drives the UI color-coding in the Permission Manager and has no effect on enforcement logic:

```
  LOW       Safe, sandboxed operation.       UI: green
  CAUTION   Broader impact, app-wide.        UI: amber/orange
  DANGER    System-wide damage potential.     UI: red
```


### Where Permissions Are Declared

Permissions live in two places that must stay in sync:

**1. The manifest** (`*.actions.json`) — declares what permissions exist and what actions require them:

```json
{
  "feature_name": "filesystem",
  "permissions": [
    { "key": "filesystem:workspace",
      "description": "Read/write files in sandboxed workspace",
      "dangerLevel": "LOW" }
  ],
  "actions": [
    { "action_name": "filesystem.WRITE",
      "required_permissions": ["filesystem:workspace"],
      ... }
  ]
}
```

**2. The identity** (`Identity.permissions`) — declares what an identity is actually granted:

```
identity.permissions = {
  "filesystem:workspace" → PermissionGrant(level = YES),
  "session:write"        → PermissionGrant(level = YES),
  "gateway:generate"     → PermissionGrant(level = NO)
}
```

The code generator validates at build time that every key referenced in `required_permissions` is declared in some feature's `permissions` array. A typo in a permission key fails the build.

**Key principle:** Permissions protect data operations and cross-feature effects. Internal (non public) Actions that only mutate transient, feature-internal UI state (`NAVIGATE`, `TOGGLE_ITEM_EXPANDED`, `SET_EDITING_SESSION`, etc.) have empty `required_permissions` and need no grants.


## The Store Guard

The Store's processing pipeline checks permissions as part of its guard sequence. Every dispatched action passes through these steps in order:

```
  Action dispatched
       │
       ▼
  ┌─────────────────────────────────────────────┐
  │ Step 1   Schema Lookup                      │  Unknown action name? → REJECT
  │ Step 1b  Targeted Validation                │  Wrong targeting flags? → REJECT
  │ Step 1c  Originator Validation              │  Not in registry or feature? → REJECT
  │ Step 2   Authorization (public flag)        │  Non-public from wrong feature? → REJECT
  │ Step 2b  Permission Guard                   │  Missing required permissions? → REJECT
  │ Step 3   Lifecycle Guard                    │  Wrong lifecycle phase? → REJECT
  │ Step 4   Route → Reduce → Side Effects      │  Action processed normally
  └─────────────────────────────────────────────┘
```


### How the Permission Guard Works

Step 2b runs only when an action's `requiredPermissions` is non-empty. The logic:

```
  requiredPermissions is empty?
       │ yes → skip guard, action passes
       │ no ↓
  Look up originator in identityRegistry
       │
       ├── originator is feature (uuid == null)?
       │   yes → PASS (feature trust exemption)
       │
       ├── originator is ephemeral identity?
       │   │
       │   ▼
       │   resolveEffectivePermissions(originator)
       │   For each required permission key:
       │     effective level == YES? → continue
       │     effective level == NO/ASK/APP_LIFETIME? → collect as missing
       │   Any missing? → REJECT + broadcast PERMISSION_DENIED
       │   All satisfied? → PASS
       │
       └── originator not in registry?
           Extract feature handle (first dot-segment)
           Is it a known feature? → PASS (feature trust exemption)
           Not a known feature? → REJECT
```


### Permission Inheritance

Permissions inherit down the identity tree. The Store resolves effective permissions by walking from the root ancestor to the identity itself, with each layer overriding:

```
  agent                          (feature — exempt, but may carry default grants)
  │ permissions: { "filesystem:workspace": YES, "gateway:generate": NO }
  │
  └── agent.gemini-coder-1       (agent — subject to checks)
      │ permissions: { "gateway:generate": YES }     ← overrides parent's NO
      │
      │ effective = parent merged with own:
      │   "filesystem:workspace" → YES  (inherited from agent)
      │   "gateway:generate"    → YES  (own grant overrides parent's NO)
      │
      └── agent.gemini-coder-1.sub-task
          │ permissions: { }                         ← no explicit grants
          │
          │ effective = inherited from parent chain:
          │   "filesystem:workspace" → YES
          │   "gateway:generate"    → YES
```

The five inheritance rules:

1. **Features are exempt.** `uuid == null` means the permission check is skipped entirely.
2. **Explicit grants override inherited.** A child's explicit grant for a key always wins.
3. **Missing grants inherit.** No explicit grant? The parent's effective level applies.
4. **No grant anywhere = NO.** If nobody in the chain declares the key, the effective level is `NO`.
5. **Controlled escalation.** A child can grant *more* than the parent (e.g., parent says `NO`, child says `YES`). This is allowed but logged at `WARN` and flagged in the Permission Manager UI with a warning indicator.


### Default Permissions

When a new identity is registered, CoreFeature applies compile-time defaults from `DefaultPermissions.kt`. These are glob-pattern rules matched against the new identity's handle:

```
  Pattern     "core.*"                  → human users
  Grants      filesystem:workspace      YES
              session:read              YES
              session:write             YES
              session:manage            YES
              gateway:generate          YES
              ... (broad access)

  Pattern     "agent.*"                 → AI agents
  Grants      filesystem:workspace      YES
              session:read              YES
              session:write             YES
              gateway:generate          YES
              ... (narrower than humans — no session:manage, no system-files)
```

Defaults only apply to newly registered identities without existing grants. Identities loaded from persistence (`identities.json`) keep their persisted permissions — edits from the Permission Manager survive restarts.

Defaults are compile-time constants, not configuration files. Changing the default permission profile requires a code change and rebuild. This is intentional — default permissions are a security-critical decision.


## Managing Identities and Permissions in the UI

The **Identity Manager** view provides two tabs: Identities and Permissions. It is opened from the Core feature's menu.


### Identities Tab

Lists all user identities (children of `"core"`) with their display name, handle, color swatch, and permission count. A toggle reveals *all* identities in the registry (including features, agents, sessions) for debugging.

Each identity card supports editing the display name and accent color. The active user identity (used as the `originator` for UI-dispatched actions) is highlighted and can be switched.

```
  ┌──────────────────────────────────────────────┐
  │  ● Alice                          [Active]   │
  │    ID: core.alice                            │
  │    6 permission(s)                           │
  │                                    [Edit][×] │
  └──────────────────────────────────────────────┘
  ┌──────────────────────────────────────────────┐
  │  ● Bob                         [Set Active]  │
  │    ID: core.bob                              │
  │    4 permission(s)                           │
  │                                    [Edit][×] │
  └──────────────────────────────────────────────┘
                                        [+ Add]
```


### Permissions Tab (Permission Manager)

A matrix view with identities on the left and permission keys grouped by domain across the top. Clicking a cell toggles between YES and NO (inherits from parent if cleared).

```
  Identity            │ filesystem         │ session        │ gateway    │
                      │ :workspace :sys-rd │ :read  :write  │ :generate  │
  ────────────────────┼────────────────────┼────────────────┼────────────┤
  agent       (feat)  │   YES       —      │  YES    YES    │    —       │
  agent.coder-1       │   YES       —      │  YES    YES    │   YES ⚠   │
  agent.mercury       │   YES       —      │  YES    YES    │    —       │
  core        (feat)  │    —        —      │   —      —     │    —       │
  core.alice          │   YES      YES     │  YES    YES    │   YES      │
  ────────────────────┴────────────────────┴────────────────┴────────────┘

  Legend:  YES = granted    — = inherited or denied
          ⚠ = escalated above parent (controlled escalation)

  Column header colors:  green = LOW    amber = CAUTION    red = DANGER
```

The view also shows contextual warning banners:
- **Red banner** when any identity holds a `DANGER`-level permission at `YES` — warns about system-wide risk.
- **Amber banner** when any identity has an escalated grant (child exceeds parent).

Changes dispatch `core.SET_PERMISSION` (single cell) or `core.SET_PERMISSIONS_BATCH` (bulk). Both are non-public actions — only CoreFeature can dispatch them. The UI operates through Core's trusted feature handle.


## What This Means for You

### Adding a New Feature

Your feature needs no special permission setup if its actions are purely internal (non-public). The Store's authorization guard already ensures only your feature can dispatch non-public actions.

If your feature exposes **public actions that modify data or trigger cross-feature effects**, you need to:

1. **Declare permissions** in your feature's `*.actions.json`:
   ```json
   "permissions": [
     { "key": "yourfeature:write",
       "description": "Modify data in YourFeature",
       "dangerLevel": "CAUTION" }
   ]
   ```

2. **Annotate actions** with `required_permissions`:
   ```json
   { "action_name": "yourfeature.UPDATE",
     "required_permissions": ["yourfeature:write"], ... }
   ```
   UI-only actions (navigation, toggling, expanding) get `"required_permissions": []`.

3. **Add defaults** in `DefaultPermissions.kt` if users or agents should get this permission on registration:
   ```kotlin
   DefaultGrant("core.*",  "yourfeature:write", PermissionLevel.YES),
   DefaultGrant("agent.*", "yourfeature:write", PermissionLevel.YES),
   ```

The code generator handles the rest — it validates your keys, cross-references them, and generates the `ActionRegistry` entries. The Store guard picks them up automatically.


### Adding a New Action to an Existing Feature

If the action is public and touches data: add the appropriate permission key(s) to its `required_permissions` in the manifest. If the permission key already exists (e.g., `session:write`), just reference it. If you need a new capability, declare a new permission in your feature's `permissions` array and add it to `DefaultPermissions.kt`.

If the action is internal or UI-only: set `"required_permissions": []` (or omit for non-public actions during migration, though explicit empty is preferred).


### Dispatching on Behalf of a Sub-Identity

When a feature dispatches actions on behalf of a child identity (e.g., `AgentRuntimeFeature` dispatching `filesystem.WRITE` on behalf of `agent.gemini-coder-1`), use the child's handle as the originator:

```kotlin
store.deferredDispatch(
    agent.identityHandle.handle,  // "agent.gemini-coder-1" — NOT "agent"
    Action(ActionRegistry.Names.FILESYSTEM_WRITE, payload)
)
```

This ensures the permission guard evaluates the *agent's* permissions, not the feature's (which would bypass checks entirely). The action must also be declared `public: true` in the manifest, because the agent's handle resolves to feature `"agent"` which differs from `"filesystem"` — the `public` flag permits cross-feature dispatch, and the permission guard then checks capability.


### Dispatching from UI Code

All UI composables dispatch using their owning feature's handle as the originator — e.g., `"core"`, `"session"`, `"agent"`. Never use ad-hoc strings like `"core.ui"` or `"session.ui"` — the originator validation guard rejects unregistered handles.


### Debugging Permission Denials

When an action is blocked by the permission guard, three things happen:

1. A `WARN`-level log: `PERMISSION DENIED: 'agent.gemini-coder-1' lacks 'gateway:generate' for action 'gateway.GENERATE_CONTENT'. Action blocked.`
2. A `core.PERMISSION_DENIED` broadcast with `blockedAction`, `originatorHandle`, and `missingPermissions` in the payload.
3. The action is silently dropped — the originator receives no direct response.

If you're debugging a "missing response" issue where an action seems to vanish, check the Store logs for `PERMISSION DENIED` or `INVALID ORIGINATOR` messages first. In tests, `RecordingStore.processedActions` will not contain the blocked action, and you can assert on the `PERMISSION_DENIED` broadcast instead.

Escalation warnings appear as `WARN`-level logs: `PERMISSION ESCALATION: 'agent.gemini-coder-1' has 'YES' for 'gateway:generate' but parent effective is 'NO'.`


### Testing Permissions

Permission-related tests follow the tier system from 02-Unit-testing:

**T1 (Unit)** — Test your reducer in isolation. Permissions don't apply here because there's no Store.

**T2 (Contract)** — Test that the Store guard correctly allows or blocks actions for identities with specific grants. Use the permission-aware test helpers:

```kotlin
// Create a descriptor that requires permissions
testDescriptorWithPermissions(
    "yourfeature.UPDATE",
    listOf("yourfeature:write")
)

// Create an identity with specific grants in the environment
harness.withIdentityAndPermissions(
    handle = "agent.test-agent",
    parentHandle = "agent",
    permissions = mapOf(
        "yourfeature:write" to PermissionGrant(PermissionLevel.YES)
    )
)
```

The key scenarios to cover: deny-by-default (no grants → blocked), YES allows, partial grants deny (has one permission but not another), feature exemption (feature handle → always allowed), and inheritance (child with no explicit grants inherits parent).


## Persistence

Permissions persist as part of the `Identity` data class via the existing `identities.json` serialization. The `permissions` field is a non-breaking schema extension — older files without it load fine (`ignoreUnknownKeys`), defaulting to an empty map (which means: apply defaults on next registration, or deny-by-default if already registered).

The `core.PERMISSIONS_UPDATED` broadcast fires after any permission change, so features that cache permission-derived state can refresh.


## Permission Actions Reference

| Action | Public | Type | Purpose |
|--------|--------|------|---------|
| `core.SET_PERMISSION` | No | Internal | Set a single grant on an identity. UI dispatches through Core's handle. |
| `core.SET_PERMISSIONS_BATCH` | No | Internal | Bulk-set grants. Used by the matrix UI and default-loading. |
| `core.PERMISSIONS_UPDATED` | No | Broadcast | Fires after any permission change. Features should refresh cached state. |
| `core.PERMISSION_DENIED` | No | Broadcast | Fired by the Store guard when an action is blocked. Carries `blockedAction`, `originatorHandle`, `missingPermissions`. |


## Quick Reference

```
  Separator conventions
  ─────────────────────────────────────────
  Dot  (.)     Hierarchy     core.alice, filesystem.WRITE
  Colon (:)    Capability    filesystem:workspace

  Dispatch originator rules
  ─────────────────────────────────────────
  UI code           → feature handle ("core", "session")
  Feature itself    → feature handle ("agent") — trusted, exempt
  On behalf of child → child handle ("agent.gemini-coder-1") — checked

  Permission resolution
  ─────────────────────────────────────────
  Feature identity (uuid == null)    → exempt, always passes
  Ephemeral (uuid != null)           → resolve effective grants via parent chain
  No grant in chain                  → NO (deny by default)
  Explicit grant                     → overrides inherited
  Child > parent                     → allowed (escalation logged at WARN)

  Build-time enforcement (code generator)
  ─────────────────────────────────────────
  Permission key without colon       → build fails
  Undeclared key in required_perms   → build fails
  Invalid dangerLevel                → build fails
  Public action without required_permissions (post-migration) → build fails
```
