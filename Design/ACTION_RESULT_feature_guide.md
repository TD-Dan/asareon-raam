# Feature Author's Guide: Implementing `ACTION_RESULT` and `DELIVER_TO_SESSION`

**Audience:** Anyone adding command feedback to a new or existing feature.
**Prerequisite:** The core infrastructure (CommandBot pending results, CoreFeature/AgentFeature correlationId injection, CommandBot ACTION_RESULT interception) is already in place. This guide covers the feature-side work only.

---

## What You're Building

When a user or agent types `auf_{actionName}` in a session, the command pipeline is:

```
Session → CommandBot → ACTION_CREATED → Core/Agent → domain action → YOUR FEATURE
```

Your feature does the work. But without ACTION_RESULT, the result disappears into the void — the user sees nothing in the session. You're adding two things:

1. **ACTION_RESULT** — A broadcast notification that tells CommandBot (and any observers) what happened. CommandBot posts a one-line summary to the session.

2. **DELIVER_TO_SESSION support** — For query actions that return data (reads, listings), you return data via targeted `RETURN_*` as before. CoreFeature/AgentFeature handles forwarding the data to the session. Your only job is to thread the `correlationId` through so they can match it.

---

## Decision: Does Your Action Need This?

Not every action needs ACTION_RESULT. Use this checklist:

| Action type | ACTION_RESULT? | correlationId threading? | Example |
|-------------|---------------|--------------------------|---------|
| Command-dispatchable mutation | **Yes** | Yes | `SYSTEM_WRITE`, `SYSTEM_DELETE` |
| Command-dispatchable query | **Yes** | Yes | `SYSTEM_READ`, `LIST` |
| Internal/UI-only action | No | No | `TOGGLE_ITEM_EXPANDED`, `NAVIGATE` |
| Targeted response (`RETURN_*`) | No | Thread it through | `RETURN_READ`, `RETURN_LIST` |

The rule: if a user or agent can invoke it via `auf_{actionName}`, it should publish ACTION_RESULT. If it's only dispatched by UI code or internal feature logic, skip it.

---

## Implementation Steps

### Step 1: Add `correlationId` to Your Payload Classes

Every command-dispatchable action's payload data class needs an optional `correlationId` field. CoreFeature injects this into the payload before dispatching the domain action, so your class just needs to accept it.

```kotlin
// BEFORE
@Serializable
private data class MyActionPayload(val targetId: String, val value: Int)

// AFTER
@Serializable
private data class MyActionPayload(
    val targetId: String,
    val value: Int,
    val correlationId: String? = null  // ← Add this
)
```

The default is `null` because the same action can be dispatched by internal code (no command context) or by the command pipeline (correlationId present). Your code works either way.

### Step 2: Declare `{feature}.ACTION_RESULT` in Your Manifest

Add a single action entry to your `{feature}_actions.json`:

```json
{
  "action_name": "{feature}.ACTION_RESULT",
  "summary": "Broadcast notification published after a command-dispatchable domain action completes. Carries a lightweight, privacy-safe summary. CommandBot matches via correlationId to post session feedback.",
  "public": false,
  "broadcast": true,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "correlationId": {
        "type": ["string", "null"],
        "description": "From the domain action's payload. Null if not command-originated."
      },
      "requestAction": {
        "type": "string",
        "description": "The domain action name that produced this result."
      },
      "success": {
        "type": "boolean"
      },
      "summary": {
        "type": ["string", "null"],
        "description": "Human-readable, privacy-safe description. No internal paths."
      },
      "error": {
        "type": ["string", "null"],
        "description": "Human-readable error. Present when success is false."
      }
    },
    "required": ["requestAction", "success"]
  }
}
```

This is a copy-paste template. Replace `{feature}` with your feature name. The schema is intentionally identical across all features — CommandBot relies on this convention.

### Step 3: Add the `publishActionResult` Helper

Add this private method to your feature class:

```kotlin
/**
 * Publishes a lightweight, privacy-safe broadcast notification after completing
 * a command-dispatchable action.
 *
 * SECURITY: Summaries MUST NOT include sandbox-internal paths, file names,
 * or any data that could leak information about one feature's sandbox to another.
 * Every feature on the bus sees this broadcast.
 */
private fun publishActionResult(
    store: Store,
    correlationId: String?,
    requestAction: String,
    success: Boolean,
    summary: String? = null,
    error: String? = null
) {
    store.deferredDispatch(identity.handle, Action(
        name = ActionRegistry.Names.{FEATURE}_ACTION_RESULT,
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

Replace `{FEATURE}` with your ActionRegistry constant prefix (e.g., `KNOWLEDGEGRAPH`, `SESSION`, `GATEWAY`).

### Step 4: Call It From Your Handlers

In `handleSideEffects`, after each command-dispatchable action completes, call `publishActionResult`. Cover both success and failure paths.

```kotlin
ActionRegistry.Names.MY_FEATURE_DO_SOMETHING -> {
    val payload = action.payload?.let { json.decodeFromJsonElement<DoSomethingPayload>(it) } ?: return
    try {
        // ... do the work ...

        // Publish success result
        publishActionResult(store, payload.correlationId, action.name, success = true,
            summary = "Processed 42 items")

    } catch (e: Exception) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "DoSomething failed", e)

        // Publish failure result
        publishActionResult(store, payload.correlationId, action.name, success = false,
            error = "Processing failed: ${e.message}")
    }
}
```

### Step 5: Thread `correlationId` Into `RETURN_*` Responses

If your action returns data via a targeted `RETURN_*` action, include the `correlationId` in that response payload. CoreFeature/AgentFeature uses it to match the response to the originating session and forward the data via `DELIVER_TO_SESSION`.

```kotlin
// BEFORE
val responsePayload = buildJsonObject {
    put("path", payload.path)
    put("content", result)
}

// AFTER
val responsePayload = buildJsonObject {
    put("path", payload.path)
    put("content", result)
    payload.correlationId?.let { put("correlationId", it) }  // ← Add this
}

store.deferredDispatch(identity.handle, Action(
    name = ActionRegistry.Names.MY_FEATURE_RETURN_SOMETHING,
    payload = responsePayload,
    targetRecipient = originator
))
```

This is the only change needed for DELIVER_TO_SESSION support on the feature side. CoreFeature/AgentFeature handles the rest — they look for `correlationId` in the response, match it to the session, format it, and send it to CommandBot.

---

## Writing Good Summaries

The `summary` field appears verbatim in the session transcript. It's also broadcast to every feature on the bus. These two facts create competing pressures: users want detail, but the broadcast channel demands privacy.

### Do

- State what happened in abstract, quantitative terms
- Include counts, sizes, durations — things that confirm the action worked
- Keep it to one line

```
"Processed 42 items"
"Read 1 file (1,247 bytes)"
"Wrote 1 file (512 bytes)"
"Deleted directory"
"Listed 23 items"
"Generated content (3,400 tokens)"
"Analysis complete (12 holons, 4 relations)"
```

### Don't

- Include file names, paths, or paths (sandbox-internal data)
- Include file content or data excerpts
- Include user names, agent names, or identity handles
- Include anything that would let a plugin infer another feature's sandbox structure

```
// BAD — leaks sandbox paths to every feature
"Read 'workspace/agents/gemini/config.json' (1,247 bytes)"

// BAD — leaks user information
"Deleted file for user 'alice' in sandbox 'core/alice'"

// GOOD — privacy-safe
"Read 1 file (1,247 bytes)"
"Deleted 1 file"
```

If the user needs to see the file name or path (they usually do), that information reaches the session through the DELIVER_TO_SESSION channel — targeted, not broadcast.

### Error Messages

Error summaries follow the same rules. Include enough for the user to understand what went wrong without leaking internals.

```
// GOOD
"Read failed: file not found"
"Write failed: permission denied"
"Delete directory failed: directory not empty"

// BAD — leaks absolute paths
"Read failed: /home/app/zones/core.alice/workspace/secret.txt not found"
```

---

## What Happens After You Publish

Here's what happens with your ACTION_RESULT, so you understand the end-to-end:

1. **Your feature** publishes `{feature}.ACTION_RESULT` (broadcast) with correlationId.

2. **CommandBot** receives it in the `else` branch of `handleSideEffects`:
   - Checks `action.name.endsWith(".ACTION_RESULT")`
   - Extracts `correlationId`, looks up in `pendingResults`
   - **Validates source feature** — the feature prefix of the ACTION_RESULT action must match the feature prefix of the original domain action. A `gateway.ACTION_RESULT` cannot match a pending `filesystem.SYSTEM_READ`. This prevents spoofing.
   - **Validates requestAction** — the `requestAction` field must match the stored `actionName`. This catches bugs where a feature accidentally publishes a result for the wrong action.
   - Formats: `[COMMAND BOT] ✓ {actionName} — {summary}` or `[COMMAND BOT] ✗ {actionName} — {error}`
   - Posts to the session
   - Dispatches `CLEAR_PENDING_RESULT`

3. **Other observers** (logging plugins, usage monitors, analytics) also see the broadcast and can process it however they want. Your summary is the public API for this.

4. **CoreFeature/AgentFeature** (separately) receives your targeted `RETURN_*` response:
   - Matches `correlationId` against its `pendingCommands` map
   - Formats the data for display (e.g., wraps file content in a code fence)
   - Dispatches `commandbot.DELIVER_TO_SESSION` (targeted to commandbot) with the formatted content and sessionId
   - CommandBot posts it to the session

The user sees two things in the session: the data (from DELIVER_TO_SESSION) and the status line (from ACTION_RESULT). For mutation actions that don't return data, they see only the status line.

---

## Adding DELIVER_TO_SESSION Routing for New RETURN_* Actions

If you're adding a new `RETURN_*` action that should have its data appear in the session, you need to add a handler in **CoreFeature** (and/or AgentFeature) — not in your feature.

In `CoreFeature.handleSideEffects`, add a case for your new targeted action:

```kotlin
ActionRegistry.Names.MY_FEATURE_RETURN_SOMETHING -> {
    val data = action.payload ?: return
    val correlationId = data["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
    if (latestCoreState == null) return
    val pendingCommand = latestCoreState.pendingCommands[correlationId] ?: return

    // Format the data for session display.
    // You control the formatting here — this is the place to decide
    // what the user sees in the transcript.
    val result = data["result"]?.jsonPrimitive?.contentOrNull ?: "(no data)"
    val formatted = "```text\n$result\n```"

    store.deferredDispatch(identity.handle, Action(
        ActionRegistry.Names.COMMANDBOT_DELIVER_TO_SESSION,
        buildJsonObject {
            put("correlationId", correlationId)
            put("sessionId", pendingCommand.sessionId)
            put("message", formatted)
        }
    ))
    store.deferredDispatch(identity.handle, Action(
        ActionRegistry.Names.CORE_CLEAR_PENDING_COMMAND,
        buildJsonObject { put("correlationId", correlationId) }
    ))
}
```

Note: CoreFeature currently handles `FILESYSTEM_RETURN_READ` and `FILESYSTEM_RETURN_LIST`. The pattern is identical for any new feature's return actions.

---

## Checklist

Use this when adding ACTION_RESULT to a feature:

- [ ] Identified which actions are command-dispatchable (can be invoked via `auf_`)
- [ ] Added `correlationId: String? = null` to each command-dispatchable payload data class
- [ ] Added `{feature}.ACTION_RESULT` to `{feature}_actions.json` (copy the template above)
- [ ] Added `publishActionResult` helper method to the feature class
- [ ] Called `publishActionResult` from every command-dispatchable handler (both success and error paths)
- [ ] Summaries are privacy-safe (no sandbox paths, no file names, no user data)
- [ ] Threaded `correlationId` into all `RETURN_*` response payloads
- [ ] (If new RETURN_* actions) Added routing handler in CoreFeature and/or AgentFeature
- [ ] Ran the ActionRegistry generator to pick up the new manifest entry

---

## Reference: FileSystemFeature

The filesystem implementation is the reference. It covers all the patterns:

| Action | Type | ACTION_RESULT summary | DELIVER_TO_SESSION |
|--------|------|----------------------|-------------------|
| `SYSTEM_READ` | Query | `"Read 1 file (N bytes)"` | File content in code fence |
| `LIST` | Query | `"Listed N items"` | Directory listing in code fence |
| `SYSTEM_WRITE` | Mutation | `"Wrote 1 file (N bytes)"` | Not needed |
| `SYSTEM_DELETE` | Mutation | `"Deleted 1 file"` | Not needed |
| `SYSTEM_DELETE_DIRECTORY` | Mutation | `"Deleted directory"` | Not needed |

Mutations: ACTION_RESULT only (status feedback).
Queries: ACTION_RESULT (status) + RETURN_* with correlationId (data delivery via Core).
