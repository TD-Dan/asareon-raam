# Agent Feature Modernization Guide
**Version:** 1.0  
**Status:** Implementation Ready  
**Audience:** Agent/User implementation pair taking over from initial architecture review

---

## Overview

This document captures the full architectural plan for modernizing the `app.auf.feature.agent` package. It is the result of a design review that identified several structural problems: strategy-specific logic leaking into core agent code, stringly-typed identifiers with no compiler enforcement, and a data model that privileges the Sovereign strategy over others at the schema level.

The goal is a clean, extensible agent core where the runtime only ever speaks to the abstract `CognitiveStrategy` interface, and all strategy-specific behavior is fully encapsulated within the strategy implementations themselves. A secondary goal is replacing plain `String` IDs throughout the feature with typed value classes that make misrouting bugs a compile-time error rather than a silent runtime failure.

These changes also lay the groundwork for plugin strategies: once the architecture is clean, a third-party strategy registers itself the same way built-in strategies do, with no changes required in core agent code.

---

## Background: What Is Wrong Today

### 1. The Core Data Model Knows About Sovereign

`AgentInstance` currently carries two fields that are Sovereign-specific:

```kotlin
val knowledgeGraphId: String? = null   // Only meaningful to SovereignStrategy
val privateSessionId: String? = null   // Only meaningful to SovereignStrategy
```

This means every agent — regardless of strategy — carries schema weight for a strategy it may never use. Worse, the runtime makes branching decisions based on these fields:

```kotlin
// This is an implicit "isSovereign" check disguised as a null check
agentState.agents.values.filter { it.knowledgeGraphId != null }.forEach { ... }
```

The strategy abstraction is bypassed entirely.

### 2. Sovereign Lifecycle Logic Lives in the Core Package

`SovereignHKGResourceLogic.kt` lives in `app.auf.feature.agent` (the core package) but implements Sovereign-specific HKG reservation and session linking. Nothing about this logic is universal. It belongs inside the strategy's own implementation.

### 3. The Core State Imports Strategy Internals

`AgentDefaults` in `AgentState.kt` directly imports `SovereignDefaults` and `VanillaStrategy` to populate the built-in resource catalog. The core state object should not know which strategies exist.

### 4. Stringly-Typed IDs Create Silent Bugs

Agents, sessions, resources, and strategies are all tracked with plain `String` IDs. There is no compiler enforcement preventing a session handle from being passed where a resource UUID is expected, or a strategy string ID from being confused with an identity handle. These bugs are silent at compile time and painful to trace at runtime.

### 5. The Session Model Is Not Universal

The concept of "where does this agent output to" and "what sessions does this agent monitor" is universal — every strategy needs to answer these questions. But today `privateSessionId` is a Sovereign-specific field and the pipeline routes output via special-case logic rather than a universal contract.

---

## Core Concepts and Decisions

### Value Classes as Typed ID Pointers

Kotlin's `value class` is a zero-cost wrapper type that erases to its underlying type at compile time. It exists on every KMP target (JVM, Native, JS/WASM) without `@JvmInline` — that annotation is JVM-only and should not appear in `commonMain`. The serialization behavior with `kotlinx.serialization` is transparent: a `value class IdentityHandle(val handle: String)` serializes as a plain JSON string, not a nested object.

The key insight: **a `value class` is a compile-time type, not a runtime object.** You cannot create new value class types at runtime. What you create at runtime are *instances* of compile-time-defined value classes. This is exactly what is needed here: the types (`IdentityHandle`, `IdentityUUID`) are defined at compile time, and instances are created dynamically from data arriving off disk or the network.

Three typed wrappers are introduced:

```kotlin
// In app.auf.core — the bus address of any registered identity
@Serializable
value class IdentityHandle(val handle: String)

// In app.auf.core — the UUID of any registered identity  
@Serializable
value class IdentityUUID(val uuid: String)
```

Note: a `StrategyId` value class is **not** needed. Once strategies are registered as identities (see Phase 2), their identity handle serves as their lookup key, and `IdentityHandle` covers it.

### The Identity Registry Is the Single Source of Truth

`AppState.identityRegistry` is the canonical owner of all `Identity` objects, keyed by `handle`. Any entity that *embeds* a full `Identity` struct inside its own persisted state is creating a stale copy that can drift from the registry. The correct pattern is to store only a typed pointer (`IdentityHandle`) and resolve the live `Identity` at read-time:

```kotlin
val liveIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
```

This applies to `AgentInstance` (currently embeds its own `Identity`) and `Session` (same problem). Both should migrate to storing `IdentityHandle` only.

### Strategies Become Registered Identities

Today strategies are anonymous singleton objects with magic string IDs (`"vanilla_v1"`, `"sovereign_v1"`). These strings are enforced by nothing — a typo or duplicate is a silent runtime failure.

Strategies should register themselves as identities in the `agent.strategy.*` namespace at feature init time. The benefits are:

- CoreFeature enforces uniqueness and handle format
- The handle becomes the stable, collision-proof contract
- Plugin strategies go through the exact same registration flow as built-in ones
- `CognitiveStrategyRegistry` becomes a runtime map populated at registration rather than a hardcoded lookup table
- No separate `StrategyId` value class is needed — `IdentityHandle` covers strategy references too

The namespace convention: `agent.strategy.vanilla`, `agent.strategy.sovereign`, `agent.strategy.{plugin-name}`.

### Universal Session Participation Model

Every agent, regardless of strategy, needs to answer two questions:
1. What sessions am I participating in? → `subscribedSessionIds`
2. Where does my output go by default? → `outputSessionId`

The Sovereign strategy's "private cognition session" is not architecturally special. It is simply a `Session` where `isPrivateTo` points to the agent's identity handle. The agent sets it as `outputSessionId`. The pipeline routes gateway responses there. The strategy influences *how* that session is created and configured — but the fields themselves are universal.

`isPrivateTo` on `Session` replaces the boolean `isPrivate`. A session is private if and only if `isPrivateTo` is non-null. This makes the ownership explicit and typed:

```kotlin
// Session.kt
val isPrivateTo: IdentityHandle? = null
```

One invariant must be enforced in `AgentCrudLogic`: `outputSessionId` must always be a member of `subscribedSessionIds`, or null. This is validated on every config update.

### Lifecycle Hooks Replace Implicit Strategy Checks

The `CognitiveStrategy` interface is extended with optional lifecycle hooks. The runtime calls these polymorphically on all agents. Strategy-specific behavior (like HKG reservation for Sovereign) moves entirely into the strategy's implementation of these hooks. The core runtime never checks which strategy an agent is using.

---

## Implementation Phases

---

### Phase 1: Foundation — Typed Value Classes

**What:** Introduce `IdentityHandle` and `IdentityUUID` in `app.auf.core`. Migrate all string IDs in `AgentInstance`, `AgentRuntimeState`, and `Session` to use these types. Migrate `AgentCrudLogic` and all ID-handling code throughout the agent feature.

**Why first:** Everything in later phases depends on the type system being in place. This phase is purely additive — no behavior changes, only type substitutions.

**Key changes:**
- Define `IdentityHandle` and `IdentityUUID` in `app.auf.core` alongside `Identity`
- `AgentInstance.identity: Identity` → `AgentInstance.identityHandle: IdentityHandle`  
  The agent no longer embeds a full `Identity` struct. It holds a pointer. Resolution happens at read-time from the registry.
- `Session.identity: Identity` → `Session.identityHandle: IdentityHandle`  
  Same reasoning.
- `AgentInstance.subscribedSessionIds: List<String>` → `List<IdentityHandle>`
- `AgentInstance.resources: Map<String, String>` → `Map<String, IdentityUUID>`  
  The key (slot ID) remains a plain `String` because slot IDs are strategy-defined string constants, not registered identities. The value (resource ID) becomes `IdentityUUID`.
- All places in `AgentCrudLogic`, `AgentRuntimeReducer`, `AgentAvatarLogic`, `AgentAutoTriggerLogic` that extract or compare these IDs must be updated to use the wrapper types.

**Serialization note:** Because `value class` serializes transparently as its underlying type, existing persisted JSON files on disk are forward-compatible. No migration of stored data is required.

---

### Phase 2: Strategy Identity Registration

**What:** Give each `CognitiveStrategy` an `identityHandle: IdentityHandle` property. Register strategies as identities in the `agent.strategy.*` namespace during `AgentRuntimeFeature` init. Refactor `CognitiveStrategyRegistry` to be a runtime map populated at registration.

**Why:** Magic string strategy IDs are enforced by nothing. Routing strategies through the identity registry gives collision detection, namespace enforcement, and discoverability for free. It also collapses `StrategyId` — `IdentityHandle` covers it.

**Key changes:**

Update the `CognitiveStrategy` interface:
```kotlin
interface CognitiveStrategy {
    val identityHandle: IdentityHandle  // replaces val id: String
    val displayName: String
    // ... all other methods unchanged
}
```

Update each built-in strategy:
```kotlin
object VanillaStrategy : CognitiveStrategy {
    override val identityHandle = IdentityHandle("agent.strategy.vanilla")
    // ...
}

object SovereignStrategy : CognitiveStrategy {
    override val identityHandle = IdentityHandle("agent.strategy.sovereign")
    // ...
}
```

Refactor `CognitiveStrategyRegistry` from a hardcoded map to a registration-driven map:
```kotlin
object CognitiveStrategyRegistry {
    private val strategies = mutableMapOf<IdentityHandle, CognitiveStrategy>()
    
    fun register(strategy: CognitiveStrategy) {
        strategies[strategy.identityHandle] = strategy
    }
    
    fun get(handle: IdentityHandle): CognitiveStrategy =
        strategies[handle] ?: VanillaStrategy
    
    fun getDefault(): CognitiveStrategy = VanillaStrategy
    
    fun getAll(): List<CognitiveStrategy> = strategies.values.toList()
    
    fun getAllBuiltInResources(): List<AgentResource> =
        getAll().flatMap { it.getBuiltInResources() }
}
```

In `AgentRuntimeFeature.init`, register all built-in strategies before any agent attempts to resolve its strategy. The ordering constraint here is the same as for session and agent identity registration — strategies must be registered before agents boot.

Update `AgentInstance`:
```kotlin
val cognitiveStrategyId: IdentityHandle = CognitiveStrategyRegistry.getDefault().identityHandle
```

Update `AgentCrudLogic` to use the registry for the default and to validate that the provided strategy handle is known:
```kotlin
// On AGENT_CREATE / AGENT_UPDATE_CONFIG:
val strategyHandle = payload["cognitiveStrategyId"]
    ?.jsonPrimitive?.contentOrNull
    ?.let { IdentityHandle(it) }
    ?: CognitiveStrategyRegistry.getDefault().identityHandle

// Validate — reject unknown handles rather than silently falling back
if (CognitiveStrategyRegistry.get(strategyHandle) === VanillaStrategy && 
    strategyHandle != VanillaStrategy.identityHandle) {
    // Unknown strategy — reject or log error
}
```

---

### Phase 3: Universal Session Participation Model

**What:** Replace `privateSessionId` with `outputSessionId`. Update `Session` to use `isPrivateTo: IdentityHandle?` instead of `isPrivate: Boolean`. Enforce the `outputSessionId ∈ subscribedSessionIds` invariant in `AgentCrudLogic`. Update `AgentCognitivePipeline` to route gateway responses to `outputSessionId`.

**Why:** Session participation is universal — all strategies need it. The old model embeds Sovereign concepts (`privateSessionId`) at the schema level. The new model is neutral: the strategy creates whatever sessions it needs and registers one as the output target. The core pipeline just routes to `outputSessionId`.

**Key changes:**

```kotlin
// AgentInstance.kt — before
val privateSessionId: String? = null

// AgentInstance.kt — after
val outputSessionId: IdentityHandle? = null
// Invariant: outputSessionId must be a member of subscribedSessionIds, or null
```

```kotlin
// Session.kt — before
val isPrivate: Boolean = false

// Session.kt — after  
val isPrivateTo: IdentityHandle? = null
// A session is private if and only if isPrivateTo is non-null
```

Invariant enforcement in `AgentCrudLogic` on every config update that touches session fields:
```kotlin
val validOutputSessionId = if (newOutputSessionId in newSubscribedSessionIds)
    newOutputSessionId
else
    newSubscribedSessionIds.firstOrNull()
```

`AgentCognitivePipeline` uses `agent.outputSessionId` to route the gateway response. Any code that previously used `privateSessionId` for routing must be updated to use `outputSessionId`.

All UI code that previously checked `session.isPrivate` must be updated to check `session.isPrivateTo != null`. The visual distinction (lightning bolt icon, hidden from default view) is unchanged — only the backing field changes.

**Note on Sovereign sessions:** The Sovereign strategy's session management (creating a private cognition session, setting it as `outputSessionId`) moves entirely into the lifecycle hooks described in Phase 4. The schema change here is purely the universal model; the Sovereign-specific behavior of populating `outputSessionId` becomes the strategy's responsibility.

---

### Phase 4: Strategy Compartmentalization

**What:** Extend the `CognitiveStrategy` interface with lifecycle hooks. Move `SovereignHKGResourceLogic` into the `strategies` package as `SovereignStrategy`'s implementation of those hooks. Remove `knowledgeGraphId` from `AgentInstance` (it moves into `cognitiveState`). Remove `AgentDefaults.builtInResources` from `AgentState` (replaced by registry aggregation). Eliminate all implicit strategy checks from the core runtime.

**Why:** This is the central goal of the entire modernization. The core runtime should be entirely unaware of which strategies exist. All branching on strategy identity must disappear from `AgentRuntimeFeature` and be replaced by polymorphic calls to the interface.

**Lifecycle hook additions to `CognitiveStrategy`:**

```kotlin
interface CognitiveStrategy {

    // --- Existing methods (unchanged) ---
    val identityHandle: IdentityHandle
    val displayName: String
    fun getResourceSlots(): List<ResourceSlot>
    fun getInitialState(): JsonElement
    fun prepareSystemPrompt(context: AgentTurnContext, state: JsonElement): String
    fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult

    // --- New lifecycle hooks (all have default no-op implementations) ---

    /**
     * Returns the built-in AgentResource objects this strategy ships with.
     * Called by CognitiveStrategyRegistry.getAllBuiltInResources() at init time.
     * Default: empty list (strategies with no built-in resources need not override).
     */
    fun getBuiltInResources(): List<AgentResource> = emptyList()

    /**
     * Called once after an agent is created and its identity is registered.
     * Use for one-time setup: infrastructure reservation, initial state seeding, etc.
     */
    fun onAgentRegistered(agent: AgentInstance, store: Store) {}

    /**
     * Called after every AGENT_UPDATE_CONFIG that changes the agent's configuration.
     * Both old and new state are provided for diff-based reactions.
     * Use for detecting configuration transitions (e.g., HKG assignment/revocation).
     */
    fun onAgentConfigChanged(old: AgentInstance, new: AgentInstance, store: Store) {}

    /**
     * Called periodically by the runtime (on the same heartbeat as auto-trigger checks)
     * to allow the strategy to verify and repair its infrastructure.
     * Use for session linking, reservation renewal, etc.
     * Must be idempotent — it will be called repeatedly.
     */
    fun ensureInfrastructure(agentState: AgentRuntimeState, store: Store) {}
}
```

**Moving `SovereignHKGResourceLogic`:**

The entire file moves to `app.auf.feature.agent.strategies`. Its three exported functions become the Sovereign strategy's implementations of the lifecycle hooks:

- `handleSovereignAssignment` + `handleSovereignRevocation` → `SovereignStrategy.onAgentConfigChanged`
- `ensureSovereignSessions` → `SovereignStrategy.ensureInfrastructure`
- `requestContextIfSovereign` → called from within `SovereignStrategy.prepareSystemPrompt` or a new `onBeforeTurn` hook if needed

**Removing `knowledgeGraphId` from `AgentInstance`:**

`knowledgeGraphId` is Sovereign-specific configuration. It moves into `cognitiveState` as a field that `SovereignStrategy` owns and manages:

```kotlin
// SovereignStrategy reads its config from cognitiveState:
val kgId = (agent.cognitiveState as? JsonObject)
    ?.get("knowledgeGraphId")
    ?.jsonPrimitive
    ?.contentOrNull
```

The `cognitiveState` field is already present on `AgentInstance` and is the right home for strategy-specific persistent state. `SovereignStrategy.getInitialState()` should return a `JsonObject` with `knowledgeGraphId: null` as a well-known key, so the field is always present with a defined default.

**Removing `AgentDefaults.builtInResources` from `AgentState`:**

The built-in resource list is replaced by registry aggregation. At `AgentRuntimeFeature` init, after strategies are registered:

```kotlin
val builtInResources = CognitiveStrategyRegistry.getAllBuiltInResources()
// Seed into AgentRuntimeState.resources
```

`AgentState.kt` no longer imports `SovereignDefaults` or `VanillaStrategy`. The `AgentDefaults` object can be removed entirely or reduced to non-strategy constants.

**Cleaning up `AgentRuntimeFeature`:**

Every instance of `agent.knowledgeGraphId != null` in the runtime is an implicit `isSovereign` check. Each must be replaced with a polymorphic lifecycle hook call:

```kotlin
// Before — strategy leaks into core
agentState.agents.values
    .filter { it.knowledgeGraphId != null }
    .forEach { SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState) }

// After — pure polymorphism
agentState.agents.values.forEach { agent ->
    val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
    strategy.ensureInfrastructure(agentState, store)
}
```

---

### Phase 5: CRUD Logic Hardening

**What:** Minor cleanup in `AgentCrudLogic` following the larger structural changes.

**Why:** Small issues that are easy to fix once the larger model is in place.

**Key changes:**

- Replace the hardcoded `"vanilla_v1"` fallback string with `CognitiveStrategyRegistry.getDefault().identityHandle`. If the default strategy ever changes, this automatically follows.

- Add validation on `AGENT_CREATE` and `AGENT_UPDATE_CONFIG`: if the provided `cognitiveStrategyId` is not a known registered strategy handle, reject with a clear error rather than silently falling back to Vanilla. Silent fallback masks configuration bugs.

- Ensure the `outputSessionId ∈ subscribedSessionIds` invariant is enforced unconditionally on every config update, not only when the session fields are explicitly included in the payload.

---

### Phase 6: `ACTION_RESULT` Compliance

**What:** Audit agent actions for command-dispatchability and add `ACTION_RESULT` coverage per the `ACTION_RESULT_feature_guide.md` specification.

**Why:** Agent actions that can be invoked via `auf_` by users or agents currently publish no `ACTION_RESULT` broadcast. This means command outcomes are invisible in the session transcript.

**Key changes:**

- Audit `agent_actions.json` against the command-dispatchability criteria in the feature guide: any action that can be invoked via `auf_` requires `ACTION_RESULT` coverage.

- Add `correlationId: String? = null` to the payload data classes of all command-dispatchable agent actions in `AgentPayloads.kt`.

- Add `agent.ACTION_RESULT` to `agent_actions.json` using the standard template from the feature guide. The schema is intentionally identical across all features — CommandBot relies on this convention.

- Add a `publishActionResult` helper method to `AgentRuntimeFeature` following the pattern in the feature guide.

- Thread `correlationId` through all relevant `RETURN_*` agent response payloads so CoreFeature can route them to the session via `DELIVER_TO_SESSION`.

- Add any needed `RETURN_*` routing handlers in `CoreFeature` following the established filesystem pattern.

---

## Dependency and Sequencing

```
Phase 1: Types
    ↓
Phase 2: Strategy Identity ──────────────── Phase 3: Session Model
         (independent, can be parallel)
    ↓                                                ↓
Phase 4: Compartmentalization (requires both 2 and 3)
    ↓
Phase 5: CRUD Hardening
    ↓
Phase 6: ACTION_RESULT
```

Phases 2 and 3 are fully independent of each other and can be implemented in parallel. Every other phase is sequential. Phase 6 is additive and could technically be done at any point after Phase 1, but waiting until Phase 5 ensures the feature's shape is stable before adding the compliance layer.

---

## Invariants to Enforce

These are correctness constraints that must hold at all times after the modernization. `AgentCrudLogic` is the enforcement point for all of them.

| Invariant | Enforcement Point |
|---|---|
| `outputSessionId` is null or a member of `subscribedSessionIds` | `AgentCrudLogic` on every config update |
| `cognitiveStrategyId` is a registered strategy handle | `AgentCrudLogic` on create and update |
| `subscribedSessionIds` contains only subscribable sessions (non-private) | Already enforced — preserve this |
| Strategy lifecycle hooks are called for all agents, not just Sovereign | `AgentRuntimeFeature` heartbeat and config change handlers |

---

## Files Affected: Quick Reference

| File | Change Type | Phase |
|---|---|---|
| `app.auf.core.Identity.kt` | Add `IdentityHandle`, `IdentityUUID` | 1 |
| `AgentState.kt` | Migrate ID types, remove `AgentDefaults`, add `outputSessionId`, remove `privateSessionId`, remove `knowledgeGraphId` | 1, 3, 4 |
| `AgentInstance` (in AgentState) | `identityHandle`, `outputSessionId`, typed IDs | 1, 3 |
| `SessionState.kt` → `Session` | `identityHandle`, `isPrivateTo` | 1, 3 |
| `CognitiveStrategy.kt` | Add `identityHandle`, add lifecycle hooks | 2, 4 |
| `CognitiveStrategyRegistry.kt` | Runtime registration map | 2 |
| `VanillaStrategy.kt` | `identityHandle`, `getBuiltInResources()` | 2, 4 |
| `SovereignStrategy.kt` | `identityHandle`, lifecycle hook implementations | 2, 4 |
| `SovereignHKGResourceLogic.kt` | Move to `strategies` package, becomes Sovereign-internal | 4 |
| `SovereignDefaults.kt` | Move to `strategies` package (already there — verify) | 4 |
| `AgentCrudLogic.kt` | Typed IDs, invariant enforcement, strategy validation | 1, 2, 3, 5 |
| `AgentRuntimeFeature.kt` | Strategy registration at init, lifecycle hook dispatch, remove implicit strategy checks | 2, 4 |
| `AgentCognitivePipeline.kt` | Route output to `outputSessionId` | 3 |
| `AgentPayloads.kt` | Add `correlationId` to command-dispatchable payloads | 6 |
| `AgentAvatar.kt` | Update ID type references | 1 |
| `AgentAutoTriggerLogic.kt` | Update ID type references | 1 |
| `AgentManagerView.kt` | Update ID type references, `isPrivateTo` display logic | 1, 3 |
| `agent_actions.json` | Add `agent.ACTION_RESULT` | 6 |
| `CoreFeature.kt` | Add RETURN_* routing handlers for new agent responses | 6 |

---

## What Does Not Change

- The `CognitiveStrategy` interface's three core methods (`getResourceSlots`, `prepareSystemPrompt`, `postProcessResponse`) are unchanged in signature and semantics.
- The Sovereign boot state machine (BOOTING → AWAKE) is unchanged. It moves entirely inside `SovereignStrategy`.
- The `AgentTurnContext` and `PostProcessResult` data classes are unchanged.
- The action bus contract — no new actions are required by this modernization except `agent.ACTION_RESULT`.
- Persisted JSON files on disk do not require migration. The `value class` serialization is transparent.

---

## Definition of Done

The modernization is complete when:

1. `AgentRuntimeFeature` contains no `if (agent.knowledgeGraphId != null)` or equivalent implicit strategy checks.
2. `AgentState.kt` contains no imports from `app.auf.feature.agent.strategies.*`.
3. Adding a hypothetical third strategy (`ScriptedStrategy`) requires changes only within the `strategies` package and a registration call at feature init — zero changes to core agent runtime code.
4. The compiler rejects passing a session `IdentityHandle` where a resource `IdentityUUID` is expected.
5. All command-dispatchable agent actions publish `agent.ACTION_RESULT`.
