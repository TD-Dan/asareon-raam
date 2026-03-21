# Session Workspace Files in Agent Context — Implementation Guide

## 1. Problem Statement

Agents need to see files in session workspaces (e.g. `{sessionUUID}/workspace/oak.txt` under the **session** sandbox) as part of their assembled context. The agent feature cannot access these files directly because each feature's filesystem I/O is sandboxed — the agent sandbox contains only agent data, and session files live in the session sandbox.

**Prior failed approach:** Dispatching `filesystem.LIST` from the agent feature with a session UUID path. The filesystem resolves paths relative to the *caller's* sandbox root (`<sandbox>/agent/`), so the path doesn't exist. Files are silently absent.

## 2. Architecture: Permission-Gated Cross-Sandbox Delegation

The session feature acts as gatekeeper. The agent asks; the session validates, reads its own files, and returns the data via targeted action.

```
┌─────────────────┐                          ┌──────────────────┐
│  Agent Feature   │                          │ Session Feature   │
│                  │                          │                   │
│  evaluateTurn    │──REQUEST_WORKSPACE_FILES─>│  handleSideEffect │
│  Context()       │  (sessionId, expanded    │                   │
│                  │   filePaths, requesterId) │  • resolve session│
│                  │                          │  • validate access│
│                  │                          │  • filesystem.LIST│
│                  │                          │    (own sandbox)  │
│                  │                          │  • filesystem.READ│
│                  │                          │    (expanded files)│
│  handleSession   │<─RETURN_WORKSPACE_FILES──│                   │
│  WorkspaceFiles  │  (listing, contents)     │  • package result │
│  Response()      │                          │                   │
│                  │                          │                   │
│  AGENT_STORE_    │                          │                   │
│  SESSION_FILES   │                          │                   │
│  (gate opens)    │                          │                   │
└─────────────────┘                          └──────────────────┘
```

### Why Not Just Broadcast?

The session feature already broadcasts `WORKSPACE_FILES_LOADED` when its pane refreshes. But broadcasts are visible to *all* features, private session files would leak, and the broadcast doesn't include file contents. The delegation model is targeted (only the requester sees the response), permission-gated, and carries contents inline.

## 3. What Needs to Change (Feature by Feature)

### 3.1 Session Feature (server side of delegation)

**New actions to handle in `handleSideEffects`:**

| Action | Direction | Purpose |
|--------|-----------|---------|
| `session.REQUEST_WORKSPACE_FILES` | agent → session | Turn-init: listing + expanded file contents |
| `session.RETURN_WORKSPACE_FILES` | session → agent (targeted) | Response with listing + contents |
| `session.READ_WORKSPACE_FILE` | agent → session | On-demand: single file read |
| `session.RETURN_WORKSPACE_FILE` | session → agent (targeted) | Response with single file content |

**New permissions in `session_actions.json`:**
- `session:read-files` (LOW) — list and read workspace files
- `session:write-files` (CAUTION) — write/edit/delete (existing concept, formalized)

**Implementation pattern:** Follow `REQUEST_LEDGER_CONTENT → RETURN_LEDGER` exactly. The session feature already does this for ledger data. The workspace delegation is the same pattern: receive request, validate, perform I/O within own sandbox, send targeted response.

**Critical: Tracking in-flight filesystem operations.** The session dispatches `filesystem.LIST` and `filesystem.READ` internally, but the responses arrive asynchronously via `FILESYSTEM_RETURN_LIST` and `FILESYSTEM_RETURN_READ`. The session needs a way to correlate these responses back to the original delegation request.

**Two approaches (choose one):**

**Option A — Stateful tracking (mutable maps):** Store `PendingWorkspaceDelegation` objects keyed by session UUID. When `FILESYSTEM_RETURN_LIST` arrives for a workspace path, check the map. When all file reads complete, send the response. *Risk: if any read fails silently (no response), the delegation hangs forever.*

**Option B — Single synchronous read via `READ_MULTIPLE`:** Instead of dispatching individual `FILESYSTEM_READ` calls, use `FILESYSTEM_READ_MULTIPLE` with a correlation ID. This returns all contents in one `FILESYSTEM_RETURN_FILES_CONTENT` response, including nulls for missing files. *Advantage: one response to handle, no dangling state.*

**Recommendation: Option B.** It's simpler and avoids the multi-read tracking problem entirely. The session already uses `FILESYSTEM_READ` for individual workspace preview; `READ_MULTIPLE` is only for the delegation response.

### 3.2 Agent Feature (client side of delegation)

**State fields (in `AgentStatusInfo`):**

```kotlin
val pendingSessionFileListingIds: Set<String> = emptySet()       // session UUIDs awaiting response
val transientSessionFileListings: Map<String, JsonArray> = emptyMap()  // UUID → listing
val transientSessionFileContents: Map<String, Map<String, String>> = emptyMap() // UUID → (path → content)
```

That's it — three fields. No `pendingSessionFileReadIds` needed because the session returns listing + contents together.

**Reducer actions (3 total):**

| Action | Purpose |
|--------|---------|
| `AGENT_SET_PENDING_SESSION_FILE_LISTINGS` | Initialize pending set, clear stale data |
| `AGENT_STORE_SESSION_FILES` | Store listing + contents for one session, remove from pending |
| `AGENT_MERGE_SESSION_FILE_CONTENT` | Merge on-demand file content (from `CONTEXT_UNCOLLAPSE`) |

**Pipeline changes (`CognitivePipeline.kt`):**

- `evaluateTurnContext()`: Dispatch `session.REQUEST_WORKSPACE_FILES` (not `filesystem.LIST`) per session
- `handleTargetedAction()`: Route `SESSION_RETURN_WORKSPACE_FILES` to new handler
- New handler: `handleSessionWorkspaceFilesResponse()` — stores via `AGENT_STORE_SESSION_FILES`
- `evaluateFullContext()` gate: `sessionFilesReady = pendingSessionFileListingIds.isEmpty()`
- `buildContextMap()`: Read from `transientSessionFileListings` / `transientSessionFileContents`

**Feature changes (`AgentRuntimeFeature.kt`):**

- `handleSideEffects`: Route `SESSION_RETURN_WORKSPACE_FILES` and `SESSION_RETURN_WORKSPACE_FILE` to pipeline/handlers
- Side-effect on `AGENT_STORE_SESSION_FILES` → `evaluateFullContext()`
- `CONTEXT_UNCOLLAPSE` for `sf:` keys: Dispatch `session.READ_WORKSPACE_FILE` (not `filesystem.READ`)
- On-demand handler: `handleOnDemandSessionFileResponse()` → `AGENT_MERGE_SESSION_FILE_CONTENT`

### 3.3 SessionFilesContextFormatter

Already written. Accepts `JsonArray` listings, produces context entries keyed as `sf:<sessionHandle>:<relativePath>`. No changes needed.

### 3.4 Correlation ID Convention

| Prefix | Context | Producer → Consumer |
|--------|---------|---------------------|
| `sf:` | Turn-init listing+contents | Pipeline → Pipeline |
| `sfod:` | On-demand single-file read | Feature → Feature |

Format: `sf:{agentUUID}:{sessionUUID}` and `sfod:{agentUUID}:{sessionUUID}`

## 4. Failure Modes & Pitfalls

### 4.1 Expanded File Path Validation (CRITICAL)

The agent's `evaluateTurnContext` builds expanded file paths from collapse overrides matching `sf:<handle>:<path>`. **Bugs observed:**

- **Empty path from trailing colon:** A collapse override key like `sf:session.pet-studies:` (with nothing after the final colon) produces an empty string `""` as an expanded path. This causes the session to dispatch `filesystem.READ` for `{uuid}/workspace/` which the filesystem rejects ("Refused filename without a file extension"). **Fix: filter out blank paths before sending.**

- **Non-existent files:** An override may reference a file that was deleted (`foobar.txt`). The read fails, but if tracking is per-file, the pending set never drains. **Fix: use `READ_MULTIPLE` (returns nulls for missing files in one response), or strip missing paths by cross-referencing against the listing.**

**Recommended approach:** The session feature should receive the expanded paths list, cross-reference against the actual listing it just received, and only read files that actually exist. Drop paths that aren't in the listing. This is both safer and more efficient.

### 4.2 Filesystem Silent Failures

The filesystem feature may reject a read without sending `RETURN_READ` (e.g. the "Refused filename" case for paths ending in `/`). If the session tracks pending reads individually, a rejected read leaves the delegation permanently stuck.

**Mitigation:** Use `READ_MULTIPLE` which always returns one response. Or if using individual reads, set a per-delegation timeout.

### 4.3 Sandbox Path Confusion

Session workspace paths in the filesystem are `{sessionUUID}/workspace/{file}`. The listing returns paths with backslashes on Windows: `e0017e90...\workspace\oak.txt`. Always normalize to forward slashes before comparison.

### 4.4 Race: Session Not Yet Loaded

During the first agent turn after startup, the session feature may not have finished loading all sessions from disk. `resolveSessionId()` will return null. The session should return an error response (not silently drop the request), and the agent should handle it by storing empty data so the gate doesn't block.

### 4.5 Gate Timeout Behavior

The 10-second context gathering timeout already handles the case where delegation responses never arrive. On timeout, the pipeline proceeds without session files. This is correct behavior — session files are supplementary context, not required.

## 5. Implementation Order

**Phase 1 — Agent side (can be tested with mock responses):**
1. Add state fields to `AgentStatusInfo`
2. Add 3 reducer actions
3. Modify `evaluateTurnContext` to dispatch `SESSION_REQUEST_WORKSPACE_FILES`
4. Add `handleSessionWorkspaceFilesResponse` to pipeline
5. Update gate condition
6. Update `buildContextMap` to read from transient state
7. Update `handleSideEffects` routing in feature
8. Add on-demand flow for `CONTEXT_UNCOLLAPSE`

**Phase 2 — Session side:**
1. Add 2 permissions to `session_actions.json`
2. Add 4 actions to `session_actions.json`
3. Add `REQUEST_WORKSPACE_FILES` handler to `handleSideEffects`
4. Add `READ_WORKSPACE_FILE` handler to `handleSideEffects`
5. Route `FILESYSTEM_RETURN_LIST` and `FILESYSTEM_RETURN_FILES_CONTENT` for delegation responses
6. Send targeted `RETURN_WORKSPACE_FILES` / `RETURN_WORKSPACE_FILE`

**Phase 3 — Validation:**
1. Verify `[SF-TRACE]` logs show: dispatch → session received → filesystem I/O in session sandbox → targeted response → agent stores → gate opens
2. Verify Context Manager UI shows session files under `sf:` keys
3. Verify on-demand expand/collapse works
4. Verify timeout fallback works (kill session feature to simulate no response)

## 6. Key Design Decision: Who Resolves Expanded Paths?

There are two options for determining which files to pre-read:

**Option A (Previous attempt):** Agent sends expanded paths from its collapse overrides. Session reads them blindly.
- *Problem:* Agent may send stale paths (deleted files), empty paths (malformed keys), or paths the listing doesn't contain. Session must handle all failure cases.

**Option B (Recommended):** Agent sends the full list of collapse overrides (or just the session handle prefix). Session cross-references against its own fresh listing and only reads files that exist and are marked expanded.
- *Advantage:* Session has ground truth. No stale path problem. No need to handle missing files.
- *Cost:* Session needs to understand the `sf:<handle>:<path>` key format to check overrides.

**Simplest viable approach:** Agent sends expanded paths, but session filters them against the listing before reading. Any path not in the listing is silently dropped. This keeps the agent's override logic unchanged and makes the session robust to stale data.

## 7. Payload Formats

### REQUEST_WORKSPACE_FILES
```json
{
  "sessionId": "e0017e90-...",
  "correlationId": "sf:{agentUUID}:{sessionUUID}",
  "requesterId": "agent.squirrel",
  "expandedFilePaths": ["readme.md", "src/main.kt"]
}
```

### RETURN_WORKSPACE_FILES (success)
```json
{
  "correlationId": "sf:{agentUUID}:{sessionUUID}",
  "sessionId": "e0017e90-...",
  "listing": [{"path": "e0017e90...\\workspace\\readme.md", "isDirectory": false, "lastModified": 1771067092980}],
  "contents": {"readme.md": "# Session workspace\nThis is the readme."}
}
```

### RETURN_WORKSPACE_FILES (error)
```json
{
  "correlationId": "sf:{agentUUID}:{sessionUUID}",
  "sessionId": "e0017e90-...",
  "error": "Session not found."
}
```

### READ_WORKSPACE_FILE
```json
{
  "sessionId": "e0017e90-...",
  "path": "src/main.kt",
  "requesterId": "agent.squirrel",
  "correlationId": "sfod:{agentUUID}:{sessionUUID}"
}
```

### RETURN_WORKSPACE_FILE (success/error)
```json
{
  "correlationId": "sfod:{agentUUID}:{sessionUUID}",
  "sessionId": "e0017e90-...",
  "path": "src/main.kt",
  "content": "fun main() { ... }",
  "error": null
}
```

## 8. What Went Wrong in the Previous Attempt (Post-Mortem)

The log from the failed run reveals three concrete bugs:

1. **Empty expanded path:** `evaluateTurnContext` sent `expandedPaths=["foobar.txt", ""]`. The empty string came from a collapse override key `sf:session.pet-studies:` with nothing after the final colon. The session dutifully tried to read `{uuid}/workspace/` which the filesystem rejected without sending a response.

2. **Non-existent file read:** `foobar.txt` didn't exist in the workspace (only `oak.txt` did). The filesystem returned `content: null`. The session's delegation tracking received the null content but counted it as a successful read, while the *other* read (empty path) never completed — leaving the delegation permanently stuck.

3. **No response for rejected reads:** The filesystem's "Refused filename without a file extension" error for the trailing-slash path produced no `RETURN_READ` action at all. The `pendingFileReads` set in the delegation tracker never drained, so `sendWorkspaceDelegationResponse` never fired, the agent's `pendingSessionFileListingIds` was never cleared, and the gate timed out after 10 seconds.

**Root cause:** Using individual `FILESYSTEM_READ` calls with mutable tracking state, combined with no validation of expanded paths before dispatch. The fix is to validate paths (filter empties + cross-reference against listing) and use `READ_MULTIPLE` for atomic response handling.
