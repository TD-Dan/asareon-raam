# ASAREON RAAM — Architecture

**Version 1.1.0-alpha** · Kotlin Multiplatform · Compose Multiplatform

---

## Overview

Asareon Raam is a multi-platform application for hosting and orchestrating autonomous AI agents. It provides a session-based conversational interface, a pluggable gateway to multiple AI providers, a hierarchical knowledge graph system, and a command-driven agent runtime — all wired together through a unidirectional data flow architecture with a manifest-driven action bus.

The application targets Desktop (JVM), Android, iOS, and WebAssembly (wasmJs) from a single Kotlin Multiplatform codebase, using Compose Multiplatform for all UI.


## Core Principles

**Unidirectional Data Flow (UDF).** All state changes flow through a single `Store`. Features dispatch `Action` objects; the Store validates, authorizes, reduces, and then triggers side effects. No feature mutates state directly.

**Absolute Decoupling.** Features never import each other. All inter-feature communication happens through the action bus using string-named actions with JSON payloads. A feature knows *what* it wants to say, never *who* will hear it.

**State Isolation.** Each feature's reducer receives only its own `FeatureState` slice, never the full `AppState`. Features must not read or cast other features' state from the Store — doing so violates decoupling and creates a security risk (e.g., a feature reading API keys from Settings state). The Store currently permits this access at the code level; a future version will enforce isolation with warnings (and eventually errors) for cross-feature state access.

**Manifest-Driven Contracts.** Every action, its authorization rules, payload schema, and permission requirements are declared in `*.actions.json` files. A Gradle code generator (`generateActionRegistry`) compiles these into `ActionRegistry.kt` — the single source of truth the Store validates against at runtime. Invalid actions, unauthorized originators, and missing permissions are rejected before any reducer runs.

**Hierarchical Identity.** Every participant on the action bus — features, users, agents, sessions — is an `Identity` with a dotted handle (`agent.gemini-coder-1`, `session.chat1`, `core.alice`). Permissions inherit down the tree. The identity registry on `AppState` is the authoritative source for "who exists" and "what they're allowed to do."

**Platform Ignorance.** All platform-specific I/O (filesystem, clipboard, logging, scheduling, native window decorations) is abstracted behind a single `expect class PlatformDependencies`. Features and the Store never touch platform APIs directly.


## Layered Architecture

Dependencies flow **strictly downward**. A layer may freely interact with siblings at the same level but must never import, dispatch to, or listen for broadcasts from a higher layer.

```
╔══════════════════════════════════════════════════════════════╗
║  L3  ACTORS         AgentRuntime        CommandBot          ║
║                        │  │  │             │  │             ║
╠════════════════════════╪══╪══╪═════════════╪══╪═════════════╣
║  L2  SCAFFOLDING       │  │  │          Session             ║
║                        │  │  │          │  │  │             ║
╠════════════════════════╪══╪══╪══════════╪══╪══╪═════════════╣
║  L1  SERVICES    Settings  Gateway  KnowledgeGraph          ║
║                     │                   │                   ║
╠═════════════════════╪═══════════════════╪═══════════════════╣
║  L0  FOUNDATION     Core          FileSystem                ║
║                        │                                    ║
║                   System / Store                            ║
╚══════════════════════════════════════════════════════════════╝

  Dependencies flow DOWN only. Siblings may interact freely.
```

### L0 — Foundation

The bedrock that every other layer depends on. Owns the runtime, lifecycle, and persistence primitives.

| Component | Responsibility |
|-----------|---------------|
| **System / Store** | The action bus, lifecycle sequencing (`INITIALIZING` → `RUNNING` → `CLOSING` → `SHUTDOWN`), reducer orchestration, authorization guard, permission guard, and the `AppState` container holding feature states, the action descriptor catalog, and the identity registry. |
| **Core** | UI infrastructure: active view routing, toast notifications, clipboard, confirmation dialogs, window size persistence. Owns the `CoreState` lifecycle enum that the Store reads for its lifecycle guard. Also owns the identity registry business logic (registration, unregistration, namespace enforcement, permission management) — see Identity and Permissions. |
| **FileSystem** | Sandboxed file I/O for the entire application. Every feature that persists data does so through FileSystem actions (`filesystem.READ`, `filesystem.WRITE`, `filesystem.LIST`). Sits at L0 because it is a universal dependency — including for Core's own persistence needs. Supports optional encryption on a per-write basis via a payload flag. |

### L1 — Services

Domain-specific services that provide capabilities to higher layers. May depend on L0 and on each other.

| Component | Responsibility |
|-----------|---------------|
| **Settings** | Manages the persistence, state, and UI for all application settings. Features register definitions via `settings.ADD` at startup; values are persisted through FileSystem and broadcast via `settings.VALUE_CHANGED`. |
| **Gateway** | Secure, centralized router for all generative AI requests. Single source of truth for available providers (Gemini, OpenAI, Anthropic, Inception) and their models. Enforces the `gateway:generate` permission. |
| **KnowledgeGraph** | Manages holon knowledge graphs (HKGs) organized into personas. Provides structured context to agents via a reservation system. Persists graph data through FileSystem. |

### L2 — Scaffolding

The structural layer that provides the conversational substrate actors operate within.

| Component | Responsibility |
|-----------|---------------|
| **Session** | Manages chat sessions — creation, message posting, ledger assembly, workspace files, input history. Registers session identities in the registry. Provides the conversational surface that both humans and agents interact through. |

### L3 — Actors

Active participants that observe, reason, and act within the system.

| Component | Responsibility |
|-----------|---------------|
| **AgentRuntime** | Hosts autonomous AI agents. Manages agent lifecycle (create, configure, delete), cognitive strategies (Vanilla, Sovereign), turn execution via Gateway, context gathering from Sessions and KnowledgeGraph, and NVRAM (cognitive state) persistence. |
| **CommandBot** | Monitors session transcripts for `raam_*` command directives. Validates commands against the action registry, enforces guardrails, handles approval flows for sensitive actions, and publishes `ACTION_CREATED` for domain features to dispatch. |


## The Action Bus

All communication between features flows through typed `Action` objects dispatched to the `Store`.

### Action Anatomy

```kotlin
data class Action(
    val name: String,            // e.g., "session.POST"
    val payload: JsonObject?,    // Serializable JSON data
    val originator: String?,     // Identity handle of the dispatcher
    val targetRecipient: String? // For targeted delivery only
)
```

The `originator` field is stamped by the Store at dispatch time — the caller passes the originator string to `deferredDispatch()`, and the Store copies it onto the action. This ensures every processed action has a traceable source for authorization and audit.

### Action Categories

Every action falls into exactly one category, determined by its manifest flags:

| Category | `public` | `broadcast` | `targeted` | Behavior |
|----------|----------|-------------|------------|----------|
| **Command** | ✓ | ✓ | ✗ | Any originator can dispatch; all features receive. |
| **Hidden Command** | ✓ (+hidden) | ✓ | ✗ | Like Command but not discoverable by agents/users. Feature-to-feature only. |
| **Event** | ✗ | ✓ | ✗ | Only the owning feature can dispatch; all features receive. |
| **Internal** | ✗ | ✗ | ✗ | Only the owning feature can dispatch and receive. |
| **Response** | ✗ | ✗ | ✓ | Only the owning feature can dispatch; delivered to `targetRecipient` only. |

These categories are derived properties on `ActionDescriptor`:

```kotlin
val isCommand: Boolean get() = public
val isEvent: Boolean get() = !public && broadcast
val isInternal: Boolean get() = !public && !broadcast && !targeted
val isResponse: Boolean get() = !public && targeted
val isHiddenCommand: Boolean get() = public && hidden
```

### Dispatch Model

The Store provides two dispatch methods. Both enqueue onto the same internal `deferredActionQueue` and trigger the same processing loop. The distinction is primarily semantic:

**`deferredDispatch(originator, action)`** — The primary dispatch method. Enqueues the action onto the internal queue and triggers the processing loop. This is the **recommended method for all dispatches**, especially from `handleSideEffects`, as the name communicates that the action is not processed immediately but queued for sequential processing.

**`dispatch(originator, action)`** — Convenience entry point that behaves identically: it enqueues the action and triggers the processing loop. If called while the Store is already processing (re-entrant dispatch from a reducer or side effect), it logs a `WARN`-level re-entrancy warning before enqueuing. Exists primarily for top-level callers (UI event handlers, app startup) where re-entrancy is not a concern.

**`scheduleDelayed(delayMs, originator, action)`** — Schedules an action to be dispatched after a delay via `PlatformDependencies.scheduleDelayed`. When the timer fires, it calls `deferredDispatch` to enqueue the action. Returns a cancellation handle. **Thread safety note:** the delayed callback fires on whatever thread the platform schedules it on. The internal queue is not currently synchronized — this relies on platform-specific guarantees (e.g., JVM desktop dispatching on the main thread). This is tracked for review.

**Processing loop.** `ensureProcessingLoop()` drains the queue synchronously in a `while` loop: dequeue the head action, run it through the full pipeline (guards → reduce → side effects), repeat until the queue is empty. Actions enqueued during processing (by side effects calling `deferredDispatch`) are picked up in the same loop iteration. All dispatch is ultimately sequential and synchronous — there is no concurrent action processing.

### Store Processing Pipeline

Every dispatched action passes through this sequence:

1. **Schema Lookup** — Reject unknown action names (not in `AppState.actionDescriptors`).
2. **Targeted Validation** — Reject `targetRecipient` on non-targeted actions (and vice versa).
3. **Originator Validation** — Reject originators not in the identity registry or resolvable to a known feature. Resolution checks the full handle first, then extracts the feature-level prefix (first segment before the dot).
4. **Authorization** — For non-public actions, verify the originator's feature handle matches the action's owning feature.
5. **Permission Guard** — For actions with `required_permissions`, verify the originator's effective grants include `YES` for all listed keys. Feature identities (uuid = null) are trusted and exempt. On denial, a `core.PERMISSION_DENIED` event is broadcast before the action is dropped.
6. **Lifecycle Guard** — Reject actions inappropriate for the current lifecycle phase.
7. **Route → Reduce** — Deliver to the appropriate feature(s) based on broadcast/targeted flags; run reducers.
8. **Side Effects** — Call `handleSideEffects` on receiving features with both previous and new state.

**Guard rejection behavior.** Steps 1–4 and step 6 reject actions silently — the action is dropped and a message is logged at `ERROR` level, but no notification is published on the action bus. Step 5 (permission guard) is the exception: it broadcasts `core.PERMISSION_DENIED` with the blocked action name, originator, and missing permissions, allowing observing features (e.g., CommandBot) to provide user-facing feedback.

**Known limitation: silent guard failures.** For steps 1–4 and 6, the caller receives no feedback that the action was rejected. An agent waiting for a `RETURN_*` response to an action that was silently dropped will wait indefinitely. This is a known gap and is tracked for a fix — likely a generalized `GUARD_REJECTED` notification similar to `PERMISSION_DENIED`.

### Reducer Execution

**Synchronous and single-threaded.** All reducers execute synchronously on the dispatch thread. Reducers must be pure functions — no I/O, no dispatching, no coroutine launches.

**Broadcast ordering.** For broadcast actions, the Store runs each feature's reducer via `fold` over the feature list. The feature list order is determined by the `AppContainer.features` initialization and is deterministic within a given build, but is not contractually guaranteed across refactors. Each feature's reducer sees the accumulated state from all prior features in the fold sequence. Features should not depend on this ordering; each feature's reducer should rely only on its own state slice and the action's payload.

**State isolation (current vs. target).** Reducers receive their own `FeatureState?` slice, not the full `AppState`. However, `handleSideEffects` receives a `Store` reference and can currently access `store.state.value.featureStates` to read other features' state. This violates state isolation and is a known issue — accessing another feature's state will produce warnings in a future version and eventually be blocked as an error.

**Targeted routing.** For targeted actions, the Store resolves the `targetRecipient` at the feature level only — `"session.chat1"` delivers to the `"session"` feature. Sub-entity targeting is the receiving feature's responsibility.

**Non-broadcast routing.** For non-broadcast, non-targeted actions (both internal and public non-broadcast like `REGISTER_IDENTITY`), delivery goes to the owning feature only.

### Side Effects

**Synchronous execution.** `handleSideEffects` is called synchronously on the dispatch thread, immediately after the reducer. This means the processing loop is blocked until all side effects for the current action complete. For features that need asynchronous work (network requests, long-running computations), the feature must launch coroutines internally using its own `CoroutineScope`. The `AppContainer` provides a `resilientCoroutineScope` (with `SupervisorJob` and a global exception handler) to features that need it. A future version may introduce async side-effect execution if feasible, but no timeline is set.

**Delivery scope mirrors routing.** Side effects are called on the same set of features that received the reducer call: all features for broadcasts, owning feature for non-broadcast, recipient feature for targeted.

**Exception handling.** The entire reduce-then-side-effects sequence for a single action is wrapped in a `try/catch`. If any feature's `handleSideEffects` throws during a broadcast, the exception is caught, a `FATAL` log is emitted with a reference ID, and a toast is shown to the user. However, **remaining features' side effects for that action are skipped**. Processing then continues with the next queued action. This means a single feature's exception can prevent other features from reacting to a broadcast. Features should handle their own exceptions internally to prevent this.


## Action Manifests

Action contracts are declared in `*.actions.json` manifest files, co-located with their feature source code. These manifests are the single source of truth for the action bus schema.

### Manifest Structure

Each manifest file describes one feature and all of its actions:

```json
{
  "feature_name": "session",
  "summary": "Human-readable description of the feature's role.",
  "permissions": [
    {
      "key": "session:read",
      "description": "Read session messages and metadata",
      "dangerLevel": "LOW"
    },
    {
      "key": "session:write",
      "description": "Post messages and modify session content",
      "dangerLevel": "CAUTION"
    }
  ],
  "actions": [
    {
      "action_name": "session.POST",
      "summary": "Posts a message to a session.",
      "public": true,
      "broadcast": true,
      "targeted": false,
      "hidden": false,
      "payload_schema": {
        "type": "object",
        "properties": {
          "sessionId": {
            "type": "string",
            "description": "Target session identifier."
          },
          "content": {
            "type": "string",
            "description": "The message text."
          }
        },
        "required": ["sessionId", "content"]
      },
      "required_permissions": ["session:write"],
    }
  ]
}
```

### Top-Level Fields

| Field | Required | Description |
|-------|----------|-------------|
| `feature_name` | ✓ | The feature's handle on the bus (e.g., `"session"`, `"agent"`, `"core"`). |
| `summary` | ✓ | Human-readable description of the feature. |
| `permissions` | ✓ | Array of permission declarations owned by this feature (may be empty `[]`). Each entry is an object with `key`, `description`, and `dangerLevel`. |
| `actions` | ✓ | Array of action descriptors. |

### Permission Declarations

Each entry in `permissions` declares a permission key that this feature owns:

| Field | Required | Description |
|-------|----------|-------------|
| `key` | ✓ | The permission key in `domain:capability` format (exactly one colon). Max 64 characters. |
| `description` | ✓ | Human-readable explanation of what the permission grants. |
| `dangerLevel` | ✓ | One of `LOW`, `CAUTION`, or `DANGER`. Drives UI color-coding in the Permission Manager. |

### Action Descriptors

Each entry in `actions` declares one action on the bus:

| Field | Required | Description |
|-------|----------|-------------|
| `action_name` | ✓ | Full action name: `"featureName.SUFFIX"` (e.g., `"session.POST"`). |
| `summary` | ✓ | Human-readable description. Shown to agents for discoverable actions. |
| `public` | ✓ | If `true`, any originator can dispatch. If `false`, only the owning feature. |
| `broadcast` | ✓ | If `true`, all features receive. If `false`, only the owning feature (or targeted recipient). |
| `targeted` | ✓ | If `true`, delivered to `targetRecipient` only. Mutually exclusive with `broadcast`. |
| `hidden` | | Default `false`. If `true`, the action is not discoverable by agents or users. Only valid on public actions. |
| `payload_schema` | | JSON Schema-style description of the payload. Used for documentation and code generation; **not currently validated at runtime** (planned for a future version). |
| `required_permissions` | public only | Array of permission keys the originator must hold. **Required** on all public actions (even if empty `[]`). Not required on non-public actions (the owning feature is already trusted). |

### Build-Time Validations

The `generateActionRegistry` Gradle task enforces these rules at compile time — violations fail the build:

- `targeted` + `broadcast` is forbidden (mutually exclusive delivery).
- `hidden` on non-public actions is forbidden (redundant — non-public actions are already non-discoverable).
- Public actions must declare `required_permissions`.
- All `required_permissions` must reference a permission key declared in some feature's `permissions` array.
- Permission keys must use `domain:capability` format with exactly one colon, max 64 characters.
- Danger levels must be `LOW`, `CAUTION`, or `DANGER`.


## Manifest-Driven Code Generation

The `generateActionRegistry` Gradle task runs before all Kotlin compilation (via `dependsOn` on every `KotlinCompile` task) and produces `ActionRegistry.kt` — a single generated file placed in `build/generated/kotlin/` and added to the `commonMain` source set.

The Gradle task walks all `*.actions.json` files under `src/commonMain/kotlin/app/auf/`, parses them, runs the build-time validations, and generates a Kotlin `object ActionRegistry` with five sections:

**Section 1: Name Constants.** `ActionRegistry.Names` provides compile-time string constants for every action name (e.g., `ActionRegistry.Names.SESSION_POST`). An `allActionNames: Set<String>` collects all constants. All production and test code uses these constants — never hard-coded strings.

**Section 2: Descriptor Data Classes.** `ActionDescriptor`, `FeatureDescriptor`, `PayloadField`, `SandboxRule`, and `PermissionDeclaration`. These are runtime-queryable metadata for every action, carrying the full manifest information (flags, payload schema, permissions, sandbox rules).

**Section 3: Feature Registry.** `ActionRegistry.features` — a `Map<String, FeatureDescriptor>` containing the full descriptor tree, keyed by feature name. Each `FeatureDescriptor` contains the feature's summary, declared permissions, and a map of its `ActionDescriptor` entries.

**Section 4: Derived Views.** Pre-computed maps for common lookups:
- `byActionName` — all descriptors keyed by full action name (e.g., `"session.POST"`). This is the map that `AppState.actionDescriptors` defaults to.
- `visibleActions` — excludes hidden actions. Used by agents and CommandBot for action discovery.
- `agentAutoFillRules` — auto-fill templates for agent-dispatched actions.
- `agentSandboxRules` — sandbox path rules for agent-dispatched actions.

**Section 5: Permission Declarations.** `ActionRegistry.permissionDeclarations` — all declared permission keys with descriptions and danger levels, collected from all feature manifests.


## Application State

### AppState

The root state container for the entire application:

```kotlin
data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap(),
    val actionDescriptors: Map<String, ActionDescriptor> = ActionRegistry.byActionName,
    val identityRegistry: Map<String, Identity> = emptyMap()
)
```

`featureStates` holds each feature's state slice, keyed by the feature's handle (e.g., `"core"`, `"session"`, `"agent"`). Features read their own slice from this map; the Store passes it to the reducer and receives the updated slice back.

`actionDescriptors` and `identityRegistry` are application infrastructure — they live on `AppState` (not in any `FeatureState`) because the Store needs them before any feature reducer runs. They are pre-populated at construction time and mutated via dedicated Store methods rather than through the action bus (see Architectural Exceptions).

### FeatureState

Each feature defines its own state class implementing the `FeatureState` marker interface. State classes are `@Serializable` data classes. Transient fields (annotated `@Transient`) are not persisted and reset to defaults on deserialization.


## Identity and Permissions

### Identity Structure

Every addressable entity is an `Identity`:

```kotlin
data class Identity(
    val uuid: String?,        // Null for features (stable handles)
    val localHandle: String,  // Leaf-level: [a-z][a-z0-9-]*
    val handle: String,       // Full bus address: "parent.local"
    val name: String,         // Display name (full Unicode)
    val parentHandle: String?, // Originator that registered this identity
    val registeredAt: Long,
    val permissions: Map<String, PermissionGrant>,
    val displayColor: String?,  // "#RRGGBB" hex
    val displayIcon: String?,   // Material icon key
    val displayEmoji: String?   // Emoji override (precedence over icon)
)
```

Typed wrappers `IdentityHandle` and `IdentityUUID` (Kotlin value classes) provide compile-time safety at zero runtime cost: a session handle cannot accidentally be passed where a resource UUID is expected. Validator functions `stringIsUUID()`, `stringIsHandle()`, and `requireUUID()` guard trust boundaries.

### Identity Tree

Identities form a hierarchy via `parentHandle`. The originator that registers an identity automatically becomes its parent — no feature can register identities outside its own namespace.

```
core                          (feature — root)
├── core.alice                (user identity)
└── core.default-user         (user identity)

session                       (feature — root)
├── session.chat1             (session identity)
└── session.private-notes     (session identity)

agent                         (feature — root)
├── agent.gemini-coder-1      (agent identity)
│   └── agent.gemini-coder-1.sub-task
└── agent.mercury             (agent identity)
```

**Identity registration** flows through the action bus: a feature dispatches `core.REGISTER_IDENTITY` with a `name` (and optional `localHandle`, `uuid`, display fields). CoreFeature validates the request, deduplicates the handle among siblings, creates the identity, and returns the approved identity via a targeted `core.RETURN_REGISTER_IDENTITY` response. **Idempotent reclaim:** if a UUID already exists in the registry (e.g., after restart), the existing identity is returned without creating a duplicate.

**Unregistration** via `core.UNREGISTER_IDENTITY` cascades — all descendants are also removed. Namespace enforcement ensures originators can only unregister identities within their own namespace.

### Identity Resolution

The identity registry supports flexible resolution via extension functions on `Map<String, Identity>`:

1. Exact handle match (O(1) map lookup)
2. UUID match
3. localHandle match
4. Case-insensitive display name match

This allows features like AgentRuntime to accept `agentId` as a UUID, handle, localHandle, or display name, all resolved through the same path. A scoped variant restricts resolution to children of a given parent handle. A `suggestMatches()` function provides "did you mean?" suggestions for error messages.

### Permission Resolution

Permissions are keyed by `domain:capability` strings (e.g., `gateway:generate`, `filesystem:workspace`). Each identity carries explicit `PermissionGrant` entries. Effective permissions are resolved by `Store.resolveEffectivePermissions()`, which walks the parent chain from root to leaf — each layer can override the inherited value. The levels, in ascending privilege order: `NO` → `ASK` → `APP_LIFETIME` → `YES`.

Feature identities (uuid = null) are always trusted and bypass permission checks entirely.

**Default permissions** are applied to feature identities at boot via `DefaultPermissions.grantsFor()`. These are compile-time decisions defined in `DefaultPermissions.kt` — not runtime configuration. Child identities inherit from their parent feature naturally via the resolution walk. For example, the `"agent"` feature identity receives `filesystem:workspace=YES` and `session:write=YES`, and `"agent.mercury"` inherits these without explicit grants.

**Controlled escalation.** A child identity may override a parent's grant to a higher level. This is permitted but logged at `WARN` for audit and is only possible via the core maintained permissions ui.

**ASK and APP_LIFETIME** exist in the enum for forward-compatibility but are currently treated as `NO` by the Store guard until the ASK approval system is implemented.

**Persistence.** Permissions are persisted as part of the identity registry via `identities.json` through FileSystem. On load, compile-time defaults are applied first, then persisted overrides are merged on top — ensuring that persisted Permission Manager edits take precedence over defaults.


## Lifecycle Sequence

```
BOOTING → INITIALIZING → RUNNING → CLOSING → SHUTDOWN
```

| Phase | Permitted Actions | Purpose |
|-------|-------------------|---------|
| `BOOTING` | Only `system.INITIALIZING` | Pre-runtime; Store and features exist but are inert. |
| `INITIALIZING` | All | Synchronous setup. Features register settings definitions via `settings.ADD`. No async work. |
| `RUNNING` | All except `INITIALIZING` and `RUNNING` | Normal operation. |
| `CLOSING` | All except `INITIALIZING`, `RUNNING`, `CLOSING` | Graceful shutdown. Features flush unsaved state via `deferredDispatch` (e.g., `filesystem.WRITE`). All deferred actions drain before `SHUTDOWN`. |
| `SHUTDOWN` | Only `system.SHUTDOWN` | Hard lockdown. No further actions accepted. |

The lifecycle is managed by CoreFeature's reducer in response to system actions (`system.INITIALIZING`, `system.RUNNING`, `system.CLOSING`, `system.SHUTDOWN`), which are dispatched by the host application entry point.


## Feature Contract

Every feature implements the `Feature` interface:

```kotlin
interface Feature {
    val identity: Identity              // Bus handle and display name
    fun reducer(state, action) → state  // Pure state transition
    fun handleSideEffects(action, store, prevState, newState)  // I/O, follow-up dispatches
    fun init(store)                     // One-time setup
    val composableProvider              // Optional UI (views, ribbon, menu)
}
```

**Reducer** — Pure, synchronous. Receives the action and current feature state; returns new state. No I/O, no dispatching. The return type is `FeatureState?` — returning `null` removes the feature's state from the map.

**handleSideEffects** — Impure, synchronous. Called after the reducer with both previous and new state, plus a `Store` reference for dispatching follow-up actions. This is where features perform file I/O, launch coroutines for network requests, dispatch follow-up actions (via `deferredDispatch`), and interact with platform services.

**init(store)** — Called once during `initFeatureLifecycles()`, before any actions are dispatched. Used for one-time wiring that doesn't fit the action model.

**ComposableProvider** — Optional. Supplies stage views (keyed by view ID), ribbon icons, partial views (embeddable fragments), and menu items. The `GlobalActionRibbon` and `MainAppContent` composables iterate all features to assemble the UI without knowing which features exist.


## Cognitive Architecture (Agents)

Agents are driven by pluggable `CognitiveStrategy` implementations that define how an agent thinks:

- **Resource Slots** — Declarative slots for system prompts, constitutions, bootloaders.
- **Prompt Building** — The strategy declares prompt structure via `PromptBuilder`; the pipeline handles collapse, budgeting, and assembly.
- **Post-Processing** — Sentinel checks on raw responses to detect state transitions (e.g., `BOOTING → AWAKE`), integrity failures, or halt conditions.
- **Lifecycle Hooks** — `onAgentRegistered`, `onAgentConfigChanged`, `ensureInfrastructure` for strategy-specific setup and maintenance.

The runtime speaks exclusively to the `CognitiveStrategy` interface. Strategy-specific behavior is fully encapsulated — no implicit strategy checks in the runtime, pipeline, or CRUD logic.


## Platform Abstraction

`PlatformDependencies` (expect/actual) provides:

- File I/O (read, write, list, delete, copy, zip — text and binary)
- Directory management and sandbox path resolution (via `BasePath.APP_ZONE` and `BasePath.USER_ZONE`)
- System utilities (UUID generation, timestamps, clipboard, logging)
- Scheduling (delayed callbacks with cancellation)
- Native window decorations

Each target (JVM, Android, iOS, wasmJs) provides its own `actual` implementation. Features interact with the platform exclusively through this interface and through FileSystem actions.


## Application Wiring

`AppContainer` is a pure, stateless factory that instantiates and wires together all application components. It contains no behavioral logic — it only connects the parts.

```kotlin
class AppContainer(platformDependencies, appCoroutineScope) {
    val features: List<Feature> = listOf(
        CoreFeature(platform),
        SettingsFeature(platform),
        FileSystemFeature(platform),
        SessionFeature(platform, scope),
        GatewayFeature(platform, scope, providers),
        AgentRuntimeFeature(platform, scope),
        KnowledgeGraphFeature(platform, scope),
        CommandBotFeature(platform)
    )

    val store = Store(AppState(), features, platformDependencies)
}
```

**Feature list order.** The order of features in this list determines broadcast reducer execution order (via `fold`). While the order is deterministic within a build, it is not contractually guaranteed and should not be relied upon by feature logic. Foundation features (Core, Settings, FileSystem) are listed first by convention, followed by Services, Scaffolding, and Actors.

**Coroutine scope.** Features that perform asynchronous work receive a `resilientCoroutineScope` backed by `SupervisorJob` and a global exception handler. This ensures that a single coroutine failure does not cancel sibling operations.


## Architectural Exceptions

Two aspects of `AppState` are mutated outside the normal action bus flow. Both are documented here as intentional exceptions to the UDF principle:

**Identity Registry.** `Store.updateIdentityRegistry()` directly mutates `AppState.identityRegistry`. This exists because the identity registry is needed before feature reducers can run (originator validation happens in the Store pipeline), creating a chicken-and-egg problem if identity mutations had to flow through actions. The business logic (validation, deduplication, namespace enforcement) remains in `CoreFeature.handleSideEffects`; the Store owns only the state container. All identity mutations are followed by a `core.IDENTITY_REGISTRY_UPDATED` broadcast so features can react.

**Feature Identity Seeding.** During `Store.initFeatureLifecycles()`, feature identities and their default permissions are seeded directly into `AppState.identityRegistry` — before any action is dispatched. This is necessary because during `BOOTING`, only `system.INITIALIZING` is permitted by the lifecycle guard, and identity registration requires `core.REGISTER_IDENTITY` which would be blocked.


## Testing Architecture

Testing is an integral part of the system architecture, not a separate concern. The unidirectional data flow, absolute decoupling, and manifest-driven contracts that define the runtime architecture also define how the system is tested. Every feature, guard, and cross-feature workflow has a corresponding test tier with explicit mandates, infrastructure, and patterns.

The testing framework is organized into five tiers of increasing scope, from pure function tests to real-platform integration. A dedicated `TestEnvironment` builder and `RecordingStore` allow multi-feature integration tests to run against the real Store — including its guards, lifecycle, and routing — while recording all processed actions for assertion. Fake infrastructure (`FakePlatformDependencies`, `FakeStore`) isolates platform I/O and side effects where needed.

The full testing strategy, tier definitions, infrastructure contracts, and authoring guidelines are documented in the companion document **02-Unit-testing**.


## Build and Targets

**Kotlin Multiplatform** with **Compose Multiplatform** for UI. JVM target requires JDK 21.

| Target | Platform | HTTP Client | Status |
|--------|----------|-------------|--------|
| `jvm` | Desktop (Windows, macOS, Linux) | Ktor CIO | Windows released, Linux and macOS planned |
| `androidTarget` | Android (min SDK per version catalog) | Ktor Android | Planned |
| `iosX64`, `iosArm64`, `iosSimulatorArm64` | iOS | Ktor Darwin | Planned |
| `wasmJs` | Browser | Ktor JS | Planned |

Key dependencies: Ktor (HTTP client), kotlinx.serialization (JSON), JNA (desktop native integration), Compose Material 3.

The `generateActionRegistry` Gradle task runs before all Kotlin compilation (`dependsOn` on every `KotlinCompile` task), ensuring the action catalog is always in sync with the manifests. Generated output is placed in `build/generated/kotlin/` and added to the `commonMain` source set.