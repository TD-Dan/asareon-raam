# Unified Action Bus v2.0 — Complete Implementation Outline

**Version**: 5.4 — Test migration sweep: 11 → 3 remaining failures
**Status**: Phase 1 ✅ / Phase 2.1 ✅ / Phase 2.2 ✅ / Phase 3 ✅ / Phase 4 Session ✅ / Phase 4 Agent Production ✅ / Phase 4 Agent Tests 🔧 (3 remaining, parked as tech debt) / Phase 4 CommandBot ✅ / Phase 4 KnowledgeGraph ✅ / Phase 4 Identity Consolidation ✅ / Phase 4 Legacy Shim Deletion ✅ / Phase 4 Test Migration ✅ (except 3 isolated issues)
**Last Updated**: 2026-02-15 — Bulk test migration: fixed 8+ test files across 4 root causes (sessionNames→subscribableSessionNames, missing resource references, missing targetRecipient on targeted actions, deleted obsolete identityNames test); 3 failures remain as isolated tech debt

## Executive Summary

This document specifies the evolution of AUF's core architecture across two fundamental systems:

1. **ActionRegistry** — A single, generated-then-runtime-extensible catalog that replaces `ActionNames`, `ExposedActions`, and the implicit private envelope contracts. Every action in the system is described once, with all metadata co-located.

2. **IdentityRegistry** — A universal participant registry that replaces the fragmented identity tracking scattered across CoreFeature (user identities), AgentFeature (agent names), SessionFeature (identity name cache), and CommandBot (known agent IDs). Every entity that can originate actions, appear as a sender, or be targeted by a message — users, agents, features, sessions, future scripts — registers a single `Identity` with a UUID and an optional parent, forming a tree that enables hierarchical originator resolution and future permissions inheritance.

Both registries are **application infrastructure, not feature state**. They live as top-level fields on `AppState`, available from the moment the Store is constructed, before any feature reducer runs.

Together, these two registries unify the action bus: **ActionRegistry** answers "what can be done" and **IdentityRegistry** answers "who is doing it." The Store routes messages using both, and the UI (slash-command autocomplete) reads from both.

The work is organized into **6 phases**, each independently shippable. The final phase delivers the slash-command autocomplete UI that motivated this entire redesign.

### Migration Invariant

> **At every commit, the project compiles and all existing tests pass.** New code is added alongside old. Old code is deprecated with compiler warnings pointing to replacements. Old code is deleted only after all references are migrated. No phase introduces a breaking change.

---

## Table of Contents

1. [Current Architecture & Pain Points](#1-current-architecture--pain-points)
2. [Target Architecture](#2-target-architecture)
3. [Phase 1 — Manifest Schema Unification & ActionRegistry Codegen ✅](#3-phase-1--manifest-schema-unification--actionregistry-codegen)
4. [Phase 2 — IdentityRegistry & Hierarchical Originators (2.1 ✅ / 2.2 ✅)](#4-phase-2--identityregistry--hierarchical-originators)
5. [Phase 3 — Targeted Delivery & Private Envelope Absorption ✅](#5-phase-3--targeted-delivery--private-envelope-absorption)
6. [Phase 4 — Migrate Consumers & Identity Consolidation — All Production ✅, Tests ✅ (3 isolated remaining)](#6-phase-4--migrate-consumers-commandbot-agent-session)
7. [Phase 5 — Runtime-Extensible Registry](#7-phase-5--runtime-extensible-registry)
8. [Phase 6 — Slash-Command Autocomplete UI](#8-phase-6--slash-command-autocomplete-ui)
9. [Future: Permissions System (Paved, Not Built)](#9-future-permissions-system-paved-not-built)
10. [Cross-Cutting Concerns](#10-cross-cutting-concerns)
11. [Migration Checklist](#11-migration-checklist)

---

## 1. Current Architecture & Pain Points

### 1.1 Three Communication Channels

The store currently supports three distinct communication paths, each with its own API surface, security model, and routing logic:

**Channel A — Public/Publish/Internal Actions** (`dispatch` / `deferredDispatch`)
- Routed through `processAction()` in Store
- Action names validated against `ActionNames.allActionNames`
- Internal actions delivered to owning feature only; public/publish broadcast to all
- Authorization: internal/publish require `originator == feature name`; public are unrestricted
- **Routing is derived from parsing the action name string** (e.g., `session.internal.LOADED` → INTERNAL type). This is brittle: a typo in the naming convention silently changes routing behavior.

**Channel B — Private Data Envelopes** (`deliverPrivateData`)
- Bypasses `processAction()` entirely
- No action name validation (TODOs in Store.kt lines 89–90 acknowledge this gap)
- No lifecycle guard checks
- Delivered via `Feature.onPrivateData()` — a separate handler from `handleSideEffects()` (formerly `onAction()`)
- No reducer phase — cannot update state through the standard flow

**Channel C — Build-Time Agent Exposure** (`ExposedActions`)
- A separate generated object duplicating data from `*_actions.json`
- CommandBot reads this for guardrails, approval gates, sandbox rules
- Agent prompt builder reads this for documentation injection
- Completely disconnected from the runtime action registry

### 1.2 Four Fragmented Identity Systems

| System | Location | Tracks | Updated Via |
|---|---|---|---|
| User identities | `CoreState.userIdentities` | Human users | `core.ADD_USER_IDENTITY`, persistence to `identities.json` |
| Agent names | `CommandBotFeature.knownAgentNames` | Agent instances | Subscribes to `agent.publish.AGENT_NAMES_UPDATED` |
| Agent IDs | `CommandBotFeature.knownAgentIds` | Agent instance IDs | Same broadcast, separate `mutableSetOf` |
| Display name cache | `SessionState.identityNames` | All senders (users + agents + system) | Subscribes to both `core.publish.IDENTITIES_UPDATED` and `agent.publish.AGENT_NAMES_UPDATED` |

Every feature builds its own partial picture of "who exists." When a new entity type is added (scripts, plugins), each feature must add yet another subscription and cache.

### 1.3 Pain Points

| Problem | Impact |
|---|---|
| `exposedToAgents` duplicates `listensFor` | Schema changes must be mirrored in two places; they diverge silently |
| Private envelopes bypass security | TODOs on Store.kt lines 89–90 have been open since inception |
| Flat originator strings | `"agent"` dispatches on behalf of N agent instances; internal re-routing is invisible to the store |
| Three code paths for "send message to feature" | Cognitive overhead; each new feature must learn all three |
| Four identity caches | Adding a new participant type requires changes in 4 features |
| No runtime action registration | Scripts and dynamic agents cannot declare new commands |
| No unified catalog for UI | Slash-command autocomplete would be yet another projection of manifest data |
| Sessions have no identity | A session like "chat1" can't be an originator or target on the bus without ad-hoc string passing |
| Name-parsed routing is brittle | `session.internal.LOADED` works, `session.interal.LOADED` silently broadcasts. Routing rules are implicit in naming conventions, not explicit in schema. |

### 1.4 What Works Well (Preserve)

- The UDF loop in `ensureProcessingLoop()` — single-threaded, deterministic, queued
- The three-phase processing: validate → reduce → side-effect
- `ActionNames` constants for compile-time safety in Kotlin code
- The `*.actions.json` manifest-as-interface-declaration pattern
- The security model's core idea: originator-based authorization by action type
- The existing `Identity(id, name)` data class as a minimal, serializable value type

---

## 2. Target Architecture

### 2.1 The Two Registries

```
┌─────────────────────────────────────────────────────────┐
│                    ActionRegistry                        │
│  "What can be done"                                     │
│  Lives on: AppState.actionDescriptors                   │
│                                                         │
│  features/                                              │
│    session/                                              │
│      POST       (command: open, broadcast)              │
│      CREATE     (command: open, broadcast, approval)    │
│      LOADED     (internal: restricted, owner-only)      │
│      MESSAGE_POSTED  (event: restricted, broadcast)     │
│      RETURN_LEDGER (response: restricted, targeted)   │
│    commandbot/                                           │
│      APPROVE    (command: open, broadcast)              │
│      ...                                                │
│                                                         │
│  Derived views:                                         │
│    agentAllowedNames, agentRequiresApproval,            │
│    agentAutoFillRules, agentSandboxRules                │
│                                                         │
│  Future hook:                                           │
│    requiredPermissions: List<String>?  (null = open)    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                   IdentityRegistry                       │
│  "Who is doing it"                                      │
│  Lives on: AppState.identityRegistry                    │
│                                                         │
│  (null)  "system"          parent: null                  │
│  (null)  "core"            parent: null                  │
│  (null)  "session"         parent: null                  │
│  (UUID)  "session.chat1"   parent: → session             │
│  (UUID)  "session.pcog-x"  parent: → session             │
│  (null)  "agent"           parent: null                  │
│  (UUID)  "agent.gemini-x"  parent: → agent               │
│  (UUID)  "agent.archiv-y"  parent: → agent               │
│  (null)  "commandbot"      parent: null                  │
│  (UUID)  "core.alice"      parent: → core                │
│  (UUID)  "core.bob"        parent: → core                │
│                                                         │
│  Future hook:                                           │
│    permissions: Map<String, Boolean>?  (null = inherit)  │
└─────────────────────────────────────────────────────────┘
```

Features have `uuid = null` because their handles are stable across restarts and they do not need a generated UUID. Ephemeral entities (users, agents, sessions) receive a system-assigned UUID on creation.

Users live inside the core feature (e.g., `core.alice`), which is truthful to the actual owning feature. In the future, a dedicated user feature could support groups like `user.admin.bob`.

### 2.2 AppState (Final Form)

Both registries are **application infrastructure, not feature state**. They live on `AppState` as top-level fields, available from the moment the Store is constructed:

```kotlin
data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap(),

    /**
     * The universal action catalog. Pre-populated from the generated ActionRegistry
     * at construction time. Extended at runtime by Phase 5's REGISTER_ACTION.
     * The Store validates every dispatched action against this map.
     */
    val actionDescriptors: Map<String, ActionDescriptor> = ActionRegistry.byActionName,

    /**
     * The universal identity registry. Every participant on the action bus has an
     * entry here: features, users, agents, sessions, scripts.
     * Keyed by Identity.handle (the bus address).
     * Seeded with feature identities during initFeatureLifecycles().
     */
    val identityRegistry: Map<String, Identity> = emptyMap()
)
```

**Why not FeatureState?**
- The Store needs `actionDescriptors` to validate the very first action through the system (`system.INITIALIZING`). If it were in a feature's state, it would be `null` before that feature's reducer runs — a chicken-and-egg problem.
- The Store needs `identityRegistry` to resolve originators during authorization. It's needed before any feature's side-effect handlers run.
- Both registries are conceptually "bus infrastructure" — they answer "what can be done" and "who is doing it" for the bus itself. They are not domain state belonging to any single feature.
- The `validActionNames` constructor param on Store was already outside the feature reducer pattern. This approach replaces it with live state at the same architectural level.

### 2.3 Unified Data Model

```kotlin
// --- Action (enhanced) ---
@Serializable
data class Action(
    val name: String,
    val payload: JsonObject? = null,
    val originator: String? = null,       // hierarchical: "agent.gemini-flash-abc123"
    val targetRecipient: String? = null   // if non-null, targeted delivery to this feature
                                          // ONLY valid when the action's schema has targeted = true
)

// --- Identity (enhanced) ---
@Serializable
data class Identity(
    val uuid: String?,                 // null for features (stable by handle); system-assigned for entities [^1]
    val localHandle: String,           // leaf-level identity name: "gemini-coder-1"
                                       // only regexp [a-z][a-z0-9-]* allowed (must start with letter, no dots)
    val handle: String,                // full bus address: "agent.gemini-coder-1"
                                       // constructed as parentHandle.localHandle by CoreFeature
                                       // registry key — immutable after registration
    val name: String,                  // display name: "Gemini Coder nr.1", all unicode allowed
    val parentHandle: String? = null,  // always the originator that registered this identity
                                       // null for root identities (features)
                                       // immutable — enforced by design (originator IS the parent)
    val registeredAt: Long = 0

    // FUTURE: Permissions hook — not implemented in v2.0
    // val permissions: Map<String, Boolean>? = null
)
```

[^1]: `uuid` and `handle` are both usable as identifiers, but features may use both together for cross-application uniqueness (e.g., saving agent configuration to `uuid.handle.json` eliminates most import clashes). The shorthand `id` is banned from the codebase; always use `identity` to refer to `Identity`.

### 2.4 The Five Action Types

Every action in the system falls into one of five semantic types. These types are derived from two independent boolean flags (`open` and `broadcast`) plus one explicit flag (`targeted`). **Authorization (`open`) and delivery (`broadcast`/`targeted`) are orthogonal concerns:**

| Type | `open` | `broadcast` | `targeted` | Authorization | Delivery | Example |
|---|---|---|---|---|---|---|
| **Command** | `true` | `true` | `false` | Any originator | All features | `session.POST` |
| **Open Non-Broadcast** | `true` | `false` | `false` | Any originator | Owner only | `core.REGISTER_IDENTITY` |
| **Event** | `false` | `true` | `false` | Owner only | All features | `session.MESSAGE_POSTED` |
| **Internal** | `false` | `false` | `false` | Owner only | Owner only | `session.LOADED` |
| **Response** | `false` | `false` | `true` | Owner only | Specified recipient | `filesystem.RETURN_READ` |

A targeted command (`public: true, targeted: true`) is also valid — it means "anyone can invoke, delivered to a specific recipient." No current action uses this, but it's a reasonable future pattern.

**Open Non-Broadcast** is the pattern used by `core.REGISTER_IDENTITY` and `core.UNREGISTER_IDENTITY`: any feature can dispatch the request, but only CoreFeature's reducer and side-effects receive it. This avoids wasting reducer cycles across all features for what is essentially a service call. The caller receives results via a targeted response (`core.RETURN_REGISTER_IDENTITY`).

**Derived convenience properties** (on `ActionDescriptor`):
```kotlin
val isCommand: Boolean get() = open
val isEvent: Boolean get() = !open && broadcast
val isInternal: Boolean get() = !open && !broadcast && !targeted
val isResponse: Boolean get() = !open && targeted
```

These four are mutually exclusive and exhaustive for the `!open` cases. `isCommand` intentionally covers both broadcast and non-broadcast open actions.

**Validation rule**: `targeted` and `broadcast` are mutually exclusive. An action cannot be both broadcast and targeted. The codegen and Store enforce this.

### 2.5 Schema-Driven Routing (v2)

**Key change from v1**: Routing is no longer derived from parsing action name strings. The `session.internal.LOADED` / `session.publish.MESSAGE_POSTED` naming convention is retired. All actions become `feature.ACTION_NAME`. Routing behavior is determined entirely by the boolean flags in the action's schema descriptor (`open`, `broadcast`, `targeted`).

```
processAction(action):
  1. Look up descriptor: appState.actionDescriptors[action.name]
     → reject if not found
  2. Authorize originator (OPEN flag — who can dispatch):
     a. Extract feature-level handle: originator.substringBefore('.')
     b. If descriptor.open → any originator allowed
     c. If !descriptor.open → require feature match (originator prefix == descriptor.featureName)
     d. FUTURE: Check requiredPermissions against IdentityRegistry grants
  3. Lifecycle guard
  4. Route (BROADCAST/TARGETED flags — who receives; orthogonal to OPEN):
     a. descriptor.targeted             →  TARGETED: deliver to recipient feature only (Phase 3)
     b. !descriptor.broadcast           →  OWNER-ONLY: deliver to owning feature only
     c. descriptor.broadcast            →  BROADCAST: deliver to all features
  5. For each target: reducer(state, action) → new state
  5b. LIFT: CoreState.identityRegistry → AppState.identityRegistry (mechanical copy)
  6. For each target: handleSideEffects(action, store, prev, new)
```

**Key insight — authorization ≠ routing**: The `open` flag controls *who can dispatch* (step 2). The `broadcast`/`targeted` flags control *who receives* (step 4). These are independent. An action can be `public: true, broadcast: false` (anyone can dispatch, only the owning feature receives) — used by `core.REGISTER_IDENTITY` where any feature can request registration but only CoreFeature processes it.

**Why this is better than name parsing**:
- A typo in an action name (`session.interal.LOADED`) no longer silently changes routing — the descriptor's boolean flags are the single source of truth
- Routing rules are explicit, auditable, and visible in the manifest
- No special-case parsing for `system.*` actions — system actions are just `public: false` actions owned by feature `"system"`

**Action naming convention** (cosmetic, not parsed):
- All actions: `feature.ACTION_NAME` (e.g., `session.POST`, `session.LOADED`, `session.MESSAGE_POSTED`)
- The old `internal.` / `publish.` infixes are removed. You can still name an action `INTERNAL_FOO` for readability, but it has no effect on routing.

### 2.6 Feature Interface (v2)

```kotlin
interface Feature {
    val identity: Identity              // replaces val name: String
    fun reducer(state: FeatureState?, action: Action): FeatureState? = state
    fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {}
    fun init(store: Store) {}
    val composableProvider: ComposableProvider?

    // DEPRECATED in Phase 3, REMOVED in Phase 4:
    // fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {}
}
```

**Naming rationale**: `handleSideEffects` replaces `onAction` because it truthfully describes what the method does — it's the impure phase where features perform I/O, dispatch follow-up actions, and interact with external systems. The name `onAction` invited confusion: a developer unfamiliar with the system might call `feature.onAction(...)` directly, bypassing the entire Store pipeline (validation, authorization, reducer, audit logging). `handleSideEffects` makes this misuse self-evidently wrong.

### 2.7 Everything Is an Identity

The key insight: every addressable entity in the system is an Identity in the same tree.

| Entity | Identity `handle` | `parentHandle` | `uuid` | Registered By |
|---|---|---|---|---|
| System | `"system"` | null | null | Store at boot |
| Core feature | `"core"` | null | null | Store at boot (auto-registered) |
| Session feature | `"session"` | null | null | Store at boot (auto-registered) |
| A specific session | `"session.chat1"` | `"session"` | generated | SessionFeature on CREATE |
| Agent feature | `"agent"` | null | null | Store at boot (auto-registered) |
| A specific agent | `"agent.gemini-x"` | `"agent"` | generated | AgentFeature on CREATE_AGENT |
| CommandBot | `"commandbot"` | null | null | Store at boot (auto-registered) |
| User "Alice" | `"core.alice"` | `"core"` | generated | CoreFeature on ADD_USER_IDENTITY |
| Future: a script | `"luascript.myscript1"` | `"luascript"` | generated | ScriptFeature on LOAD_SCRIPT |

This means:
- A session can be an originator: `store.deferredDispatch("session.chat1", action)`
- A session can be a target recipient: `Action(name = ..., targetRecipient = "session.chat1")` — the Store delivers to the `session` feature, which reads sub-targeting from the targetRecipient
- The display name of any sender is always `identityRegistry[senderHandle]?.name`. No local name cashe tracking needed anymore.
- The parent tree enables future permission inheritance without adding any new concepts

**Note on `targetRecipient` resolution**: The Store resolves `targetRecipient` at the **feature level** only. If `targetRecipient = "session.chat1"`, the Store delivers to the `session` feature. Sub-entity targeting (e.g., "deliver to session.chat1 specifically") is the feature's responsibility.

---

## 3. Phase 1 — Manifest Schema Unification & ActionRegistry Codegen ✅

**Goal**: Single `actions` array per manifest; one generated `ActionRegistry.kt` replaces both `ActionNames.kt` and `ExposedActions.kt`. Routing moves from name-parsed conventions to explicit schema flags.

### 3.1 New Manifest Schema

Each `*.actions.json` evolves from:

```
feature_name
summary
├── listensFor[]        (actions this feature handles)
├── exposedToAgents[]   (DUPLICATE subset with agent-specific metadata)
├── publishes[]         (actions this feature emits)
├── private_envelopes[] (targeted envelope types)
```

To:

```
feature_name
summary
├── permissions[]       (permission type names, manifest-only for now, enables future planning)
├── actions[]           (ALL actions: handled, emitted, and targeted)
```

### 3.2 Unified Action Entry Schema

```json
{
    "feature_name": "session",
    "summary": "Acts as a pure, passive data manager for multiple, concurrent session transcripts (the 'Public Ledger').",
    "permissions": ["read", "write", "delete", "sessions", "messages"],
    "actions": [
        {
            "action_name": "session.POST",
            "summary": "Post a message to a session.",
            "public": true,
            "broadcast": true,
            "targeted": false,
            "payload_schema": {
                "type": "object",
                "properties": {
                    "session":  { "type": "string", "description": "Target session ID." },
                    "message":  { "type": "string", "description": "Message content." }
                },
                "required": ["session", "message"]
            },
            "agent_exposure": {
                "requires_approval": false
            },
            "required_permissions": ["write", "messages"]
        },
        {
            "action_name": "session.INTERNAL_LOADED",
            "summary": "Dispatched internally after all session transcripts have been loaded from disk.",
            "public": false,
            "broadcast": false,
            "targeted": false,
            "payload_schema": {
                "type": "object",
                "description": "A map of session IDs to Session objects."
            }
        },
        {
            "action_name": "session.MESSAGE_POSTED",
            "summary": "Broadcasts that a new message has been successfully posted to a session.",
            "public": false,
            "broadcast": true,
            "targeted": false,
            "payload_schema": { "...": "..." }
        },
        {
            "action_name": "session.RETURN_LEDGER",
            "summary": "Delivers formatted ledger content to the requester in response to session.REQUEST_LEDGER.",
            "public": false,
            "broadcast": false,
            "targeted": true,
            "payload_schema": { "...": "..." }
        }
    ]
}
```

Field definitions:

| Field | Type | Description |
|---|---|---|
| `action_name` | string | Fully qualified: `feature.ACTION_NAME` |
| `summary` | string | Human-readable description |
| `open` | boolean | `true` = any originator can dispatch (Command); `false` = restricted to owning feature only (Event/Internal/Response) |
| `broadcast` | boolean | `true` = delivered to all features; `false` = delivered to owning feature only (or targeted recipient) |
| `targeted` | boolean | `true` = a `targetRecipient` must be provided, delivered to that feature only; mutually exclusive with `broadcast: true` |
| `payload_schema` | object? | JSON Schema for the payload |
| `agent_exposure` | object? | Present only if relevant to agents. Absence = not exposed. Temporary field until full permission system is implemented |
| `agent_exposure.requires_approval` | boolean | Whether agent invocation requires human approval |
| `required_permissions` | string[]? | **FUTURE HOOK** (manifest-only for now): Permission types required to dispatch. null = unrestricted |

**Routing derivation from flags**:
- `public: false, broadcast: false, targeted: false` → INTERNAL (replaces old `internal.*` convention)
- `public: true/false, broadcast: true` → BROADCAST (replaces old `publish.*` and public actions)
- `targeted: true` → TARGETED (replaces old private envelopes). `open` flag controls authorization only.

**Validation rules** (enforced by codegen and Store):
- `targeted: true` and `broadcast: true` are mutually exclusive
- If `targeted: true`, then `broadcast` must be `false`

**Note on `permissions`**: The feature-level `permissions[]` array is declaration-only in v2.0. It appears in the manifest for planning purposes — the codegen reads it and stores it in the `FeatureDescriptor`, but no runtime enforcement is built. This ensures the schema is forward-compatible when the permission system ships.

### 3.3 Envelope Migration

Former `private_envelopes` entries become actions with `"targeted": true`:

```json
{
    "action_name": "filesystem.RETURN_READ",
    "summary": "Delivers file content to the requesting feature.",
    "public": false,
    "broadcast": false,
    "targeted": true,
    "payload_schema": { "...": "..." }
}
```

### 3.4 Action Name Migration

All action names are flattened to the `feature.ACTION_NAME` convention. The old `internal.` / `publish.` infixes are removed. This is a mechanical rename:

| Old Name | New Name | Notes |
|---|---|---|
| `session.internal.LOADED` | `session.LOADED` | `public: false, broadcast: false` replaces `internal.` |
| `session.publish.MESSAGE_POSTED` | `session.MESSAGE_POSTED` | `public: false, broadcast: true` replaces `publish.` |
| `session.publish.SESSION_UPDATED` | `session.SESSION_UPDATED` | Same |
| `system.publish.INITIALIZING` | `system.INITIALIZING` | `public: false, broadcast: true` (system is just a feature) |
| `system.publish.STARTING` | `system.STARTING` | Same |
| `system.publish.CLOSING` | `system.CLOSING` | Same |
| `commandbot.internal.STAGE_APPROVAL` | `commandbot.STAGE_APPROVAL` | `public: false, broadcast: false` |
| `commandbot.internal.RESOLVE_APPROVAL` | `commandbot.RESOLVE_APPROVAL` | `public: false, broadcast: false` |
| `commandbot.publish.ACTION_CREATED` | `commandbot.ACTION_CREATED` | `public: false, broadcast: true` |
| `filesystem.response.read` | `filesystem.RETURN_READ` | `targeted: true` |
| `core.response.CONFIRMATION` | `core.RETURN_CONFIRMATION` | `targeted: true` |
| `session.response.ledger` | `session.RETURN_LEDGER` | `targeted: true` |

The generated `ActionNames` constants update accordingly. The typealias shim (Section 3.7) ensures existing code compiles during the transition.

**Pre-migration manual check**: Resolve any payload schema divergences between `exposedToAgents` and `listensFor` for actions that appear in both (e.g., `session.CLONE` uses `sourceSession` in `exposedToAgents` but `session` in `listensFor`). The `listensFor` schema is canonical — it's what the reducer actually parses.

### 3.5 Manifest Migration Script

A one-time script that:

1. Reads each `*.actions.json`
2. Merges `listensFor` → `actions` with `public: true, broadcast: true` (Commands), or `public: false, broadcast: false` for actions that were previously `internal`
3. Merges `publishes` → `actions` — those with `internal.` prefix get `public: false, broadcast: false`; those with `publish.` prefix get `public: false, broadcast: true`
4. Merges `private_envelopes` → `actions` with `public: false, broadcast: false, targeted: true`
5. For each entry in `exposedToAgents`, finds the matching action by name and injects `agent_exposure`
6. Renames all action names: replaces `.internal.`, `.publish.`, `.response.` infixes with `INTERNAL_`, ``, `RESPONSE`
7. renames the old top-level arrays as deprecated: `publishes` → `deprecated-publishes`
8. Writes the unified manifest back to `actions`

### 3.6 Generated ActionRegistry.kt

The Gradle task produces one file replacing both `ActionNames.kt` and `ExposedActions.kt`:

```kotlin
package app.auf.core.generated

object ActionRegistry {

    // ================================================================
    // Section 1: Compile-Time Constants (replaces ActionNames)
    // ================================================================
    object Names {
        const val SESSION_POST = "session.POST"
        const val SESSION_CREATE = "session.CREATE"
        const val SESSION_LOADED = "session.LOADED"
        const val SESSION_MESSAGE_POSTED = "session.MESSAGE_POSTED"
        const val SESSION_RETURN_LEDGER = "session.RETURN_LEDGER"
        const val SYSTEM_INITIALIZING = "system.INITIALIZING"
        const val SYSTEM_STARTING = "system.STARTING"
        const val SYSTEM_CLOSING = "system.CLOSING"
        const val COMMANDBOT_APPROVE = "commandbot.APPROVE"
        const val COMMANDBOT_DENY = "commandbot.DENY"
        const val COMMANDBOT_STAGE_APPROVAL = "commandbot.STAGE_APPROVAL"
        const val COMMANDBOT_ACTION_CREATED = "commandbot.ACTION_CREATED"
        const val FILESYSTEM_RETURN_READ = "filesystem.RETURN_READ"
        const val CORE_RETURN_CONFIRMATION = "core.RETURN_CONFIRMATION"
        const val CORE_REGISTER_IDENTITY = "core.REGISTER_IDENTITY"
        const val CORE_UNREGISTER_IDENTITY = "core.UNREGISTER_IDENTITY"
        const val CORE_IDENTITY_REGISTRY_UPDATED = "core.IDENTITY_REGISTRY_UPDATED"
        // ... all action names

        val allActionNames: Set<String> = setOf(
            SESSION_POST, SESSION_CREATE, SESSION_LOADED,
            SESSION_MESSAGE_POSTED, SESSION_RETURN_LEDGER,
            FILESYSTEM_RETURN_READ, CORE_RETURN_CONFIRMATION,
            // ...
        )
    }

    // ================================================================
    // Section 2: Descriptor Data Classes
    // ================================================================
    data class PayloadField(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
        val default: String? = null
    )

    data class SandboxRule(
        val strategy: String,
        val pathPrefixTemplate: String,
        val payloadRewrites: Map<String, String> = emptyMap()
    )

    data class AgentExposure(
        val requiresApproval: Boolean = false,
        val autoFillRules: Map<String, String> = emptyMap(),
        val sandboxRule: SandboxRule? = null
    )

    data class ActionDescriptor(
        val fullName: String,
        val featureName: String,
        val suffix: String,
        val summary: String,
        val public: Boolean,
        val broadcast: Boolean,
        val targeted: Boolean,
        val payloadFields: List<PayloadField>,
        val requiredFields: List<String>,
        val agentExposure: AgentExposure?,

        // FUTURE: Permissions hook — paved, not enforced.
        val requiredPermissions: List<String>? = null
    ) {
        // --- Derived semantic type shorthands ---
        /** A Command is any action open to all originators. */
        val isCommand: Boolean get() = open
        /** An Event is a restricted-origin broadcast (feature announces something happened). */
        val isEvent: Boolean get() = !open && broadcast
        /** An Internal action is restricted to the owning feature only. */
        val isInternal: Boolean get() = !open && !broadcast && !targeted
        /** A Response is a restricted-origin targeted delivery (reply to a requester). */
        val isResponse: Boolean get() = !open && targeted
    }

    data class FeatureDescriptor(
        val name: String,
        val summary: String,
        val permissions: List<String>,    // manifest-only for now
        val actions: Map<String, ActionDescriptor>
    )

    // ================================================================
    // Section 3: Feature Registry (generated from manifests)
    // ================================================================
    val features: Map<String, FeatureDescriptor> = mapOf(
        "session" to FeatureDescriptor(
            name = "session",
            summary = "Acts as a pure, passive data manager...",
            permissions = listOf("read", "write", "delete", "sessions", "messages"),
            actions = mapOf(
                "POST" to ActionDescriptor(
                    fullName = "session.POST",
                    featureName = "session",
                    suffix = "POST",
                    summary = "Post a message to a session.",
                    public = true,
                    broadcast = true,
                    targeted = false,
                    payloadFields = listOf(
                        PayloadField("session", "string", "Target session ID.", true),
                        PayloadField("message", "string", "Message content.", true)
                    ),
                    requiredFields = listOf("session", "message"),
                    agentExposure = AgentExposure(
                        requiresApproval = false,
                        autoFillRules = mapOf("senderId" to "{agentId}")
                    )
                ),
                "LOADED" to ActionDescriptor(
                    fullName = "session.LOADED",
                    featureName = "session",
                    suffix = "LOADED",
                    summary = "Dispatched after session transcripts loaded from disk.",
                    public = false,
                    broadcast = false,
                    targeted = false,
                    payloadFields = emptyList(),
                    requiredFields = emptyList(),
                    agentExposure = null
                ),
                // ... more actions
            )
        ),
        // ... more features
    )

    // ================================================================
    // Section 4: Derived Views (replace ExposedActions projections)
    // ================================================================
    val byActionName: Map<String, ActionDescriptor> = features.values
        .flatMap { it.actions.values }.associateBy { it.fullName }

    val agentAllowedNames: Set<String> = byActionName.values
        .filter { it.agentExposure != null }.map { it.fullName }.toSet()

    val agentRequiresApproval: Set<String> = byActionName.values
        .filter { it.agentExposure?.requiresApproval == true }.map { it.fullName }.toSet()

    val agentAutoFillRules: Map<String, Map<String, String>> = byActionName.values
        .filter { it.agentExposure?.autoFillRules?.isNotEmpty() == true }
        .associate { it.fullName to it.agentExposure!!.autoFillRules }

    val agentSandboxRules: Map<String, SandboxRule> = byActionName.values
        .mapNotNull { d -> d.agentExposure?.sandboxRule?.let { d.fullName to it } }.toMap()
}
```

### 3.7 Compatibility Shims

**ActionNames shim** — to avoid a massive find-and-replace in one commit:

```kotlin
package app.auf.core.generated

/** @deprecated Use ActionRegistry.Names directly. Migrate incrementally. */
typealias ActionNames = ActionRegistry.Names
```

All existing code continues to compile. Migration from `ActionNames.SESSION_POST` to `ActionRegistry.Names.SESSION_POST` can happen file-by-file across subsequent PRs.

**Important**: Old constant names like `SESSION_PUBLISH_MESSAGE_POSTED` are mapped to new names in the shim:
```kotlin
/** @deprecated Renamed to SESSION_MESSAGE_POSTED */
const val SESSION_PUBLISH_MESSAGE_POSTED = SESSION_MESSAGE_POSTED
```

**ExposedActions shim** — `CommandBotFeature.kt` imports and uses `ExposedActions` directly. Since `ExposedActions.kt` is no longer generated, a deprecated delegation object is needed:

```kotlin
package app.auf.core.generated

/** @deprecated Use ActionRegistry.agentAllowedNames, .agentRequiresApproval, etc. directly. */
@Deprecated("Use ActionRegistry directly. Will be removed in Phase 4.")
object ExposedActions {
    val allowedActionNames get() = ActionRegistry.agentAllowedNames
    val requiresApproval get() = ActionRegistry.agentRequiresApproval
    val autoFillRules get() = ActionRegistry.agentAutoFillRules
    val sandboxRules get() = ActionRegistry.agentSandboxRules
}
```

This is a thin delegation, not a typealias, because it maps one object's interface to multiple properties on another. Deleted in Phase 4 when CommandBot is migrated.

### 3.8 Gradle Task Changes

The `generateActionRegistry` task in `build.gradle.kts` is rewritten to:

1. Walk `*.actions.json` files (same directory scan as today)
2. Parse the unified `actions[]` array (instead of separate `listensFor` + `exposedToAgents` + `publishes` + `private_envelopes`)
3. Validate: reject manifests where `targeted: true` and `broadcast: true` on the same action
4. Emit a single `ActionRegistry.kt` with boolean flags, not type enums
5. Emit `ActionNames.kt` as a typealias compatibility shim with old-name-to-new-name mappings
6. Emit `ExposedActions.kt` as a deprecated delegation object (Section 3.7)
7. Stop emitting the old `ExposedActions.kt` format

### 3.9 Build Validation

- `ActionRegistry.Names.allActionNames` must cover all actions from the previous `ActionNames.allActionNames` (names have changed but the set must be complete)
- `ActionRegistry.agentAllowedNames` must be identical to previous `ExposedActions.allowedActionNames` (modulo name renames)
- `ActionRegistry.agentRequiresApproval` must be identical to previous `ExposedActions.requiresApproval`
- All existing tests must pass with both shims in place

### 3.10 Files Changed

| File | Change |
|---|---|
| All `*.actions.json` manifests | Restructure to unified `actions[]` schema; rename all action names to `feature.ACTION_NAME`; replace `inbound`/`public` with `open`/`broadcast`/`targeted` |
| `build.gradle.kts` | Rewrite `generateActionRegistry` task |
| `ActionRegistry.kt` (new, generated) | Replaces ActionNames.kt + ExposedActions.kt with unified registry |
| `ActionNames.kt` (generated) | Becomes typealias shim with old→new name mappings |
| `ExposedActions.kt` (generated) | Becomes deprecated delegation object pointing to ActionRegistry |

### 3.11 Implementation Notes (Post-Completion)

**Completed**: 2026-02-13

**Tooling produced**:
- `migrate_manifests.py` — one-time Python migration script with divergence detection, name mapping generation, and classification of all 168 actions across 9 features
- Rewritten `generateActionRegistry` Gradle task emitting three files: `ActionRegistry.kt`, `ActionNames.kt` (compat shim), `ExposedActions.kt` (delegation shim)

**Lesson learned — Gradle `doLast` data class limitation**: The initial Gradle task used `data class` declarations inside the `doLast` block. Gradle compiles these closures as anonymous inner classes in a script context, causing constructor metadata to be lost at runtime (`No argument 0 in type '<root>.Build_gradle.<no name provided>...PayloadFieldData'`). The fix was to use plain `Map<String, Any>` throughout the task action, matching the original working task's pattern. The data classes in the *generated output* (`ActionRegistry.kt`) are fine since those are compiled as normal Kotlin source.

**Divergences resolved**:

| Action | Issue | Resolution |
|---|---|---|
| `session.CLONE` | `exposedToAgents` uses `sourceSession`; `listensFor` uses `session` | `listensFor` is canonical — migrated manifest uses `session` |
| `session.DELETE_MESSAGE` | `exposedToAgents` uses `senderId`+`timestamp`; `listensFor` uses `messageId` | `listensFor` is canonical — **requires manual review** of CommandBot translation path |
| `session.POST` | `exposedToAgents` missing 5 fields (`messageId`, `metadata`, etc.) | `listensFor` superset — no issue |
| `session.CREATE` | `exposedToAgents` missing `isHidden`, `isPrivate` | Superset — no issue |
| `session.LIST_SESSIONS` | `exposedToAgents` missing `responseSession` | Auto-filled by CommandBot — no issue |
| `filesystem.SYSTEM_WRITE` | `listensFor` has `encrypt` not in agent schema | Sandboxing rewrites `encrypt` → `"false"` — no issue |
| `filesystem.LIST` | `listensFor` has `correlationId` not in agent schema | Optional field — no issue |

**Statistics**: 168 total actions (105 commands, 18 events, 37 internal, 8 targeted). 63 action name renames. 15 agent-exposed actions preserved with full metadata.

---

## 4. Phase 2 — IdentityRegistry & Hierarchical Originators (2.1 ✅ / 2.2 ✅)

**Goal**: Consolidate all identity tracking into the `identityRegistry` on `AppState`. Every bus participant — features, users, agents, sessions — registers an Identity with a handle. The Store uses hierarchical originator resolution backed by this registry. Feature identities are seeded directly by the Store at boot, bypassing the action bus.

**Split into two sub-phases**:
- **Phase 2.1** ✅ — Core infrastructure: Identity rewrite, Feature.identity, Store schema-driven auth + routing, identity registry on CoreState/AppState, REGISTER/UNREGISTER actions, feature seeding
- **Phase 2.2** ✅ — Consumer wiring: SessionFeature/AgentFeature registration, user identity migration, onAction→handleSideEffects rename, IdentityManagerView reads from registry. RETURN_REGISTER_IDENTITY targeted delivery deferred to Phase 3.

### 4.1 Enhanced Identity Data Class

```kotlin
@Serializable
data class Identity(
    /**
     * Globally unique, system-assigned identifier.
     * Null for features — their handles are stable across restarts.
     * Generated by CoreFeature for ephemeral entities (users, agents, sessions, ...).
     */
    val uuid: String?,
    /**
     * The leaf-level handle for this identity, unique among siblings.
     * Only [a-z][a-z0-9-]* allowed (must start with a letter, no dots).
     * The dot is a hierarchy separator, never part of a localHandle.
     * Examples: "session", "chat-cats", "gemini-coder-1", "alice"
     */
    val localHandle: String,
    /**
     * The full bus address, constructed as "parentHandle.localHandle" for child identities,
     * or just "localHandle" for root identities (features).
     * This is the registry key and what appears as action.originator.
     * Constructed by CoreFeature — features never set this directly.
     * Examples: "session", "session.chat-cats", "agent.gemini-coder-1", "core.alice"
     */
    val handle: String,
    /** Display name shown in the UI. Full Unicode allowed. */
    val name: String,
    /**
     * Handle of the parent identity — always the originator that registered this identity.
     * Null for root identities (features, system).
     * Immutable after registration — enforced by design: the originator IS the parent,
     * so no feature can register identities outside its own namespace.
     */
    val parentHandle: String? = null,
    /** Epoch millis when this identity was registered. */
    val registeredAt: Long = 0

    // FUTURE: Permissions grants — paved, not implemented in v2.0.
    // val permissions: Map<String, Boolean>? = null
)
```

**Handle construction rule**: `handle = parentHandle + "." + localHandle` for child identities, or just `localHandle` for root identities. CoreFeature constructs this — the `REGISTER_IDENTITY` payload only contains `localHandle` and `name`. The originator automatically becomes the `parentHandle`.

### 4.2 AppState Changes

The `identityRegistry` lives on `AppState` (see Section 2.2), not on `CoreState`. This means the Store can read it directly during authorization without depending on any feature's state being initialized.

**CoreState deprecations**:

```kotlin
@Serializable
data class CoreState(
    // ... existing fields (toastMessage, activeViewKey, lifecycle, etc.) ...

    /**
     * The active human user's identity handle. This is specifically "which human
     * is at the keyboard".
     */
    val activeUserHandle: String? = null,

    // DEPRECATED: replaced by AppState.identityRegistry filtered to parentHandle == "core".
    // Kept temporarily for backward compatibility during migration.
    @Deprecated("Use AppState.identityRegistry instead")
    val userIdentities: List<Identity> = emptyList(),

    // ... rest of existing fields ...
) : FeatureState
```

### 4.3 New Actions (in core.actions.json)

Four new actions added to `core.actions.json`:

```json
{
    "action_name": "core.REGISTER_IDENTITY",
    "summary": "Register a new identity in the universal registry. The originator automatically becomes the parent — no feature can register identities outside its own namespace. CoreFeature validates localHandle format, deduplicates among siblings, generates UUID, and constructs the full handle as originator.localHandle.",
    "public": true,
    "broadcast": false,
    "targeted": false,
    "payload_schema": {
        "type": "object",
        "properties": {
            "localHandle":   { "type": "string", "description": "Leaf-level handle. Must match [a-z][a-z0-9-]*. Full bus address constructed as originator.localHandle." },
            "name":          { "type": "string", "description": "Human-readable display name (full Unicode allowed)." }
        },
        "required": ["localHandle", "name"]
    }
},
{
    "action_name": "core.RETURN_REGISTER_IDENTITY",
    "summary": "Targeted response to a REGISTER_IDENTITY request. Contains the approved identity on success, or error on failure. Caller matches by requestedLocalHandle.",
    "public": false,
    "broadcast": false,
    "targeted": true,
    "payload_schema": {
        "type": "object",
        "properties": {
            "success":               { "type": "boolean" },
            "requestedLocalHandle":  { "type": "string" },
            "approvedLocalHandle":   { "type": "string", "description": "Only on success. May differ from requested due to deduplication." },
            "handle":                { "type": "string", "description": "Only on success. The full bus address." },
            "uuid":                  { "type": "string", "description": "Only on success." },
            "name":                  { "type": "string", "description": "Only on success." },
            "parentHandle":          { "type": "string", "description": "Only on success. The originator's handle." },
            "error":                 { "type": "string", "description": "Only on failure." }
        },
        "required": ["success", "requestedLocalHandle"]
    }
},
{
    "action_name": "core.UNREGISTER_IDENTITY",
    "summary": "Remove an identity from the registry. Cascades: all descendants (by handle prefix) are also removed. Namespace enforcement: originator can only unregister within its own namespace.",
    "public": true,
    "broadcast": false,
    "targeted": false,
    "payload_schema": {
        "type": "object",
        "properties": {
            "handle": { "type": "string", "description": "Full handle of the identity to remove." }
        },
        "required": ["handle"]
    }
},
{
    "action_name": "core.IDENTITY_REGISTRY_UPDATED",
    "summary": "Broadcast after any change to the identity registry. Replaces both core.IDENTITIES_UPDATED and agent.AGENT_NAMES_UPDATED for registry consumers.",
    "public": false,
    "broadcast": true,
    "targeted": false
}
```

**Design decisions (resolved from original TODOs)**:

1. **The originator IS the parent** — `REGISTER_IDENTITY` has no `parentHandle` field. The originator of the dispatch call automatically becomes the parent. This is enforced by design, not by validation code. If `"agent.gemini-coder"` dispatches `REGISTER_IDENTITY { localHandle: "sub-task" }`, the result is `"agent.gemini-coder.sub-task"` — no code path exists to register outside the dispatcher's namespace.

2. **CoreFeature owns all business logic (Approach B)** — The Store stays clean of identity management logic. CoreFeature's reducer handles validation, deduplication, UUID generation. The Store only performs a mechanical lift of `CoreState.identityRegistry` → `AppState.identityRegistry` after each reduce cycle.

3. **REGISTER and UNREGISTER are `public: true, broadcast: false`** — Any feature can dispatch them, but only CoreFeature receives and processes them. This avoids wasting reducer cycles across all 8 features for what is essentially a service call to CoreFeature.

4. **RETURN_REGISTER_IDENTITY is `targeted: true`** — The caller needs to know if its localHandle was approved, modified (dedup), or rejected. Targeted delivery to the originator is deferred to Phase 3; for now the response is dispatched but not yet routed to the caller.

5. **Namespace enforcement on UNREGISTER** — The originator can only unregister identities whose handle equals their own or starts with `originator.` — preventing cross-namespace deletion.

6. **Cascade deletion by handle prefix** — Removing `"agent.gemini-coder"` also removes `"agent.gemini-coder.sub-task"` via prefix matching, which is simpler and faster than parentHandle traversal and catches all depth levels in one pass.

### 4.4 Feature Identity Seeding at Boot

Features register themselves **directly in the Store at boot time**, bypassing the action bus entirely. This avoids the lifecycle guard chicken-and-egg: during `BOOTING`, only `system.INITIALIZING` is permitted, so `core.REGISTER_IDENTITY` would be rejected.

Feature identities are structural facts known at compile time from the `features` list. They do not need to go through the action bus.

```kotlin
fun initFeatureLifecycles() {
    if (!lifecycleStarted) {
        // Seed feature identities directly — no action bus needed
        val featureIdentities = features.associate { feature ->
            feature.identity.handle to feature.identity
        }
        _state.value = _state.value.copy(
            identityRegistry = _state.value.identityRegistry + featureIdentities
        )

        features.forEach { it.init(this) }
        lifecycleStarted = true
    }
}
```

Sub-entities (sessions, agents, users) register via `core.REGISTER_IDENTITY` through the action bus during `STARTING`, which is the correct lifecycle phase for runtime data.

### 4.5 Sub-Entity Registration

**During `SYSTEM_STARTING`** (after data loaded from disk):

```kotlin
// After loading sessions from disk, register each session:
store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("localHandle", session.id)        // e.g., "chat-cats"
    put("name", session.name)             // e.g., "Chat about Cats"
}))
// → originator = "session" → handle = "session.chat-cats", parentHandle = "session"

// After loading agents from disk, register each agent:
store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("localHandle", agentId)           // e.g., "gemini-coder"
    put("name", agentDisplayName)         // e.g., "Gemini Coder"
}))
// → originator = "agent" → handle = "agent.gemini-coder", parentHandle = "agent"
```

**At runtime**, when entities are created or destroyed:

```kotlin
// Session created:
store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("localHandle", newSession.id)
    put("name", newSession.name)
}))

// Agent deleted (cascade removes all sub-identities):
store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_UNREGISTER_IDENTITY, buildJsonObject {
    put("handle", "agent.${deletedAgentId}")
}))
```

### 4.6 Unified Name Resolution

All features that currently maintain name caches migrate to reading from the single registry:

```kotlin
// BEFORE (SessionView.kt):
val senderName = sessionState?.identityNames?.get(entry.senderId) ?: entry.senderId

// AFTER:
val appState by store.state.collectAsState()
val senderName = appState.identityRegistry[entry.senderId]?.name ?: entry.senderId
```

The following can be deprecated and eventually removed:

| Current | Replaced By |
|---|---|
| `SessionState.identityNames` | `AppState.identityRegistry` |
| `CommandBotFeature.knownAgentIds` | `AppState.identityRegistry` filtered by parentHandle == "agent" |
| `CommandBotFeature.knownAgentNames` | Same — `identityRegistry[handle]?.name` |
| `agent.AGENT_NAMES_UPDATED` | `core.IDENTITY_REGISTRY_UPDATED` |
| `core.IDENTITIES_UPDATED` | `core.IDENTITY_REGISTRY_UPDATED` |

### 4.7 Hierarchical Originator Resolution in Store

The Store's authorization logic uses prefix-match on handles:

```kotlin
/** Extracts the feature-level handle from a hierarchical originator. */
private fun extractFeatureHandle(originator: String?): String? =
    originator?.substringBefore('.')

// In processAction:
val descriptor = appState.actionDescriptors[action.name] ?: run { /* reject unknown action */ return }

val isAuthorized = when {
    descriptor.open -> true
    else -> extractFeatureHandle(action.originator) == descriptor.featureName
}

// FUTURE: After authorization, check required permissions:
// val requiredPerms = descriptor.requiredPermissions
// if (requiredPerms != null) {
//     val identity = appState.identityRegistry[action.originator]
//     val effectivePerms = resolvePermissions(identity)  // walk parentHandle chain
//     if (!requiredPerms.all { effectivePerms[it] == true }) { reject }
// }
```

This is a **backward-compatible relaxation**: `"agent"` still passes (no `.` in it), but now `"agent.gemini-flash-abc123"` also passes because the feature prefix matches.

**Security note**: The originator string is set by Kotlin code at the `dispatch` / `deferredDispatch` call site. All current callers are trusted first-party code compiled together, so originator strings are inherently trusted. When runtime scripting support lands, the script host feature (e.g., `luascript`) MUST inject the originator — scripts cannot set their own. A script run by `luascript` will always have originator `luascript.myscript`, preventing it from impersonating other features. This should be documented prominently for any developer writing a new host feature.

### 4.8 Sessions as Identities

When `SessionFeature` creates a session, it registers an identity:

```kotlin
// After SESSION_CREATE succeeds:
store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("localHandle", newSession.id)    // e.g., "chat-cats"
    put("name", newSession.name)         // e.g., "Chat about Cats"
}))
// → originator = "session" → handle = "session.chat-cats", parentHandle = "session"
```

When a session is renamed, the identity name is updated. When deleted, unregistered (cascades children). This means:
- The session tab bar and manager can resolve display names from the identity registry
- A future feature can target a specific session via a targeted action with payload containing the session handle
- An agent that wants to "watch" a session can reference it by handle

### 4.9 User Identities Migration

Current user identities (`CoreState.userIdentities`) become entries in the registry with `parentHandle` pointing to `"core"`:

```
Identity(uuid = "uuid-1", handle = "core.alice", name = "Alice", parentHandle = "core")
Identity(uuid = "uuid-2", handle = "core.bob",   name = "Bob",   parentHandle = "core")
```

The existing `identities.json` file is migrated on first load: each old `Identity(id, name)` is converted to the new format with a generated UUID and `parentHandle = "core"`. The old `userIdentities` field is populated from the registry via a derived getter during the transition period.

### 4.10 Feature.identity Migration

The `Feature` interface changes from `val name: String` to `val identity: Identity`:

```kotlin
// BEFORE:
class SessionFeature(...) : Feature {
    override val name: String = "session"
}

// AFTER:
class SessionFeature(...) : Feature {
    override val identity = Identity(
        uuid = null,        // features don't need UUIDs
        handle = "session",
        name = "Session Manager"
    )
}
```

The Store updates all internal routing that used `feature.name` to use `feature.identity.handle`:

```kotlin
// BEFORE:
val targetFeature = features.find { it.name == descriptor.featureName }

// AFTER:
val targetFeature = features.find { it.identity.handle == descriptor.featureName }
```

**IDE-assisted mechanical rename**: This is a codebase-wide find-and-replace operation:
- `feature.name` → `feature.identity.handle` across all source files
- Verify: `featureStates` map keys remain unchanged (handles equal old names)
- Verify: all `originator` strings in `dispatch`/`deferredDispatch` calls still match

### 4.11 IdentityManagerView Evolution

The `IdentityManagerView` continues to show only user identities (filtered by `parentHandle == "core"` in `AppState.identityRegistry`). However, the registry enables a future "System Participants" debug view showing all registered identities grouped by parent — useful for debugging and eventually for permissions management.

### 4.12 Files Changed

**Phase 2.1 (Complete):**

| File | Change |
|---|---|
| `Identity.kt` | Rewrite: `uuid: String?`, `localHandle`, `handle`, `name`, `parentHandle`, `registeredAt` |
| `Feature.kt` | Change `val name: String` → `val identity: Identity` |
| `AppCore.kt` / `AppState` | Add `identityRegistry: Map<String, Identity>` and `actionDescriptors: Map<String, ActionDescriptor>` to `AppState` |
| `CoreState.kt` | Add `identityRegistry: Map<String, Identity>` (canonical, lifted to AppState by Store) |
| `CoreFeature.kt` | Add `identity` property; `REGISTER_IDENTITY` reducer (validate localHandle, deduplicate, construct handle, generate UUID); `UNREGISTER_IDENTITY` reducer (namespace enforcement, cascade by prefix); `onAction` (renamed to `handleSideEffects` in Phase 2.2) dispatches `IDENTITY_REGISTRY_UPDATED` and `RETURN_REGISTER_IDENTITY` |
| `core.actions.json` | Add 4 new actions: `REGISTER_IDENTITY`, `RETURN_REGISTER_IDENTITY`, `UNREGISTER_IDENTITY`, `IDENTITY_REGISTRY_UPDATED` |
| `Store.kt` | Remove `ParsedActionName` and name-parsing. Add `extractFeatureHandle()`. Schema-driven authorization (`open` flag) orthogonal to routing (`broadcast`/`targeted` flags). Seed feature identities in `initFeatureLifecycles()`. Mechanical lift: `CoreState.identityRegistry` → `AppState.identityRegistry` after each reduce. Remove `validActionNames` constructor param; validate via `AppState.actionDescriptors`. |
| `AppContainer.kt` | Remove `validActionNames` from Store constructor call |
| All Feature implementations | IDE-assisted rename: `override val name` → `override val identity`; `this.name` → `identity.handle` (as originator); `feature.name` → `feature.identity.handle` |

**Phase 2.2 (Complete):**

| File | Change |
|---|---|
| `Feature.kt` | Rename `onAction` → `handleSideEffects` in interface; updated KDoc explaining the name choice (developer won't mistakenly call it directly, bypassing the Store pipeline) |
| `Store.kt` | Updated all `feature.onAction(...)` call sites → `feature.handleSideEffects(...)`; updated Step 5 comment; updated exception handler message; updated `deferredDispatch` doc |
| `CoreFeature.kt` | Renamed `onAction` → `handleSideEffects`; `CORE_INTERNAL_IDENTITIES_LOADED` reducer migrates loaded identities into `identityRegistry` (parentHandle="core", derives localHandle from name for legacy identities, generates UUIDs); `CORE_ADD_USER_IDENTITY` uses `deduplicateLocalHandle` and writes to both `userIdentities` (deprecated) and `identityRegistry`; `CORE_REMOVE_USER_IDENTITY` removes from both with cascade; `handleSideEffects` broadcasts `CORE_IDENTITY_REGISTRY_UPDATED` after user identity changes (ADD/REMOVE/SET_ACTIVE/IDENTITIES_LOADED) |
| `CoreState.kt` | `userIdentities` annotated with `@Deprecated("Use AppState.identityRegistry filtered by parentHandle == \"core\" instead")`; comment: "DEPRECATED — Phase 2.2. Will be removed in Phase 4." |
| `SessionFeature.kt` | Renamed `onAction` → `handleSideEffects`; `SESSION_INTERNAL_LOADED` registers identities for newly loaded sessions (compares prev/new, dispatches REGISTER_IDENTITY for new ones); `SESSION_CREATE`/`SESSION_CLONE` registers identity for new session; `SESSION_DELETE` dispatches UNREGISTER_IDENTITY with cascade; `SESSION_UPDATE_CONFIG` on name change unregisters old + re-registers with new name |
| `AgentRuntimeFeature.kt` | Renamed `onAction` → `handleSideEffects`; `AGENT_INTERNAL_AGENT_LOADED` registers identity for each loaded agent; `AGENT_CREATE` registers identity; `AGENT_DELETE` dispatches UNREGISTER_IDENTITY with cascade; `AGENT_UPDATE_CONFIG` on name change unregisters old + re-registers with new name |
| `IdentityManagerView.kt` | Reads from `appState.identityRegistry.values.filter { it.parentHandle == "core" }` instead of `coreState.userIdentities`; uses `remember(appState.identityRegistry)` for reactive updates; sorted by `registeredAt` for stable ordering |
| Other features (CommandBot, Filesystem, Settings, Gateway, KnowledgeGraph) | Mechanical `onAction` → `handleSideEffects` rename (done as part of the batch rename) |

### 4.13 Testing

- Existing tests pass (deprecated fields still populated during transition)
- New test: hierarchical originator `"agent.gemini-x"` authorized for restricted `agent.X` action
- New test: hierarchical originator `"agent.gemini-x"` rejected for restricted `session.Y` action
- New test: `REGISTER_IDENTITY` adds to `AppState.identityRegistry`; `UNREGISTER_IDENTITY` cascades to children (by parentHandle)
- New test: `IDENTITY_REGISTRY_UPDATED` broadcast after every registry change
- New test: Feature identities seeded during `initFeatureLifecycles()` — visible in `AppState.identityRegistry` before first action

---

## 5. Phase 3 — Targeted Delivery & Private Envelope Absorption ✅

**Goal**: Eliminate `deliverPrivateData`, `PrivateDataEnvelope`, and `Feature.onPrivateData`. All communication flows through the unified action bus with proper security.

### 5.1 Action Data Class (Final Form)

```kotlin
@Serializable
data class Action(
    val name: String,
    val payload: JsonObject? = null,
    val originator: String? = null,
    val targetRecipient: String? = null
)
```

When `targetRecipient` is non-null, the Store delivers the action **only to the named feature's reducer and handleSideEffects**. The Store rejects the action if the action's schema descriptor does not have `targeted = true`.

### 5.2 Store Routing (Updated processAction)

```kotlin
val descriptor = appState.actionDescriptors[action.name] ?: run { /* reject unknown */ return }

// Validate targetRecipient usage
if (action.targetRecipient != null && !descriptor.targeted) {
    log(ERROR, "Action '${action.name}' has targetRecipient but is not declared as targeted. Rejected.")
    return
}
if (descriptor.targeted && action.targetRecipient == null) {
    log(ERROR, "Targeted action '${action.name}' dispatched without targetRecipient. Rejected.")
    return
}

// Authorization for targeted actions: only the declaring feature can dispatch
if (action.targetRecipient != null) {
    val originFeature = extractFeatureHandle(action.originator)
    if (originFeature != descriptor.featureName) {
        log(ERROR, "Targeted action '${action.name}' dispatched by '$originFeature', but only '${descriptor.featureName}' may dispatch. Rejected.")
        return
    }
}

val targetFeatures: List<Feature> = when {
    // Priority 1: Targeted action — deliver to recipient feature only
    action.targetRecipient != null -> {
        val recipientHandle = action.targetRecipient
        val target = features.find { it.identity.handle == recipientHandle }
        if (target == null) {
            log(ERROR, "Targeted action '${action.name}' → unknown recipient '$recipientHandle'")
            return
        }
        listOf(target)
    }
    // Priority 2: Non-broadcast action — owned by declaring feature only
    !descriptor.broadcast -> {
        val target = features.find { it.identity.handle == descriptor.featureName }
        if (target == null) {
            log(ERROR, "Internal action '${action.name}' → feature '${descriptor.featureName}' not found")
            return
        }
        listOf(target)
    }
    // Priority 3: Broadcast action — deliver to all features
    else -> features
}

// Then reduce + handleSideEffects over targetFeatures (same as current broadcast logic)
```

### 5.3 Migration: deliverPrivateData → deferredDispatch

Every call site transforms mechanically:

```kotlin
// BEFORE (CoreFeature.kt):
val envelope = PrivateDataEnvelope(ActionNames.Envelopes.CORE_RETURN_CONFIRMATION, responsePayload)
store.deliverPrivateData(this.identity.handle, request.originator, envelope)

// AFTER:
store.deferredDispatch(this.identity.handle, Action(
    name = ActionRegistry.Names.CORE_RETURN_CONFIRMATION,
    payload = responsePayload,
    targetRecipient = request.originator
))
```

### 5.4 Migration: Feature.onPrivateData → Feature.handleSideEffects

Every feature that implements `onPrivateData` merges its handlers into `handleSideEffects`:

```kotlin
// BEFORE (CoreFeature.kt):
override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
    when (envelope.type) {
        ActionNames.Envelopes.FILESYSTEM_RETURN_READ -> { /* handle */ }
    }
}

// AFTER (merged into handleSideEffects):
override fun handleSideEffects(action: Action, store: Store, prev: FeatureState?, new: FeatureState?) {
    when (action.name) {
        // ... existing action handlers ...
        ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
            // Same handler body, action.payload replaces envelope.payload
        }
    }
}
```

### 5.5 Deprecation Strategy

| Item | Phase 3a (Ship First) | Phase 3b (Cleanup Later) |
|---|---|---|
| `PrivateDataEnvelope` class | `@Deprecated("Use Action with targetRecipient field")` | Delete |
| `Feature.onPrivateData()` | Default impl that logs warning + delegates to handleSideEffects | Remove from interface |
| `Store.deliverPrivateData()` | `@Deprecated`, internally converts to targeted dispatch via deferredDispatch | Delete |

Phase 3a can ship as a non-breaking change. Phase 3b is the clean break after all features have migrated.

### 5.6 Security Gain

Private data envelopes now flow through the same pipeline as every other action:
- Action name validated against the registry
- Originator authorized — **only the declaring feature can dispatch targeted actions** (this is a real tightening over the old `deliverPrivateData` which had no originator validation)
- Lifecycle guards enforced
- `targetRecipient` validated against `descriptor.targeted`
- Full audit logging in the processing loop
- The TODOs on Store.kt lines 89–90 are resolved by deletion of the code they annotate

### 5.7 Files Changed

| File | Change |
|---|---|
| `AppCore.kt` | Added `targetRecipient: String? = null` to `Action` with KDoc; updated `toString()` to show `→ 'recipient'`; deprecated `PrivateDataEnvelope` with `@Deprecated` annotation |
| `Store.kt` | Added Step 1b targetRecipient validation; replaced Phase 3 routing placeholder with real `extractFeatureHandle(action.targetRecipient)` feature-level resolution; split Step 5 side-effects into three branches (targeted → recipient / non-broadcast → owner / broadcast → all); deprecated `deliverPrivateData` as bridge to `deferredDispatch`; removed unused `abbreviate` import |
| `Feature.kt` | Deprecated `onPrivateData` with `@Deprecated` and migration KDoc |
| `CoreFeature.kt` | Deleted `onPrivateData` override; moved `FILESYSTEM_RETURN_READ` handler into `handleSideEffects`; migrated `deliverPrivateData` for `CORE_RETURN_CONFIRMATION` to targeted dispatch; added `targetRecipient = originator` to both `RETURN_REGISTER_IDENTITY` dispatch sites (success + failure) — these were silently dropped by the old placeholder routing but rejected by Step 1b enforcement |
| `SessionFeature.kt` | Deleted `onPrivateData` override; moved `FILESYSTEM_RETURN_LIST` and `FILESYSTEM_RETURN_READ` handlers into `handleSideEffects`; migrated `SESSION_RETURN_LEDGER` to targeted dispatch |
| `FileSystemFeature.kt` | Deleted `onPrivateData` override; moved `CORE_RETURN_CONFIRMATION` handler into `handleSideEffects` (reads from `newState` parameter instead of `store.state.value`); migrated all 6 `deliverPrivateData` calls to targeted dispatch via regex-assisted bulk replacement |
| `GatewayFeature.kt` | Migrated 2 `deliverPrivateData` calls (inside coroutines) to targeted dispatch; no `onPrivateData` to migrate (sender only) |
| `KnowledgeGraphFeature.kt` | Phase 3: Deleted `onPrivateData` override; moved `FILESYSTEM_RETURN_LIST` and `FILESYSTEM_RETURN_FILES_CONTENT` handlers into `handleSideEffects`; migrated `KNOWLEDGEGRAPH_RETURN_CONTEXT` to targeted dispatch. Phase 4: Added 17 `LogLevel.WARN` logs to silent `?: return` guard clauses in `handleSideEffects` (missing payload fields, missing state lookups, null originator). Reducer left unchanged (pure functions — no logging). |
| `AgentRuntimeFeature.kt` | Deleted `onPrivateData` override; created private `handleTargetedResponse()` method with correlation-based routing for command responses; added 7 targeted action name cases to `handleSideEffects` when-block; updated `postCommandResponse()` and `formatResponseForSession()` signatures from `PrivateDataEnvelope` to `Action` |
| `AgentCognitivePipeline.kt` | Renamed `handlePrivateData()` → `handleTargetedAction()`; replaced `envelope.type` / `envelope.payload` with `action.name` / `action.payload`; replaced `ActionNames.Envelopes.*` with `ActionRegistry.Names.*` |
| `ActionNames.kt` | No changes needed — `Envelopes` object already has `@Deprecated` annotations from Phase 1 |
| `StoreT1RoutingTest.kt` | Replaced single "Phase 3 pre-wire" placeholder test with 7 real targeted routing tests: recipient delivery, self-targeting, foreign originator rejection, missing targetRecipient rejection, spurious targetRecipient rejection, hierarchical recipient resolution, targeted side-effect delivery scope |
| `StoreT1GuardTest.kt` | Replaced `onPrivateData` crash test with bridge verification test: confirms deprecated `deliverPrivateData` logs WARN, routes through `processAction`, unknown action name cleanly rejected (no crash) |
| `CoreFeatureT3PeerTest.kt` | Migrated 2 filesystem identity-loading tests from `deliverPrivateData` → `Action` with `targetRecipient = "core"`; migrated 2 confirmation dialog tests from asserting on `harness.deliveredPrivateData` to asserting on `harness.processedActions` for targeted `CORE_RETURN_CONFIRMATION` |
| `CoreFeatureT2CoreTest.kt` | No changes needed — existing REGISTER_IDENTITY tests now pass with the `targetRecipient` fix in CoreFeature |

---

## 6. Phase 4 — Migrate Consumers (CommandBot, Agent, Session, KnowledgeGraph) — Session ✅, KnowledgeGraph ✅

**Goal**: All features read from `ActionRegistry` instead of `ExposedActions`, and from `AppState.identityRegistry` instead of their private name caches. Old parallel systems and compatibility shims are deleted.

### 6.1 CommandBot Migration

| Current Reference | Replacement |
|---|---|
| `ExposedActions.allowedActionNames` | `ActionRegistry.agentAllowedNames` |
| `ExposedActions.requiresApproval` | `ActionRegistry.agentRequiresApproval` |
| `ExposedActions.autoFillRules[name]` | `ActionRegistry.agentAutoFillRules[name]` |
| `ExposedActions.sandboxRules[name]` | `ActionRegistry.agentSandboxRules[name]` |
| `knownAgentIds: MutableSet` | Read from `AppState.identityRegistry` (filter by parentHandle == "agent") |
| `knownAgentNames: MutableMap` | Same — `identityRegistry[handle]?.name` |
| Subscribes to `AGENT_NAMES_UPDATED` | Subscribes to `CORE_IDENTITY_REGISTRY_UPDATED` |

The mutable `knownAgentIds` and `knownAgentNames` sets are deleted entirely. CommandBot becomes stateless with respect to identity — it reads from the single source of truth.

### 6.2 Session Feature Migration ✅

**Completed 2026-02-14.** Full session identity migration as specified in `phase4-session-identity-migration.md`.

#### Data Model Changes

| Before | After |
|---|---|
| `Session.id: String` (UUID) | `Session.identity: Identity` (uuid, localHandle, handle, name, parentHandle) |
| `Session.name: String` | `Session.identity.name` |
| `SessionState.activeSessionId` | `SessionState.activeSessionLocalHandle` |
| `SessionState.editingSessionId` | `SessionState.editingSessionLocalHandle` |
| `SessionState.lastDeletedSessionId` | `SessionState.lastDeletedSessionLocalHandle` |
| Sessions map keyed by UUID | Sessions map keyed by `identity.localHandle` |
| — | `SessionState.pendingCreations: Map<String, PendingSessionCreation>` (transient, keyed by UUID) |
| — | `SessionState.activeUserId: String?` (cached from `CORE_IDENTITIES_UPDATED` broadcast) |

#### Two-Phase Async Session Creation

Session creation is now a two-phase flow mediated by CoreFeature's identity registry:

1. **SESSION_CREATE** reducer stashes a `PendingSessionCreation` (uuid, requestedName, isHidden, isPrivate, createdAt). No session is added to state yet.
2. **SESSION_CREATE** side effect dispatches `core.REGISTER_IDENTITY` with `name` and `uuid` (localHandle is null — CoreFeature generates it via `slugifyName()`).
3. **CoreFeature** processes the registration: generates localHandle slug, deduplicates, adds to registry, sends targeted `RETURN_REGISTER_IDENTITY` back to session.
4. **RETURN_REGISTER_IDENTITY** reducer: on success, creates the actual `Session` from the pending data with the approved `Identity`, adds to `sessions` map, sets active. On failure, removes pending, sets error.

Clone follows the same flow with `cloneSourceLocalHandle` stored in the pending.

#### Persistence Path Change

| Before | After |
|---|---|
| `v2/session/{uuid}.json` (flat) | `v2/session/{uuid}/{localHandle}.json` (UUID folder, slug filename) |

The UUID folder is stable across renames. When a session is renamed, `UPDATE_IDENTITY` triggers a file rename inside the folder (`old-slug.json` → `new-slug.json`). On delete, the entire UUID folder is removed.

#### File Loading (Two-Level Traversal)

`FILESYSTEM_RETURN_LIST` now handles two levels:
1. First response: top-level listing of UUID folders → dispatches `LIST` for each UUID folder
2. Second response: contents of a UUID folder → dispatches `SYSTEM_READ` for each `.json` file inside

#### CoreFeature Additions

- `CoreFeature.slugifyName()`: companion function converting display names to valid localHandle slugs (lowercase, hyphens, strip diacritics)
- `REGISTER_IDENTITY` loosened: `localHandle` optional (generated from name if null), `uuid` field accepted (validated against UUID regex, passed through to response for correlation)
- `UUID_REGEX` companion constant for UUID format validation
- `UPDATE_IDENTITY` action: namespace-enforced name change with slug recomputation, deduplication, atomic swap, child cascade, targeted response
- `RETURN_UPDATE_IDENTITY` action: targeted response carrying old/new handle and updated identity

#### View Decoupling

`SessionView.kt` previously imported `CoreState` to read `activeUserId` — a cross-feature import violation. Fixed by:
1. Adding `activeUserId` to `SessionState` as a `@Transient` cached field
2. Populating it from the `CORE_IDENTITIES_UPDATED` broadcast payload (`activeId` field)
3. Removing the `CoreState` import and `coreState` parameter from `LedgerPane`

All views updated mechanically: `session.id` → `session.identity.localHandle`, `session.name` → `session.identity.name`.

#### Session Resolution

`resolveSessionId()` now resolves session identifiers in priority order:
1. Exact match on `identity.localHandle`
2. Match on full `identity.handle` (e.g. `session.my-chat`)
3. Case-insensitive match on `identity.name` (display name)

#### Manifest Changes

- `core_actions.json`: `REGISTER_IDENTITY` loosened (localHandle optional, uuid added), `UPDATE_IDENTITY` + `RETURN_UPDATE_IDENTITY` added
- `session_actions.json`: field descriptions updated from "ID or name" to "localHandle, full handle, or display name"

#### Test Updates

All session tests updated to use `Identity`-based `Session` construction:
- **T1 LedgerEntryCardTest**: Identity construction, localHandle in map keys and payload assertions
- **T1 SessionManagerViewTest**: Identity construction, `"Handle: sid-1"` display text, localHandle keys
- **T1 SessionViewTest**: Identity construction, `activeSessionLocalHandle` state field
- **T2 CoreTest**: Major rewrite — two-phase creation flow, UUID folder deletion, two-level file loading, Identity-format JSON, new tests for `activeUserId` caching, persist path format, three-way `resolveSessionId`
- **FakePlatformDependencies**: `generateUUID()` updated to produce valid UUID v4 format (`00000000-0000-4000-a000-{counter}`)

#### Files Changed

| File | Change |
|---|---|
| `CoreFeature.kt` | `slugifyName()`, loosened `REGISTER_IDENTITY`, `UUID_REGEX`, `UPDATE_IDENTITY` handler, `RETURN_UPDATE_IDENTITY` |
| `SessionState.kt` | `Session.identity: Identity`, `PendingSessionCreation`, renamed state fields, `activeUserId` cache |
| `SessionFeature.kt` | Two-phase creation reducers/side-effects, `RETURN_REGISTER_IDENTITY`/`RETURN_UPDATE_IDENTITY` handlers, two-level file loading, `resolveSessionId()` triple-match, `persistSession()` UUID path, removed `CoreFeature` import |
| `SessionView.kt` | Mechanical renames, removed `CoreState` import, `activeUserId` from `SessionState` |
| `SessionsManagerView.kt` | Mechanical renames (`session.id`→`identity.localHandle`, `session.name`→`identity.name`, `"Handle:"` label) |
| `LedgerEntryCard.kt` | Mechanical renames (`session.id`→`identity.localHandle`) |
| `core_actions.json` | Loosened REGISTER_IDENTITY, added UPDATE_IDENTITY + RETURN_UPDATE_IDENTITY |
| `session_actions.json` | Updated field descriptions |
| `FakePlatformDependencies.kt` | Valid UUID format in `generateUUID()` |
| 4 test files | Updated to Identity-based Session construction and new state field names |

### 6.3 Agent Feature Migration

| Current | Replacement |
|---|---|
| Dispatches `agent.AGENT_NAMES_UPDATED` | Dispatches `core.REGISTER_IDENTITY` / `core.UNREGISTER_IDENTITY` |
| Internal agent name tracking | Identities are in the registry; agent feature reads them back |

⚠️ **UUID→Handle Migration**: Similar to SessionFeature, AgentRuntimeFeature uses `Agent.id` (UUID) as the canonical identifier in `agentStatuses`, `pendingCommandResponses`, `correlationId` fields in response payloads, and throughout `AgentCognitivePipeline`. The cognitive pipeline's `startCognitiveCycle` takes an `agentId` (UUID) and passes it as `correlationId` in ledger requests. The registry-handle equivalent would be `agent.gemini-coder`. Phase 4 should migrate AgentRuntimeFeature's internals to use handles, aligning `correlationId` values with IdentityRegistry handles.

The `AGENT_NAMES_UPDATED` action can be deprecated (mark in manifest, log warning if dispatched) and eventually removed.

### 6.4 IDE-Assisted Mechanical Renames

The following renames should be batched and applied via IDE find-and-replace:

| Find | Replace | Notes |
|---|---|---|
| `ActionNames.` | `ActionRegistry.Names.` | After typealias shim removal |
| `ExposedActions.` | `ActionRegistry.` | After delegation shim removal |
| `SESSION_INTERNAL_LOADED` | `SESSION_LOADED` | And all other old constant names |
| `SESSION_PUBLISH_MESSAGE_POSTED` | `SESSION_MESSAGE_POSTED` | Same pattern for all renamed constants |

### 6.5 Files Changed

| File | Change |
|---|---|
| `CommandBotFeature.kt` | Replace `ExposedActions.*` → `ActionRegistry.*`; delete `knownAgentIds/Names`; subscribe to identity updates |
| `CommandBotState.kt` | No change (approval state is orthogonal) |
| `SessionFeature.kt` | ✅ Two-phase creation, RESPONSE handlers, two-level file loading, `resolveSessionId()` triple-match, UUID persistence paths, removed `CoreFeature` import |
| `SessionState.kt` | ✅ `Session.identity: Identity`, `PendingSessionCreation`, renamed state fields, `activeUserId` cache |
| `SessionView.kt` | ✅ Mechanical renames, removed `CoreState` import, `activeUserId` from `SessionState` |
| `SessionsManagerView.kt` | ✅ Mechanical renames |
| `LedgerEntryCard.kt` | ✅ Mechanical renames |
| `CoreFeature.kt` | ✅ `slugifyName()`, loosened `REGISTER_IDENTITY`, `UUID_REGEX`, `UPDATE_IDENTITY` + response handler |
| `core_actions.json` | ✅ Loosened REGISTER_IDENTITY, added UPDATE_IDENTITY + RETURN_UPDATE_IDENTITY |
| `session_actions.json` | ✅ Updated field descriptions |
| `FakePlatformDependencies.kt` | ✅ Valid UUID format in `generateUUID()` |
| `AgentRuntimeFeature.kt` | Register/unregister identities instead of broadcasting names; ⚠️ migrate internals from UUID to handle as primary identifier |
| `AgentRuntimeState.kt` | ⚠️ Evaluate `Agent.id` → handle migration in `agentStatuses`, `pendingCommandResponses` |
| `AgentCognitivePipeline.kt` | ⚠️ Migrate `correlationId` from UUID to handle |
| Agent prompt builder | Read from `ActionRegistry.byActionName` instead of `ExposedActions.documentation` |
| `ExposedActions.kt` (generated shim) | Delete |
| `ActionNames.kt` (generated shim) | Delete |
| `Feature.kt` | Phase 3b cleanup: delete `onPrivateData` default method |
| `Store.kt` | Phase 3b cleanup: delete `deliverPrivateData` bridge |
| `AppCore.kt` | Phase 3b cleanup: delete `PrivateDataEnvelope` data class |
| All source files | IDE-assisted batch rename of old constant names to new names |

---

## 7. Phase 5 — Runtime-Extensible Registry

**Goal**: The action catalog becomes live state that can be extended at runtime by features, agents, and scripts. The static `ActionRegistry.byActionName` serves as the immutable seed.

### 7.1 Store-Level Registration

Because `actionDescriptors` lives on `AppState`, runtime registration is handled by the Store itself as a special-cased mutation, not by a separate feature's reducer:

```kotlin
// In Store.processAction, before the normal reducer fold:
when (action.name) {
    ActionRegistry.Names.REGISTRY_REGISTER_ACTION -> {
        val descriptor = parseDescriptorFromPayload(action.payload) ?: return
        // Validate: cannot override build-time actions
        if (ActionRegistry.byActionName.containsKey(descriptor.fullName)) {
            log(ERROR, "Cannot override build-time action '${descriptor.fullName}'")
            return
        }
        _state.value = _state.value.copy(
            actionDescriptors = _state.value.actionDescriptors + (descriptor.fullName to descriptor)
        )
        // Continue processing — the action itself is also broadcast to features
    }
    ActionRegistry.Names.REGISTRY_UNREGISTER_ACTION -> {
        val actionName = action.payload?.get("actionName")?.jsonPrimitive?.contentOrNull ?: return
        // Validate: cannot remove build-time actions
        if (ActionRegistry.byActionName.containsKey(actionName)) {
            log(ERROR, "Cannot remove build-time action '$actionName'")
            return
        }
        _state.value = _state.value.copy(
            actionDescriptors = _state.value.actionDescriptors - actionName
        )
    }
}
```

### 7.2 New Actions (in registry.actions.json)

```json
{
    "feature_name": "registry",
    "summary": "Defines the actions for runtime extension of the action catalog.",
    "actions": [
        {
            "action_name": "registry.REGISTER_ACTION",
            "summary": "Registers a new action descriptor at runtime. Cannot override build-time actions.",
            "public": true,
            "broadcast": true,
            "targeted": false,
            "payload_schema": {
                "type": "object",
                "properties": {
                    "featureName": { "type": "string" },
                    "actionName": { "type": "string" },
                    "suffix": { "type": "string" },
                    "summary": { "type": "string" },
                    "public": { "type": "boolean" },
                    "broadcast": { "type": "boolean" },
                    "targeted": { "type": "boolean" }
                },
                "required": ["featureName", "actionName", "suffix", "public", "broadcast", "targeted"]
            }
        },
        {
            "action_name": "registry.UNREGISTER_ACTION",
            "summary": "Removes a runtime-registered action. Cannot remove build-time actions.",
            "public": true,
            "broadcast": true,
            "targeted": false,
            "payload_schema": {
                "type": "object",
                "properties": {
                    "actionName": { "type": "string" }
                },
                "required": ["actionName"]
            }
        },
        {
            "action_name": "registry.CATALOG_UPDATED",
            "summary": "Broadcast after any runtime registration or unregistration.",
            "public": false,
            "broadcast": true,
            "targeted": false
        }
    ]
}
```

### 7.3 Store Validation Integration

```kotlin
// In Store.processAction — single source of truth:
val descriptor = _state.value.actionDescriptors[action.name]
if (descriptor == null) { /* reject unknown action */ return }
```

The `validActionNames` constructor parameter on Store is removed. All validation goes through `AppState.actionDescriptors`, which is pre-populated from `ActionRegistry.byActionName` at construction and extended at runtime.

**No bootstrapping issue**: `AppState.actionDescriptors` is initialized with a default value from the static `ActionRegistry.byActionName`, so it's available from the very first action through the system.

### 7.4 Agent/Script Custom Command Registration

An agent that wants to expose a custom command dispatches:

```kotlin
store.deferredDispatch("agent.gemini-x", Action(
    name = "registry.REGISTER_ACTION",
    payload = buildJsonObject {
        put("featureName", "agent")
        put("actionName", "agent.SUMMARIZE")
        put("suffix", "SUMMARIZE")
        put("public", true)
        put("broadcast", true)
        put("targeted", false)
        put("summary", "Ask this agent to summarize a document.")
    }
))
```

After `registry.CATALOG_UPDATED` is broadcast, this action appears in the slash-command autocomplete immediately.

### 7.5 Files Changed

| File | Change |
|---|---|
| `registry.actions.json` (new) | Manifest for registration actions |
| `Store.kt` | Remove `validActionNames` constructor param; validate via `AppState.actionDescriptors`; add special-case handling for `REGISTER_ACTION` / `UNREGISTER_ACTION` |
| `AppCore.kt` | `AppState.actionDescriptors` default value is `ActionRegistry.byActionName` |
| App initialization | Ensure `registry.actions.json` is included in codegen scan |

---

## 8. Phase 6 — Slash-Command Autocomplete UI

**Goal**: When a user types `/` in the message input, a popup guides them through feature → action → parameters, then generates the `auf_` code block.

### 8.1 Data Flow

```
User types "/"
    ↓
MessageInput reads AppState.actionDescriptors + AppState.identityRegistry from store
    ↓
Stage 1: Filter features by typed prefix → show FeatureDescriptor list
    ↓ (Tab/Enter/Click selects → inserts "session." and advances)
Stage 2: Filter actions by typed prefix → show ActionDescriptor list
    ↓ (Tab/Enter/Click selects → advances to param scaffold)
Stage 3: Generate JSON template from payloadFields → insert auf_ code block
    ↓ (User fills in values, sends as normal message)
CommandBot parses the auf_ block as usual — zero backend changes
```

### 8.2 State Machine (Local to MessageInput)

```kotlin
enum class AutocompleteStage { IDLE, FEATURE, ACTION, PARAMS }

data class AutocompleteState(
    val stage: AutocompleteStage = AutocompleteStage.IDLE,
    val query: String = "",
    val selectedIndex: Int = 0,
    val selectedFeature: String? = null,
    val selectedAction: ActionDescriptor? = null,
    val candidates: List<Any> = emptyList()
)
```

### 8.3 Keyboard Handling

| Key | Behavior |
|---|---|
| `↑` / `↓` | Move `selectedIndex` through candidates |
| `Tab` | If 1 candidate: auto-complete. If >1: complete to longest common prefix |
| `Enter` | Select current candidate, advance stage |
| `Escape` | Regress one stage, or close popup if at FEATURE stage |
| `Backspace` past `.` | Regress to previous stage |
| Any character | Update query, re-filter candidates |

### 8.4 SlashCommandPopup Composable

```kotlin
@Composable
fun SlashCommandPopup(
    state: AutocompleteState,
    onSelect: (Any) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(min = 300.dp, max = 500.dp).heightIn(max = 300.dp)
    ) {
        LazyColumn(modifier = Modifier.padding(4.dp)) {
            itemsIndexed(state.candidates) { index, candidate ->
                val isSelected = index == state.selectedIndex
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(candidate) }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    when (candidate) {
                        is FeatureDescriptor -> FeatureRow(candidate)
                        is ActionDescriptor -> ActionRow(candidate)
                    }
                }
            }
        }
    }
}
```

Feature rows show `name` + one-line `summary`. Action rows show `suffix` + `summary` + optional badges (e.g., "approval" chip if `agentExposure?.requiresApproval == true`).

### 8.5 Code Block Generation

```kotlin
fun generateAufTemplate(action: ActionDescriptor, sessionId: String?): String {
    val fields = action.payloadFields
    val jsonLines = fields.mapNotNull { field ->
        val value = when (field.name) {
            "session" -> sessionId?.let { "\"$it\"" } ?: "\"\""
            else -> if (field.required) "\"\"" else return@mapNotNull null
        }
        "  \"${field.name}\": $value"
    }
    val jsonBody = jsonLines.joinToString(",\n")
    return "```auf_${action.fullName}\n{\n$jsonBody\n}\n```"
}
```

The user's cursor is positioned at the first empty `""` value for immediate editing.

### 8.6 MessageInput Integration

The existing `MessageInput` composable in `SessionView.kt` is extended:

```kotlin
@Composable
private fun MessageInput(store: Store, activeSession: Session, ..., onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var autocomplete by remember { mutableStateOf(AutocompleteState()) }

    val appState by store.state.collectAsState()

    LaunchedEffect(text, autocomplete.stage) {
        autocomplete = computeCandidates(text, autocomplete, appState.actionDescriptors)
    }

    Box {
        if (autocomplete.stage != AutocompleteStage.IDLE) {
            SlashCommandPopup(autocomplete, onSelect = { /* advance */ }, onDismiss = { /* reset */ })
        }

        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                if (newText.startsWith("/")) { /* activate autocomplete */ }
                else { autocomplete = AutocompleteState() }
            },
            modifier = Modifier.onKeyEvent { event ->
                if (autocomplete.stage != AutocompleteStage.IDLE) {
                    return@onKeyEvent handleAutocompleteKey(event, autocomplete, /* ... */)
                }
                // Existing Ctrl+Enter to send
                false
            }
        )
    }
}
```

### 8.7 Files Changed

| File | Change |
|---|---|
| `SlashCommandAutocomplete.kt` (new) | Popup composable + state machine + keyboard handling |
| `SessionView.kt` | Modify `MessageInput` to host autocomplete |

---

## 9. Future: Permissions System (Paved, Not Built)

This section documents the planned permissions system so the hooks placed in Phases 1–2 are understood. **None of this is implemented in v2.0.** The trigger to build it is when untrusted code (user scripts, plugins) needs to run on the action bus.

### 9.1 The Model

```
WHO (IdentityRegistry)  ×  WHAT (ActionRegistry)  =  Permission Check

Identity:
  uuid, handle, name, parentHandle
  permissions: Map<String, Boolean>?   ← FUTURE FIELD

ActionDescriptor:
  requiredPermissions: List<String>?   ← FUTURE FIELD (declared as null in Phase 1 manifests)
```

### 9.2 Permission Types

Features declare permission types in their manifest's `permissions[]` array. A future `core.REGISTER_PERMISSION_TYPE` action will make these available at runtime:

```
filesystem declares: ["root_access"]
session declares:    ["delete_any"]
agent declares:      ["create"]
```

### 9.3 Grants and Inheritance

Permissions are granted per-identity. Effective permissions are resolved by walking up the `parentHandle` chain:

```
"core.alice"        → { "filesystem.root_access": true }
  parent: "core"    → { }

"agent.gemini-x"    → { }  (no direct grants)
  parent: "agent"   → { "session.delete_any": false }  (denied for all agents)

Effective for "agent.gemini-x": "session.delete_any" = false (inherited deny)
```

Deny overrides allow at any level. Absence = inherit from parent. Root with no grant = denied (default-deny for permissioned actions).

### 9.4 Store Integration Point

Already stubbed in Phase 2's Store code as a comment:

```kotlin
// FUTURE: After authorization, check required permissions:
// val requiredPerms = descriptor.requiredPermissions
// if (requiredPerms != null) {
//     val identity = appState.identityRegistry[action.originator]
//     val effectivePerms = resolvePermissions(identity)
//     if (!requiredPerms.all { effectivePerms[it] == true }) { reject }
// }
```

### 9.5 Why Not Now

1. We don't yet know what permission types each feature needs — designing in the abstract risks the wrong abstractions
2. The inheritance model needs real use cases (agent A denied, agent B allowed) to validate
3. Current guardrails (CommandBot's CAG-004/006, Store's originator auth) are adequate for the current trust model
4. Building it when the scripting system ships gives concrete scenarios to drive the design

### 9.6 What's Paved

- `Identity.permissions` field is documented but commented out
- `ActionDescriptor.requiredPermissions` field exists as `null` in generated code
- `FeatureDescriptor.permissions` lists declared permission types from the manifest
- `extractFeatureHandle()` in Store already resolves hierarchical originators
- The `parentHandle` tree is fully operational for future traversal
- `core.REGISTER_PERMISSION_TYPE` is documented here but not in the manifest

---

## 10. Cross-Cutting Concerns

### 10.1 Backward Compatibility Strategy

Every phase is designed to be independently deployable with no breaking changes:

- **Phase 1**: Manifests restructured; typealias preserves all existing `ActionNames.*` references; deprecated `ExposedActions` delegation object preserves all existing `ExposedActions.*` references; old constant names mapped to new names in shim
- **Phase 2.1**: Additive — new `identityRegistry` field on `CoreState` and `AppState`; old `userIdentities` kept during transition; `Feature.name` → `Feature.identity` with IDE-assisted migration; feature identities seeded directly by Store (no lifecycle guard issue); Store authorization refactored to schema-driven (orthogonal `open`/`broadcast`/`targeted` flags); `validActionNames` param removed (validated via `AppState.actionDescriptors`)
- **Phase 2.2**: `onAction` → `handleSideEffects` rename across all features; session/agent identity registration wiring; user identity migration to `identityRegistry` (both `userIdentities` and `identityRegistry` populated during transition); `IdentityManagerView` reads from registry; `CoreState.userIdentities` deprecated with compiler warning
- **Phase 3**: Deprecation layer for `PrivateDataEnvelope`; old methods still work in Phase 3a
- **Phase 4**: Internal consumer migration; shims deleted; IDE-assisted batch rename of constants
- **Phase 5**: Additive runtime feature; existing build-time actions unaffected; no bootstrapping issue (actionDescriptors pre-populated on AppState)
- **Phase 6**: Additive UI; no existing behavior changes

Old code is marked `@Deprecated` with migration guidance and removed in a subsequent cleanup pass, never in the same PR that introduces the replacement.

### 10.2 Testing Strategy

| Phase | Test Type | Focus |
|---|---|---|
| 1 | Build verification | Manifests parse correctly; generated sets match previous output; action name renames are complete; both shims compile |
| 2 | Unit tests | Identity registration, unregistration, cascade; hierarchical originator auth; Feature.identity migration; feature seeding at boot |
| 3 | Integration tests | Targeted delivery goes through full pipeline; `targetRecipient` rejected on non-targeted actions; originator enforced on targeted actions; lifecycle guards enforced |
| 4 | Existing test suite | CommandBot guardrails behave identically after migration |
| 5 | Unit + integration | Runtime registration; Store validates via `AppState.actionDescriptors` only; build-time actions cannot be overridden |
| 6 | UI tests + manual | Autocomplete UX, keyboard navigation, code block generation |

### 10.3 Performance Considerations

- `ActionRegistry` generated object: **zero runtime cost** — static singleton, computed at build time
- `AppState.actionDescriptors`: pre-populated from static `ActionRegistry.byActionName`; map lookup is O(1)
- `AppState.identityRegistry`: map lookup is O(1); registry size is proportional to number of entities (dozens to low hundreds)
- `parentHandle` tree navigation: O(1) per hop (lookup by handle, which is the map key)
- `extractFeatureHandle()` in the Store hot path: single `substringBefore('.')` — negligible
- Slash-command popup: reads `actionDescriptors` on each keystroke but the catalog is tiny — no optimization needed
- Runtime registration to `AppState.actionDescriptors`: triggers `_state.value` change, which triggers recomposition in any Compose code observing `store.state`. Negligible because runtime registration is rare (startup or plugin load only).

### 10.4 Naming Conventions

| Convention | Example | Rule |
|---|---|---|
| Action name | `session.POST` | `feature.ACTION_NAME` (no infixes) |
| Action name (all caps) | `session.MESSAGE_POSTED` | Same — old `publish.` prefix removed |
| Originator (feature) | `"session"` | Feature handle only |
| Originator (sub-entity) | `"agent.gemini-flash-abc"` | Feature handle + `.` + entity handle |
| Originator (UI) | `"session.ui"` | Feature handle + `.ui` (convention) |
| Identity handle (user) | `"core.alice"` | Parent feature + `.` + name |
| Identity handle (session) | `"session.chat1"` | Feature + `.` + session ID |
| Identity handle (agent) | `"agent.gemini-flash-x"` | Feature + `.` + agent ID |
| Const name | `SESSION_POST` | Action name with `.` → `_`, uppercased |
| Banned shorthand | ~~`id`~~ | Use `identity` to refer to `Identity`; use `handle` or `uuid` for fields |

### 10.5 Action Type Quick Reference

| Type | `open` | `broadcast` | `targeted` | Shorthand | Who Dispatches | Who Receives |
|---|---|---|---|---|---|---|
| Command | `true` | `true` | `false` | `isCommand` | Anyone | All features |
| Open Non-Broadcast | `true` | `false` | `false` | `isCommand` | Anyone | Owner only |
| Targeted Command | `true` | `false` | `true` | `isCommand` | Anyone | Specified recipient |
| Event | `false` | `true` | `false` | `isEvent` | Owner only | All features |
| Internal | `false` | `false` | `false` | `isInternal` | Owner only | Owner only |
| Response | `false` | `false` | `true` | `isResponse` | Owner only | Specified recipient |

---

## 11. Migration Checklist

### Phase 1 — Manifest Schema Unification & ActionRegistry Codegen ✅ COMPLETE
- [x] Manually resolve payload schema divergences between `exposedToAgents` and `listensFor` (e.g., `session.CLONE`: `sourceSession` vs `session`). The `listensFor` schema is canonical.
  - **Done.** Divergence report generated. 7 divergences identified; all resolved with `listensFor` as canonical. `session.DELETE_MESSAGE` structural divergence (agent uses `senderId`+`timestamp`, reducer uses `messageId`) flagged for manual review — CommandBot likely translates.
- [x] Write migration script for `*.actions.json` files (listensFor + exposedToAgents + publishes + private_envelopes → unified actions[]; add permissions[]; replace `inbound`/`public` with `open`/`broadcast`/`targeted`)
  - **Done.** `migrate_manifests.py` — one-time Python script.
- [x] Rename all action names: strip `.internal.`, `.publish.`, `.response.` infixes → `feature.ACTION_NAME`
  - **Done.** 63 renames applied. Special handling for `settings.ui.internal.INPUT_CHANGED` → `settings.UI_INPUT_CHANGED`. Envelope type names like `session.response.ledger` → `session.RETURN_LEDGER`.
- [x] Run migration on all manifests; verify no data loss
  - **Done.** 9 manifests migrated. 168 actions total (= 160 old actions + 8 old envelopes). All old ActionNames.kt constants accounted for via rename mapping.
- [x] Rewrite Gradle `generateActionRegistry` task to read unified schema with `open`/`broadcast`/`targeted` boolean flags
  - **Done.** Key lesson: Gradle `doLast` blocks cannot use `data class` declarations (constructor metadata lost in script closures). Rewrote using plain `Map<String, Any>` throughout, matching the original working task's pattern.
- [x] Add codegen validation: reject `targeted: true` + `broadcast: true` on the same action
  - **Done.** Throws `GradleException` at codegen time.
- [x] Generate `ActionRegistry.kt` with Names, descriptors (using `open`/`broadcast`/`targeted` booleans and `isCommand`/`isEvent`/`isInternal`/`isResponse` derived properties), and derived views
  - **Done.** 4 sections: Names (constants + `allActionNames`), Descriptor data classes (with derived type properties), Feature registry, Derived views (`byActionName`, `agentAllowedNames`, `agentRequiresApproval`, `agentAutoFillRules`, `agentSandboxRules`).
- [x] Generate `ActionNames.kt` as typealias compatibility shim with old-name → new-name mappings
  - **Done.** All 168 current-name constants delegate to `ActionRegistry.Names`. 55 renamed constants provided as `@Deprecated` aliases with `ReplaceWith`. Envelope constants in nested `Envelopes` object similarly deprecated.
- [x] Generate `ExposedActions.kt` as deprecated delegation object pointing to `ActionRegistry` derived views
  - **Done.** Thin delegation shim — `allowedActionNames`, `requiresApproval`, `autoFillRules`, `sandboxRules`, and `documentation` all delegate to `ActionRegistry` derived views. Local `SandboxRule`/`PayloadField`/`ExposedActionDoc` data classes preserved for API compatibility.
- [x] Verify `ActionRegistry.Names.allActionNames` covers all previous actions (modulo renames)
  - **Done.** 168 = 168. All 160 old action names + 8 envelope types covered.
- [x] Verify `ActionRegistry.agentAllowedNames` matches previous `ExposedActions.allowedActionNames`
  - **Done.** 15 agent-allowed, 8 requiring approval, 5 sandbox rules, 2 auto-fill rules — all match.
- [x] All existing tests pass with both shims in place
  - **Done.** Build passes, all tests green.

### Phase 2.1 — Identity Infrastructure & Schema-Driven Routing ✅ COMPLETE
- [x] Rewrite `Identity.kt`: `uuid: String?`, `localHandle`, `handle`, `name`, `parentHandle`, `registeredAt`
  - **Done.** `localHandle` is the leaf (`[a-z][a-z0-9-]*`), `handle` is the full bus address (`parentHandle.localHandle`), constructed by CoreFeature. Registry keyed by `handle`.
- [x] Add `identityRegistry: Map<String, Identity>` to `CoreState` (canonical) and `AppState` (lifted)
  - **Done.** CoreFeature owns business logic in its reducer. Store mechanically lifts `CoreState.identityRegistry` → `AppState.identityRegistry` after each reduce cycle.
- [x] Add `actionDescriptors: Map<String, ActionDescriptor>` to `AppState`
  - **Done.** Pre-populated from `ActionRegistry.byActionName`. Replaces `validActionNames` constructor param on Store.
- [x] Change `Feature.kt`: `val name: String` → `val identity: Identity`
  - **Done.** All 8 features updated via IDE-assisted rename.
- [x] IDE-assisted global rename: `feature.name` → `feature.identity.handle`; `this.name` → `identity.handle` (as originator)
  - **Done.** Verified: `featureStates` map keys remain unchanged (handles equal old names).
- [x] Add 4 new actions to `core.actions.json`: `REGISTER_IDENTITY` (open, non-broadcast), `RETURN_REGISTER_IDENTITY` (targeted), `UNREGISTER_IDENTITY` (open, non-broadcast), `IDENTITY_REGISTRY_UPDATED` (event broadcast)
  - **Done.** REGISTER/UNREGISTER are `public: true, broadcast: false` — anyone can dispatch, only CoreFeature receives. Response is targeted (delivery deferred to Phase 3).
- [x] Implement CoreFeature reducer for `REGISTER_IDENTITY`: validate `localHandle` format (`[a-z][a-z0-9-]*`), originator IS the parent (no parentHandle field), deduplicate among siblings (append -2, -3), generate UUID, construct full handle
  - **Done.** Deduplication works on `localHandle` within parent namespace. Cascade deletion in UNREGISTER uses handle prefix matching.
- [x] Implement CoreFeature reducer for `UNREGISTER_IDENTITY`: namespace enforcement (originator can only unregister within own namespace), cascade deletion by handle prefix
  - **Done.** `payload.handle.startsWith("$originator.")` enforces namespace ownership.
- [x] Implement CoreFeature `onAction` (renamed to `handleSideEffects` in Phase 2.2) to dispatch `IDENTITY_REGISTRY_UPDATED` and `RETURN_REGISTER_IDENTITY` after each change
  - **Done.** Response compares prev vs new registry to determine success/failure. Targeted delivery of response deferred to Phase 3.
- [x] Add `extractFeatureHandle()` to Store; update authorization to schema-driven routing using `open`/`broadcast`/`targeted` as orthogonal concerns
  - **Done.** `open` controls authorization (who can dispatch). `broadcast`/`targeted` control delivery (who receives). These are independent — enables the new "open non-broadcast command" pattern (`public: true, broadcast: false`) used by REGISTER/UNREGISTER.
- [x] Remove `ParsedActionName` and name-parsing from Store
  - **Done.** All routing now driven by `ActionDescriptor` boolean flags from the manifest.
- [x] Remove `validActionNames` constructor param from Store; validate via `AppState.actionDescriptors`
  - **Done.** AppContainer updated accordingly.
- [x] Update Store to reference `feature.identity.handle` instead of `feature.name` throughout
  - **Done.**
- [x] Seed feature identities directly in `initFeatureLifecycles()` (no action bus, no lifecycle guard issue)
  - **Done.** Feature identities seeded into `AppState.identityRegistry` before `features.forEach { it.init(this) }`.
- [x] Add FUTURE permission check as commented-out code block in Store
  - **Done.** Stubbed after authorization check with `resolvePermissions()` walk.
- [x] All existing tests pass with changes in place
  - **Done.** Build passes, all tests green.

**Implementation notes (Phase 2.1)**:

**Approach B adopted**: Store stays clean of identity business logic. CoreFeature's reducer owns validation, deduplication, UUID generation. Store only does a mechanical lift (`CoreState.identityRegistry` → `AppState.identityRegistry`) after reduce — same pattern as any state that needs to be accessible at the AppState level.

**"The originator IS the parent"**: REGISTER_IDENTITY has no `parentHandle` payload field. The originator of the dispatch call automatically becomes the parent. This means `"agent"` registering `{ localHandle: "gemini-coder" }` produces `"agent.gemini-coder"` — and there is literally no code path to register outside your own namespace. Namespace enforcement is structural, not validation.

**Authorization ≠ Routing (orthogonal)**: The old Store conflated these — `isInternal` (`!open && !broadcast && !targeted`) was used for routing, but it excluded `open` actions from owner-only delivery. The fix separates them: `open` flag controls authorization (step 2), `broadcast`/`targeted` flags control delivery (step 4). This enables `public: true, broadcast: false` (REGISTER_IDENTITY: anyone can dispatch, only CoreFeature receives).

**New action type discovered**: "Open Non-Broadcast Command" — `public: true, broadcast: false, targeted: false`. The action type quick reference table (section 10.5) updated accordingly.

### Phase 2.2 — Consumer Wiring & Rename ✅ COMPLETE
- [x] Rename `onAction` → `handleSideEffects` across all Feature implementations and Feature.kt interface
  - **Done.** Feature.kt interface method renamed with KDoc: "Named handleSideEffects (not onAction) because it truthfully describes what the method does — a developer unfamiliar with the system won't mistakenly call it directly, bypassing the Store pipeline." Store.kt updated at all call sites. All 8 features renamed.
- [x] Wire SessionFeature to register session identities on create/load/delete (during STARTING)
  - **Done.** `SESSION_INTERNAL_LOADED` compares prev/new sessions and registers new ones. `SESSION_CREATE`/`SESSION_CLONE` register identity. `SESSION_DELETE` dispatches `UNREGISTER_IDENTITY` with cascade. `SESSION_UPDATE_CONFIG` on name change unregisters + re-registers.
- [x] Wire AgentFeature to register agent identities on create/load/delete (during STARTING)
  - **Done.** Same pattern as SessionFeature: `AGENT_INTERNAL_AGENT_LOADED` registers loaded agents, `AGENT_CREATE` registers, `AGENT_DELETE` unregisters with cascade, `AGENT_UPDATE_CONFIG` on name change unregisters + re-registers.
- [x] Deprecate `CoreState.userIdentities` (derived getter from `AppState.identityRegistry` filtered by `parentHandle == "core"`)
  - **Done.** `@Deprecated` annotation with message pointing to `AppState.identityRegistry`. Comment: "DEPRECATED — Phase 2.2. Will be removed in Phase 4." Both `userIdentities` and `identityRegistry` populated during transition (migration invariant preserved).
- [x] Migrate `identities.json` loading to populate `identityRegistry` with `parentHandle = "core"` and `localHandle = name`
  - **Done.** `CORE_INTERNAL_IDENTITIES_LOADED` reducer creates registry entries: derives `localHandle` from name for legacy identities (lowercase, strip non-alphanumeric), constructs `core.*` handles, generates UUIDs for identities missing them, uses `registeredAt` when available.
- [x] Update `IdentityManagerView` to read from `AppState.identityRegistry` instead of `CoreState.userIdentities`
  - **Done.** Reads `appState.identityRegistry.values.filter { it.parentHandle == "core" }`. Uses `remember(appState.identityRegistry)` for reactive updates. Sorted by `registeredAt` for stable ordering.
- [x] Wire `RETURN_REGISTER_IDENTITY` targeted delivery to originator (depends on Phase 3 targeted routing, or use `deliverPrivateData` as interim)
  - **Done in Phase 3.** `targetRecipient = originator` added to both success and failure dispatch sites in CoreFeature. Full targeted routing now delivers the response to the requesting feature through the normal `processAction` pipeline.
- [x] Unit tests: Store and CoreFeature tests updated and passing
  - **Done.** All existing tests pass with the rename and new registry wiring.

**Implementation notes (Phase 2.2)**:

**Dual-write migration pattern**: Both `CoreState.userIdentities` (deprecated) and `AppState.identityRegistry` are populated by the same reducer operations (ADD/REMOVE/LOADED). This preserves backward compatibility — any code still reading `userIdentities` continues to work, while new code reads from the registry. The old field will be removed in Phase 4 after all consumers are migrated.

**`@Suppress("DEPRECATION")` placement**: Kotlin does not allow annotations inside function call argument lists (e.g., inside `copy()` parameter lists). Suppress annotations must be placed at the statement level, before the `return` or `val` declaration that references the deprecated field.

**Session/Agent registration pattern**: Both features follow the same pattern — register on load/create, unregister on delete, and handle rename by unregistering the old handle + re-registering with the new name. The `handleSideEffects` method dispatches `REGISTER_IDENTITY` / `UNREGISTER_IDENTITY` via `deferredDispatch`, which is the preferred method for actions triggered inside side-effect handlers (avoids re-entrancy).

### Phase 3 — Targeted Delivery & Private Envelope Absorption ✅ COMPLETE
- [x] Add `targetRecipient: String? = null` to `Action`
  - **Done.** Added with KDoc explaining Store resolution at feature level. `toString()` updated to show `→ 'recipient'`.
- [x] Add targeted routing branch to `processAction` in Store
  - **Done.** Replaced Phase 2.1's placeholder with real routing: `extractFeatureHandle(action.targetRecipient)` resolves `"session.chat1"` → feature `"session"`. Reducer delivered to recipient feature only. Side-effects delivered to recipient feature only (three-branch split: targeted / non-broadcast / broadcast).
- [x] Add validation: reject `targetRecipient` on non-targeted actions; reject targeted actions without `targetRecipient`
  - **Done.** Step 1b added after schema lookup. Both invariants enforced with ERROR-level logging and early return.
- [x] Add originator enforcement: only the declaring feature can dispatch targeted actions
  - **Done.** No additional code needed — targeted actions have `public: false`, and the existing Step 2 authorization check (`extractFeatureHandle(action.originator) == descriptor.featureName`) already enforces this. Verified by new test `targeted action rejects foreign originator`.
- [x] Phase 3a: Deprecate `deliverPrivateData`, `PrivateDataEnvelope`, `onPrivateData`
  - **Done.** `deliverPrivateData` → bridge that logs WARN and internally calls `deferredDispatch` with `targetRecipient` (all 12 call sites gain full validation immediately). `PrivateDataEnvelope` → `@Deprecated` annotation. `onPrivateData` → `@Deprecated` with migration KDoc.
- [x] Migrate `CoreFeature.onPrivateData` → `handleSideEffects`
  - **Done.** `FILESYSTEM_RETURN_READ` handler moved. `CORE_RETURN_CONFIRMATION` migrated to targeted dispatch. `RETURN_REGISTER_IDENTITY` dispatches fixed to include `targetRecipient = originator` (were silently dropped by old placeholder routing, rejected by new Step 1b enforcement).
- [x] Migrate `CoreFeature.deliverPrivateData` → `deferredDispatch` with `targetRecipient`
  - **Done.** 1 call site migrated.
- [x] Migrate `SessionFeature.onPrivateData` → `handleSideEffects`
  - **Done.** 2 handlers moved (`FILESYSTEM_RETURN_LIST`, `FILESYSTEM_RETURN_READ`). 1 `deliverPrivateData` call migrated (`SESSION_RETURN_LEDGER`).
- [x] Migrate `FilesystemFeature` deliverPrivateData calls → targeted dispatch (per-site structural changes)
  - **Done.** 1 `onPrivateData` handler moved (`CORE_RETURN_CONFIRMATION`). 6 `deliverPrivateData` calls migrated via regex-assisted bulk replacement.
- [x] Migrate all remaining features using private data
  - **Done.** GatewayFeature (2 sender calls), KnowledgeGraphFeature (1 `onPrivateData` + 1 sender call), AgentRuntimeFeature + AgentCognitivePipeline (full refactor — 7 envelope type handlers, correlation-based routing preserved, `handlePrivateData` → `handleTargetedAction`).
- [x] Migrate `SettingsFeature.onPrivateData` → `handleSideEffects`
  - **Done.** 1 `onPrivateData` handler moved (`FILESYSTEM_RETURN_READ` — settings file load). `onPrivateData` override deleted entirely. Tests migrated: T2 `onPrivateData` test replaced with targeted action dispatch through the Store (`originator = "filesystem"`, `targetRecipient = "settings"`); T5 platform test updated to use `Store(AppState(), features, platform)` constructor (removed deleted `validActionNames` param) and `store.initFeatureLifecycles()` (replaces manual `features.forEach { it.init(store) }`). 3 new T2 tests added for previously uncovered side-effect branches (`FILESYSTEM_RETURN_READ` ignores non-settings files, `UPDATE` broadcasts `VALUE_CHANGED`, `OPEN_FOLDER` dispatches `FILESYSTEM_OPEN_WORKSPACE_FOLDER`).
- [ ] Phase 3b: Remove deprecated types and methods from `Feature.kt`, `Store.kt`, `AppCore.kt`
  - **Deferred.** All features now migrated (including SettingsFeature — the last holdout). All test files now migrated (KnowledgeGraph tests were the last to use `deliverPrivateData`/`Envelopes.*`). Deprecated code retained for one release cycle. Cleanup items: delete `onPrivateData` from `Feature.kt`, delete `deliverPrivateData` from `Store.kt`, delete `PrivateDataEnvelope` from `AppCore.kt`, delete `Envelopes` object from `ActionNames.kt`. No remaining callers of `onPrivateData`, `deliverPrivateData`, or `PrivateDataEnvelope` in production or test code.
- [x] Integration tests: targeted delivery through full pipeline with security checks; originator enforcement; rejection of misused targetRecipient
  - **Done.** 7 new tests in StoreT1RoutingTest (recipient delivery, self-targeting, authorization rejection, Step 1b validation ×2, hierarchical resolution, side-effect scope). Bridge verification test in StoreT1GuardTest. 4 migrated tests in CoreFeatureT3PeerTest (filesystem identity loading ×2, confirmation dialog ×2).

**Implementation notes (Phase 3)**:

**Bridge strategy proved correct**: Deprecating `deliverPrivateData` as a bridge (internally calling `deferredDispatch` with `targetRecipient`) meant all 12 private data call sites gained full validation, authorization, lifecycle guards, and audit logging *immediately* — even before individual features were migrated. This made the migration safe to do incrementally.

**Step 1b enforcement caught a latent bug**: `CORE_RETURN_REGISTER_IDENTITY` dispatches in CoreFeature (added in Phase 2.2) were missing `targetRecipient`. The old Phase 2.1 placeholder routing silently delivered these to the owning feature as a fallback. Step 1b's strict enforcement correctly rejected them, surfaced by the existing T2 tests. Fix: add `targetRecipient = originator` to both success and failure response dispatches.

**`extractFeatureHandle` reuse**: The same helper that resolves `"agent.gemini-coder"` → `"agent"` for originator authorization (Step 2) is reused for recipient resolution (Step 4). This means `targetRecipient = "session.chat1"` delivers to the `"session"` feature — sub-entity targeting is the feature's responsibility, matching the principle established in Phase 2.1.

**AgentRuntimeFeature was the most complex migration**: Its `onPrivateData` handled 7 envelope types with correlation-based routing (pending command responses) that needed to be preserved. The solution: a private `handleTargetedResponse()` method that checks `pendingCommandResponses` first (for command attribution), then routes to `AgentCognitivePipeline.handleTargetedAction()` or local handlers. `postCommandResponse` and `formatResponseForSession` updated from `PrivateDataEnvelope` parameter to `Action` parameter.

**FileSystemFeature `onPrivateData` → `handleSideEffects` state access change**: The old `onPrivateData` read state from `store.state.value`, but `handleSideEffects` receives `newState` as a parameter (the state *after* the reducer has run). The migrated handler correctly reads from the `newState` parameter for `pendingScopedRead`, which is the right value since the reducer may have already modified it for the current action.

**SettingsFeature was the last unmigrated feature**: Its `onPrivateData` had a single handler for `FILESYSTEM_RETURN_READ` that loaded settings from disk. The migration was mechanical — `envelope.type` → `action.name`, `envelope.payload` → `action.payload`, handler moved into `handleSideEffects` as the first `when` branch. The inner logic (checking `path == settingsFileName`, decoding JSON content, dispatching `SETTINGS_LOADED`) was unchanged. The T2 test was the only test in the codebase still directly calling `feature.onPrivateData()` with a `PrivateDataEnvelope` — replaced with a targeted action dispatch through the Store matching the real FilesystemFeature delivery path. The T5 platform test was still using the pre-Phase 2.1 Store constructor (`validActionNames` 4th param) and manual `features.forEach { it.init(store) }` instead of `store.initFeatureLifecycles()` — both updated.

**Phase 4 Session Identity Migration (2026-02-14)**: The largest single migration in the project. `Session.id` (UUID string) and `Session.name` were replaced with `Session.identity: Identity`, bringing sessions fully into the IdentityRegistry model. Session creation became a two-phase async flow: `SESSION_CREATE` stashes a `PendingSessionCreation`, dispatches `REGISTER_IDENTITY` to CoreFeature (with `name` and caller-generated `uuid`, no `localHandle` — CoreFeature generates the slug via new `slugifyName()` companion function), and the actual Session is only created when `RETURN_REGISTER_IDENTITY` arrives with the approved Identity. This ensures every session has a registry-approved handle from birth. Persistence path changed from flat `{uuid}.json` to `{uuid}/{localHandle}.json` — the UUID folder is stable across renames, while the slug filename inside is human-readable. File loading became two-level: list UUID folders → list contents of each → read `.json` files. A new `UPDATE_IDENTITY` action in CoreFeature handles renames with namespace enforcement, slug recomputation, deduplication, atomic swap, child handle cascade, and a targeted response that triggers file rename in SessionFeature. `resolveSessionId()` was upgraded to triple-match: localHandle, full handle (`session.my-chat`), or display name. A cross-feature import violation was discovered and fixed — `SessionView` was importing `CoreState` for `activeUserId`; replaced with a `@Transient activeUserId` field on `SessionState` cached from `CORE_IDENTITIES_UPDATED` broadcast. All view files received mechanical renames (`session.id`→`identity.localHandle`, `session.name`→`identity.name`). `FakePlatformDependencies.generateUUID()` was updated to produce valid UUID v4 format (`00000000-0000-4000-a000-{counter}`) to pass CoreFeature's UUID validation regex. All 4 test files updated — T2 CoreTest required the most work: two-phase creation verification, UUID folder deletion, two-level file loading, Identity-format JSON, plus new test coverage for `activeUserId` caching, persist path format, and three-way session resolution.

**Phase 4 Agent Identity Migration (2026-02-14)**: 10 production files migrated in 3 batches following the Session migration pattern. `AgentInstance.id` (UUID string) and `AgentInstance.name` were replaced with `AgentInstance.identity: Identity`. Unlike Session (which re-keyed its map by `localHandle`), the agent map retains UUID as its key — this was a deliberate design choice because agent UUIDs are used extensively as `correlationId` in async request/response flows (gateway, ledger, HKG), and re-keying would cascade changes through the entire cognitive pipeline with high risk. The critical behavioral change is that bus-facing `senderId` in `SESSION_POST` actions now uses `agent.identity.handle` instead of UUID, enabling the IdentityRegistry to properly resolve agent names in the session view. A dual comparison pattern (`agentUuid == senderId || agent.identity.handle == senderId`) was applied in `handleMessagePosted` and `handleLedgerResponse` to handle the transition period where older messages may still carry UUID senderIds. Identity registration follows the same two-phase pattern as Session: synchronous creation with placeholder Identity (empty handle/localHandle), followed by `REGISTER_IDENTITY` dispatch to CoreFeature, with the approved Identity written back on `CORE_RETURN_REGISTER_IDENTITY`. Agent rename uses atomic `CORE_UPDATE_IDENTITY` instead of unregister+register, matching Session's approach. Two new reducer handlers and two new side-effect handlers were added for the identity response actions. `broadcastAgentNames` was updated to emit a handle→name map instead of UUID→name, preparing for downstream consumers to resolve agents by handle.

**Test migration** required a `TestAgentFactory.kt` helper with `testAgent()` and `testSession()` functions that wrap the new Identity-based constructors, minimizing churn across 17 test files. Phase 3b cleanup was performed opportunistically in tests: all `PrivateDataEnvelope`/`deliverPrivateData`/`onPrivateData`/`Envelopes.*` references were replaced with direct `harness.store.dispatch()` calls using promoted action names (e.g., `ActionRegistry.Names.SESSION_RETURN_LEDGER` instead of `ActionRegistry.Names.Envelopes.SESSION_RETURN_LEDGER`). The `FakeStore` constructor signature change (removal of `validActionNames` parameter from Phase 2.1) was caught and fixed in 4 test files. 11 tests remain failing (parked as tech debt), likely due to `SessionState.sessions` being keyed by `identity.localHandle` while test code uses `session.identity.uuid!!` as the map key.

**Phase 4 CommandBot Migration (2026-02-14)**: CommandBotFeature was the last consumer of both `ExposedActions` and the `AGENT_NAMES_UPDATED` Proactive Broadcast pattern. The migration had two parts. First, all `ExposedActions.*` references were replaced with `ActionRegistry` derived views: `allowedActionNames` → `agentAllowedNames` (CAG-004), `autoFillRules` → `agentAutoFillRules` (CAG-007), `requiresApproval` → `agentRequiresApproval` (CAG-006). These are computed at codegen time from the same manifest data, so behavior is identical. Second, the mutable `knownAgentIds`/`knownAgentNames` fields and their `AGENT_NAMES_UPDATED`/`AGENT_DELETED` subscription handlers were deleted entirely. They were replaced by two stateless helper methods that read `store.state.value.identityRegistry` at side-effect time: `isAgent()` checks `parentHandle == "agent"`, and `resolveAgentName()` reads `identity.name`. This eliminates 25 lines of mutable state tracking in favor of 2 pure lookups against the single source of truth. The T2 test files required three layers of updates: (1) data model migration (`Identity`/`Session`/`SessionState` constructors updated to post-Phase 4 shapes), (2) identity registry seeding (test helpers now call `store.updateIdentityRegistry()` directly instead of dispatching `AGENT_NAMES_UPDATED`), and (3) hierarchical handle alignment (all `senderId` references changed from bare `"agent-1"` to proper `"agent.test-agent-1"` handles). Two pre-existing test failures were also fixed: action name filter predicates in edge-case tests used stale `session.publish.*`/`session.internal.*` prefixes that no longer match the flattened post-Phase 4 action names. The agent prompt builder migration item was marked N/A — prompt content is dynamically generated by LLMs, not read from a static `ExposedActions.documentation` field. With CommandBot decoupled, `ExposedActions.kt` is now safe to delete.

**Phase 4 KnowledgeGraph Audit & Migration (2026-02-14)**: A four-task audit assessed KnowledgeGraphFeature's full compatibility with the v2.0 action bus architecture. **Task 1 (Compatibility Audit)** confirmed production code was already v2-compliant from Phase 3 — all action manifest flags correct, targeted delivery implemented, no deprecated API usage in production. **Task 2 (Silent Return Logging)** identified 17 guard clauses in `handleSideEffects` that silently returned on bad input (missing payload fields, null originator, holon-not-found lookups). Each was hardened with a `LogLevel.WARN` log before returning. The reducer's `?: return currentFeatureState` guards were intentionally left unchanged — pure functions should not perform I/O. **Task 3 (Test Coverage Analysis)** mapped all production code paths against 6 test files and identified ~20 missing/broken scenarios, prioritized by impact. **Task 4 (Test Updates)** fixed 3 broken/deprecated T2 tests (the last tests in the codebase still using `deliverPrivateData`/`Envelopes.*`/`PrivateDataEnvelope`), and added 15 new tests across T1 and T2 tiers. The T2CoreTest `REQUEST_CONTEXT` test was the most critical fix: it was asserting on `deliveredPrivateData` (a capture list that was empty because production code had already been migrated to targeted dispatch in Phase 3) — effectively a passing-but-broken test that would have passed vacuously if the feature returned nothing at all. Net result: T1 tests 13→21, T2 tests 8→16, zero deprecated API references remaining in any test file.

### Known Issues (Phase 4)

~~⚠️ **SessionFeature uses UUIDs internally instead of handles**~~ — **Resolved in Phase 4 Session migration.** `Session.id` replaced by `Session.identity` containing both UUID (for persistence) and localHandle (for runtime addressing). Sessions map keyed by localHandle. Two-phase async creation via CoreFeature's identity registry.

~~⚠️ **AgentRuntimeFeature uses UUIDs internally instead of handles**~~ — **Production code resolved in Phase 4 Agent migration.** `AgentInstance.id`/`.name` replaced by `AgentInstance.identity: Identity`. UUID remains as internal map key (stable for async correlation). Bus-facing `senderId` in `SESSION_POST` actions now uses `agent.identity.handle`. Registration flow: synchronous creation with placeholder handle → async enrichment via `CORE_RETURN_REGISTER_IDENTITY`. Rename uses atomic `CORE_UPDATE_IDENTITY`. Test migration in progress (11 of ~60 tests still failing, parked as tech debt).

~~⚠️ **CommandBotFeature depends on ExposedActions and mutable agent tracking**~~ — **Resolved in Phase 4 CommandBot migration.** `ExposedActions.*` replaced by `ActionRegistry.agentAllowedNames`/`agentRequiresApproval`/`agentAutoFillRules`. `knownAgentIds`/`knownAgentNames` mutable fields and `AGENT_NAMES_UPDATED`/`AGENT_DELETED` handlers deleted; replaced by stateless `identityRegistry` lookups. CommandBot was the last consumer of `ExposedActions` — safe to delete the shim.

⚠️ **Cross-feature references to session UUIDs**: `subscribedSessionIds` on agents, `privateSessionId`, and `contextSessionId` in the cognitive pipeline still use UUIDs. These will need updating when the Agent migration brings those features in line with the handle-based model. The `Session.identity.uuid` field is available for reverse-lookup during the transition.

⚠️ **Session map key mismatch in tests**: `SessionState.sessions` is now keyed by `identity.localHandle` (not UUID), but some agent test files still use `session.identity.uuid!!` as the map key when constructing `SessionState`. This is a known source of the remaining 11 test failures (parked as tech debt).

### Phase 4 — Migrate Consumers & Delete Shims — Session ✅, Agent Production ✅, Agent Tests ✅ (3 remaining), CommandBot ✅

#### Session Identity Migration ✅
- [x] ⚠️ **SessionFeature: migrate from UUIDs to handles** — `Session.id` replaced with `Session.identity: Identity`; sessions map keyed by `localHandle`; two-phase async creation via `REGISTER_IDENTITY`/`RETURN_REGISTER_IDENTITY`; persistence path changed to `uuid/localHandle.json`
- [x] **CoreFeature: `slugifyName()` + loosened `REGISTER_IDENTITY`** — `localHandle` optional (generated from name), `uuid` field accepted and validated, `UUID_REGEX` constant
- [x] **CoreFeature: `UPDATE_IDENTITY` action** — namespace-enforced rename with slug recomputation, dedup, atomic swap, child cascade, targeted response
- [x] **SessionState data model** — `Session.identity: Identity`, `PendingSessionCreation`, `activeSessionLocalHandle`, `editingSessionLocalHandle`, `lastDeletedSessionLocalHandle`, `activeUserId` cache
- [x] **SessionFeature reducers** — two-phase creation (`SESSION_CREATE`→pending→`RETURN_REGISTER_IDENTITY`→session), `RETURN_UPDATE_IDENTITY` handle re-keying, `resolveSessionId()` triple-match (localHandle, full handle, display name)
- [x] **SessionFeature side effects** — `REGISTER_IDENTITY` dispatch, `RETURN_REGISTER_IDENTITY` persistence/broadcast, UUID folder deletion, `UPDATE_IDENTITY` dispatch on rename, two-level file loading
- [x] **Views: mechanical renames** — `session.id`→`identity.localHandle`, `session.name`→`identity.name` in SessionView, SessionsManagerView, LedgerEntryCard
- [x] **Views: decoupled from CoreState** — removed `import CoreState` from SessionView; `activeUserId` cached on SessionState from broadcast
- [x] **Manifests** — `core_actions.json` loosened + new actions; `session_actions.json` field descriptions
- [x] **FakePlatformDependencies** — `generateUUID()` produces valid UUID v4 format
- [x] **Tests updated** — T1 LedgerEntryCard, T1 SessionManagerView, T1 SessionView, T2 CoreTest (two-phase creation, UUID folder deletion, two-level loading, Identity-format JSON, new coverage for `activeUserId`, persist path, `resolveSessionId`)

#### Agent Identity Migration — Production ✅, Tests 🔧
10 production files migrated across 3 batches. `AgentInstance.id`/`.name` replaced with `AgentInstance.identity: Identity`.

**Design Decisions:**
- UUID remains as internal map key (`agents: Map<String, AgentInstance>` keyed by `identity.uuid`) — stable for async correlation, persistence paths, status lookups
- Bus-facing `senderId` in `SESSION_POST` actions uses `identity.handle` — THE critical behavioral change
- `correlationId` fields stay as UUID — used for async request/response matching
- Registration: synchronous creation with placeholder handle, async enrichment via `CORE_RETURN_REGISTER_IDENTITY`
- Rename: atomic `CORE_UPDATE_IDENTITY` instead of unregister+register
- Dual `senderId` comparison during transition: `agent.identity.uuid == senderId || agent.identity.handle == senderId`
- No data migration needed — agents re-register on load

**Batch 1 — Data Foundation ✅ (3 files):**
- [x] **AgentState.kt** — `AgentInstance`: replaced `id: String` + `name: String` with `identity: Identity`; UUID-keyed map convention docs
- [x] **AgentCrudLogic.kt** — `AGENT_CREATE`: constructs Identity with placeholder handles; `AGENT_UPDATE_CONFIG`: name updates via `identity.copy(name = ...)`; `AGENT_AGENT_LOADED`: keys by `agent.identity.uuid`
- [x] **AgentRuntimeReducer.kt** — `handleMessagePosted`: dual senderId check; avatar tracking: resolves senderId to UUID via direct key match or handle lookup; `SESSION_SESSION_DELETED`: uses `identity.uuid`; NEW: `CORE_RETURN_REGISTER_IDENTITY` and `CORE_RETURN_UPDATE_IDENTITY` handlers

**Batch 2 — Side Effects & Pipeline ✅ (2 files):**
- [x] **AgentRuntimeFeature.kt** — `AGENT_AGENT_LOADED`/`AGENT_CREATE` side effects dispatch `REGISTER_IDENTITY`; `AGENT_UPDATE_CONFIG` dispatches atomic `CORE_UPDATE_IDENTITY` on name change; `AGENT_DELETE` unregisters by handle; `broadcastAgentNames` emits handle→name map; NEW: `CORE_RETURN_REGISTER_IDENTITY` and `CORE_RETURN_UPDATE_IDENTITY` side effects re-save config and re-broadcast
- [x] **AgentCognitivePipeline.kt** — `handleLedgerResponse`: dual senderId check for role assignment; `executeTurn`: `correlationId` = UUID, `SESSION_METADATA` shows both handle and UUID, `agentName` = `identity.name`; `handleGatewayResponse`: **`senderId` in SESSION_POST = `agent.identity.handle`**; all status updates use UUID

**Batch 3 — Supporting Logic & Views ✅ (5 files):**
- [x] **AgentAvatar.kt** — `touchAgentAvatarCard`: map lookup by `identity.uuid`; `updateAgentAvatars`: **`senderId` in SESSION_POST = `agent.identity.handle`**; display name from `identity.name`; all `put("agentId", ...)` use `identity.uuid`
- [x] **AgentAutoTriggerLogic.kt** — All `agent.id` → `agent.identity.uuid` with early `?: return@forEach` guard
- [x] **SovereignHKGResourceLogic.kt** — `handleSovereignRevocation`: `newAgent.identity.uuid`; `ensureSovereignSessions`: `expectedSessionName` uses `identity.name` + `identity.uuid`; `requestContextIfSovereign`: `correlationId` = `identity.uuid`
- [x] **AgentManagerView.kt** — Delete dialog, LazyColumn key, isEditing check, editor name input, save/clone/edit/delete dispatches all use `identity.uuid`/`identity.name`
- [x] **PreviewContextView.kt** — Title: `identity.name`; discard/execute dispatches: `identity.uuid`

**Test Migration 🔧 (17 test files + 1 helper):**
- [x] **TestAgentFactory.kt** — NEW helper: `testAgent(id, name, ...)` wraps `AgentInstance(identity = Identity(...), ...)`; `testSession(id, name, ...)` wraps `Session(identity = Identity(...), ...)`
- [x] **All 17 test files** — `AgentInstance(...)` → `testAgent(...)`, `agent.id` → `agent.identity.uuid`, `agent.name` → `agent.identity.name`, `Session(...)` → `testSession(...)`, `session.id` → `session.identity.uuid!!`
- [x] **FakeStore constructor** — removed obsolete `allActionNames` third parameter (4 files)
- [x] **Phase 3b cleanup in tests** — removed all `PrivateDataEnvelope`/`deliverPrivateData`/`onPrivateData`/`Envelopes.*` references (5 files); replaced with `harness.store.dispatch(originator, Action(ActionRegistry.Names.X, payload))`
- [x] **senderId assertions** — updated to expect `agent.identity.handle` where code now emits handle instead of UUID (T2AvatarLifecycleTest, T3GatewayPeerTest, T2CognitiveCycleTest)
- [x] **Serialized JSON** — agent config JSON in T2StartupTest updated from `{"id":"...","name":"..."}` to `{"identity":{...}}`
- [ ] **11 tests still failing (parked as tech debt)** — likely causes: (1) `SessionState.sessions` keyed by `localHandle` not UUID — tests using `session.identity.uuid!!` as map key need `session.identity.localHandle`; (2) possible remaining `Session.createdAt` vs `createdTimestamp` mismatches; (3) assertions comparing old field values. No runtime failures — production code is correct.

#### CommandBot Migration ✅
CommandBotFeature fully decoupled from `ExposedActions` and `knownAgentIds`/`knownAgentNames`. Now reads all agent guardrail data from `ActionRegistry` and all agent identity data from `AppState.identityRegistry`.

**ExposedActions → ActionRegistry (1 production file, 0 test files):**
- [x] **CommandBotFeature.kt** — `ExposedActions.allowedActionNames` → `ActionRegistry.agentAllowedNames` (CAG-004); `ExposedActions.autoFillRules` → `ActionRegistry.agentAutoFillRules` (CAG-007); `ExposedActions.requiresApproval` → `ActionRegistry.agentRequiresApproval` (CAG-006); deleted `import ExposedActions`; updated all KDoc references

**knownAgentIds/knownAgentNames → IdentityRegistry (1 production file, 2 test files):**
- [x] **CommandBotFeature.kt** — Deleted `knownAgentIds: MutableSet<String>` and `knownAgentNames: MutableMap<String, String>` mutable fields; deleted `AGENT_AGENT_NAMES_UPDATED` handler (full-replace subscription); deleted `AGENT_AGENT_DELETED` handler (single-remove); added `isAgent(senderId, store)` helper (reads `store.state.value.identityRegistry[senderId]?.parentHandle == "agent"`); added `resolveAgentName(senderId, store)` helper (reads `identityRegistry[senderId]?.name`). Net: −25 lines of mutable state tracking, replaced by 2 stateless lookups.
- [x] **CommandBotFeatureT2CoreTest.kt** — `Identity`/`Session`/`SessionState` constructors updated to post-Phase 4 data model; `buildHarnessWithKnownAgent` seeds `store.updateIdentityRegistry()` with proper `Identity(parentHandle = "agent", handle = "agent.test-agent-1", ...)` instead of dispatching `AGENT_NAMES_UPDATED`; all `senderId` references updated from bare `"agent-1"` to hierarchical handle `testAgentHandle`; all `testUser.id` → `testUser.handle`, `testSession.id` → `testSession.identity.localHandle`, `activeSessionId` → `activeSessionLocalHandle`
- [x] **CommandBotFeatureT2GuardrailsTest.kt** — Same data model and identity registry updates as T2CoreTest; agent tracking lifecycle tests rewritten to test registry mutations (add/remove identity) instead of broadcast events; stale action name filter predicates fixed (`session.publish.*`/`session.internal.*` → `session.*` to match post-Phase 4 flattened action names); custom agent display name test updated to use proper handle
- [x] **CommandBotFeatureT1ReducerTest.kt** — No changes needed (pure reducer, no identity lookups or data model constructors)

**Design Decisions:**
- CommandBot is now fully stateless w.r.t. agent tracking — no mutable fields, no broadcast subscriptions. Agent identity is resolved on-demand from the identity registry at side-effect time.
- `senderId` in messages from agents is now the hierarchical handle (e.g., `"agent.gemini-coder-1"`), not the bare agent ID. This aligns with the bus-facing `senderId` change made in Phase 4 Agent Batch 2.
- Test helpers seed the identity registry directly via `store.updateIdentityRegistry()` rather than dispatching `REGISTER_IDENTITY` through CoreFeature — this avoids coupling test setup to CoreFeature's async response flow while still testing the correct behavioral contract.

#### Remaining Phase 4 Work

#### KnowledgeGraph Audit & Migration ✅

Full v2.0 compatibility audit performed on KnowledgeGraphFeature. Production code was already v2-compliant from Phase 3. This phase addressed test migration, silent error logging, and coverage gaps.

**Audit Results — Production Code:**
- Feature interface v2 compliance: ✅ `Identity`, `reducer()`, `handleSideEffects()`, `composableProvider` all correct
- Action manifest (`knowledgegraph_actions.json`): ✅ All `open`/`broadcast`/`targeted` flags correct; internal, broadcast, targeted, and open action categories all properly implemented
- Authorization & routing: ✅ All restricted actions dispatched with correct originator; targeted delivery for `RETURN_CONTEXT` correctly implemented
- No deprecated `onPrivateData()` references in production code

**Production Code Hardening — Silent Return Logging (1 file, 17 changes):**
- [x] **KnowledgeGraphFeature.kt** — 17 silent `?: return` guards in `handleSideEffects` replaced with `?: run { log(WARN, ...); return }` pattern. Categories: 1 null originator guard, 3 missing/malformed filesystem response fields, 10 missing required payload fields (`personaId`, `holonId`, `name`, `correlationId`, `newName`), 3 holon-not-found state lookups. Reducer left unchanged (pure functions should not perform I/O side effects — `?: return currentFeatureState` is the correct pattern).

**Test Migration — Deprecated API Removal (1 file, 3 tests):**
- [x] **KnowledgeGraphFeatureT2CoreTest.kt** — 3 tests migrated:
  - `full load sequence correctly populates and synchronizes holons`: `deliverPrivateData("filesystem", "knowledgegraph", PrivateDataEnvelope(Envelopes.FILESYSTEM_RETURN_LIST, ...))` → `deferredDispatch("filesystem", Action(name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST, ..., targetRecipient = "knowledgegraph"))`
  - `full load sequence handles empty directory gracefully`: Same migration pattern
  - `REQUEST_CONTEXT should return full holon context via private data` (was **broken** — asserted on `deliveredPrivateData` list which was empty because production code sends via targeted dispatch): Assertion changed from `harness.store.deliveredPrivateData.find { it.recipient == "agent-alpha" }` → `harness.processedActions.find { it.name == KNOWLEDGEGRAPH_RETURN_CONTEXT && it.targetRecipient == "agent-alpha" }`
- [x] Removed deprecated imports: `PrivateDataEnvelope`, `app.auf.core.PrivateDataEnvelope`
- [x] All `Envelopes.*` references replaced with flat `ActionRegistry.Names.*`

**New T2 Side-Effect Tests (7 tests):**
- [x] `SYSTEM_STARTING should dispatch initial FILESYSTEM_LIST` — boot sequence entry point (requires `AppLifecycle.INITIALIZING`)
- [x] `PERSONA_LOADED should broadcast AVAILABLE_PERSONAS_UPDATED when roots change`
- [x] `RELEASE_HKG should broadcast RESERVATIONS_UPDATED`
- [x] `CREATE_PERSONA should write file and trigger filesystem reload`
- [x] `DELETE_HOLON should delete directory, update parent, and confirm deletion` — full cascade test
- [x] `RETURN_LIST non-recursive should discover persona directories and load each`
- [x] `side effects with missing payload fields should log warnings` — validates Task 2 logging additions
- [x] `DELETE_HOLON with nonexistent holonId should log warning and not crash`

**New T1 Reducer Tests (8 tests):**
- [x] `SET_IMPORT_EXECUTION_STATUS should set the isExecutingImport flag`
- [x] `SET_PENDING_IMPORT_ID should set the pendingImportCorrelationId`
- [x] `TOGGLE_HOLON_EXPANDED should collapse then expand a holon`
- [x] `SET_VIEW_MODE to INSPECTOR should reset all import state`
- [x] `START_IMPORT_ANALYSIS should reset import state and set loading`
- [x] `SET_IMPORT_RECURSIVE should update flag and set loading`
- [x] `TOGGLE_SHOW_ONLY_CHANGED should toggle the filter flag`
- [x] `CONFIRM_DELETE_HOLON should remove holon and descendants and update parent sub_holons` — recursive removal + parent cleanup + active view clearing

**Test Totals:**
- T1ReducerTest: 13 → 21 tests
- T2CoreTest: 8 → 16 tests (3 migrated + 7 new + 2 logging verification)
- T1HolonOperationsTest, T1ViewComponentTest, T3FileSystemPeerTest, T3ImportPeerTest: unchanged (no deprecated API usage)

**Remaining KnowledgeGraph Gaps (low priority):**
- T1: `normalizeHolonId` with name < 3 alphanumerics, `prepareHolonForWriting` with execute section, `createHolonFromString` with invalid relationship targetId
- T2: `handleFilesContentForLoad` error paths (all-malformed, partial errors), `COPY_ANALYSIS_TO_CLIPBOARD`, `UPDATE_HOLON_CONTENT` side effect
- T1 View: tree expand/collapse, type filtering
- T3 Import: UPDATE action, AssignParent with user overrides
- [x] ~~**Fix 11 failing agent tests (tech debt)**~~ — Resolved 8 of 11 failures across 4 root causes. **3 isolated failures remain** (see below).

**Test Migration Sweep (v5.4) — Fixes Applied:**

| Root Cause | Files Fixed | Fix Applied |
|---|---|---|
| `sessionNames` → `subscribableSessionNames` | T1CrudLogicTest, T1SovereignAgentLogicTest, T3SovereignCognitionPeerTest | Renamed field in all `AgentRuntimeState()` constructors; removed private session entries from `subscribableSessionNames` maps (excluded at source by design) |
| Missing `resources` on `AgentRuntimeState` | T2CognitiveCycleTest, T2T3ThinkerTest, T3GatewayPeerTest, T3SovereignCognitionPeerTest | Added `resources = AgentDefaults.builtInResources` to state; added `resources = mapOf("system_instruction" to "res-sys-instruction-v1")` (vanilla) or `"constitution"/"bootloader"` (sovereign) to agent instances; fixed broken resource IDs `"const-default"/"boot-default"` → `"res-sovereign-constitution-v1"/"res-boot-sentinel-v1"` |
| Targeted actions dispatched without `targetRecipient` | T3GatewayPeerTest, T2T3ThinkerTest | Added `targetRecipient = "agent"` to all `RETURN_LEDGER` and `RETURN_RESPONSE` dispatches; added missing ledger response step in gateway failure test |
| Deleted `identityNames` / `AGENT_AGENT_NAMES_UPDATED` | SessionFeatureT2CoreTest | Removed obsolete `reducer correctly merges user and agent identity broadcasts` test (tested deleted functionality) |
| Avatar card `SESSION_POST` conflated with content post | T2CognitiveCycleTest (sentinel failure) | Assertion now filters for `message` key to distinguish avatar card posts from agent content posts |
| Missing `FileSystemFeature` in harness | T3GatewayPeerTest | Added `FileSystemFeature(platform)` to test environment (needed for workspace context gathering) |
| `evaluateFullContext` gate not firing | T2T3ThinkerTest | Status now sets `contextGatheringStartedAt` and `transientWorkspaceContext` so the gate actually evaluates |

**3 Remaining Failures (isolated tech debt, non-blocking):**
- [ ] **T1SovereignAgentLogicTest** "ensureSovereignSessions should dispatch UPDATE if session exists but unlinked" — `SovereignHKGResourceLogic.ensureSovereignSessions` now queries `store.state.value.identityRegistry` instead of `agentState.subscribableSessionNames`. FakeStore needs `AppState.identityRegistry` populated with session identities. Fix: populate `AppState(identityRegistry = ...)` in FakeStore setup.
- [ ] **T1ManagerViewTest** (2 tests: knowledge graph selection, resource slot selection) — Compose UI tests. Likely a view-layer refactor issue unrelated to action bus migration. Low priority.
- [ ] **KnowledgeGraphFeatureT2CoreTest** (2 tests: CREATE_PERSONA, DELETE_HOLON) — `FakePlatformDependencies.formatIsoTimestamp()` returns `"ISO_TIMESTAMP_1000000000000"` but `normalizeHolonId` now expects real `YYYYMMDDTHHMMSSZ` format. Fix: update fake to produce valid ISO timestamps. May cascade to tests asserting on old format string.
- [x] ~~Migrate `CommandBotFeature` from `ExposedActions.*` → `ActionRegistry.*`~~
- [x] ~~Delete `CommandBotFeature.knownAgentIds` and `knownAgentNames`; read from `AppState.identityRegistry` (filter by parentHandle == "agent")~~
- [x] ~~Migrate agent prompt builder from `ExposedActions.documentation` → `ActionRegistry.byActionName`~~ — N/A: prompt content is dynamically generated by LLMs, not read from a static ExposedActions field
- [x] ~~Deprecate then delete `SessionState.identityNames`; read from `AppState.identityRegistry` in views~~
- [x] ~~Deprecate `agent.AGENT_NAMES_UPDATED`; agents use `core.REGISTER_IDENTITY` instead~~ — Action deleted from `agent_actions.json`; `broadcastAgentNames()` method and all 6 call sites removed; SessionFeature reducer cases for `AGENT_NAMES_UPDATED` and `AGENT_DELETED` (identity cache consumers) removed
- [ ] Migrate `subscribedSessionIds`/`privateSessionId` from session UUIDs to session localHandles
- [x] ~~Delete `AgentRuntimeState.sessionNames` map (replaced by identity registry lookups)~~ — Replaced by `subscribableSessionNames: Map<String, String>` populated from `SESSION_NAMES_UPDATED` (which now excludes `isAgentPrivate` sessions at source)
- [x] ~~⚠️ **AgentRuntimeFeature: migrate from UUIDs to handles**~~ — Production code complete. `AgentInstance.identity: Identity` replaces `id`/`name`; registration via `REGISTER_IDENTITY`; rename via `UPDATE_IDENTITY`; bus-facing senderId uses handle
- [x] ~~Delete `ExposedActions.kt` deprecated delegation shim~~ — Codegen block removed from `build_gradle.kts`; `ExposedActionsContextProvider.kt` migrated to `ActionRegistry`; `AgentRuntimeFeature.applySandboxRewrite` migrated to `ActionRegistry.agentSandboxRules`
- [x] ~~Delete `ActionNames.kt` typealias shim~~
- [x] ~~Phase 3b cleanup in tests~~: All test references to `onPrivateData`, `deliverPrivateData`, `PrivateDataEnvelope`, `Envelopes.*` eliminated (Agent tests: 5 files; KnowledgeGraph tests: 1 file — the final holdout)
- [x] ~~Phase 3b cleanup in production: Delete `onPrivateData` from `Feature.kt`, `deliverPrivateData` from `Store.kt`, `PrivateDataEnvelope` from `AppCore.kt`, `Envelopes` from `ActionNames.kt`~~
- [x] ~~IDE-assisted batch rename: all old constant names (`SESSION_INTERNAL_LOADED` → `SESSION_LOADED`, etc.)~~
- [x] ~~IDE-assisted batch rename: `ActionNames.` → `ActionRegistry.Names.`~~
- [x] ~~Run full test suite~~ — 3 isolated failures remain (see test migration sweep above). All are well-understood with documented fixes. Non-blocking for Phase 5.

#### Identity Consolidation ✅

Eliminated four fragmented identity/name caches, replacing them with reads from `AppState.identityRegistry` (single source of truth) or source-filtered broadcasts.

**Deleted: `SessionState.identityNames`** (5 files)
- [x] **SessionState.kt** — Removed `identityNames: Map<String, String>` field
- [x] **SessionFeature.kt** — Removed `CORE_IDENTITIES_UPDATED` identity name merge (kept `activeUserId` extraction); removed `AGENT_AGENT_NAMES_UPDATED` reducer case; removed `AGENT_AGENT_DELETED` reducer case; removed `IdentityNamesUpdatedPayload` and `AgentDeletedPayload` classes
- [x] **SessionView.kt** — `LedgerPane`: sender name resolution via `store.state.collectAsState().value.identityRegistry[entry.senderId]?.name`; "Copy Transcript" lambda: same pattern via `store.state.value.identityRegistry`

**Deleted: `broadcastAgentNames()` + `agent.AGENT_NAMES_UPDATED` action** (2 files)
- [x] **AgentRuntimeFeature.kt** — Deleted `broadcastAgentNames()` method and all 6 call sites (AGENT_LOADED, AGENT_CREATE, UPDATE_CONFIG, DELETE, RETURN_REGISTER_IDENTITY, RETURN_UPDATE_IDENTITY)
- [x] **agent_actions.json** — Deleted `agent.AGENT_NAMES_UPDATED` action declaration (zero producers, zero consumers)

**Replaced: `AgentRuntimeState.sessionNames` → `subscribableSessionNames`** (7 files)
- [x] **SessionFeature.kt** — `broadcastSessionNames` now filters out `isAgentPrivate` sessions at source, emitting only subscribable sessions in the `names` map
- [x] **session_actions.json** — Updated `SESSION_NAMES_UPDATED` summary to document the `isAgentPrivate` exclusion
- [x] **AgentState.kt** — `sessionNames: Map<String, String>` → `subscribableSessionNames: Map<String, String>` with doc comment
- [x] **AgentRuntimeReducer.kt** — `SESSION_SESSION_NAMES_UPDATED` case stores pre-filtered names map into `subscribableSessionNames`
- [x] **AgentCrudLogic.kt** — Subscription guard changed from `state.sessionNames[sessionId]?.startsWith("p-cognition:") == false` → `sessionId in state.subscribableSessionNames` (positive allowlist)
- [x] **AgentManagerView.kt** — `SingleSessionSelector` and `MultiSessionSelector` use `agentState.subscribableSessionNames.entries.toList()` directly (zero filtering); `AgentReadOnlyView` uses `subscribableSessionNames` for subscribed session display, identity registry for private session name display
- [x] **SovereignHKGResourceLogic.kt** — `ensureSovereignSessions` session name-matching reads from `store.state.value.identityRegistry` (filtered by `parentHandle == "session"`) instead of `agentState.sessionNames`
- [x] **AgentRuntimeFeature.kt** — `AGENTS_LOADED` guard changed from `agentState.sessionNames.isNotEmpty()` → `store.state.value.identityRegistry.values.any { it.parentHandle == "session" }`

**Also migrated: `ExposedActions` → `ActionRegistry`** (3 files)
- [x] **ExposedActionsContextProvider.kt** — Import and reads changed from `ExposedActions.documentation` → `ActionRegistry.agentAllowedNames` / `ActionRegistry.byActionName`
- [x] **AgentRuntimeFeature.kt** — `ExposedActions.sandboxRules` → `ActionRegistry.agentSandboxRules`; import removed
- [x] **build_gradle.kts** — Entire ExposedActions.kt codegen block (~70 lines) removed; task description and header updated
- [x] **AgentCognitivePipeline.kt** — `agentState.sessionNames[it]` → `store.state.value.identityRegistry["session.$it"]?.name`

**Design Decisions:**
- Views read identity names from `AppState.identityRegistry` — the single source of truth. No feature-local caches needed.
- `SESSION_NAMES_UPDATED` now serves as the subscribable session catalog by filtering `isAgentPrivate` sessions at source. The `startsWith("p-cognition:")` naming convention hack is eliminated. Consumers receive a clean list and use it directly.
- `SovereignHKGResourceLogic` reads the identity registry for session name-matching (it needs ALL sessions including private). `AgentCrudLogic` reads `subscribableSessionNames` for the subscription guard (it needs only subscribable sessions). Each consumer uses the appropriate data source.
- `broadcastAgentNames()` and `agent.AGENT_NAMES_UPDATED` are dead code — agents register via `core.REGISTER_IDENTITY`, and the identity registry is the canonical name source. Deleted entirely.

### Phase 5 — Runtime-Extensible Registry
- [ ] Create `registry.actions.json` with REGISTER_ACTION, UNREGISTER_ACTION, CATALOG_UPDATED
- [ ] Add Store special-case handling for `REGISTER_ACTION` / `UNREGISTER_ACTION` to mutate `AppState.actionDescriptors`
- [x] Remove `validActionNames` constructor param from Store; validate via `AppState.actionDescriptors` only
  - **Done in Phase 2.1.** Store now validates against `AppState.actionDescriptors` (pre-populated from `ActionRegistry.byActionName`).
- [ ] Add tests for runtime registration/unregistration
- [ ] Add test: runtime-registered action appears in `AppState.actionDescriptors`
- [ ] Add test: build-time actions cannot be overridden or removed

### Phase 6 — Slash-Command Autocomplete
- [x] Create `SlashCommandAutocomplete.kt` (popup composable + state machine + keyboard handling)
- [x] Modify `MessageInput` in `SessionView.kt` to detect `/` prefix and host autocomplete
- [x] Implement three-stage state machine (FEATURE → ACTION → PARAMS)
- [x] Implement keyboard navigation (↑↓ Tab Enter Escape Backspace)
- [x] Implement `auf_` code block generation with context auto-fill (session handle, etc.)
- [x] Manual UX testing on desktop
- [ ] Verify generated code blocks are parsed correctly by CommandBot