# TASK: Eliminate Identity Registry Dual-Ownership Bug

## Issue

The About view displays feature handles (e.g. `"session"`, `"filesystem"`) instead of human-readable names (e.g. `"Session Manager"`, `"File System"`). The root cause is a **data race between two copies of `identityRegistry`** during boot.

### Root Cause Analysis

`identityRegistry` currently exists in two places:

1. **`AppState.identityRegistry`** — infrastructure-level, read by the Store for authorization.
2. **`CoreState.identityRegistry`** — feature-level, mutated by CoreFeature's reducer.

A "lift" mechanism in `Store.processAction()` copies CoreState's registry over AppState's after every reduce cycle:

```kotlin
val updatedCoreState = newState.featureStates["core"] as? CoreState
val liftedState = if (updatedCoreState != null && updatedCoreState.identityRegistry != newState.identityRegistry) {
    newState.copy(identityRegistry = updatedCoreState.identityRegistry)
} else {
    newState
}
```

The bug sequence:

1. `initFeatureLifecycles()` seeds feature identities **directly into `AppState.identityRegistry`** — this works.
2. However, `CoreState` may not exist yet at that point, so `CoreState.identityRegistry` is **not seeded**.
3. On the first action that triggers CoreFeature's reducer, a `CoreState` is created with an **empty** `identityRegistry`.
4. The lift detects a difference (populated AppState vs empty CoreState) and **overwrites the seeded AppState registry with CoreState's empty one**.
5. All feature identity lookups now return `null`, so `AboutView` falls back to displaying raw handles.

### Architectural Problem

The lift is a symptom of a deeper design flaw: `identityRegistry` is **infrastructure** (the Store needs it before any reducer runs for authorization), but it is **owned by a feature reducer**. This creates a chicken-and-egg synchronization problem. Compare with `actionDescriptors`, which has the same characteristics but correctly lives only on `AppState` with no feature-level copy and no lift.

---

## Solution

**Promote `identityRegistry` to be exclusively owned by `AppState`, mutated via a Store method, with CoreFeature retaining business logic.**

This follows the existing precedent of `actionDescriptors`: infrastructure state lives on `AppState`, not in any `FeatureState`.

### Changes Required

#### 1. `Store.kt` — Add mutation method, remove lift, simplify init

**Add** a new public method for identity registry mutations:

```kotlin
/**
 * Mutates AppState.identityRegistry directly.
 * Called by CoreFeature from handleSideEffects to register/unregister identities.
 * Business logic (validation, parent checks) remains in CoreFeature;
 * the Store owns only the state.
 */
fun updateIdentityRegistry(transform: (Map<String, Identity>) -> Map<String, Identity>) {
    _state.value = _state.value.copy(
        identityRegistry = transform(_state.value.identityRegistry)
    )
}
```

**Simplify** `initFeatureLifecycles()` — remove the `CoreState` seeding branch entirely:

```kotlin
fun initFeatureLifecycles() {
    if (!lifecycleStarted) {
        val featureIdentities = features.associate { it.identity.handle to it.identity }

        // Seed AppState directly — single source of truth from the start
        _state.value = _state.value.copy(
            identityRegistry = _state.value.identityRegistry + featureIdentities
        )

        features.forEach { it.init(this) }
        lifecycleStarted = true
    }
}
```

**Delete** the entire lift block in `processAction()`:

```kotlin
// DELETE THIS BLOCK (and rename all remaining 'liftedState' references to 'newState'):
val updatedCoreState = newState.featureStates["core"] as? CoreState
val liftedState = if (updatedCoreState != null && updatedCoreState.identityRegistry != newState.identityRegistry) {
    newState.copy(identityRegistry = updatedCoreState.identityRegistry)
} else {
    newState
}
```

After deletion, replace every remaining occurrence of `liftedState` with `newState` in `processAction()`.

#### 2. `CoreState` — Remove `identityRegistry` field

Remove the `identityRegistry` field from `CoreState` entirely. It is no longer needed.

```kotlin
// BEFORE:
data class CoreState(
    val lifecycle: AppLifecycle = AppLifecycle.BOOTING,
    val identityRegistry: Map<String, Identity> = emptyMap(),
    // ... other fields
) : FeatureState

// AFTER:
data class CoreState(
    val lifecycle: AppLifecycle = AppLifecycle.BOOTING,
    // ... other fields — identityRegistry removed
) : FeatureState
```

#### 3. `CoreFeature` — Migrate identity mutations from reducer to `handleSideEffects`

Move all `REGISTER_IDENTITY` / `UNREGISTER_IDENTITY` handling out of `reducer()` and into `handleSideEffects()`. CoreFeature keeps the business logic (parent-handle validation, duplicate checks, etc.) but delegates storage to the Store.

**In `handleSideEffects`:**

```kotlin
ActionRegistry.Names.CORE_REGISTER_IDENTITY -> {
    val newIdentity = /* deserialize from action.payload */

    // Business logic: validate parent exists
    val registry = store.state.value.identityRegistry
    if (newIdentity.parentHandle != null && newIdentity.parentHandle !in registry) {
        // log error, return
        return
    }

    // Delegate storage to the Store
    store.updateIdentityRegistry { it + (newIdentity.handle to newIdentity) }
}

ActionRegistry.Names.CORE_UNREGISTER_IDENTITY -> {
    val handle = /* deserialize from action.payload */
    store.updateIdentityRegistry { it - handle }
}
```

**In `reducer`:** Remove the corresponding `REGISTER_IDENTITY` / `UNREGISTER_IDENTITY` cases that previously mutated `CoreState.identityRegistry`.

#### 4. Grep for remaining references

Search the entire codebase for:

- `CoreState.identityRegistry` or `coreState.identityRegistry` — should have zero hits after the change.
- `identityRegistry` in any reducer — should only appear in `AppState` and `Store` now.
- Any direct reads of `CoreState` for identity data — redirect to `store.state.value.identityRegistry`.

---

## Test Conditions

### T1: About View displays feature names (regression test for the original bug)

1. Launch the app from a cold start.
2. Navigate to the About view.
3. **Verify** each feature card shows the human-readable `displayName` (e.g. `"File System"`, `"Session Manager"`), NOT the raw handle (e.g. `"filesystem"`, `"session"`).

### T2: Feature identities survive the full boot sequence

1. Launch the app.
2. Wait for lifecycle to reach `RUNNING`.
3. Read `store.state.value.identityRegistry`.
4. **Verify** every feature in `store.features` has a corresponding entry in the registry with the correct `handle`, `name`, and `parentHandle`.

### T3: Runtime identity registration still works

1. With the app in `RUNNING` state, dispatch a `CORE_REGISTER_IDENTITY` action to register a new identity (e.g. a new agent session with a `parentHandle` pointing to an existing feature).
2. **Verify** the new identity appears in `store.state.value.identityRegistry`.
3. **Verify** the About view reflects the updated child identity count for the parent feature.

### T4: Runtime identity unregistration works

1. Register an identity per T3.
2. Dispatch `CORE_UNREGISTER_IDENTITY` for that identity's handle.
3. **Verify** the identity is removed from `store.state.value.identityRegistry`.

### T5: Parent validation is enforced

1. Dispatch `CORE_REGISTER_IDENTITY` with a `parentHandle` that does not exist in the registry.
2. **Verify** the identity is NOT added to the registry.
3. **Verify** an error is logged.

### T6: No CoreState.identityRegistry references remain

1. Run a project-wide search for `identityRegistry` in any `CoreState` context.
2. **Verify** zero hits — `identityRegistry` should only exist on `AppState`.

### T7: Existing unit tests pass

1. Run the full test suite.
2. **Verify** all existing tests pass (update any tests that previously asserted against `CoreState.identityRegistry` to assert against `store.state.value.identityRegistry` instead).
