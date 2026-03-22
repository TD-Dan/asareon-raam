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

The `originator` is stamped by the Store at dispatch time from the caller-provided string, ensuring every processed action has a traceable source.

### Action Categories

Every action falls into exactly one category, determined by its manifest flags:

| Category | `public` | `broadcast` | `targeted` | Behavior |
|----------|----------|-------------|------------|----------|
| **Command** | ✓ | ✓ | ✗ | Any originator can dispatch; all features receive. |
| **Hidden Command** | ✓ (+hidden) | ✓ | ✗ | Like Command but not discoverable by agents/users. Feature-to-feature only. |
| **Event** | ✗ | ✓ | ✗ | Only the owning feature can dispatch; all features receive. |
| **Internal** | ✗ | ✗ | ✗ | Only the owning feature can dispatch and receive. |
| **Response** | ✗ | ✗ | ✓ | Only the owning feature can dispatch; delivered to `targetRecipient` only. |

### Dispatch Model

Both dispatch methods enqueue onto the same internal `deferredActionQueue` and trigger the same processing loop:

**`deferredDispatch(originator, action)`** — The primary dispatch method. Recommended for all dispatches, especially from `handleSideEffects`, as the name communicates that the action is queued for sequential processing, not executed immediately.

**`dispatch(originator, action)`** — Convenience alias that behaves identically but logs a `WARN` if called re-entrantly. Exists for top-level callers (UI event handlers, app startup) where re-entrancy is not a concern.

**`scheduleDelayed(delayMs, originator, action)`** — Schedules an action via `PlatformDependencies.scheduleDelayed`; when the timer fires, it calls `deferredDispatch`. Returns a cancellation handle. **Thread safety note:** the internal queue is not currently synchronized — this relies on platform-specific guarantees. Tracked for review.

**Processing loop.** `ensureProcessingLoop()` drains the queue synchronously in a `while` loop: dequeue → full pipeline (guards → reduce → side effects) → repeat. Actions enqueued during processing are picked up in the same loop iteration. All dispatch is sequential — there is no concurrent action processing.

### Store Processing Pipeline

Every dispatched action passes through this sequence:

1. **Schema Lookup** — Reject unknown action names (not in `AppState.actionDescriptors`).
2. **Targeted Validation** — Reject `targetRecipient` on non-targeted actions (and vice versa).
3. **Originator Validation** — Reject originators not in the identity registry or resolvable to a known feature.
4. **Authorization** — For non-public actions, verify the originator's feature handle matches the action's owning feature.
5. **Permission Guard** — For actions with `required_permissions`, verify the originator's effective grants include `YES` for all listed keys. Feature identities (uuid = null) are trusted and exempt. On denial, a `core.PERMISSION_DENIED` event is broadcast before the action is dropped.
6. **Lifecycle Guard** — Reject actions inappropriate for the current lifecycle phase.
7. **Route → Reduce** — Deliver to the appropriate feature(s) based on broadcast/targeted flags; run reducers.
8. **Side Effects** — Call `handleSideEffects` on receiving features with both previous and new state.

**Guard rejection behavior.** Steps 1–4 and 6 reject actions silently — the action is dropped with an `ERROR` log, but no bus notification. Step 5 (permission guard) is the exception: it broadcasts `core.PERMISSION_DENIED` with the blocked action name, originator, and missing permissions.

**Known limitation: silent guard failures.** For steps 1–4 and 6, the caller receives no feedback. An agent waiting for a `RETURN_*` response to a silently dropped action will wait indefinitely. Tracked for a fix — likely a generalized `GUARD_REJECTED` notification.

### Routing and Reducer Ordering

**Broadcast:** all features' reducers run via `fold` over the feature list. Order is determined by `AppContainer.features` initialization — deterministic within a build but not contractually guaranteed. Each feature sees the accumulated state from prior features in the fold. Features should not depend on this ordering.

**Targeted:** the Store resolves `targetRecipient` at the feature level only — `"session.chat1"` delivers to `"session"`. Sub-entity targeting is the feature's responsibility.

**Non-broadcast, non-targeted:** delivery goes to the owning feature only.

### Side Effects

**Synchronous execution.** `handleSideEffects` runs on the dispatch thread immediately after the reducer, blocking the processing loop. Features needing async work must launch coroutines on their own scope. A future version may introduce async side-effect execution, but no timeline is set.

**Delivery scope mirrors routing.** All features for broadcasts, owning feature for non-broadcast, recipient feature for targeted.

**Exception handling.** The reduce-then-side-effects sequence for a single action is wrapped in a `try/catch`. If any feature throws, the exception is caught, a `FATAL` log is emitted with a reference ID, and a toast is shown — but **remaining features' side effects for that action are skipped**. Features should handle their own exceptions to prevent this.


## Action Manifests

Action contracts are declared in `*.actions.json` manifest files, co-located with their feature source code.

### Manifest Structure

Each manifest describes one feature and all of its actions:

```json
{
  "feature_name": "session",
  "summary": "Manages chat sessions and conversational surfaces.",
  "permissions": [
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
      "payload_schema": {
        "type": "object",
        "properties": {
          "sessionId": { "type": "string" },
          "content": { "type": "string" }
        },
        "required": ["sessionId", "content"]
      },
      "required_permissions": ["session:write"]
    }
  ]
}
```

### Top-Level Fields

| Field | Required | Description |
|-------|----------|-------------|
| `feature_name` | ✓ | The feature's handle on the bus (e.g., `"session"`, `"agent"`, `"core"`). |
| `summary` | ✓ | Human-readable description of the feature. |
| `permissions` | ✓ | Array of permission declarations (may be empty `[]`). Each entry has `key` (`domain:capability`, one colon, max 64 chars), `description`, and `dangerLevel` (`LOW`, `CAUTION`, or `DANGER`). |
| `actions` | ✓ | Array of action descriptors. |

### Action Descriptor Fields

| Field | Required | Description |
|-------|----------|-------------|
| `action_name` | ✓ | Full name: `"feature.SUFFIX"` (e.g., `"session.POST"`). |
| `summary` | ✓ | Human-readable description. Shown to agents for discoverable actions. |
| `public` | ✓ | If `true`, any originator can dispatch. If `false`, only the owning feature. |
| `broadcast` | ✓ | If `true`, all features receive. Mutually exclusive with `targeted`. |
| `targeted` | ✓ | If `true`, delivered to `targetRecipient` only. Mutually exclusive with `broadcast`. |
| `hidden` | | Default `false`. Not discoverable by agents/users. Only valid on public actions. |
| `payload_schema` | | JSON Schema-style payload description. Used for docs and codegen; **not validated at runtime** (planned). |
| `required_permissions` | public only | Permission keys the originator must hold. **Required** on all public actions (even if empty `[]`). |
| `agent_exposure` | | Optional. Contains `auto_fill_rules` (template-based field injection, e.g., `"sessionId": "{{agent.activeSessionId}}"`) and `sandbox_rule` (path prefix enforcement for filesystem operations). |

### Build-Time Validations

The `generateActionRegistry` Gradle task enforces at compile time — violations fail the build:

- `targeted` + `broadcast` is forbidden.
- `hidden` on non-public actions is forbidden.
- Public actions must declare `required_permissions` (even if empty `[]`).
- All `required_permissions` must reference a declared permission key.
- Permission keys must use `domain:capability` format with exactly one colon, max 64 characters.
- Danger levels must be `LOW`, `CAUTION`, or `DANGER`.


## Code Generation

The `generateActionRegistry` Gradle task runs before all Kotlin compilation and produces `ActionRegistry.kt` with five sections:

**Section 1: Name Constants.** `ActionRegistry.Names` — compile-time string constants for every action name (e.g., `ActionRegistry.Names.SESSION_POST`) plus an `allActionNames: Set<String>`. All code uses these — never hard-coded strings.

**Section 2: Descriptor Data Classes.** `ActionDescriptor`, `FeatureDescriptor`, `PayloadField`, `SandboxRule`, `PermissionDeclaration` — runtime-queryable metadata carrying the full manifest information.

**Section 3: Feature Registry.** `ActionRegistry.features` — `Map<String, FeatureDescriptor>` with the full descriptor tree.

**Section 4: Derived Views.** `byActionName` (all descriptors by full name — this is what `AppState.actionDescriptors` defaults to), `visibleActions` (excludes hidden), `agentAutoFillRules`, `agentSandboxRules`.

**Section 5: Permission Declarations.** All declared permission keys with descriptions and danger levels.


## Application State

The root state container:

```kotlin
data class AppState(
    val featureStates: Map<String, FeatureState> = emptyMap(),
    val actionDescriptors: Map<String, ActionDescriptor> = ActionRegistry.byActionName,
    val identityRegistry: Map<String, Identity> = emptyMap()
)
```

`featureStates` holds each feature's state slice, keyed by feature handle. `actionDescriptors` and `identityRegistry` are application infrastructure — they live on `AppState` (not in any `FeatureState`) because the Store needs them before any feature reducer runs. They are pre-populated at construction and mutated via dedicated Store methods (see Architectural Exceptions).


## Identity and Permissions

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

Feature identities have `uuid = null` (stable handles across restarts). Ephemeral identities (users, agents, sessions) have system-assigned UUIDs. Typed wrappers `IdentityHandle` and `IdentityUUID` (Kotlin value classes) provide compile-time safety at zero runtime cost. The registry supports flexible resolution by handle, UUID, localHandle, or display name.

**Registration** flows through the action bus: dispatch `core.REGISTER_IDENTITY` with a `name` → CoreFeature validates, deduplicates, creates → targeted `core.RETURN_REGISTER_IDENTITY` response. Idempotent: if a UUID already exists, the existing identity is returned. **Unregistration** via `core.UNREGISTER_IDENTITY` cascades to all descendants.

### Permission Resolution

Permissions are keyed by `domain:capability` strings (e.g., `gateway:generate`). Each identity carries explicit `PermissionGrant` entries with levels `NO` → `ASK` → `APP_LIFETIME` → `YES` (ascending privilege). `ASK` and `APP_LIFETIME` are reserved for a future approval system and currently treated as `NO`.

Effective permissions are resolved by `Store.resolveEffectivePermissions()`, walking the parent chain from root to leaf — each layer can override. Feature identities are always trusted and bypass checks entirely. Child escalations (child level > parent) are permitted but logged at `WARN`.

**Default permissions** are applied to feature identities at boot from `DefaultPermissions.kt` — compile-time decisions, not runtime configuration. Children inherit naturally. Persisted edits from the Permission Manager take precedence over defaults.


## Lifecycle Sequence

```
BOOTING → INITIALIZING → RUNNING → CLOSING → SHUTDOWN
```

| Phase | Permitted Actions | Purpose |
|-------|-------------------|---------|
| `BOOTING` | Only `system.INITIALIZING` | Pre-runtime; Store and features exist but are inert. |
| `INITIALIZING` | All | Synchronous setup. Features register settings definitions. No async work. |
| `RUNNING` | All except `INITIALIZING` and `RUNNING` | Normal operation. |
| `CLOSING` | All except `INITIALIZING`, `RUNNING`, `CLOSING` | Graceful shutdown. Features flush unsaved state via `deferredDispatch`. All deferred actions drain before `SHUTDOWN`. |
| `SHUTDOWN` | Only `system.SHUTDOWN` | Hard lockdown. No further actions accepted. |

The lifecycle is managed by CoreFeature's reducer in response to system actions dispatched by the host application entry point.


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

**Reducer** — Pure, synchronous. Receives the action and current feature state; returns new state. No I/O, no dispatching.

**handleSideEffects** — Impure, synchronous. Called after the reducer with both previous and new state. This is where features perform file I/O, launch coroutines for async work, and dispatch follow-up actions via `deferredDispatch`.

**init(store)** — Called once during `initFeatureLifecycles()`, before any actions are dispatched.

**ComposableProvider** — Optional. Supplies stage views, ribbon icons, partial views, and menu items. The `GlobalActionRibbon` and `MainAppContent` composables iterate all features to assemble the UI without knowing which features exist.


## Cognitive Architecture (Agents)

Agents are driven by pluggable `CognitiveStrategy` implementations that define how an agent thinks:

- **Resource Slots** — Declarative slots for system prompts, constitutions, bootloaders.
- **Prompt Building** — The strategy declares prompt structure via `PromptBuilder`; the pipeline handles collapse, budgeting, and assembly.
- **Post-Processing** — Sentinel checks on raw responses to detect state transitions, integrity failures, or halt conditions.
- **Lifecycle Hooks** — `onAgentRegistered`, `onAgentConfigChanged`, `ensureInfrastructure` for strategy-specific setup.

The runtime speaks exclusively to the `CognitiveStrategy` interface. Strategy-specific behavior is fully encapsulated.


## Platform Abstraction

`PlatformDependencies` (expect/actual) provides: file I/O (text and binary), directory management and sandbox path resolution, system utilities (UUID, timestamps, clipboard, logging), scheduling (delayed callbacks with cancellation), and native window decorations.

Each target (JVM, Android, iOS, wasmJs) provides its own `actual` implementation. Features interact with the platform exclusively through this interface and through FileSystem actions.


## Application Wiring

`AppContainer` is a stateless factory that instantiates and wires all components:

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

**Feature list order** determines broadcast reducer execution order. Deterministic within a build but not contractually guaranteed — features should not depend on it. Foundation → Services → Scaffolding → Actors by convention.

**Coroutine scope.** Features needing async work receive a `resilientCoroutineScope` backed by `SupervisorJob` and a global exception handler.


## Architectural Exceptions

Two aspects of `AppState` are mutated outside the normal action bus flow:

**Identity Registry.** `Store.updateIdentityRegistry()` directly mutates `AppState.identityRegistry`. The identity registry is needed before feature reducers can run (originator validation), creating a chicken-and-egg problem. Business logic remains in `CoreFeature.handleSideEffects`; the Store owns only the state. All mutations are followed by a `core.IDENTITY_REGISTRY_UPDATED` broadcast.

**Feature Identity Seeding.** During `Store.initFeatureLifecycles()`, feature identities and default permissions are seeded directly — before any action is dispatched. Necessary because `BOOTING` only permits `system.INITIALIZING`.


## Testing Architecture

Testing is an integral part of the system architecture. The testing framework is organized into five tiers of increasing scope, from pure function tests to real-platform integration. A dedicated `TestEnvironment` builder and `RecordingStore` run multi-feature integration tests against the real Store — guards, lifecycle, routing — while recording all processed actions for assertion.

The full testing strategy, tier definitions, and authoring guidelines are in the companion document **02-Unit-testing**.


## Build and Targets

**Kotlin Multiplatform** with **Compose Multiplatform** for UI. JVM target requires JDK 21.

| Target | Platform | HTTP Client | Status |
|--------|----------|-------------|--------|
| `jvm` | Desktop (Windows, macOS, Linux) | Ktor CIO | Windows released, Linux and macOS planned |
| `androidTarget` | Android | Ktor Android | Planned |
| `iosX64`, `iosArm64`, `iosSimulatorArm64` | iOS | Ktor Darwin | Planned |
| `wasmJs` | Browser | Ktor JS | Planned |

Key dependencies: Ktor (HTTP client), kotlinx.serialization (JSON), JNA (desktop native integration), Compose Material 3.

The `generateActionRegistry` Gradle task runs before all Kotlin compilation (`dependsOn` on every `KotlinCompile` task), ensuring the action catalog is always in sync with the manifests. Generated output is placed in `build/generated/kotlin/` and added to the `commonMain` source set.