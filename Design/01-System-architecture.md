# ASAREON RAAM — Architecture

**Version 1.0.0-alpha** · Kotlin Multiplatform · Compose Multiplatform

---

## Overview

Asareon Raam is a multi-platform application for hosting and orchestrating autonomous AI agents. It provides a session-based conversational interface, a pluggable gateway to multiple AI providers, a hierarchical knowledge graph system, and a command-driven agent runtime — all wired together through a unidirectional data flow architecture with a manifest-driven action bus.

The application targets Desktop (JVM), Android, iOS, and WebAssembly (wasmJs) from a single Kotlin Multiplatform codebase, using Compose Multiplatform for all UI.


## Core Principles

**Unidirectional Data Flow (UDF).** All state changes flow through a single `Store`. Features dispatch `Action` objects; the Store validates, authorizes, reduces, and then triggers side effects. No feature mutates state directly.

**Absolute Decoupling.** Features never import each other. All inter-feature communication happens through the action bus using string-named actions with JSON payloads. A feature knows *what* it wants to say, never *who* will hear it.

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
| **System / Store** | The action bus, lifecycle sequencing (`INITIALIZING` → `STARTING` → `RUNNING` → `CLOSING` → `SHUTDOWN`), reducer orchestration, authorization guard, permission guard, and the `AppState` container holding feature states, the action descriptor catalog, and the identity registry. |
| **Core** | UI infrastructure: active view routing, toast notifications, clipboard, confirmation dialogs, window size persistence. Owns the `CoreState` lifecycle enum that the Store reads for its lifecycle guard. |
| **FileSystem** | Sandboxed, encrypted file I/O for the entire application. Every feature that persists data does so through FileSystem actions (`filesystem.READ`, `filesystem.WRITE`, `filesystem.LIST`). Sits at L0 because it is a universal dependency — including for Core's own persistence needs. |

### L1 — Services

Domain-specific services that provide capabilities to higher layers. May depend on L0 and on each other.

| Component | Responsibility |
|-----------|---------------|
| **Settings** | Manages the encrypted persistence, state, and UI for all application settings. Features register definitions via `settings.ADD` at startup; values are persisted through FileSystem and broadcast via `settings.VALUE_CHANGED`. |
| **Gateway** | Secure, centralized router for all generative AI requests. Single source of truth for available providers (Gemini, OpenAI, Anthropic, Inception) and their models. Enforces the `gateway:generate` permission. |
| **KnowledgeGraph** | Manages hierarchical knowledge graphs (HKGs) organized into personas. Provides structured context to agents via a reservation system. Persists graph data through FileSystem. |

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

### Action Categories

Every action falls into exactly one category, determined by its manifest flags:

| Category | `public` | `broadcast` | `targeted` | Behavior |
|----------|----------|-------------|------------|----------|
| **Command** | ✓ | ✓ | ✗ | Any originator can dispatch; all features receive. |
| **Hidden Command** | ✓ (+hidden) | ✓ | ✗ | Like Command but not discoverable by agents/users. Feature-to-feature only. |
| **Event** | ✗ | ✓ | ✗ | Only the owning feature can dispatch; all features receive. |
| **Internal** | ✗ | ✗ | ✗ | Only the owning feature can dispatch and receive. |
| **Response** | ✗ | ✗ | ✓ | Only the owning feature can dispatch; delivered to `targetRecipient` only. |

### Store Processing Pipeline

Every dispatched action passes through this sequence:

1. **Schema Lookup** — Reject unknown action names.
2. **Targeted Validation** — Reject `targetRecipient` on non-targeted actions (and vice versa).
3. **Originator Validation** — Reject originators not in the identity registry or resolvable to a known feature.
4. **Authorization** — For non-public actions, verify the originator belongs to the owning feature's namespace.
5. **Permission Guard** — For actions with `required_permissions`, verify the originator's effective grants include `YES` for all listed keys. Feature identities (uuid = null) are trusted and exempt.
6. **Lifecycle Guard** — Reject actions inappropriate for the current lifecycle phase.
7. **Route → Reduce** — Deliver to the appropriate feature(s) based on broadcast/targeted flags; run reducers.
8. **Side Effects** — Call `handleSideEffects` on receiving features with both previous and new state.


## Manifest-Driven Code Generation

Action contracts are declared in `*.actions.json` files co-located with their feature source. A Gradle task (`generateActionRegistry`) runs before compilation and produces `ActionRegistry.kt` containing:

- **Name constants** — `ActionRegistry.Names.SESSION_POST`, etc.
- **Action descriptors** — Full metadata (public/broadcast/targeted flags, payload schema, required permissions, sandbox rules, auto-fill rules).
- **Feature descriptors** — Feature summaries, owned actions, declared permissions.
- **Permission declarations** — All permission keys with descriptions and danger levels.
- **Derived views** — `byActionName`, `visibleActions`, `agentAutoFillRules`, `agentSandboxRules`.

Build-time validations enforced by the generator:

- `targeted` + `broadcast` is forbidden (mutually exclusive delivery).
- `hidden` on non-public actions is forbidden (redundant).
- Public actions must declare `required_permissions` (even if empty).
- All `required_permissions` must reference a declared permission key.
- Permission keys must use `domain:capability` format with exactly one colon.
- Danger levels must be `LOW`, `CAUTION`, or `DANGER`.


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

### Permission Resolution

Permissions are keyed by `domain:capability` strings (e.g., `gateway:generate`, `filesystem:workspace`). Each identity carries explicit `PermissionGrant` entries. Effective permissions are resolved by walking the parent chain from root to leaf — each layer can override the inherited value. The levels, in ascending privilege order: `NO` → `ASK` → `APP_LIFETIME` → `YES`.

Feature identities (uuid = null) are always trusted and bypass permission checks entirely.


## Lifecycle Sequence

```
BOOTING → INITIALIZING → STARTING → RUNNING → CLOSING → SHUTDOWN
```

| Phase | Permitted Actions | Purpose |
|-------|-------------------|---------|
| `BOOTING` | Only `system.INITIALIZING` | Pre-runtime; Store and features exist but are inert. |
| `INITIALIZING` | All | Synchronous setup. Features register settings definitions. No async work. |
| `RUNNING` | All except `INITIALIZING` and `STARTING` | Normal operation. |
| `CLOSING` | All except `INITIALIZING`, `STARTING`, `CLOSING` | Graceful shutdown. Features flush unsaved state via `deferredDispatch`. All deferred actions drain before `SHUTDOWN`. |
| `SHUTDOWN` | Only `system.SHUTDOWN` | Hard lockdown. No further actions accepted. |


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

**handleSideEffects** — Impure. Called after the reducer with both previous and new state. This is where features perform file I/O, start network requests, dispatch follow-up actions, and interact with platform services.

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

- File I/O (read, write, list, delete, copy, zip)
- Directory management and sandbox path resolution
- System utilities (UUID generation, timestamps, clipboard, logging)
- Scheduling (delayed callbacks with cancellation)
- Native window decorations

Each target (JVM, Android, iOS, wasmJs) provides its own `actual` implementation. Features interact with the platform exclusively through this interface and through FileSystem actions.


## Testing Architecture

Testing is an integral part of the system architecture, not a separate concern. The unidirectional data flow, absolute decoupling, and manifest-driven contracts that define the runtime architecture also define how the system is tested. Every feature, guard, and cross-feature workflow has a corresponding test tier with explicit mandates, infrastructure, and patterns.

The testing framework is organized into five tiers of increasing scope, from pure function tests to real-platform integration. A dedicated `TestEnvironment` builder and `RecordingStore` allow multi-feature integration tests to run against the real Store — including its guards, lifecycle, and routing — while recording all processed actions for assertion. Fake infrastructure (`FakePlatformDependencies`, `FakeStore`) isolates platform I/O and side effects where needed.

The full testing strategy, tier definitions, infrastructure contracts, and authoring guidelines are documented in the companion document **02-Unit-testing**.


## Build and Targets

**Kotlin Multiplatform** with **Compose Multiplatform** for UI.

| Target | Platform | HTTP Client | Status |
|--------|----------|-------------|--------|
| `jvm` | Desktop (Windows, macOS, Linux) | Ktor CIO | Windows released, Linux and macOS planned |
| `androidTarget` | Android (min SDK per version catalog) | Ktor Android | Planned |
| `iosX64`, `iosArm64`, `iosSimulatorArm64` | iOS | Ktor Darwin | Planned |
| `wasmJs` | Browser | Ktor JS | Planned |

Key dependencies: Ktor (HTTP client), kotlinx.serialization (JSON), JNA (desktop native integration), Compose Material 3.

The `generateActionRegistry` Gradle task runs before all Kotlin compilation, ensuring the action catalog is always in sync with the manifests.