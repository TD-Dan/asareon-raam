# AUF Permissions — ASK Approval System

**Version:** 1.0-draft  
**Date:** 2026-03-01  
**Status:** Design draft. Pending implementation learnings from the core permission system.  
**Prerequisite:** `permissions-system-spec-v3.md` Phases 1–2 fully delivered and validated.

---

## 1. Purpose

This document specifies the interactive approval protocol for the `ASK` permission level and its companion `APP_LIFETIME` transient grants. It was deliberately separated from the core permission spec to allow the YES/NO enforcement system to ship first and generate real-world operational data that informs this design.

**What the core spec already delivers:**
- `PermissionLevel.ASK` and `PermissionLevel.APP_LIFETIME` exist in the enum
- The Store guard recognizes both levels and denies them with a WARN log: `"PERMISSION ASK not yet implemented (treating as NO)"`
- The `PermissionGrant` data class supports both levels
- `default-permissions.json` can declare `ASK` grants (currently equivalent to `NO`)

**What this task adds:**
- Suspended action queue in the Store
- An extensible approval presenter protocol
- APP_LIFETIME transient override storage
- Integration with CommandBot's approval card UI
- Extension points for other features (KnowledgeGraph, remote connections)

---

## 2. Design Principles

**D12 Decision: Delegate to Core with extensible presenter protocol.** The Store suspends the action and emits an event. Core orchestrates presenter selection. Any feature can register as a presenter for specific permission/originator combinations. This keeps the Store protocol-aware but presenter-agnostic.

**Separation of concerns:**
- **Store** — suspends actions, manages the pending queue, enforces TTL, resumes or drops actions based on responses
- **CoreFeature** — routes ASK events to the right presenter, arbitrates conflicts, handles timeouts
- **Presenter features** (CommandBot, KnowledgeGraph, future) — render the approval UI and dispatch responses

---

## 3. Suspended Action Protocol

### 3.1 When ASK is Encountered

When the Store guard (Step 2b) encounters `PermissionLevel.ASK` for a required permission:

```kotlin
PermissionLevel.ASK -> {
    val approvalId = platformDependencies.generateUUID()
    val suspendedAction = SuspendedAction(
        approvalId = approvalId,
        action = action,
        originator = originatorIdentity,
        triggeringPermissions = requiredPerms.filter { key ->
            val level = effective[key]?.level ?: PermissionLevel.NO
            level == PermissionLevel.ASK
        },
        suspendedAt = platformDependencies.currentTimeMillis()
    )
    
    pendingApprovals[approvalId] = suspendedAction
    
    // Schedule TTL cleanup
    scheduleDelayed(APPROVAL_TTL_MS, "core", Action(
        ActionRegistry.Names.CORE_APPROVAL_EXPIRED,
        buildJsonObject { put("approvalId", approvalId) }
    ))
    
    // Emit event for presenters
    deferredDispatch("core", Action(
        ActionRegistry.Names.CORE_PERMISSION_ASK_REQUIRED,
        buildJsonObject {
            put("approvalId", approvalId)
            put("originatorHandle", originatorIdentity.handle)
            put("originatorName", originatorIdentity.name)
            put("actionName", action.name)
            put("actionSummary", descriptor.summary)
            put("triggeringPermissions", Json.encodeToJsonElement(suspendedAction.triggeringPermissions))
            put("dangerLevels", Json.encodeToJsonElement(
                suspendedAction.triggeringPermissions.associate { key ->
                    key to (ActionRegistry.permissionDeclarations[key]?.dangerLevel?.name ?: "CAUTION")
                }
            ))
        }
    ))
    
    platformDependencies.log(
        LogLevel.INFO, "Store",
        "PERMISSION ASK: Action '${action.name}' from '${action.originator}' " +
        "suspended (approvalId=$approvalId). Waiting for presenter."
    )
    return  // Action is suspended, not processed
}
```

### 3.2 Data Model

```kotlin
/**
 * An action suspended by the permission guard, awaiting human approval.
 */
data class SuspendedAction(
    val approvalId: String,
    val action: Action,
    val originator: Identity,
    val triggeringPermissions: List<String>,
    val suspendedAt: Long,
    val ttlHandle: Any? = null  // Handle for cancelling the TTL timer
)

// In Store:
private val pendingApprovals: MutableMap<String, SuspendedAction> = mutableMapOf()

companion object {
    /** Time in ms before a suspended action auto-denies. */
    const val APPROVAL_TTL_MS = 120_000L  // 2 minutes
}
```

### 3.3 Approval Response

When a presenter dispatches `core.PERMISSION_ASK_RESPONSE`:

```kotlin
ActionRegistry.Names.CORE_PERMISSION_ASK_RESPONSE -> {
    val responsePayload = json.decodeFromJsonElement<AskResponsePayload>(action.payload ?: return)
    val suspended = pendingApprovals.remove(responsePayload.approvalId) ?: return
    
    // Cancel the TTL timer
    suspended.ttlHandle?.let { platformDependencies.cancelScheduled(it) }
    
    when (responsePayload.decision) {
        AskDecision.DENY -> {
            platformDependencies.log(LogLevel.INFO, "Store",
                "ASK DENIED: '${suspended.action.name}' from '${suspended.originator.handle}'.")
            // Dispatch PERMISSION_DENIED notification
        }
        AskDecision.ALLOW_ONCE -> {
            platformDependencies.log(LogLevel.INFO, "Store",
                "ASK APPROVED (once): '${suspended.action.name}' from '${suspended.originator.handle}'.")
            // Resume the action — re-enter processAction, skipping Step 2b
            processActionResumed(suspended.action)
        }
        AskDecision.ALLOW_APP_LIFETIME -> {
            // Set transient override for all triggering permissions
            suspended.triggeringPermissions.forEach { key ->
                setTransientOverride(suspended.originator.handle, key, PermissionLevel.APP_LIFETIME)
            }
            platformDependencies.log(LogLevel.INFO, "Store",
                "ASK APPROVED (app lifetime): '${suspended.action.name}' from '${suspended.originator.handle}'.")
            processActionResumed(suspended.action)
        }
        AskDecision.ALWAYS_ALLOW -> {
            // Persist the upgrade via core.SET_PERMISSION
            suspended.triggeringPermissions.forEach { key ->
                deferredDispatch("core", Action(ActionRegistry.Names.CORE_SET_PERMISSION, buildJsonObject {
                    put("identityHandle", suspended.originator.handle)
                    put("permissionKey", key)
                    put("level", "YES")
                }))
            }
            platformDependencies.log(LogLevel.INFO, "Store",
                "ASK APPROVED (always): '${suspended.action.name}' from '${suspended.originator.handle}'.")
            processActionResumed(suspended.action)
        }
    }
}
```

### 3.4 TTL Expiry

```kotlin
ActionRegistry.Names.CORE_APPROVAL_EXPIRED -> {
    val approvalId = action.payload?.get("approvalId")?.jsonPrimitive?.content ?: return
    val suspended = pendingApprovals.remove(approvalId) ?: return  // Already resolved
    
    platformDependencies.log(LogLevel.WARN, "Store",
        "ASK EXPIRED: Action '${suspended.action.name}' from '${suspended.originator.handle}' " +
        "timed out after ${APPROVAL_TTL_MS}ms. Auto-denied.")
    
    // Dispatch PERMISSION_DENIED notification
}
```

---

## 4. APP_LIFETIME Transient Overrides

### 4.1 Storage

```kotlin
// In Store:
private val transientPermissionOverrides: MutableMap<String, MutableMap<String, PermissionGrant>> =
    mutableMapOf()

private fun setTransientOverride(identityHandle: String, permKey: String, level: PermissionLevel) {
    transientPermissionOverrides
        .getOrPut(identityHandle) { mutableMapOf() }[permKey] = PermissionGrant(level)
}
```

### 4.2 Integration with `resolveEffectivePermissions`

After inheritance resolution, apply transient overrides:

```kotlin
private fun resolveEffectivePermissions(identity: Identity): Map<String, PermissionGrant> {
    // ... existing inheritance chain resolution ...
    
    // Apply transient APP_LIFETIME overrides (ASK system)
    val overrides = transientPermissionOverrides[identity.handle]
    if (overrides != null) {
        for ((key, grant) in overrides) {
            effective[key] = grant
        }
    }
    
    return effective
}
```

### 4.3 Lifecycle

- Transient overrides are **not persisted**. They exist only in memory.
- On app restart, all transient overrides are lost. Identities revert to their persisted grants.
- `ASK` grants that were temporarily overridden to `APP_LIFETIME` revert to `ASK` and will prompt again.

---

## 5. Extensible Presenter Protocol

### 5.1 Event: `core.PERMISSION_ASK_REQUIRED`

Broadcast event emitted by the Store when an action is suspended. All features receive it.

```json
{
  "action_name": "core.PERMISSION_ASK_REQUIRED",
  "public": false,
  "broadcast": true,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "approvalId": { "type": "string" },
      "originatorHandle": { "type": "string" },
      "originatorName": { "type": "string" },
      "actionName": { "type": "string" },
      "actionSummary": { "type": "string" },
      "triggeringPermissions": { "type": "array", "items": { "type": "string" } },
      "dangerLevels": { "type": "object", "description": "Map of permission key → DangerLevel name" }
    },
    "required": ["approvalId", "originatorHandle", "originatorName", "actionName", "triggeringPermissions"]
  }
}
```

### 5.2 Response: `core.PERMISSION_ASK_RESPONSE`

Non-public targeted action dispatched by the claiming presenter.

```json
{
  "action_name": "core.PERMISSION_ASK_RESPONSE",
  "public": false,
  "broadcast": false,
  "targeted": false,
  "payload_schema": {
    "type": "object",
    "properties": {
      "approvalId": { "type": "string" },
      "decision": {
        "type": "string",
        "enum": ["DENY", "ALLOW_ONCE", "ALLOW_APP_LIFETIME", "ALWAYS_ALLOW"]
      }
    },
    "required": ["approvalId", "decision"]
  }
}
```

### 5.3 Presenter Self-Selection

Features self-select by listening for `core.PERMISSION_ASK_REQUIRED` and deciding whether to claim it based on their own domain logic:

**CommandBotFeature claims when:**
- The originator is an agent identity (parentHandle == "agent")
- The agent has an active session where the approval card can be posted

**KnowledgeGraphFeature claims when:**
- The triggering permission is `knowledgegraph:write` and the originator is a remote connection or external tool

**Future features claim when:**
- Their domain-specific criteria match

A feature claims by rendering its approval UI and (when the user responds) dispatching `core.PERMISSION_ASK_RESPONSE`.

### 5.4 Arbitration Rules

**First responder wins.** The Store accepts the first `PERMISSION_ASK_RESPONSE` for a given `approvalId` and ignores subsequent responses. The `pendingApprovals.remove()` call is atomic — only one feature's response is processed.

**Unclaimed ASK.** If no feature claims the ASK within the TTL, it auto-denies. This prevents hung actions.

**CoreFeature as fallback presenter.** If Core detects that an ASK has been pending for a configurable threshold (e.g., 10 seconds) without a presenter claiming it, Core itself can render a generic system-level approval dialog. This is a safety net for cases where no domain-specific presenter exists.

### 5.5 Presenter UI Patterns

**CommandBot approval card** (reuses existing `PartialView` infrastructure):
```
┌─────────────────────────────────────────────┐
│ ⚠ Permission Request                        │
│                                              │
│ agent.coder-1 wants to:                      │
│ filesystem.WRITE — Create and modify files   │
│                                              │
│ Required permission: filesystem:workspace    │
│ Current level: ASK                           │
│                                              │
│ [Deny] [Once] [This Session] [Always Allow]  │
└─────────────────────────────────────────────┘
```

**KnowledgeGraph modal** (for remote modification requests):
```
┌─────────────────────────────────────────────┐
│ 🔒 External Modification Request             │
│                                              │
│ remote.connection-1 wants to modify holons   │
│ Permission: knowledgegraph:write (CAUTION)   │
│                                              │
│ [Deny]  [Allow Once]  [Allow for Session]    │
└─────────────────────────────────────────────┘
```

**Generic Core fallback dialog** (system-level AlertDialog):
```
┌─────────────────────────────────────────────┐
│ Permission Required                          │
│                                              │
│ "agent.coder-1" is requesting permission     │
│ "filesystem:workspace" to perform            │
│ "filesystem.WRITE".                          │
│                                              │
│ [Deny]              [Allow Once]             │
└─────────────────────────────────────────────┘
```

### 5.6 Danger Level Integration

The `dangerLevels` map in the ASK event payload drives UI presentation:
- **LOW** — Standard card/dialog, neutral colors
- **CAUTION** — Orange/amber styling, brief explanation of scope
- **DANGER** — Red styling, prominent warning text, "Allow Once" is the strongest option available (no "Always Allow" for DANGER permissions to prevent accidental permanent escalation)

---

## 6. New Actions Summary

| Action | Type | Description |
|---|---|---|
| `core.PERMISSION_ASK_REQUIRED` | Event (broadcast) | Emitted when the Store suspends an action pending approval |
| `core.PERMISSION_ASK_RESPONSE` | Internal (non-public) | Dispatched by the claiming presenter with the user's decision |
| `core.APPROVAL_EXPIRED` | Internal (non-public) | Scheduled timer fires when TTL elapses |

These actions are added to `core_actions.json` alongside the existing permission actions.

---

## 7. Interaction with `core.SET_PERMISSION`

When the user chooses "Always Allow," the Store dispatches `core.SET_PERMISSION` to persist the upgrade. Since `core.SET_PERMISSION` is non-public (per the core spec), only CoreFeature can dispatch it — and the Store dispatches with originator `"core"`, which is the CoreFeature handle. This is correct and closes the self-escalation vector.

When the `SET_PERMISSION` level enum is updated to accept ASK:
```json
"level": { "type": "string", "enum": ["NO", "ASK", "APP_LIFETIME", "YES"] }
```

The Permission Manager UI can now set `ASK` on grants, and the full four-option cycle becomes available: `NO → YES → ASK → NO`.

---

## 8. Testing Strategy

### 8.1 Unit Tests — Store Suspension

1. Action with required permission at ASK level → action suspended, not processed.
2. `PERMISSION_ASK_REQUIRED` event emitted with correct payload.
3. `PERMISSION_ASK_RESPONSE` with ALLOW_ONCE → action resumed and processed.
4. `PERMISSION_ASK_RESPONSE` with DENY → action dropped, PERMISSION_DENIED dispatched.
5. `PERMISSION_ASK_RESPONSE` with ALLOW_APP_LIFETIME → transient override set, action resumed.
6. `PERMISSION_ASK_RESPONSE` with ALWAYS_ALLOW → `SET_PERMISSION` dispatched, action resumed.
7. TTL expiry → action auto-denied.
8. Duplicate response (second feature responds after first) → ignored.
9. Unknown `approvalId` in response → ignored.

### 8.2 Unit Tests — APP_LIFETIME Overrides

1. After ALLOW_APP_LIFETIME, subsequent actions with the same permission pass without ASK.
2. Overrides apply only to the specific identity, not siblings.
3. Overrides are lost on Store reconstruction (simulating app restart).

### 8.3 Integration Tests

1. Full round-trip: agent dispatches action → ASK triggered → CommandBot renders card → user clicks "Allow Once" → action completes → result appears in session.
2. Timeout scenario: ASK triggered → no presenter claims → TTL fires → auto-denied → PERMISSION_DENIED posted to session.
3. Multiple simultaneous ASKs: two actions suspended → independent approval flows → both resolve correctly.

---

## 9. Open Questions

### 9.1 Multiple ASK Permissions on One Action

If an action requires `["filesystem:workspace", "gateway:generate"]` and the identity has ASK for both, should one approval card cover both permissions or two separate cards?

**Recommended:** One card listing all ASK-triggering permissions. The user's decision applies to all listed permissions uniformly. This avoids approval fatigue.

### 9.2 ASK + NO on Same Action

If an action requires two permissions and one is `NO` (hard deny) while another is `ASK`, the guard should short-circuit to deny without suspending. There's no point asking the user about one permission when another is hard-blocked.

**Implementation:** The guard iterates all required permissions first, collecting any `NO` results. If any `NO` found, deny immediately. Only if all non-YES permissions are `ASK` (with none being `NO`), proceed to suspend.

### 9.3 Presenter Priority

Should there be a priority system for presenter selection (e.g., CommandBot always wins over Core fallback)?

**Recommended:** No explicit priority. First responder wins. The 10-second Core fallback threshold provides a natural grace period for domain-specific presenters to claim. If the presenter ecosystem grows, revisit with a priority field on a per-feature basis.

### 9.4 DANGER Permission Restrictions

Should DANGER-level permissions restrict approval options? For example, preventing "Always Allow" for `filesystem:system-files-modify`.

**Recommended:** Yes. For DANGER permissions, the strongest available option is "Allow for this session" (APP_LIFETIME). "Always Allow" is hidden. The user can still permanently grant DANGER permissions through the Permission Manager UI (which is a deliberate, reflective action), but the in-context approval card encourages caution.

---

## 10. Implementation Sequence

This task should not begin until Phases 1–2 of the core permission spec are complete and the following conditions are met:

1. Store guard is live and processing YES/NO correctly in production
2. `resolveEffectivePermissions()` is battle-tested with real identity trees
3. `default-permissions.json` is loading correctly via registration hooks
4. Permission Manager UI is functional (grants can be viewed and edited)
5. At least one release cycle has passed with the WARN logs for ASK-as-NO, providing data on which permissions are commonly set to ASK

**Implementation order:**
1. Add `SuspendedAction` data class and `pendingApprovals` map to Store
2. Update Store guard Step 2b to suspend on ASK instead of denying
3. Add TTL cleanup via existing `scheduleDelayed` infrastructure
4. Add `core.PERMISSION_ASK_REQUIRED` and `core.PERMISSION_ASK_RESPONSE` actions
5. Implement `APP_LIFETIME` transient override storage and integration with `resolveEffectivePermissions`
6. Implement CommandBot presenter (reuse existing approval card PartialView)
7. Implement Core fallback presenter (generic AlertDialog)
8. Update Permission Manager UI to support ASK level in the cycle
9. Update `core.SET_PERMISSION` to accept ASK and APP_LIFETIME levels
10. (Future) Implement KnowledgeGraph presenter for remote connections
