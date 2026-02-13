# Unified Action Bus v2.0 — Complete Implementation Outline

**Version**: 2.2 — Revised (red-team fixes propagated)
**Status**: Phase 1 Complete / Phase 2 Ready
**Last Updated**: 2026-02-13 — Phase 1 shipped and verified

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
4. [Phase 2 — IdentityRegistry & Hierarchical Originators ⬅️ NEXT](#4-phase-2--identityregistry--hierarchical-originators)
5. [Phase 3 — Targeted Delivery & Private Envelope Absorption](#5-phase-3--targeted-delivery--private-envelope-absorption)
6. [Phase 4 — Migrate Consumers (CommandBot, Agent, Session)](#6-phase-4--migrate-consumers-commandbot-agent-session)
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
- Delivered via `Feature.onPrivateData()` — a separate handler from `handleSideEffects()`
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
│      RESPONSE_LEDGER (response: restricted, targeted)   │
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
    val handle: String,                // human-readable bus address: "gemini-flash-nr1"
                                       // only regexp [a-z0-9-] allowed; uniqueness forced by appending number
    val name: String,                  // display name: "Gemini Flash nr.1", all unicode allowed
    val parentHandle: String? = null,  // handle of parent identity (for tree structure)
    val registeredAt: Long = 0

    // FUTURE: Permissions hook — not implemented in v2.0
    // val permissions: Map<String, Boolean>? = null
)
```

[^1]: `uuid` and `handle` are both usable as identifiers, but features may use both together for cross-application uniqueness (e.g., saving agent configuration to `uuid.handle.json` eliminates most import clashes). The shorthand `id` is banned from the codebase; always use `identity` to refer to `Identity`.

### 2.4 The Four Action Types

Every action in the system falls into exactly one of four semantic types. These types are derived from two independent boolean flags (`open` and `broadcast`) plus one explicit flag (`targeted`):

| Type | `open` | `broadcast` | `targeted` | Authorization | Delivery | Example |
|---|---|---|---|---|---|---|
| **Command** | `true` | `true` | `false` | Any originator | All features | `session.POST` |
| **Event** | `false` | `true` | `false` | Owner only | All features | `session.MESSAGE_POSTED` |
| **Internal** | `false` | `false` | `false` | Owner only | Owner only | `session.LOADED` |
| **Response** | `false` | `false` | `true` | Owner only | Specified recipient | `filesystem.RESPONSE_READ` |

A targeted command (`open: true, targeted: true`) is also valid — it means "anyone can invoke, delivered to a specific recipient." No current action uses this, but it's a reasonable future pattern.

**Derived convenience properties** (on `ActionDescriptor`):
```kotlin
val isCommand: Boolean get() = open
val isEvent: Boolean get() = !open && broadcast
val isInternal: Boolean get() = !open && !broadcast && !targeted
val isResponse: Boolean get() = !open && targeted
```

These four are mutually exclusive and exhaustive for the `!open` cases. `isCommand` intentionally covers both broadcast and targeted commands.

**Validation rule**: `targeted` and `broadcast` are mutually exclusive. An action cannot be both broadcast and targeted. The codegen and Store enforce this.

### 2.5 Schema-Driven Routing (v2)

**Key change from v1**: Routing is no longer derived from parsing action name strings. The `session.internal.LOADED` / `session.publish.MESSAGE_POSTED` naming convention is retired. All actions become `feature.ACTION_NAME`. Routing behavior is determined entirely by the boolean flags in the action's schema descriptor (`open`, `broadcast`, `targeted`).

```
processAction(action):
  1. Look up descriptor: appState.actionDescriptors[action.name]
     → reject if not found
  2. Authorize originator:
     a. Extract feature-level handle: originator.substringBefore('.')
     b. If descriptor.open → open (any originator)
     c. If !descriptor.open → require feature match (originator prefix == descriptor.featureName)
     d. FUTURE: Check requiredPermissions against IdentityRegistry grants
  3. Lifecycle guard
  4. Route:
     a. action.targetRecipient != null  →  TARGETED: deliver to recipient feature only
        (only valid when descriptor.targeted == true; reject otherwise)
        (only valid when originator prefix == descriptor.featureName; reject otherwise)
     b. !descriptor.broadcast           →  INTERNAL: deliver to owning feature only
     c. descriptor.broadcast            →  BROADCAST: deliver to all features
  5. For each target: reducer(state, action) → new state
  6. For each target: handleSideEffects(action, store, prev, new)
```

**Why this is better than name parsing**:
- A typo in an action name (`session.interal.LOADED`) no longer silently changes routing — the descriptor's boolean flags are the single source of truth
- Routing rules are explicit, auditable, and visible in the manifest
- No special-case parsing for `system.*` actions — system actions are just `open: false` actions owned by feature `"system"`

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
            "open": true,
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
            "open": false,
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
            "open": false,
            "broadcast": true,
            "targeted": false,
            "payload_schema": { "...": "..." }
        },
        {
            "action_name": "session.RESPONSE_LEDGER",
            "summary": "Delivers formatted ledger content to the requester in response to session.REQUEST_LEDGER.",
            "open": false,
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
- `open: false, broadcast: false, targeted: false` → INTERNAL (replaces old `internal.*` convention)
- `open: true/false, broadcast: true` → BROADCAST (replaces old `publish.*` and public actions)
- `targeted: true` → TARGETED (replaces old private envelopes). `open` flag controls authorization only.

**Validation rules** (enforced by codegen and Store):
- `targeted: true` and `broadcast: true` are mutually exclusive
- If `targeted: true`, then `broadcast` must be `false`

**Note on `permissions`**: The feature-level `permissions[]` array is declaration-only in v2.0. It appears in the manifest for planning purposes — the codegen reads it and stores it in the `FeatureDescriptor`, but no runtime enforcement is built. This ensures the schema is forward-compatible when the permission system ships.

### 3.3 Envelope Migration

Former `private_envelopes` entries become actions with `"targeted": true`:

```json
{
    "action_name": "filesystem.RESPONSE_READ",
    "summary": "Delivers file content to the requesting feature.",
    "open": false,
    "broadcast": false,
    "targeted": true,
    "payload_schema": { "...": "..." }
}
```

### 3.4 Action Name Migration

All action names are flattened to the `feature.ACTION_NAME` convention. The old `internal.` / `publish.` infixes are removed. This is a mechanical rename:

| Old Name | New Name | Notes |
|---|---|---|
| `session.internal.LOADED` | `session.LOADED` | `open: false, broadcast: false` replaces `internal.` |
| `session.publish.MESSAGE_POSTED` | `session.MESSAGE_POSTED` | `open: false, broadcast: true` replaces `publish.` |
| `session.publish.SESSION_UPDATED` | `session.SESSION_UPDATED` | Same |
| `system.publish.INITIALIZING` | `system.INITIALIZING` | `open: false, broadcast: true` (system is just a feature) |
| `system.publish.STARTING` | `system.STARTING` | Same |
| `system.publish.CLOSING` | `system.CLOSING` | Same |
| `commandbot.internal.STAGE_APPROVAL` | `commandbot.STAGE_APPROVAL` | `open: false, broadcast: false` |
| `commandbot.internal.RESOLVE_APPROVAL` | `commandbot.RESOLVE_APPROVAL` | `open: false, broadcast: false` |
| `commandbot.publish.ACTION_CREATED` | `commandbot.ACTION_CREATED` | `open: false, broadcast: true` |
| `filesystem.response.read` | `filesystem.RESPONSE_READ` | `targeted: true` |
| `core.response.CONFIRMATION` | `core.RESPONSE_CONFIRMATION` | `targeted: true` |
| `session.response.ledger` | `session.RESPONSE_LEDGER` | `targeted: true` |

The generated `ActionNames` constants update accordingly. The typealias shim (Section 3.7) ensures existing code compiles during the transition.

**Pre-migration manual check**: Resolve any payload schema divergences between `exposedToAgents` and `listensFor` for actions that appear in both (e.g., `session.CLONE` uses `sourceSession` in `exposedToAgents` but `session` in `listensFor`). The `listensFor` schema is canonical — it's what the reducer actually parses.

### 3.5 Manifest Migration Script

A one-time script that:

1. Reads each `*.actions.json`
2. Merges `listensFor` → `actions` with `open: true, broadcast: true` (Commands), or `open: false, broadcast: false` for actions that were previously `internal`
3. Merges `publishes` → `actions` — those with `internal.` prefix get `open: false, broadcast: false`; those with `publish.` prefix get `open: false, broadcast: true`
4. Merges `private_envelopes` → `actions` with `open: false, broadcast: false, targeted: true`
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
        const val SESSION_RESPONSE_LEDGER = "session.RESPONSE_LEDGER"
        const val SYSTEM_INITIALIZING = "system.INITIALIZING"
        const val SYSTEM_STARTING = "system.STARTING"
        const val SYSTEM_CLOSING = "system.CLOSING"
        const val COMMANDBOT_APPROVE = "commandbot.APPROVE"
        const val COMMANDBOT_DENY = "commandbot.DENY"
        const val COMMANDBOT_STAGE_APPROVAL = "commandbot.STAGE_APPROVAL"
        const val COMMANDBOT_ACTION_CREATED = "commandbot.ACTION_CREATED"
        const val FILESYSTEM_RESPONSE_READ = "filesystem.RESPONSE_READ"
        const val CORE_RESPONSE_CONFIRMATION = "core.RESPONSE_CONFIRMATION"
        const val CORE_REGISTER_IDENTITY = "core.REGISTER_IDENTITY"
        const val CORE_UNREGISTER_IDENTITY = "core.UNREGISTER_IDENTITY"
        const val CORE_IDENTITY_REGISTRY_UPDATED = "core.IDENTITY_REGISTRY_UPDATED"
        // ... all action names

        val allActionNames: Set<String> = setOf(
            SESSION_POST, SESSION_CREATE, SESSION_LOADED,
            SESSION_MESSAGE_POSTED, SESSION_RESPONSE_LEDGER,
            FILESYSTEM_RESPONSE_READ, CORE_RESPONSE_CONFIRMATION,
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
        val subpathPrefixTemplate: String,
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
        val open: Boolean,
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
                    open = true,
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
                    open = false,
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
| `session.CREATE` | `exposedToAgents` missing `isHidden`, `isAgentPrivate` | Superset — no issue |
| `session.LIST_SESSIONS` | `exposedToAgents` missing `responseSession` | Auto-filled by CommandBot — no issue |
| `filesystem.SYSTEM_WRITE` | `listensFor` has `encrypt` not in agent schema | Sandboxing rewrites `encrypt` → `"false"` — no issue |
| `filesystem.SYSTEM_LIST` | `listensFor` has `correlationId` not in agent schema | Optional field — no issue |

**Statistics**: 168 total actions (105 commands, 18 events, 37 internal, 8 targeted). 63 action name renames. 15 agent-exposed actions preserved with full metadata.

---

## 4. Phase 2 — IdentityRegistry & Hierarchical Originators ⬅️ NEXT

**Goal**: Consolidate all identity tracking into the `identityRegistry` on `AppState`. Every bus participant — features, users, agents, sessions — registers an Identity with a handle. The Store uses hierarchical originator resolution backed by this registry. Feature identities are seeded directly by the Store at boot, bypassing the action bus.

### 4.1 Enhanced Identity Data Class

```kotlin
@Serializable
data class Identity(
    /**
     * Globally unique, system-assigned identifier.
     * Null for features — their handles are stable across restarts.
     * Generated for ephemeral entities (users, agents, sessions, ...).
     */
    val uuid: String?,
    /**
     * Human-readable bus address. This is what appears as action.originator
     * and what other systems use to reference this entity.
     * Only [a-z0-9-] characters allowed. Uniqueness enforced by appending numbers.
     * Examples: "session", "session.chat1", "agent.gemini-flash-x", "core.alice-2"
     */
    val handle: String,
    /** Display name shown in the UI. Full Unicode allowed. */
    val name: String,
    /**
     * Handle of the parent identity. Forms a tree:
     *   "session.chat1" → parent is "session"
     *   "agent.gemini-x" → parent is "agent"
     *   "core.alice" → parent is "core"
     * Root identities (features, system) have parentHandle = null.
     */
    val parentHandle: String? = null,
    /** When this identity was registered. */
    val registeredAt: Long = 0

    // FUTURE: Permissions grants — paved, not implemented in v2.0.
    // val permissions: Map<String, Boolean>? = null
)
```

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

// TODO: This step needs improving. "core.REGISTER_IDENTITY" should be responsible for creating the uuid and making sure that handle is valid and not taken already. core should respond to the command requester with core.RESPONSE_REGISTER_IDENTITY that inludes the issued uuid, valid handle and payload including the original request OR an error code and message stating what went wrong. Every action should also carry an uuid themselves so they can easily be mapped in the receiving end to the right request. So session registering identity for `chat-cats` when a `session.chat-cats` already exist gets a response back with `session.chat-cats-2` as the handle and session can match the request to the response via actions uuid. This moves the duplicated id management code from features to store.

```json
{
    "action_name": "core.REGISTER_IDENTITY",
    "summary": "Register a new identity in the universal registry. Any feature can register identities for its sub-entities.",
    "open": true,
    "broadcast": true,
    "targeted": false,
    "payload_schema": {
        "type": "object",
        "properties": {
            "uuid":          { "type": ["string", "null"], "description": "Globally unique ID. Null for features." },
            "handle":        { "type": "string", "description": "Bus address (e.g., 'agent.gemini-flash-x')." },
            "name":          { "type": "string", "description": "Human-readable display name." },
            "parentHandle":  { "type": ["string", "null"], "description": "Handle of the parent identity." }
        },
        "required": ["handle", "name"]
    }
},
{
    "action_name": "core.UNREGISTER_IDENTITY",
    "summary": "Remove an identity from the registry. Cascades: children are also removed.",
    "open": true,
    "broadcast": true,
    "targeted": false,
    "payload_schema": {
        "type": "object",
        "properties": {
            "handle": { "type": "string", "description": "Handle of the identity to remove." }
        },
        "required": ["handle"]
    }
},
{
    "action_name": "core.IDENTITY_REGISTRY_UPDATED",
    "summary": "Broadcast after any change to the identity registry. Replaces both core.IDENTITIES_UPDATED and agent.AGENT_NAMES_UPDATED.",
    "open": false,
    "broadcast": true,
    "targeted": false
}
```

//TODO: No speacial store handling needed. CoreFeature can still take care of all the business logic as it is a privileged "companion" class to the core classes. This keeps the store clean of business logic and hides it all in the CoreFeature files.

**Special Store handling**: Because `identityRegistry` lives on `AppState` (not `CoreState`), the Store intercepts `core.REGISTER_IDENTITY` and `core.UNREGISTER_IDENTITY` in `processAction` and applies mutations to `AppState.identityRegistry` directly, before the normal reducer fold. This is the same pattern used for `actionDescriptors` in Phase 5 — both are system infrastructure, not feature domain state.

CoreFeature's `handleSideEffects` still dispatches `core.IDENTITY_REGISTRY_UPDATED` after each change to inform subscribing features.

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


// v v v v v   TODO: HUMAN CHECK Needed for all from this point forward    v v v v v

Sub-entities (sessions, agents, users) register via `core.REGISTER_IDENTITY` through the action bus during `STARTING`, which is the correct lifecycle phase for runtime data.

### 4.5 Sub-Entity Registration

**During `SYSTEM_STARTING`** (after data loaded from disk):

```kotlin
// After loading sessions from disk, register each session:
store.deferredDispatch(this.identity.handle, Action(Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("uuid", platformDependencies.generateUUID())
    put("handle", "session.${session.id}")
    put("name", session.name)
    put("parentHandle", "session")
}))

// After loading agents from disk, register each agent:
store.deferredDispatch(this.identity.handle, Action(Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("uuid", platformDependencies.generateUUID())
    put("handle", "agent.${agentId}")
    put("name", agentDisplayName)
    put("parentHandle", "agent")
}))
```

**At runtime**, when entities are created or destroyed:

```kotlin
// Session created:
store.deferredDispatch(this.identity.handle, Action(Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("uuid", platformDependencies.generateUUID())
    put("handle", "session.${newSession.id}")
    put("name", newSession.name)
    put("parentHandle", "session")
}))

// Agent deleted:
store.deferredDispatch(this.identity.handle, Action(Names.CORE_UNREGISTER_IDENTITY, buildJsonObject {
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
store.deferredDispatch(this.identity.handle, Action(Names.CORE_REGISTER_IDENTITY, buildJsonObject {
    put("uuid", platformDependencies.generateUUID())
    put("handle", "session.${newSession.id}")
    put("name", newSession.name)
    put("parentHandle", "session")
}))
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

| File | Change |
|---|---|
| `Identity.kt` | Rewrite: `uuid: String?`, `handle`, `parentHandle`, `registeredAt` |
| `Feature.kt` | Change `val name: String` → `val identity: Identity`; rename `onAction` → `handleSideEffects` |
| `AppState.kt` / `AppCore.kt` | Add `identityRegistry: Map<String, Identity>` to `AppState` |
| `CoreState.kt` | Remove `identityRegistry` (lives on AppState); deprecate `userIdentities`; add `activeUserHandle` |
| `CoreFeature.kt` | Add `REGISTER_IDENTITY` / `UNREGISTER_IDENTITY` handling; dispatch `IDENTITY_REGISTRY_UPDATED`; migrate boot flow |
| `core.actions.json` | Add new identity actions and broadcast event |
| `Store.kt` | Add `extractFeatureHandle()`; update authorization to schema-driven routing; update all `feature.name` references to `feature.identity.handle`; seed feature identities in `initFeatureLifecycles()`; intercept identity actions to mutate `AppState.identityRegistry` |
| `SessionFeature.kt` | Add `identity` property; register session identities on create/load; unregister on delete |
| `AgentFeature.kt` | Add `identity` property; register agent identities on create/load; unregister on delete |
| `CommandBotFeature.kt` | Add `identity` property |
| `IdentityManagerView.kt` | Read from `AppState.identityRegistry` instead of `CoreState.userIdentities` |
| All Feature implementations | IDE-assisted rename: `override val name` → `override val identity`; `onAction` → `handleSideEffects` |

### 4.13 Testing

- Existing tests pass (deprecated fields still populated during transition)
- New test: hierarchical originator `"agent.gemini-x"` authorized for restricted `agent.X` action
- New test: hierarchical originator `"agent.gemini-x"` rejected for restricted `session.Y` action
- New test: `REGISTER_IDENTITY` adds to `AppState.identityRegistry`; `UNREGISTER_IDENTITY` cascades to children (by parentHandle)
- New test: `IDENTITY_REGISTRY_UPDATED` broadcast after every registry change
- New test: Feature identities seeded during `initFeatureLifecycles()` — visible in `AppState.identityRegistry` before first action

---

## 5. Phase 3 — Targeted Delivery & Private Envelope Absorption

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
val envelope = PrivateDataEnvelope(ActionNames.Envelopes.CORE_RESPONSE_CONFIRMATION, responsePayload)
store.deliverPrivateData(this.identity.handle, request.originator, envelope)

// AFTER:
store.deferredDispatch(this.identity.handle, Action(
    name = ActionRegistry.Names.CORE_RESPONSE_CONFIRMATION,
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
        ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> { /* handle */ }
    }
}

// AFTER (merged into handleSideEffects):
override fun handleSideEffects(action: Action, store: Store, prev: FeatureState?, new: FeatureState?) {
    when (action.name) {
        // ... existing action handlers ...
        ActionRegistry.Names.FILESYSTEM_RESPONSE_READ -> {
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
| `Action.kt` / `AppCore.kt` | Add `targetRecipient: String? = null` to `Action` |
| `Store.kt` | Add targeted routing branch with `descriptor.targeted` validation; add originator enforcement for targeted actions; deprecate `deliverPrivateData` |
| `Feature.kt` | Deprecate `onPrivateData` |
| `PrivateDataEnvelope.kt` / `AppCore.kt` | Deprecate |
| `CoreFeature.kt` | Migrate `onPrivateData` → `handleSideEffects`; migrate `deliverPrivateData` → targeted dispatch |
| `SessionFeature.kt` | Same migration |
| `FilesystemFeature.kt` | Migrate `deliverPrivateData` calls to `deferredDispatch` with `targetRecipient` (per-site structural changes, not pure find-replace) |
| *(all features using private data)* | Same pattern |

---

## 6. Phase 4 — Migrate Consumers (CommandBot, Agent, Session)

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

### 6.2 Session Feature Migration

| Current | Replacement |
|---|---|
| `SessionState.identityNames: Map<String, String>` | `AppState.identityRegistry` — read directly |
| Subscribes to `CORE_IDENTITIES_UPDATED` | Subscribes to `CORE_IDENTITY_REGISTRY_UPDATED` |
| Subscribes to `AGENT_NAMES_UPDATED` | Removed — single broadcast covers all |
| `SessionView` name resolution | `appState.identityRegistry[senderHandle]?.name ?: senderHandle` |

### 6.3 Agent Feature Migration

| Current | Replacement |
|---|---|
| Dispatches `agent.AGENT_NAMES_UPDATED` | Dispatches `core.REGISTER_IDENTITY` / `core.UNREGISTER_IDENTITY` |
| Internal agent name tracking | Identities are in the registry; agent feature reads them back |

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
| `SessionFeature.kt` | Remove `identityNames` maintenance; simplify subscriptions |
| `SessionState.kt` | Deprecate then delete `identityNames` field |
| `SessionView.kt` | Read names from `AppState.identityRegistry` |
| `SessionsManagerView.kt` | Same |
| `AgentFeature.kt` | Register/unregister identities instead of broadcasting names |
| Agent prompt builder | Read from `ActionRegistry.byActionName` instead of `ExposedActions.documentation` |
| `ExposedActions.kt` (generated shim) | Delete |
| `ActionNames.kt` (generated shim) | Delete |
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
            "open": true,
            "broadcast": true,
            "targeted": false,
            "payload_schema": {
                "type": "object",
                "properties": {
                    "featureName": { "type": "string" },
                    "actionName": { "type": "string" },
                    "suffix": { "type": "string" },
                    "summary": { "type": "string" },
                    "open": { "type": "boolean" },
                    "broadcast": { "type": "boolean" },
                    "targeted": { "type": "boolean" }
                },
                "required": ["featureName", "actionName", "suffix", "open", "broadcast", "targeted"]
            }
        },
        {
            "action_name": "registry.UNREGISTER_ACTION",
            "summary": "Removes a runtime-registered action. Cannot remove build-time actions.",
            "open": true,
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
            "open": false,
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
        put("open", true)
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
- **Phase 2**: Additive — new `identityRegistry` field on `AppState`; old `userIdentities` populated during transition; `Feature.name` → `Feature.identity` with IDE-assisted migration; feature identities seeded directly by Store (no lifecycle guard issue)
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
  - **Done.** 63 renames applied. Special handling for `settings.ui.internal.INPUT_CHANGED` → `settings.UI_INPUT_CHANGED`. Envelope type names like `session.response.ledger` → `session.RESPONSE_LEDGER`.
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

### Phase 2 — IdentityRegistry & Hierarchical Originators
- [ ] Rewrite `Identity.kt`: `uuid: String?`, `handle`, `name`, `parentHandle`, `registeredAt`
- [ ] Add `identityRegistry: Map<String, Identity>` and `actionDescriptors: Map<String, ActionDescriptor>` to `AppState`
- [ ] Change `Feature.kt`: `val name: String` → `val identity: Identity`; rename `onAction` → `handleSideEffects`
- [ ] IDE-assisted global rename: `feature.name` → `feature.identity.handle` across all source files; `onAction(` → `handleSideEffects(`
- [ ] Verify: `featureStates` map keys remain unchanged (handles equal old names)
- [ ] Update all Feature implementations with `override val identity` and renamed method
- [ ] Add `core.REGISTER_IDENTITY`, `core.UNREGISTER_IDENTITY` actions to `core.actions.json` with `open`/`broadcast`/`targeted` flags
- [ ] Add `core.IDENTITY_REGISTRY_UPDATED` event action
- [ ] Implement Store interception of identity actions to mutate `AppState.identityRegistry`
- [ ] Implement CoreFeature `handleSideEffects` to dispatch `IDENTITY_REGISTRY_UPDATED` after each change
- [ ] Add `extractFeatureHandle()` to Store; update authorization to schema-driven routing using `open`/`broadcast`/`targeted`
- [ ] Update Store to reference `feature.identity.handle` instead of `feature.name`
- [ ] Seed feature identities directly in `initFeatureLifecycles()` (no action bus, no lifecycle guard issue)
- [ ] Add FUTURE permission check as commented-out code block in Store
- [ ] Wire SessionFeature to register session identities on create/load/delete (during STARTING)
- [ ] Wire AgentFeature to register agent identities on create/load/delete (during STARTING)
- [ ] Deprecate `CoreState.userIdentities` (derived getter from `AppState.identityRegistry` during transition)
- [ ] Migrate `identities.json` loading to populate `AppState.identityRegistry` with `parentHandle = "core"` and `handle = "core.{name}"`
- [ ] Unit tests: registration, unregistration, cascade by parentHandle, hierarchical auth, Feature auto-registration, feature seeding at boot

### Phase 3 — Targeted Delivery & Private Envelope Absorption
- [ ] Add `targetRecipient: String? = null` to `Action`
- [ ] Add targeted routing branch to `processAction` in Store
- [ ] Add validation: reject `targetRecipient` on non-targeted actions; reject targeted actions without `targetRecipient`
- [ ] Add originator enforcement: only the declaring feature can dispatch targeted actions
- [ ] Phase 3a: Deprecate `deliverPrivateData`, `PrivateDataEnvelope`, `onPrivateData`
- [ ] Migrate `CoreFeature.onPrivateData` → `handleSideEffects`
- [ ] Migrate `CoreFeature.deliverPrivateData` → `deferredDispatch` with `targetRecipient`
- [ ] Migrate `SessionFeature.onPrivateData` → `handleSideEffects`
- [ ] Migrate `FilesystemFeature` deliverPrivateData calls → targeted dispatch (per-site structural changes)
- [ ] Migrate all remaining features using private data
- [ ] Phase 3b: Remove deprecated types and methods from `Feature.kt`, `Store.kt`, `PrivateDataEnvelope.kt`
- [ ] Integration tests: targeted delivery through full pipeline with security checks; originator enforcement; rejection of misused targetRecipient

### Phase 4 — Migrate Consumers & Delete Shims
- [ ] Migrate `CommandBotFeature` from `ExposedActions.*` → `ActionRegistry.*`
- [ ] Delete `CommandBotFeature.knownAgentIds` and `knownAgentNames`; read from `AppState.identityRegistry` (filter by parentHandle == "agent")
- [ ] Migrate agent prompt builder from `ExposedActions.documentation` → `ActionRegistry.byActionName`
- [ ] Deprecate then delete `SessionState.identityNames`; read from `AppState.identityRegistry` in views
- [ ] Deprecate `agent.AGENT_NAMES_UPDATED`; agents use `core.REGISTER_IDENTITY` instead
- [ ] Delete `ExposedActions.kt` deprecated delegation shim
- [ ] Delete `ActionNames.kt` typealias shim
- [ ] IDE-assisted batch rename: all old constant names (`SESSION_INTERNAL_LOADED` → `SESSION_LOADED`, etc.)
- [ ] IDE-assisted batch rename: `ActionNames.` → `ActionRegistry.Names.`
- [ ] Run full test suite

### Phase 5 — Runtime-Extensible Registry
- [ ] Create `registry.actions.json` with REGISTER_ACTION, UNREGISTER_ACTION, CATALOG_UPDATED
- [ ] Add Store special-case handling for `REGISTER_ACTION` / `UNREGISTER_ACTION` to mutate `AppState.actionDescriptors`
- [ ] Remove `validActionNames` constructor param from Store; validate via `AppState.actionDescriptors` only
- [ ] Add tests for runtime registration/unregistration
- [ ] Add test: runtime-registered action appears in `AppState.actionDescriptors`
- [ ] Add test: build-time actions cannot be overridden or removed

### Phase 6 — Slash-Command Autocomplete
- [ ] Create `SlashCommandAutocomplete.kt` (popup composable + state machine + keyboard handling)
- [ ] Modify `MessageInput` in `SessionView.kt` to detect `/` prefix and host autocomplete
- [ ] Implement three-stage state machine (FEATURE → ACTION → PARAMS)
- [ ] Implement keyboard navigation (↑↓ Tab Enter Escape Backspace)
- [ ] Implement `auf_` code block generation with context auto-fill (session handle, etc.)
- [ ] Manual UX testing on desktop and mobile
- [ ] Verify generated code blocks are parsed correctly by CommandBot