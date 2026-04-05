# ASAREON RAAM — Testing

**Version 1.0.0-alpha** · Companion to 01-System-architecture

---

## Overview

This document defines the testing architecture for Asareon Raam. The system's unidirectional data flow, absolute decoupling, and manifest-driven action bus create a naturally testable architecture — every state transition is a pure function, every side effect is an observable action on the bus, and every authorization decision is a lookup against a declarative schema. The testing strategy exploits these properties to deliver high-confidence coverage with minimal mocking.

All tests live in the `commonTest` source set and run on every platform target unless explicitly annotated otherwise.


## Core Principles

**Test the architecture, not around it.** Tests exercise the real Store guards, real reducers, and real routing logic. The `RecordingStore` inherits from the production `Store` and uses its `onDispatch` test hook to observe processed actions — no guard bypass, no mock dispatch.

**Tier-driven scope.** Every test file declares its tier (T1–T5) in its class-level KDoc. The tier determines what infrastructure is permitted, what is under test, and what must be faked. Mixing tiers in a single file is prohibited.

**Diagnostic-first failure.** All assertions in T2+ tests are wrapped in `TestHarness.runAndLogOnFailure {}`, which prints a full action log and platform log dump before re-throwing the original exception. Test failures are debugged from the log, not from stack traces alone.

**Descriptors over allow-lists.** The Store validates every dispatched action against `AppState.actionDescriptors`. Test-only actions are registered via `testDescriptorsFor()` or `withExtraDescriptors()` — never by disabling validation.


## Test Tiers

The five tiers form a pyramid. Lower tiers are fast, numerous, and isolated. Higher tiers are slower, fewer, and exercise broader integration surfaces.

```
         ╱╲
        ╱T5╲         Platform: real OS, real filesystem
       ╱────╲
      ╱  T4  ╲       Workspace: full pipeline with context collapse
     ╱────────╲
    ╱    T3    ╲     Peer: multi-feature targeted/broadcast workflows
   ╱────────────╲
  ╱      T2      ╲   Contract: feature + Store + side effects
 ╱────────────────╲
╱        T1        ╲  Unit: pure functions, reducers, strategies
╲__________________╱
```

### T1 — Unit

Test a single pure function, reducer, or strategy in complete isolation. No Store, no `TestEnvironment`, no coroutines. Call the function directly, assert on the return value.

**Permitted:** Direct function calls, `FakePlatformDependencies` (for UUID/timestamp generation only), test data builders.

**What to test:** Reducer state transitions for every action (including null-state init, unknown-action no-op, boundary conditions), pure utilities (normalization, serialization, prompt building), strategy logic (resource slots, prompt assembly, post-processing).

```kotlin
@Test
fun `CREATE should add new agent with valid defaults`() {
    val initialState = AgentRuntimeState()
    val action = Action(ActionRegistry.Names.AGENT_CREATE, buildJsonObject {
        put("name", "My Agent")
    })
    val newState = AgentCrudLogic.reduce(initialState, action, platform)
    assertEquals(1, newState.agents.size)
    assertEquals("My Agent", newState.agents.values.first().identity.name)
}
```

### T2 — Contract

Test a feature's integration with the real Store — reducer execution, guard enforcement, side-effect dispatch, and cross-cutting contracts — using the `TestEnvironment` builder.

**Permitted:** `TestEnvironment`, `RecordingStore` (via `TestHarness`), `FakePlatformDependencies`, `runTest`/`runCurrent`, identity registration helpers.

**What to test:** Side effects fire correctly (persistence, broadcasts), `ACTION_RESULT` is published with `success: true` on the happy path and `success: false` on error for every public command-dispatchable action, error paths log at `ERROR`/`WARN`, Store guards block invalid actions.

**Batch contract testing.** For cross-cutting obligations, use table-driven `HappyCase` / `FailureCase` descriptors with a batch assertion helper that collects all failures before throwing, surfacing every broken contract in a single run:

```kotlin
private fun <T> assertAllCases(cases: List<T>, block: (T) -> Unit) {
    val failures = mutableListOf<String>()
    for (case in cases) {
        try { block(case) }
        catch (e: Throwable) { failures.add(e.message ?: e.toString()) }
    }
    if (failures.isNotEmpty()) {
        throw AssertionError("${failures.size} of ${cases.size} cases failed:\n\n" +
            failures.joinToString("\n\n") { "  • $it" })
    }
}
```

**Discovery audit.** A dedicated test verifies every public action in `ActionRegistry.features[featureHandle]` appears in at least one case entry. New actions without contract coverage cause immediate test failure.

### T3 — Peer

Test a feature's role as a participant in multi-feature workflows. Same infrastructure as T2, plus multiple real features in `TestEnvironment` and identity seeding via `Store.updateIdentityRegistry`.

**What to test:** Targeted action delivery to correct recipients, identity registry as a shared service (registration, namespace isolation, cascade unregister), full cognitive cycles (Agent → Gateway → Response), sentinel output sanitization.

### T4 — Workspace / Pipeline

Test end-to-end data pipelines spanning multiple features with transient state, asynchronous context gathering, and collapse/uncollapse mechanics. Same infrastructure as T3.

**What to test:** Full workspace context flow (listing → read → store → generate), context collapse with correct `ws:`/`hkg:` prefix handling, timeout and error recovery, known bug regressions. T4 files often contain a pure-reducer section alongside a pipeline-integration section, since the pipeline tests exist to guard specific integration bugs.

### T5 — Platform

Test the full workflow using real platform dependencies — real filesystem, real I/O, no fakes. Uses a real `PlatformDependencies` subclass (e.g., `JvmTestPlatformDependencies` with `createTempDirectory`). T5 tests live in platform-specific source sets (`jvmTest`, etc.).

**What to test:** Settings persist and reload across two independent Store lifecycles, encryption roundtrips, any workflow where fake fidelity is insufficient.

```kotlin
@Test
fun `settings persist and reload via real FileSystem`() = runTest {
    val platform = JvmTestPlatformDependencies(testAppVersion)
    // SCOPE 1: Save
    run {
        val store = Store(AppState(), features, platform)
        store.initFeatureLifecycles()
        store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
        store.dispatch("test.setup", addTestAction)
        store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_RUNNING))
        store.dispatch("settings", updateAction)
    }
    // SCOPE 2: Reload
    run {
        val store = Store(AppState(), features, platform)
        store.initFeatureLifecycles()
        store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))
        store.dispatch("test.setup", addTestAction)
        store.dispatch("system.test", Action(ActionRegistry.Names.SYSTEM_RUNNING))
        assertEquals("live_value",
            (store.state.value.featureStates["settings"] as SettingsState).values["test.key"])
    }
}
```

### Naming Convention

All tiers follow: **`{Feature}T{N}{Concern}Test.kt`** — e.g., `AgentRuntimeFeatureT1CrudLogicTest`, `CoreFeatureT3PeerTest`, `SettingsFeatureT5PlatformTest`.


## Test Infrastructure

### TestEnvironment Builder

The primary entry point for T2–T4 tests. Constructs a `RecordingStore` wired with real features, the production action descriptor catalog, and controlled initial state.

```kotlin
val harness = TestEnvironment.create()
    .withFeature(AgentRuntimeFeature(platform, scope))
    .withFeature(SessionFeature(platform, scope))
    .withInitialState("agent", AgentRuntimeState(agents = mapOf(...)))
    .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))
    .build(platform = platform)
```

`CoreFeature` and its state are always injected automatically. Features without explicit initial state get defaults via their reducer. The descriptor catalog defaults to `ActionRegistry.byActionName`; override with `.withActionRegistry()` for a minimal set or `.withExtraDescriptors()` to add test-only actions on top.

**Lifecycle warning:** If you provide a custom `CoreState`, you must explicitly set `lifecycle = AppLifecycle.RUNNING`. The `CoreState` constructor defaults to `BOOTING`, which blocks most actions. This is the most common cause of puzzling test failures.

### RecordingStore

Extends the real `Store`. Registers a callback on the `onDispatch` test hook so that every action passing the Store's guards is appended to `processedActions`. Because it inherits from `Store`, all production guards, routing, and reducer orchestration run exactly as they do in the real application.

### FakeStore

A lightweight test double that captures all `dispatch`, `deferredDispatch`, and `scheduleDelayed` calls in `dispatchedActions` without executing any guards or reducers. Use in T1 tests for `handleSideEffects` logic where you need to verify dispatch intent without the full pipeline. Use `RecordingStore` (via `TestEnvironment`) in T2+ where you need the real pipeline.

### FakePlatformDependencies

A comprehensive in-memory fake providing: filesystem operations on a `Map<String, FakeFile>`, deterministic time and counter-based UUIDs, `capturedLogs` list for log-level assertions, `scheduledCallbacks` list with manual `fireAllScheduledCallbacks()` / `fireScheduledCallbacks(delayMs)` triggers, and a `writtenFiles` map for convenient write assertions.

### Helper Functions

| Helper | Purpose |
|--------|---------|
| `testDescriptorsFor(names)` | Creates open+broadcast `ActionDescriptor` entries for test-only action names so they pass schema validation. |
| `testDescriptorWithPermissions(name, perms)` | Creates a descriptor with specific `requiredPermissions` for testing the permission guard. |
| `testDescriptorsWithPermissions(vararg pairs)` | Batch version — `"action.NAME" to listOf("perm:key")`. |
| `harness.runAndLogOnFailure { }` | Wraps assertions; on failure prints full action log + platform logs before re-throwing. Use in all T2+ tests. |


## Assertion Patterns

| Pattern | Example | When |
|---------|---------|------|
| **Action presence** | `assertNotNull(actions.find { it.name == Names.ACTION_RESULT })` | Verify a side effect or broadcast fired |
| **Action absence** | `assertNull(actions.find { it.name == "unknown.ACTION" })` | Verify a guard blocked an action |
| **Payload inspection** | `assertEquals(true, result.payload?.get("success")?.jsonPrimitive?.boolean)` | Check ACTION_RESULT fields, posted messages |
| **Log-level check** | `assertNotNull(platform.capturedLogs.find { it.level == LogLevel.FATAL })` | Verify error handling logged correctly |
| **State after dispatch** | `assertEquals(1, (featureStates["agent"] as AgentRuntimeState).agents.size)` | Verify reducer produced correct state |
| **Guard rejection** | `assertEquals(initialState, store.state.value)` after dispatch | Confirm state unchanged for blocked action |
| **Targeted delivery** | `assertEquals("filesystem", responseAction.targetRecipient)` | Verify response routed to correct feature |
| **Broadcast reaches peer** | `assertNotNull(actions.findLast { it.name == Names.AGENT_NAMES_UPDATED })` | Verify cross-feature propagation |


## Test Data Builders

Each feature provides test-only builder functions (typically in a `TestHelpers.kt` file within the test package) for constructing domain objects with sensible defaults:

```kotlin
fun testAgent(id: String, name: String, ...) : AgentInstance
fun testSession(id: String, name: String): Session
fun TestHarness.registerAgentIdentity(agent: AgentInstance)
fun TestHarness.registerSessionIdentity(session: Session)
```

These ensure tests focus on behavior under test rather than on constructing valid prerequisite state. All IDs must be valid UUID format since `CoreFeature` validates them.


## Writing New Tests — Checklist

1. **Choose the right tier.** Pure function or reducer → T1. Side effects through the Store → T2. Cross-feature targeted delivery → T3. Full pipeline → T4. Real platform I/O → T5.
2. **Name the file** `{Feature}T{N}{Concern}Test.kt` in the feature's test package.
3. **Add a class-level KDoc** declaring the tier and mandate.
4. **Use `TestEnvironment.create()`** for T2+. Never construct `Store` directly in T2+.
5. **Set `lifecycle = AppLifecycle.RUNNING`** when providing a custom `CoreState`.
6. **Register identities** before dispatching actions that require originator validation.
7. **Wrap assertions in `runAndLogOnFailure`** for T2+.
8. **Use `ActionRegistry.Names.*`** for all action names — never hard-code strings.
9. **Use `testDescriptorsFor()`** for test-only actions.
10. **Assert on `processedActions`** rather than state alone — this verifies the full pipeline.
11. **For contract tests**, add `HappyCase` and `FailureCase` entries and include the action in the discovery audit.