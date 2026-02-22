# Implementation Plan: Agent Session References → UUID (v2 — Complete)

## Problem Statement

Session handles (e.g. `"session.chat1"`) are mutable — renaming a session changes its handle. Agents store session references as `IdentityHandle` in `subscribedSessionIds` and `outputSessionId`. When a session is renamed, these links break silently.

**Fix:** All internal agent references to sessions must use `IdentityUUID` (the system-assigned, immutable identifier). Handles and display names are resolved at point-of-use via the `identityRegistry`.

**Additional requirement:** All action payloads that accept an identity (agent, session etc.) must resolve it flexibly: by UUID, handle, localHandle, or display name. On failure, the `ACTION_RESULT` error should suggest close matches.

---

## Architectural Decisions

### 1. UUID is the internal key; handle is resolved at dispatch time

```kotlin
AgentInstance.subscribedSessionIds : List<IdentityUUID>   // stored, persisted
AgentInstance.outputSessionId      : IdentityUUID?        // stored, persisted
```

### 2. Identity registry helper extensions (new file: `IdentityResolution.kt`)

```kotlin
// UUID → Identity (for session resolution at dispatch time)
fun Map<String, Identity>.findByUUID(uuid: IdentityUUID): Identity? =
    values.find { it.uuid == uuid.uuid }

// Flexible agent resolution (for incoming action payloads)
fun AgentRuntimeState.resolveAgent(raw: String): AgentInstance? {
    // 1. Direct UUID match (most common)
    agents[IdentityUUID(raw)]?.let { return it }
    // 2. Full handle match (e.g. "agent.gemini-coder-1")
    agents.values.find { it.identityHandle.handle == raw }?.let { return it }
    // 3. Local handle match (e.g. "gemini-coder-1")
    agents.values.find { it.identity.localHandle == raw }?.let { return it }
    // 4. Exact display name match (case-insensitive)
    agents.values.find { it.identity.name.equals(raw, ignoreCase = true) }?.let { return it }
    return null
}

// Close-match suggestions for error messages
fun AgentRuntimeState.suggestAgentMatches(raw: String): List<String> {
    val lower = raw.lowercase()
    return agents.values
        .filter {
            it.identity.name.lowercase().contains(lower) ||
            it.identity.localHandle.contains(lower) ||
            it.identityHandle.handle.contains(lower)
        }
        .take(3)
        .map { "'${it.identity.name}' (${it.identityUUID})" }
}
```

### 3. Cross-feature session actions still use handles

`SESSION_POST`, `SESSION_REQUEST_LEDGER_CONTENT`, etc. are owned by the session feature and use handles. At the agent's dispatch site, we resolve UUID → handle immediately before dispatching. We never change the session feature's contract.

### 4. Session-related payloads from session feature

`SESSION_SESSION_NAMES_UPDATED`, `SESSION_MESSAGE_POSTED`, `SESSION_SESSION_DELETED` come from the session feature and use `IdentityHandle`. The agent reducer must convert these to `IdentityUUID` at the boundary. This requires the reducer to look up sessions in the `identityRegistry` (which it can access via `AppState`), or — more practically — the `subscribableSessionNames` map must be keyed by UUID, which means the session feature should broadcast UUIDs. **If changing the session feature is out of scope**, we can build a local `handleToUUID` lookup inside the reducer from the identity registry.

**Recommendation:** Change the session feature's `SESSION_NAMES_UPDATED` broadcast to send UUIDs as keys (or add a UUID field). This is a one-line change in the session feature and avoids building secondary lookup tables.

### 5. Clean comment policy

All `[PHASE N]`, `[ROBUSTNESS FIX]`, `[E7]`, `[E8]`, `[NEW]`, `[FIX]`, and similar historical comments are removed. Architectural doc-comments and KDoc are preserved and updated.

---

## Complete Change Inventory

### Layer 0: Core Identity Resolution Extensions — `Identity.kt`

These are **universal** extensions on the identity registry map (`Map<String, Identity>`),
living in `app.auf.core.Identity.kt` alongside the existing `Identity`, `IdentityHandle`,
and `IdentityUUID` types. Every feature in the app can use them — agent, session,
knowledgegraph, commandbot, etc.

```kotlin
// ============================================================================
// Identity Registry Extensions
//
// The identity registry is Map<String, Identity> keyed by handle.
// These extensions provide lookup by any form of identity reference
// and close-match suggestions for error messages.
// ============================================================================

/**
 * Find an identity by its UUID.
 * Returns null if no identity with this UUID exists in the registry.
 */
fun Map<String, Identity>.findByUUID(uuid: IdentityUUID): Identity? =
    values.find { it.uuid == uuid.uuid }

/**
 * Find an identity by its UUID string.
 */
fun Map<String, Identity>.findByUUID(uuid: String): Identity? =
    values.find { it.uuid == uuid }

/**
 * Universal identity resolution. Accepts any form of identity reference
 * and returns the matching Identity, or null.
 *
 * Resolution order (first match wins):
 *   1. Exact handle match (map key lookup — O(1))
 *   2. UUID match
 *   3. localHandle match
 *   4. Case-insensitive display name match
 *
 * This order prioritizes unambiguous identifiers over potentially
 * ambiguous ones (multiple identities could share a display name).
 */
fun Map<String, Identity>.resolve(raw: String): Identity? {
    // 1. Direct handle (O(1) map lookup)
    this[raw]?.let { return it }
    // 2. UUID
    values.find { it.uuid == raw }?.let { return it }
    // 3. localHandle
    values.find { it.localHandle == raw }?.let { return it }
    // 4. Display name (case-insensitive)
    values.find { it.name.equals(raw, ignoreCase = true) }?.let { return it }
    return null
}

/**
 * Scoped variant: resolves only among identities that are children of
 * the given parent handle (e.g. "agent", "session").
 */
fun Map<String, Identity>.resolve(raw: String, parentHandle: String): Identity? {
    val scoped = values.filter { it.parentHandle == parentHandle }
    scoped.find { it.handle == raw }?.let { return it }
    scoped.find { it.uuid == raw }?.let { return it }
    scoped.find { it.localHandle == raw }?.let { return it }
    scoped.find { it.name.equals(raw, ignoreCase = true) }?.let { return it }
    return null
}

/**
 * Returns up to [limit] identities whose name, localHandle, or handle
 * contain the query string (case-insensitive). Useful for "did you mean?"
 * suggestions in error messages.
 *
 * Optionally scoped to a parent handle.
 */
fun Map<String, Identity>.suggestMatches(
    query: String,
    parentHandle: String? = null,
    limit: Int = 3
): List<Identity> {
    val lower = query.lowercase()
    return values
        .filter { identity ->
            (parentHandle == null || identity.parentHandle == parentHandle) &&
            (identity.name.lowercase().contains(lower) ||
             identity.localHandle.lowercase().contains(lower) ||
             identity.handle.lowercase().contains(lower))
        }
        .take(limit)
}
```

**Usage in agent feature** (thin wrapper, no agent-specific resolution logic):

```kotlin
// In AgentRuntimeFeature / AgentCrudLogic:
val registry = store.state.value.identityRegistry
val identity = registry.resolve(raw, parentHandle = "agent") ?: run {
    val suggestions = registry.suggestMatches(raw, parentHandle = "agent")
        .map { "'${it.name}' (${it.uuid})" }
    val hint = if (suggestions.isNotEmpty())
        " Did you mean: ${suggestions.joinToString(", ")}?" else ""
    publishActionResult(store, correlationId, action.name, false,
        error = "Agent '$raw' not found.$hint")
    return
}
val agent = agentState.agents[identity.identityUUID!!] ?: return

// For session handle resolution at dispatch time:
val sessionHandle = registry.findByUUID(sessionUUID)?.handle ?: return
```

This is used by:
- **All dispatch sites** for session handle resolution (Pipeline, AvatarLogic, RuntimeFeature)
- **All `agentId` extraction points** for flexible resolution (CrudLogic, RuntimeFeature side effects)
- **Any future feature** that needs to resolve identity references from user/agent input

---

### Layer 1: Data Model Changes

#### `AgentState.kt` — `AgentInstance`

| Field | Before | After |
|---|---|---|
| `subscribedSessionIds` | `List<IdentityHandle>` | `List<IdentityUUID>` |
| `outputSessionId` | `IdentityHandle?` | `IdentityUUID?` |

All other fields unchanged. Serialization remains transparent (value classes serialize as plain strings).

#### `AgentState.kt` — `AgentRuntimeState`

| Field | Before | After |
|---|---|---|
| `subscribableSessionNames` | `Map<IdentityHandle, String>` | `Map<IdentityUUID, String>` |
| `agentAvatarCardIds` (inner) | `Map<IdentityUUID, Map<IdentityHandle, String>>` | `Map<IdentityUUID, Map<IdentityUUID, String>>` |

#### `AgentState.kt` — `AgentPendingCommand`

| Field | Before | After |
|---|---|---|
| `sessionId` | `IdentityHandle` | `IdentityUUID` |

#### `AgentPayloads.kt`

| Payload | Field | Before | After | Notes |
|---|---|---|---|---|
| `SessionNamesPayload` | `names` | `Map<IdentityHandle, String>` | `Map<IdentityUUID, String>` | Requires session feature to send UUIDs |
| `MessagePostedPayload` | `sessionId` | `IdentityHandle` | `IdentityUUID` | Session feature sends UUID |
| `MessageDeletedPayload` | `sessionId` | `IdentityHandle` | `IdentityUUID` | Session feature sends UUID |
| `AvatarMovedPayload` | `sessionId` | `IdentityHandle` | `IdentityUUID` | Internal — we control this |
| `RegisterPendingCommandPayload` | `sessionId` | `IdentityHandle` | `IdentityUUID` | Internal — we control this |

#### `CognitiveStrategy.kt` — `SessionInfo`

```kotlin
// Before:
data class SessionInfo(val handle: String, val name: String, val isOutput: Boolean)

// After:
data class SessionInfo(
    val uuid: String,
    val handle: String,
    val name: String,
    val isOutput: Boolean
)
```

#### `CognitiveStrategy.kt` — `AgentTurnContext`

```kotlin
// Before:
val outputSessionHandle: String? = null

// After:
val outputSessionUUID: String? = null,
val outputSessionHandle: String? = null
```

Strategies use `outputSessionHandle` for display; the pipeline uses `outputSessionUUID` for equality comparison.

---

### Layer 2: Action Schemas — `agent_actions.json`

Update descriptions for these payload fields:

- `agent.CREATE` → `subscribedSessionIds`: "List of session **UUIDs**..."
- `agent.UPDATE_CONFIG` → `subscribedSessionIds`, `outputSessionId`: "Session **UUID**..."
- `agent.AVATAR_MOVED` → `sessionId`: "Session **UUID**..."
- `agent.REGISTER_PENDING_COMMAND` → `sessionId`: "Session **UUID**..."

---

### Layer 3: Flexible Agent Resolution (using core `Identity.resolve()`)

Every site that currently does `payload.agentUUID()` → `state.agents[agentId]` on a
**public, command-dispatchable action** must use the core `resolve()` extension from
`Identity.kt`, scoped to `parentHandle = "agent"`.

#### `AgentCrudLogic.kt`

The existing `agentUUID()` boundary helper stays for **internal** actions where the ID
is always a UUID (from our own dispatches). For user-facing actions, resolution goes
through the identity registry:

```kotlin
// New helper in AgentCrudLogic (or shared in the feature):
private fun resolveAgentFromPayload(
    payload: JsonObject,
    state: AgentRuntimeState,
    registry: Map<String, Identity>,
    field: String = "agentId"
): AgentInstance? {
    val raw = payload[field]?.jsonPrimitive?.contentOrNull ?: return null
    val identity = registry.resolve(raw, parentHandle = "agent") ?: return null
    return identity.identityUUID?.let { state.agents[it] }
}
```

**Problem:** The reducer doesn't have access to the identity registry (it only receives
`FeatureState`). Two options:

- **Option A:** The reducer keeps using direct UUID lookup. Flexible resolution only
  happens in `handleSideEffects`, which has access to the `Store`. The reducer silently
  returns unchanged state on miss; the side effect logs the error with suggestions.
  This is consistent with the existing pattern where reducers are pure and side effects
  handle error reporting.

- **Option B:** Pass the identity registry into the reducer. This breaks the current
  `(FeatureState, Action) -> FeatureState` contract.

**Recommendation: Option A.** The reducer stays pure. Side effects do the user-facing
resolution and error messaging. This means the reducer's `agentUUID()` helper stays
as-is for now — it only matches exact UUIDs, which is fine because by the time the
reducer runs, either the UUID was correct or the side effect will report the error.

#### `AgentRuntimeFeature.kt` — side effects

Every `payload?.agentUUID()` on a **public** action is replaced with:

```kotlin
val raw = payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
val registry = store.state.value.identityRegistry
val identity = registry.resolve(raw, parentHandle = "agent") ?: run {
    val suggestions = registry.suggestMatches(raw, parentHandle = "agent")
        .map { "'${it.name}' (${it.uuid})" }
    val hint = if (suggestions.isNotEmpty())
        " Did you mean: ${suggestions.joinToString(", ")}?" else ""
    publishActionResult(store, correlationId, action.name, false,
        error = "Agent '$raw' not found.$hint")
    return
}
val agentId = identity.identityUUID!!
val agent = agentState.agents[agentId] ?: return
```

**Affected side effects (public/command-dispatchable):** `AGENT_CLONE`,
`AGENT_TOGGLE_AUTOMATIC_MODE`, `AGENT_TOGGLE_ACTIVE`, `AGENT_UPDATE_CONFIG`,
`AGENT_DELETE`, `AGENT_INITIATE_TURN`, `AGENT_EXECUTE_PREVIEWED_TURN`,
`AGENT_DISCARD_PREVIEW`, `AGENT_CANCEL_TURN`, `AGENT_UPDATE_NVRAM`.

**Internal actions stay with direct UUID:** `AGENT_STAGE_TURN_CONTEXT`,
`AGENT_SET_HKG_CONTEXT`, `AGENT_SET_WORKSPACE_CONTEXT`,
`AGENT_CONTEXT_GATHERING_TIMEOUT`, `AGENT_NVRAM_LOADED`.

---

### Layer 4: Session UUID→Handle Resolution Sites

Every place that puts a session identifier into an action payload for a cross-feature dispatch needs to resolve UUID → handle via the identity registry.

#### `AgentCognitivePipeline.kt`

**`startCognitiveCycle`** (line 50):
```kotlin
val contextSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
val contextSessionHandle = resolveSessionHandle(contextSessionUUID, registry) ?: return
put("sessionId", contextSessionHandle)
```

**`executeTurn`** — SESSION_METADATA context (line ~350):
```kotlin
val sessionUUID = agent.subscribedSessionIds.firstOrNull()
val sessionIdentity = sessionUUID?.let { registry.findByUUID(it) }
val sessionName = sessionIdentity?.name ?: "Unknown Session"
```

**`executeTurn`** — subscribedSessionInfos construction (lines 431–441):
```kotlin
val subscribedSessionInfos = agent.subscribedSessionIds.mapNotNull { sessUUID ->
    val sessIdentity = identityRegistry.findByUUID(sessUUID)
    val sessName = sessIdentity?.name
        ?: agentState.subscribableSessionNames[sessUUID]
        ?: sessUUID.uuid
    val sessHandle = sessIdentity?.handle ?: sessUUID.uuid
    SessionInfo(
        uuid = sessUUID.uuid,
        handle = sessHandle,
        name = sessName,
        isOutput = sessUUID == agent.outputSessionId
    )
}
val outputSessionHandle = agent.outputSessionId?.let { registry.findByUUID(it)?.handle }
```

**`executeTurn`** — response posting (lines 573, 612–621):
```kotlin
val targetSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
val targetSessionHandle = resolveSessionHandle(targetSessionUUID, registry) ?: return
put("session", targetSessionHandle)
```

**Total sites in Pipeline:** ~6 session handle resolutions.

#### `AgentAvatar.kt` — `AgentAvatarLogic`

**`touchAgentAvatarCard`** — iterate session map (line ~35):
```kotlin
val registry = store.state.value.identityRegistry
sessionMap.forEach { (sessionUUID, messageId) ->
    val handle = registry.findByUUID(sessionUUID)?.handle ?: return@forEach
    put("session", handle)
}
```

**`updateAgentAvatars`** — target sessions (line ~79):
```kotlin
val targetSessions: List<IdentityUUID> =
    (agent.subscribedSessionIds + listOfNotNull(agent.outputSessionId)).distinct()
```

All `sessionId.handle` occurrences in `SESSION_DELETE_MESSAGE`, `SESSION_POST` dispatches → resolve via registry.

**Total sites in AvatarLogic:** ~5 session handle resolutions.

#### `AgentRuntimeFeature.kt`

**`getAgentResponseSessionId`** (line 1067):
```kotlin
// Before: returns IdentityHandle?
// After: returns IdentityUUID?
private fun getAgentResponseSessionId(agent: AgentInstance): IdentityUUID? {
    return agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
}
```

Then at every call site where the result is used in a dispatch:
```kotlin
val sessionUUID = getAgentResponseSessionId(agent) ?: return
val sessionHandle = resolveSessionHandle(sessionUUID, store.state.value.identityRegistry) ?: return
put("session", sessionHandle)
```

**Affected sites:**
- `handleFileSystemListResponse` — workspace listing (line ~901)
- `handleFileSystemReadResponse` — workspace file read (line ~944)
- `routeCommandResponseToSession` — pending command delivery (line ~751): `pending.sessionId` is now `IdentityUUID`

**AGENT_DELETE side effects** (line ~321): `agentAvatarCardIds[agentId]` inner map keys are now `IdentityUUID`:
```kotlin
agentState.agentAvatarCardIds[agentId]?.forEach { (sessionUUID, messageId) ->
    val handle = registry.findByUUID(sessionUUID)?.handle ?: return@forEach
    put("session", handle)
}
```

**AGENT_CLONE** (line ~227): serializing subscribedSessionIds:
```kotlin
put("subscribedSessionIds", buildJsonArray {
    agentToClone.subscribedSessionIds.forEach { add(it.uuid) }
})
```

**Total sites in RuntimeFeature:** ~6 session handle resolutions.

#### `AgentRuntimeReducer.kt`

**`SESSION_SESSION_NAMES_UPDATED`** (line 184): If session feature sends UUIDs, parse as `IdentityUUID`:
```kotlin
action.payload?.get("names")?.jsonObject
    ?.mapKeys { IdentityUUID(it.key) }
    ?.mapValues { it.value.jsonPrimitive.content }
```

**`SESSION_SESSION_DELETED`** (line 145): Parse as `IdentityUUID`:
```kotlin
val deletedSessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
    ?.let { IdentityUUID(it) } ?: return state
```

**`handleMessagePosted`** (line ~354): `agent.subscribedSessionIds.contains(sessionId)` — works if both are `IdentityUUID` after payload type change.

**`AGENT_AVATAR_MOVED`** (line 78): `AvatarMovedPayload.sessionId` is now `IdentityUUID`. The map update works because inner key type matches.

**`SESSION_MESSAGE_DELETED`** (line 130): Find session entry by messageId → key removal works on `IdentityUUID` inner key.

---

### Layer 5: Strategy Changes

#### `VanillaStrategy.kt`

**`prepareSystemPrompt`** — session awareness block:
- `session.handle` used for display → still available in `SessionInfo`
- `context.outputSessionHandle` used for `[PRIMARY]` tagging → still available

**`validateConfig`** — `agent.outputSessionId !in agent.subscribedSessionIds` → now comparing `IdentityUUID` to `List<IdentityUUID>`. Works.

**No functional changes needed.** Clean up `[PHASE N]` comments only.

#### `MinimalStrategy.kt`

Same as Vanilla. **No functional changes.** Clean up comments.

#### `SovereignStrategy.kt`

**`onAgentConfigChanged`** (line 221): KG revocation truncates subscriptions:
```kotlin
// Before:
put("subscribedSessionIds", buildJsonArray {
    truncatedSubscriptions.forEach { add(it.handle) }
})
// After:
put("subscribedSessionIds", buildJsonArray {
    truncatedSubscriptions.forEach { add(it.uuid) }
})
```

**`ensureInfrastructure`** (line 273): Links session by `localHandle`:
```kotlin
// Before:
put("outputSessionId", existingSessionIdentity.localHandle)
// After — must send UUID:
put("outputSessionId", existingSessionIdentity.uuid)
```
Note: `existingSessionIdentity.uuid` may be null for feature-level identities, but session identities always have UUIDs. Add a null check for safety.

**`prepareSystemPrompt`** — uses `context.outputSessionHandle` for display → still available.

**Clean up all `[PHASE N]` comments.**

#### `SovereignDefaults.kt`

No session references. **Clean comments only.**

---

### Layer 6: UI Changes

#### `AgentManagerView.kt`

**`AgentEditorView` — `onSave`** (line ~308):
```kotlin
// Before:
put("subscribedSessionIds", buildJsonArray {
    draftAgent.subscribedSessionIds.forEach { add(it.handle) }
})
if (draftAgent.outputSessionId != null) put("outputSessionId", draftAgent.outputSessionId!!.handle)

// After:
put("subscribedSessionIds", buildJsonArray {
    draftAgent.subscribedSessionIds.forEach { add(it.uuid) }
})
if (draftAgent.outputSessionId != null) put("outputSessionId", draftAgent.outputSessionId!!.uuid)
```

**`AgentReadOnlyView`** (line ~225): outputSessionId display lookup:
```kotlin
// Before:
identityRegistry["session.$it"]?.name ?: agentState.subscribableSessionNames[it] ?: it.handle

// After:
identityRegistry.findByUUID(it)?.name ?: agentState.subscribableSessionNames[it] ?: it.uuid
```

**`MultiSessionSelector`** — `subscribableSessionNames` key type changes to `IdentityUUID`. Code works after type change because `agent.subscribedSessionIds.contains(sessionId)` compares `IdentityUUID` to `IdentityUUID`.

**`OutputSessionSelector`** — same. `sessionId == agent.outputSessionId` compares `IdentityUUID`s.

**Clean up `[PHASE 7]` and other historical comments.**

#### `PreviewContextView.kt`

No session references. **Clean comments only.**

---

### Layer 7: Persistence & Migration

Persisted `agent.json` files currently contain `subscribedSessionIds` and `outputSessionId` as handle strings. After migration, they'll be UUID strings.

**Recommendation: Migration on load** (better UX than breaking change since we now know the code):

In `AgentRuntimeFeature.handleFileSystemReadResponse` — agent config loading (line ~962):
```kotlin
// After deserializing:
// Check if subscribedSessionIds look like handles (contain dots or match known patterns)
// Convert to UUIDs by looking up the identity registry
agent = agent.copy(
    subscribedSessionIds = agent.subscribedSessionIds.map { maybeHandle ->
        // If it's already a UUID format, keep it
        // If it looks like a handle, resolve via registry
        val resolved = store.state.value.identityRegistry.values
            .find { it.handle == maybeHandle.uuid || it.localHandle == maybeHandle.uuid }
        if (resolved?.uuid != null) IdentityUUID(resolved.uuid) else maybeHandle // keep as-is, will fail gracefully
    },
    outputSessionId = agent.outputSessionId?.let { maybeHandle ->
        val resolved = store.state.value.identityRegistry.values
            .find { it.handle == maybeHandle.uuid || it.localHandle == maybeHandle.uuid }
        if (resolved?.uuid != null) IdentityUUID(resolved.uuid) else maybeHandle
    }
)
```

Wait — after the type change, these fields will be `List<IdentityUUID>` and `IdentityUUID?`. Since `IdentityUUID` serializes as a plain string, the deserializer will happily load old handle strings into `IdentityUUID` wrappers. The value will just be wrong (a handle string in a UUID field). The migration code above should detect this and re-resolve.

**Heuristic for detection:** UUIDs typically match `[0-9a-f-]{36}`. If the value doesn't match this pattern, it's a legacy handle.

---

## Implementation Order

```
Phase A — Core Foundation (do first — everything else depends on this):
  1. Add to Identity.kt: findByUUID(), resolve(), resolve(parentHandle),
     suggestMatches() extensions on Map<String, Identity>
  2. Change AgentInstance: subscribedSessionIds → List<IdentityUUID>,
     outputSessionId → IdentityUUID?
  3. Change AgentRuntimeState: subscribableSessionNames → Map<IdentityUUID, String>,
     agentAvatarCardIds inner key → IdentityUUID
  4. Change AgentPendingCommand.sessionId → IdentityUUID
  5. Change AgentPayloads.kt: AvatarMovedPayload, RegisterPendingCommandPayload
  6. Change CognitiveStrategy.kt: SessionInfo (add uuid), AgentTurnContext (add outputSessionUUID)

Phase B — Reducer & CRUD (fix compilation):
  7. AgentCrudLogic — parse subscribedSessionIds as IdentityUUID in CREATE/UPDATE_CONFIG
  8. AgentRuntimeReducer — update all session-related comparisons and map operations
  9. AgentPayloads — MessagePostedPayload, MessageDeletedPayload, SessionNamesPayload
     (depends on session feature change or boundary conversion)

Phase C — Flexible agent resolution (uses core resolve() from Phase A):
  10. Update AgentRuntimeFeature side effects — resolve() + suggestMatches() for
      all 10 public command-dispatchable actions, with ACTION_RESULT error hints

Phase D — Session handle resolution at dispatch sites:
  11. AgentCognitivePipeline — all ~6 sites using findByUUID()
  12. AgentAvatarLogic — all ~5 sites using findByUUID()
  13. AgentRuntimeFeature — all ~6 sites (getAgentResponseSessionId, DELETE, CLONE, etc.)

Phase E — Strategies:
  14. SovereignStrategy — UUID serialization in onAgentConfigChanged & ensureInfrastructure
  15. VanillaStrategy, MinimalStrategy — no functional changes

Phase F — UI:
  16. AgentManagerView — editor save, display lookups

Phase G — Migration & Cleanup:
  17. Add legacy handle→UUID migration in agent config loading
  18. Clean all [PHASE N] / [FIX] / [NEW] / [E7] comments from ALL touched files
  19. Update agent_actions.json descriptions

Phase H — Testing:
  20. Create agent, subscribe to session, rename session → verify link survives
  21. Test identity resolution by name, handle, localHandle, and UUID (via any feature)
  22. Test error messages with close-match suggestions
  23. Test persistence: save agent, restart, verify session links persist correctly
  24. Test legacy migration: load old agent.json with handle-based session IDs
```

---

## Files Requiring External Changes (Session Feature)

The session feature broadcasts session identifiers in several actions. These currently use handles:

| Action | Field | Current | Needed |
|---|---|---|---|
| `session.SESSION_NAMES_UPDATED` | `names` map keys | handles | UUIDs |
| `session.MESSAGE_POSTED` | `sessionId` | handle | UUID |
| `session.MESSAGE_DELETED` | `sessionId` | handle | UUID |
| `session.SESSION_DELETED` | `sessionId` | handle | UUID |

**If changing the session feature is not feasible now**, the agent reducer can perform handle→UUID conversion at the boundary using the identity registry. This adds ~5 lines per action handler but avoids cross-feature changes.

---

## Summary of Universal Patterns

### Pattern 1: Session UUID → Handle at dispatch (applied ~17 times)
```kotlin
val handle = store.state.value.identityRegistry.findByUUID(sessionUUID)?.handle
    ?: run { log(ERROR, "Session UUID '$sessionUUID' not in registry"); return }
put("session", handle)
```

### Pattern 2: Flexible agent resolution at boundary (applied ~10 times)
```kotlin
val raw = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return
val agent = agentState.resolveAgent(raw) ?: run {
    val hints = agentState.suggestAgentMatches(raw)
    val msg = "Agent '$raw' not found." +
        if (hints.isNotEmpty()) " Did you mean: ${hints.joinToString(", ")}?" else ""
    publishActionResult(store, correlationId, action.name, false, error = msg)
    return
}
```

### Pattern 3: Comment cleanup (applied to all touched files)
```
Remove: [PHASE N], [E7], [E8], [FIX], [NEW], [ROBUSTNESS FIX], [REFACTORED]
Keep: KDoc, architectural doc-comments (reworded to remove phase references)
```
