# Agent Feature Modernization Guide
**Version:** 1.1  
**Status:** Phases 1–3 Implemented, Phase 4 Next  
**Audience:** Agent/User implementation pair taking over from initial architecture review

---

## Changelog

### v1.1 — Post Phase 1–3 Implementation

| # | Section | Change | Reason |
|---|---------|--------|--------|
| E1 | Core Concepts / Value Classes | Corrected `@JvmInline` guidance | Compiler requires it; `kotlin.jvm.JvmInline` IS available in `commonMain` |
| E2 | Core Concepts / Identity Registry | Noted `AgentInstance` retains embedded `Identity` for now | Serialization compatibility; deferred to post-Phase 6 cleanup |
| E3 | Phase 1 | Added notes on backward-compat strategy and compiler ripple files | Implementation experience |
| E4 | Phase 2 | Documented `migrateStrategyId` and `legacyId` mechanism | Not in original spec; required for backward compat with persisted `"vanilla_v1"` |
| E5 | Phase 3 | Removed hard `outputSessionId ∈ subscribedSessionIds` invariant | Causes infinite loop with Sovereign's out-of-band cognition session |
| E6 | Phase 3 | Added `Session.isPrivate` backward-compat shim | Old session JSON files have `"isPrivate": true`; field retained for deserialization |
| E7 | Phase 4 | Added strategy-owned validation hook for `outputSessionId` | Invariant enforcement must be strategy-aware |
| E8 | Phase 5 | Revised invariant enforcement to be strategy-delegated | Direct consequence of E5 |
| E9 | Invariants table | Updated `outputSessionId` invariant row | Reflects actual implementation |

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

Kotlin's `value class` is a zero-cost wrapper type that erases to its underlying type at compile time. It exists on every KMP target (JVM, Native, JS/WASM).

> **[E1] CORRECTION:** The `@JvmInline` annotation **is required** by the Kotlin compiler for single-field value classes. Despite its name, `kotlin.jvm.JvmInline` is part of the Kotlin stdlib and **is available in `commonMain`** across all KMP targets. On non-JVM targets it acts as a forward-compatibility marker. The original guidance stating "do not annotate with `@JvmInline`" was incorrect.

The serialization behavior with `kotlinx.serialization` is transparent: a `value class IdentityHandle(val handle: String)` serializes as a plain JSON string, not a nested object.

The key insight: **a `value class` is a compile-time type, not a runtime object.** You cannot create new value class types at runtime. What you create at runtime are *instances* of compile-time-defined value classes. This is exactly what is needed here: the types (`IdentityHandle`, `IdentityUUID`) are defined at compile time, and instances are created dynamically from data arriving off disk or the network.

Two typed wrappers are introduced:

```kotlin
// In app.auf.core — the bus address of any registered identity
@JvmInline
@Serializable
value class IdentityHandle(val handle: String)

// In app.auf.core — the UUID of any registered identity  
@JvmInline
@Serializable
value class IdentityUUID(val uuid: String)
```

Note: a `StrategyId` value class is **not** needed. Once strategies are registered as identities (see Phase 2), their identity handle serves as their lookup key, and `IdentityHandle` covers it.

### The Identity Registry Is the Single Source of Truth

`AppState.identityRegistry` is the canonical owner of all `Identity` objects, keyed by `handle`. Any entity that *embeds* a full `Identity` struct inside its own persisted state is creating a stale copy that can drift from the registry. The correct pattern is to store only a typed pointer (`IdentityHandle`) and resolve the live `Identity` at read-time:

```kotlin
val liveIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
```

> **[E2] IMPLEMENTATION NOTE:** `AgentInstance` currently retains the full `identity: Identity` field for serialization compatibility with existing `agent.json` files on disk. Computed properties (`identityHandle`, `identityUUID`) provide typed access at call sites without breaking persistence. The migration to store-only `IdentityHandle` (dropping the embedded `Identity`) is deferred to a post-Phase 6 cleanup, as it requires a coordinated persistence migration across all agent files. The same applies to `Session`.

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

> **[E5] CORRECTION — `outputSessionId` invariant:** The original spec prescribed hard enforcement of `outputSessionId ∈ subscribedSessionIds` in `AgentCrudLogic`. This is **incorrect** for the Sovereign architecture, where `outputSessionId` points to a private cognition session that is deliberately *not* a subscribed session. Hard enforcement creates an infinite loop:
>
> 1. `ensureSovereignSessions` sees `outputSessionId == null` → dispatches `UPDATE_CONFIG`
> 2. Invariant fires: `outputSessionId ∉ subscribedSessionIds` → nullifies it
> 3. Next heartbeat tick → loop
>
> **Resolution:** The invariant is **not** enforced in `AgentCrudLogic`. Instead, it is deferred to Phase 4 as a **strategy-owned validation hook** (`validateConfig`), where each strategy defines its own rules. Vanilla can enforce strict membership; Sovereign permits out-of-band output sessions. See Phase 4 and Phase 5 sections.

### Lifecycle Hooks Replace Implicit Strategy Checks

The `CognitiveStrategy` interface is extended with optional lifecycle hooks. The runtime calls these polymorphically on all agents. Strategy-specific behavior (like HKG reservation for Sovereign) moves entirely into the strategy's implementation of these hooks. The core runtime never checks which strategy an agent is using.

---

## Implementation Phases

---

### Phase 1: Foundation — Typed Value Classes ✅ COMPLETE

**What:** Introduce `IdentityHandle` and `IdentityUUID` in `app.auf.core`. Migrate all string IDs in `AgentInstance`, `AgentRuntimeState`, and `Session` to use these types. Migrate `AgentCrudLogic` and all ID-handling code throughout the agent feature.

**Why first:** Everything in later phases depends on the type system being in place. This phase is purely additive — no behavior changes, only type substitutions.

**Key changes:**
- Define `IdentityHandle` and `IdentityUUID` in `app.auf.core` alongside `Identity`
- Both value classes require `@JvmInline` and `import kotlin.jvm.JvmInline`
- `AgentInstance.subscribedSessionIds: List<String>` → `List<IdentityHandle>`
- `AgentInstance.resources: Map<String, String>` → `Map<String, IdentityUUID>`  
  The key (slot ID) remains a plain `String` because slot IDs are strategy-defined string constants, not registered identities. The value (resource ID) becomes `IdentityUUID`.
- All places in `AgentCrudLogic`, `AgentRuntimeReducer`, `AgentAvatarLogic`, `AgentAutoTriggerLogic` that extract or compare these IDs must be updated to use the wrapper types.

> **[E3] IMPLEMENTATION NOTES:**
>
> **Backward compatibility strategy:** `AgentInstance` retains `identity: Identity` as the serialized field. Computed properties `identityHandle: IdentityHandle` and `identityUUID: IdentityUUID` provide typed access. No changes to persisted JSON format.
>
> **Boundary wrapping pattern:** Private extension functions (`.agentUUID()`, `.sessionHandle()`) wrap raw JSON strings at extraction boundaries in `AgentCrudLogic` and `AgentRuntimeReducer`. Internal logic operates on typed values exclusively.
>
> **Compiler ripple files:** The following files were NOT in the initial deliverable but required updates due to typed map key changes: `AgentAutoTriggerLogic.kt`, `AgentAvatar.kt`, `AgentManagerView.kt`. The primary cause is `AgentAvatarLogic.updateAgentAvatars` changing its parameter from `String` to `IdentityUUID`, which cascades through all callers in `AgentCognitivePipeline` and `AgentRuntimeFeature`.

**Serialization note:** Because `value class` serializes transparently as its underlying type, existing persisted JSON files on disk are forward-compatible. No migration of stored data is required.

---

### Phase 2: Strategy Identity Registration ✅ COMPLETE

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
    
    fun register(strategy: CognitiveStrategy, legacyId: String? = null) {
        strategies[strategy.identityHandle] = strategy
        legacyId?.let { legacyIdMap[it] = strategy.identityHandle }
    }
    
    fun get(handle: IdentityHandle): CognitiveStrategy =
        strategies[handle] ?: VanillaStrategy
    
    fun getDefault(): CognitiveStrategy = VanillaStrategy
    
    fun getAll(): List<CognitiveStrategy> = strategies.values.toList()
    
    fun getAllBuiltInResources(): List<AgentResource> =
        getAll().flatMap { it.getBuiltInResources() }

    fun migrateStrategyId(raw: String): IdentityHandle { ... }
}
```

In `AgentRuntimeFeature.init`, register all built-in strategies before any agent attempts to resolve its strategy. The ordering constraint here is the same as for session and agent identity registration — strategies must be registered before agents boot.

Update `AgentInstance`:
```kotlin
val cognitiveStrategyId: IdentityHandle = IdentityHandle("agent.strategy.vanilla")
```

Update `AgentCrudLogic` to use the registry for the default and to validate that the provided strategy handle is known:
```kotlin
// On AGENT_CREATE / AGENT_UPDATE_CONFIG:
val strategyHandle = payload["cognitiveStrategyId"]
    ?.jsonPrimitive?.contentOrNull
    ?.let { CognitiveStrategyRegistry.migrateStrategyId(it) }
    ?: CognitiveStrategyRegistry.getDefault().identityHandle
```

> **[E4] IMPLEMENTATION NOTE — Legacy ID Migration:**
>
> Persisted `agent.json` files on disk still contain the old string IDs (`"vanilla_v1"`, `"sovereign_v1"`). Since `cognitiveStrategyId` is now an `IdentityHandle`, deserialization produces `IdentityHandle("vanilla_v1")` which won't match `IdentityHandle("agent.strategy.vanilla")` in the registry.
>
> **Solution:** `CognitiveStrategyRegistry.register()` accepts an optional `legacyId: String` parameter. When provided, it maps the old ID to the new handle in an internal `legacyIdMap`. The `migrateStrategyId(raw: String)` function checks (1) if the raw value is already a registered handle, (2) if it's a known legacy ID, and (3) wraps it as-is as a fallback.
>
> **Load path:** `AgentRuntimeFeature` calls `migrateStrategyId` on every agent loaded from disk, transparently upgrading old references. Re-saving writes the new format.
>
> **Phase 4 must be aware** of this mechanism when moving strategy registration or adding plugin strategies.

---

### Phase 3: Universal Session Participation Model ✅ COMPLETE

**What:** Replace `privateSessionId` with `outputSessionId`. Update `Session` to use `isPrivateTo: IdentityHandle?` instead of `isPrivate: Boolean`. Update `AgentCognitivePipeline` to route gateway responses to `outputSessionId`.

**Why:** Session participation is universal — all strategies need it. The old model embeds Sovereign concepts (`privateSessionId`) at the schema level. The new model is neutral: the strategy creates whatever sessions it needs and registers one as the output target. The core pipeline just routes to `outputSessionId`.

**Key changes:**

```kotlin
// AgentInstance.kt — before
val privateSessionId: IdentityHandle? = null

// AgentInstance.kt — after
val outputSessionId: IdentityHandle? = null
```

```kotlin
// Session.kt — before
val isPrivate: Boolean = false

// Session.kt — after  
val isPrivateTo: IdentityHandle? = null
// A session is private if and only if isPrivateTo is non-null
```

> **[E5] INVARIANT NOT ENFORCED HERE:** The `outputSessionId ∈ subscribedSessionIds` invariant originally prescribed for this phase was removed. See the correction note in the Core Concepts section above for the full rationale. Enforcement is deferred to Phase 4 as a strategy-owned validation hook.

`AgentCognitivePipeline` uses `agent.outputSessionId` to route the gateway response. Any code that previously used `privateSessionId` for routing must be updated to use `outputSessionId`.

All UI code that previously checked `session.isPrivate` must be updated to check `session.isPrivateTo != null`. The visual distinction (lightning bolt icon, hidden from default view) is unchanged — only the backing field changes.

> **[E6] IMPLEMENTATION NOTE — `Session.isPrivate` backward compatibility:**
>
> Old persisted session JSON files contain `"isPrivate": true` with no `isPrivateTo` field. The `isPrivate: Boolean = false` field is retained on `Session` as a deprecated backward-compatibility shim so that `kotlinx.serialization` can deserialize old files without data loss.
>
> The broadcast filter in `SessionFeature.broadcastSessionNames` checks both: `it.isPrivateTo == null && !it.isPrivate`.
>
> `isPrivate` can be removed once all persisted sessions have been re-saved with `isPrivateTo` (this happens naturally as sessions are modified and re-persisted).

> **IMPLEMENTATION NOTE — `AgentInstance` migration:**
>
> Old `agent.json` files contain `"privateSessionId"`. Since the field was renamed to `outputSessionId`, `kotlinx.serialization` ignores the old key (with `ignoreUnknownKeys = true`). The load path in `AgentRuntimeFeature` parses raw JSON to check for `"privateSessionId"` and maps it to `outputSessionId`.

**Note on Sovereign sessions:** The Sovereign strategy's session management (creating a private cognition session, setting it as `outputSessionId`) moves entirely into the lifecycle hooks described in Phase 4. The schema change here is purely the universal model; the Sovereign-specific behavior of populating `outputSessionId` becomes the strategy's responsibility.

**Action payload changes:**
- `agent_actions.json`: `agent.UPDATE_CONFIG` field `privateSessionId` → `outputSessionId`
- `session_actions.json`: `session.CREATE` field `isPrivate: boolean` → `isPrivateTo: ["string", "null"]`

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
    fun ensureInfrastructure(agent: AgentInstance, agentState: AgentRuntimeState, store: Store) {}

    /**
     * [E7] Strategy-owned config validation. Called by AgentCrudLogic after
     * AGENT_UPDATE_CONFIG to let the strategy validate or repair config fields.
     *
     * Example: VanillaStrategy enforces outputSessionId ∈ subscribedSessionIds.
     * SovereignStrategy permits out-of-band outputSessionId (cognition session).
     *
     * Returns the (possibly corrected) agent instance, or null to reject the update.
     * Default: returns the agent unchanged (no validation).
     */
    fun validateConfig(agent: AgentInstance): AgentInstance = agent
}
```

> **[E7] NEW — `validateConfig` hook:** This hook was not in the original spec. It is required because the `outputSessionId ∈ subscribedSessionIds` invariant (originally planned for Phase 3) cannot be enforced uniformly — Sovereign's architecture legitimately violates it. Each strategy must define its own validation rules. See Phase 5 for the `AgentCrudLogic` integration point.

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
    strategy.ensureInfrastructure(agent, agentState, store)
}
```

---

### Phase 5: CRUD Logic Hardening

**What:** Minor cleanup in `AgentCrudLogic` following the larger structural changes.

**Why:** Small issues that are easy to fix once the larger model is in place.

**Key changes:**

- Replace any remaining hardcoded strategy handle strings with `CognitiveStrategyRegistry.getDefault().identityHandle`. If the default strategy ever changes, this automatically follows.

- Add validation on `AGENT_CREATE` and `AGENT_UPDATE_CONFIG`: if the provided `cognitiveStrategyId` is not a known registered strategy handle, reject with a clear error rather than silently falling back to Vanilla. Silent fallback masks configuration bugs.

> **[E8] REVISED — Strategy-delegated `outputSessionId` validation:**
>
> The original spec called for unconditional enforcement of `outputSessionId ∈ subscribedSessionIds` in `AgentCrudLogic`. This was found to be incorrect during Phase 3 implementation (see E5).
>
> The correct integration point is via the `validateConfig` hook added in Phase 4:
>
> ```kotlin
> // AgentCrudLogic — after building updatedAgent:
> val strategy = CognitiveStrategyRegistry.get(updatedAgent.cognitiveStrategyId)
> val validatedAgent = strategy.validateConfig(updatedAgent)
> state.copy(agents = state.agents + (agentId to validatedAgent))
> ```
>
> **VanillaStrategy.validateConfig** should enforce the strict invariant:
> ```kotlin
> override fun validateConfig(agent: AgentInstance): AgentInstance {
>     if (agent.outputSessionId != null && agent.outputSessionId !in agent.subscribedSessionIds) {
>         return agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
>     }
>     return agent
> }
> ```
>
> **SovereignStrategy.validateConfig** should permit out-of-band output sessions (no correction needed).

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
Phase 1: Types ✅
    ↓
Phase 2: Strategy Identity ✅ ─────────────── Phase 3: Session Model ✅
         (independent, can be parallel)
    ↓                                                ↓
Phase 4: Compartmentalization (requires both 2 and 3) ← NEXT
    ↓
Phase 5: CRUD Hardening
    ↓
Phase 6: ACTION_RESULT
```

Phases 2 and 3 are fully independent of each other and can be implemented in parallel. Every other phase is sequential. Phase 6 is additive and could technically be done at any point after Phase 1, but waiting until Phase 5 ensures the feature's shape is stable before adding the compliance layer.

---

## Invariants to Enforce

These are correctness constraints that must hold at all times after the modernization. `AgentCrudLogic` is the enforcement point for all of them.

| Invariant | Enforcement Point | Notes |
|---|---|---|
| `outputSessionId` validity | `CognitiveStrategy.validateConfig()` called from `AgentCrudLogic` | **[E9]** Strategy-owned, not universal. Vanilla enforces `∈ subscribedSessionIds`; Sovereign permits out-of-band. |
| `cognitiveStrategyId` is a registered strategy handle | `AgentCrudLogic` on create and update | Reject unknown handles rather than silent fallback |
| `subscribedSessionIds` contains only subscribable sessions (non-private) | Already enforced — preserve this | |
| Strategy lifecycle hooks are called for all agents, not just Sovereign | `AgentRuntimeFeature` heartbeat and config change handlers | |

---

## Files Affected: Quick Reference

| File | Change Type | Phase |
|---|---|---|
| `app.auf.core.Identity.kt` | Add `IdentityHandle`, `IdentityUUID` (with `@JvmInline`) | 1 |
| `AgentState.kt` | Migrate ID types, remove `AgentDefaults`, add `outputSessionId`, remove `privateSessionId`, remove `knowledgeGraphId` | 1, 3, 4 |
| `AgentInstance` (in AgentState) | Typed IDs, `outputSessionId`, retains embedded `Identity` for compat | 1, 2, 3 |
| `SessionState.kt` → `Session` | `isPrivateTo`, deprecated `isPrivate` compat shim | 3 |
| `SessionState.kt` → `PendingSessionCreation` | `isPrivateTo` replaces `isPrivate` | 3 |
| `SessionFeature.kt` | `CreatePayload`, broadcast filter, session creation from pending | 3 |
| `CognitiveStrategy.kt` | Add `identityHandle`, add lifecycle hooks, add `validateConfig` | 2, 4 |
| `CognitiveStrategyRegistry.kt` | Runtime registration map with `migrateStrategyId` | 2 |
| `VanillaStrategy.kt` | `identityHandle`, `getBuiltInResources()`, `validateConfig` | 2, 4, 5 |
| `SovereignStrategy.kt` | `identityHandle`, lifecycle hook implementations | 2, 4 |
| `SovereignHKGResourceLogic.kt` | Move to `strategies` package, becomes Sovereign-internal | 4 |
| `SovereignDefaults.kt` | Move to `strategies` package (already there — verify) | 4 |
| `AgentCrudLogic.kt` | Typed IDs, `migrateStrategyId`, `validateConfig` call, strategy validation | 1, 2, 3, 5 |
| `AgentRuntimeFeature.kt` | Strategy registration with `legacyId`, lifecycle hook dispatch, agent load migration, remove implicit strategy checks | 2, 3, 4 |
| `AgentRuntimeReducer.kt` | `outputSessionId` references | 3 |
| `AgentCognitivePipeline.kt` | Route output to `outputSessionId` | 3 |
| `AgentPayloads.kt` | Add `correlationId` to command-dispatchable payloads | 6 |
| `AgentAvatar.kt` | Typed IDs, `outputSessionId` | 1, 3 |
| `AgentAutoTriggerLogic.kt` | Typed IDs | 1 |
| `AgentManagerView.kt` | Typed IDs, strategy `identityHandle`, `outputSessionId` display | 1, 2, 3 |
| `agent_actions.json` | `outputSessionId` field, `agent.ACTION_RESULT` | 3, 6 |
| `session_actions.json` | `isPrivateTo` field | 3 |
| `CoreFeature.kt` | Add RETURN_* routing handlers for new agent responses | 6 |

---

## What Does Not Change

- The `CognitiveStrategy` interface's three core methods (`getResourceSlots`, `prepareSystemPrompt`, `postProcessResponse`) are unchanged in signature and semantics.
- The Sovereign boot state machine (BOOTING → AWAKE) is unchanged. It moves entirely inside `SovereignStrategy`.
- The `AgentTurnContext` and `PostProcessResult` data classes are unchanged.
- The action bus contract — no new actions are required by this modernization except `agent.ACTION_RESULT`.
- Persisted JSON files on disk do not require migration. The `value class` serialization is transparent. Legacy fields (`privateSessionId`, `vanilla_v1`, `isPrivate`) are handled by load-time migration code.

---

## Definition of Done

The modernization is complete when:

1. `AgentRuntimeFeature` contains no `if (agent.knowledgeGraphId != null)` or equivalent implicit strategy checks.
2. `AgentState.kt` contains no imports from `app.auf.feature.agent.strategies.*`.
3. Adding a hypothetical third strategy (`ScriptedStrategy`) requires changes only within the `strategies` package and a registration call at feature init — zero changes to core agent runtime code.
4. The compiler rejects passing a session `IdentityHandle` where a resource `IdentityUUID` is expected.
5. All command-dispatchable agent actions publish `agent.ACTION_RESULT`.