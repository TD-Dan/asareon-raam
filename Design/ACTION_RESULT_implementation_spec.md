# Implementation Specification: `{feature}.ACTION_RESULT`

## Status
**Ready for implementation.** This document captures the complete design rationale and specification. The implementer should read this fully before making any changes.

---

## 1. Problem Statement

The application uses an event-driven architecture where users and AI agents can issue commands by typing `auf_{actionName}` code blocks into a session. CommandBot parses these, validates them, and publishes `commandbot.ACTION_CREATED`. CoreFeature (for user commands) or AgentFeature (for agent commands) then dispatches the actual domain action.

**The gap:** When a domain action completes, the result has no path back to the session where the command was typed. Specifically:

- **Mutation actions** (e.g., `filesystem.SYSTEM_WRITE`) complete silently. The user who typed the command gets no feedback that it succeeded or failed.

- **Query actions** (e.g., `filesystem.SYSTEM_READ`) produce targeted `RETURN_*` actions that are delivered only to the `originator` identity. This data reaches the requesting feature internally, but the user sees nothing in the session transcript. Agents see nothing in their next context window.

The result: commands feel like they disappear into a void. Users expect to see feedback in the session. Agents need results in the transcript to reason about on subsequent turns.

---

## 2. Solution Overview

Introduce a **lightweight broadcast report** — `{feature}.ACTION_RESULT` — that any feature publishes after completing a command-originated domain action. CommandBot subscribes to all `*.ACTION_RESULT` broadcasts, matches them to pending commands via `correlationId`, and posts formatted feedback into the originating session.

This is a **complement** to the existing targeted data-delivery actions, not a replacement. The two serve fundamentally different purposes.

### The Two Channels

| Aspect | `{feature}.RETURN_{X}` | `{feature}.ACTION_RESULT` (new) |
|--------|-------------------------------------------|----------------------------------|
| **Purpose** | Deliver requested data to the caller | Report that an action happened |
| **Routing** | Targeted (`targetRecipient = originator`) | Broadcast (all features see it) |
| **Payload size** | Potentially large (file contents, listings) | Always lightweight (summary string) |
| **Audience** | The single feature that requested the data | CommandBot, logging, debugging, auditing |
| **Contains raw data?** | Yes — the full result payload | No — a human-readable summary |
| **Analogy** | Function return value | Log entry / event notification |

### Why Not Just Broadcast the Data?

This was considered and rejected. Broadcasting a 10MB file read to every feature on the action bus is not premature optimization — it's a real performance and security concern. The split keeps the data channel targeted and the notification channel lightweight.

---

## 3. The correlationId Threading Chain

The mechanism relies on a `correlationId` that threads through four hops:

```
1. CommandBot generates correlationId, publishes ACTION_CREATED
       │
       ├── CommandBot also dispatches REGISTER_PENDING_RESULT
       │   (stores correlationId → sessionId mapping)
       │
2. CoreFeature handles ACTION_CREATED for user commands:
   - Extracts correlationId from ACTION_CREATED payload
   - Injects it into the domain action's payload
   - Dispatches domain action with user as originator
       │
3. FileSystemFeature (or any feature) handles the domain action:
   - Extracts correlationId from the action payload
   - Performs the operation
   - Publishes RETURN_* (targeted, with data) — existing behavior
   - NEW: Also publishes {feature}.ACTION_RESULT (broadcast, lightweight)
     with the same correlationId
       │
4. CommandBot receives ACTION_RESULT broadcast:
   - Matches correlationId against pendingResults map
   - Posts formatted feedback to the registered session
   - Clears the pending result entry
```

**Key constraint:** CommandBot never impersonates anyone. CommandBot never dispatches domain actions. CoreFeature dispatches with the real user identity as originator, preserving all permission and sandbox resolution. The correlationId is just metadata that passes through.

---

## 4. Changes by Component

### 4.1 Action Manifest Schema (*.actions.json)

No changes to the manifest schema itself. Features declare `ACTION_RESULT` as a regular action following the naming convention.

### 4.2 ActionRegistry

Add one new derived view:

```kotlin
/** 
 * All {feature}.ACTION_RESULT action names. 
 * Built by convention: any action whose suffix is "ACTION_RESULT".
 * CommandBot uses this set for O(1) interception. 
 */
val actionResultNames: Set<String> = byActionName.values
    .filter { it.suffix == "ACTION_RESULT" }
    .map { it.fullName }
    .toSet()
```

This is purely convention-driven. New features and plugins participate automatically by declaring `{feature}.ACTION_RESULT` in their manifest. No schema additions to `ActionDescriptor` are needed.

### 4.3 CommandBotState

Add a new field to `CommandBotState`:

```kotlin
val pendingResults: Map<String, PendingResult> = emptyMap()
```

Where `PendingResult` is:

```kotlin
data class PendingResult(
    val correlationId: String,
    val sessionId: String,        // Where to post feedback
    val originatorId: String,     // Who issued the command (for display)
    val originatorName: String,
    val actionName: String,       // The domain action (for display)
    val createdAt: Long           // For TTL-based cleanup of stale entries
)
```

Stale entries (e.g., if a feature never publishes ACTION_RESULT) should be cleaned up after a TTL, suggested 5 minutes.

### 4.4 CommandBot Actions (commandbot_actions.json)

Add two new internal actions:

- **`commandbot.REGISTER_PENDING_RESULT`** — Adds a `correlationId → PendingResult` entry. Dispatched by CommandBot alongside every `ACTION_CREATED`.

- **`commandbot.CLEAR_PENDING_RESULT`** — Removes a pending result entry. Dispatched after a matching `ACTION_RESULT` is processed, or on TTL expiry.

### 4.5 CommandBotFeature — Reducer

Handle `REGISTER_PENDING_RESULT` (add entry to `pendingResults` map) and `CLEAR_PENDING_RESULT` (remove entry).

### 4.6 CommandBotFeature — handleSideEffects

Two changes:

**a) In `publishActionCreated`:** After publishing `ACTION_CREATED`, also dispatch `REGISTER_PENDING_RESULT` with the same `correlationId` and the `sessionId` from the command.

**b) In the `else` branch of `handleSideEffects`:** Check if the incoming action's name is in `ActionRegistry.actionResultNames`. If so:

1. Extract `correlationId` from the payload. If null, ignore (not command-originated).
2. Look up `correlationId` in `pendingResults`. If not found, ignore (not ours, or already processed).
3. Format a feedback message from the ACTION_RESULT's `requestAction`, `success`, `summary`, and `error` fields.
4. Post the formatted message to the session via `session.POST`.
5. Dispatch `CLEAR_PENDING_RESULT` to clean up.

### 4.7 CoreFeature — ACTION_CREATED Handler

CoreFeature already handles `ACTION_CREATED` for user commands (dispatches the domain action with the user's identity as originator). The only change:

**Inject `correlationId` into the domain action's payload before dispatching.**

Currently (simplified):
```kotlin
val domainAction = Action(name = actionName, payload = actionPayload)
store.deferredDispatch(originatorId, domainAction)
```

Should become:
```kotlin
// Inject correlationId so the handling feature can thread it to ACTION_RESULT
val enrichedPayload = if (correlationId != null && actionPayload["correlationId"] == null) {
    JsonObject(actionPayload + ("correlationId" to JsonPrimitive(correlationId)))
} else {
    actionPayload
}
val domainAction = Action(name = actionName, payload = enrichedPayload)
store.deferredDispatch(originatorId, domainAction)
```

The guard `actionPayload["correlationId"] == null` prevents overwriting a correlationId that the user explicitly included in their command payload (some actions like `SYSTEM_LIST` already support their own `correlationId` field).

### 4.8 Feature-Side: Publishing ACTION_RESULT

Each feature that handles command-dispatchable actions adds a `publishActionResult` helper and calls it after completing operations. Example for filesystem:

```kotlin
private fun publishActionResult(
    store: Store,
    correlationId: String?,
    requestAction: String,
    success: Boolean,
    summary: String? = null,
    error: String? = null
) {
    store.deferredDispatch(identity.handle, Action(
        name = ActionRegistry.Names.FILESYSTEM_ACTION_RESULT,
        payload = buildJsonObject {
            correlationId?.let { put("correlationId", it) }
            put("requestAction", requestAction)
            put("success", success)
            summary?.let { put("summary", it) }
            error?.let { put("error", it) }
        }
    ))
}
```

Called like:
```kotlin
// After a successful SYSTEM_READ:
publishActionResult(store, correlationId, action.name, success = true,
    summary = "Read ${content.length} bytes from '${payload.subpath}'")

// After a successful SYSTEM_WRITE:
publishActionResult(store, correlationId, action.name, success = true,
    summary = "Wrote ${payload.content.length} bytes to '${payload.subpath}'")

// After a failed operation:
publishActionResult(store, correlationId, action.name, success = false,
    error = "File not found: '${payload.subpath}'")
```

The `summary` is feature-controlled and human-readable. The feature decides what level of detail is appropriate. CommandBot renders it verbatim. No raw data goes into the broadcast.

If the `correlationId` is null (the action was triggered by internal feature logic, not by a command), the ACTION_RESULT is still published — it's useful for logging and debugging — but CommandBot will ignore it since there's no pending result to match.

---

## 5. ACTION_RESULT Payload Envelope (Convention)

Every `{feature}.ACTION_RESULT` should follow this structure:

```json
{
  "correlationId": "string | null",
  "requestAction": "string",
  "success": true,
  "summary": "string | null",
  "error": "string | null"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `correlationId` | No | From the domain action's payload. Null if not command-originated. |
| `requestAction` | Yes | The domain action name that produced this result. e.g., `"filesystem.SYSTEM_READ"` |
| `success` | Yes | Whether the action completed successfully. |
| `summary` | No | Human-readable description of what happened. Feature-controlled. Rendered by CommandBot in the session. |
| `error` | No | Human-readable error message. Present when `success` is `false`. |

**What the user sees in the session (examples):**

```
[COMMAND BOT] ✓ filesystem.SYSTEM_READ — Read 1,247 bytes from 'config.json'
```
```
[COMMAND BOT] ✓ filesystem.SYSTEM_WRITE — Wrote 512 bytes to 'notes.md'
```
```
[COMMAND BOT] ✓ filesystem.SYSTEM_LIST — Found 23 items in 'workspace/'
```
```
[COMMAND BOT] ✗ filesystem.SYSTEM_READ — File not found: 'missing.txt'
```
```
[COMMAND BOT] ✓ session.POST — completed.
```

If `summary` is null and `success` is true, CommandBot falls back to `"✓ {requestAction} completed."`.

---

## 7. Implementation Order


### Step 1: ActionRegistry derived view
Add `actionResultNames` to ActionRegistry. No consumers yet, so this is inert.

### Step 2: CommandBotState + reducer additions
Add `PendingResult` data class, `pendingResults` map, and the `REGISTER_PENDING_RESULT` / `CLEAR_PENDING_RESULT` reducer cases. No side effects yet — state management only.

### Step 3: CommandBot — register pending results
In `publishActionCreated`, dispatch `REGISTER_PENDING_RESULT` alongside `ACTION_CREATED`. Pending results now accumulate but aren't consumed yet.

### Step 4: CoreFeature — correlationId injection
Thread `correlationId` from `ACTION_CREATED` into domain action payloads. This is a ~5 line change. Features now receive correlationId in their action payloads but don't use it yet.

### Step 5: Feature-side ACTION_RESULT publishing
Add `{feature}.ACTION_RESULT` action to each feature's manifest. Add `publishActionResult` helper. Call it from each command-dispatchable handler. Start with FileSystemFeature as the reference implementation since it has the most visible command actions (`SYSTEM_READ`, `SYSTEM_LIST`, `SYSTEM_WRITE`, `SYSTEM_DELETE`, `SYSTEM_DELETE_DIRECTORY`). The action result broadcasts are now flowing but nobody consumes them yet.

### Step 6: CommandBot — ACTION_RESULT interception
Add the `else` branch in `handleSideEffects` that checks `actionResultNames`, matches `correlationId`, formats feedback, posts to session, and clears the pending result. The feedback loop is now live.

### Step 7: Expand to other features
Add ACTION_RESULT publishing to session, knowledgegraph, gateway, and any other features that handle command-dispatchable actions. Each feature decides its own `summary` content.

---

## 8. Plugin/Script Contract

For external plugins and runtime scripts to participate in the feedback loop, they need only:

1. **Declare `{plugin}.ACTION_RESULT`** in their action manifest as a broadcast action.
2. **Extract `correlationId`** from the domain action payload (it's injected by Core).
3. **Publish `{plugin}.ACTION_RESULT`** with the standard envelope after handling the action.

No imports from CommandBot. No knowledge of sessions. No registration step. The naming convention (`suffix == "ACTION_RESULT"`) and the `correlationId` field are the entire contract.

---

## 9. Agent Commands

For agent commands, AgentFeature handles `ACTION_CREATED` instead of CoreFeature. AgentFeature should apply the same `correlationId` injection into domain payloads that CoreFeature does. The rest of the chain is identical: the feature publishes ACTION_RESULT, CommandBot matches the correlationId, feedback appears in the session.

If AgentFeature already discards the `correlationId` or doesn't thread it, this needs to be addressed. The same ~5 line pattern from the CoreFeature delta applies.

---

## 10. Edge Cases

**Action has no handler:** If a user types a command for an action that nobody handles, no ACTION_RESULT will ever arrive. The pending result entry sits in state until TTL cleanup removes it. No feedback appears in the session. Consider whether CommandBot should post a timeout notice after TTL expiry.

**Feature publishes ACTION_RESULT without correlationId:** Normal. It means the action was triggered by internal logic (not a command). CommandBot ignores it. Other observers (logging, debugging) may still use it.

**Multiple commands for the same action in flight:** Each gets a unique `correlationId`, so matching is unambiguous.

**Feature does not publish ACTION_RESULT yet:** The feature simply hasn't been migrated. No feedback appears for commands targeting that feature. This is the same behavior as before — no regression, just not yet improved.

---

## 11. What This Does NOT Change

- **Targeted RETURN_* actions continue to work unchanged.** They carry the full data payload to the requesting feature. ACTION_RESULT does not replace them.
- **CommandBot's command parsing, validation, and guardrails are unchanged.** This only adds the feedback-posting capability.
- **The approval flow is unchanged.** Approved actions go through the same ACTION_CREATED path and will get ACTION_RESULT feedback like any other command.
- **Sandbox resolution and permission enforcement are unchanged.** CoreFeature dispatches with the real user identity. Features see the real originator. No impersonation occurs.
