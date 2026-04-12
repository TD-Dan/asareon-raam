# ASAREON RAAM — Lua Scripting (DRAFT)

**Version 1.0.0-alpha** · Companion to 01-System-architecture, 03-Identities-and-permissions

---

## Overview

The Lua scripting feature embeds a sandboxed Lua 5.2 runtime into Asareon Raam, enabling user-authored automation scripts that interact with the full action bus. Scripts can dispatch actions, subscribe to broadcast events, query the identity registry, and serve as cognitive strategies for AI agents.

Scripts can run in two modes:
 - **App Script** that has its own identity
 - **Strategy Script** that runs inside an agent and uses the agents identity
 
Each script runs in its own sandboxed environment with a registered identity in the `lua.*` namespace (e.g., `lua.my-script`) or . All actions dispatched by a script go through the Store's full validation and permission pipeline — scripts are first-class participants on the bus, not privileged backdoors.

The scripting runtime is currently available on Desktop (JVM) via LuaJ. Other platforms (Android, iOS, WASM) show the Script Manager UI but cannot execute scripts.


## Getting Started

### Creating a Script

1. Open the **Lua Scripts** view via the code icon in the ribbon
2. Click **New Script** and enter a name
3. The script file is created in the lua workspace with a default template containing all API entry points
4. Scripts are auto-loaded and begin executing immediately

### File Location

Scripts are stored as `.lua` files in the lua feature's sandboxed workspace directory. The Script Manager UI provides an **Open Folder** button to access this directory in the OS file explorer.

### Autodiscovery

On app startup, the Lua feature:
1. Reads `scripts.json` (persisted script configuration with active/inactive state)
2. Scans the workspace for `.lua` files not already in the config
3. Newly discovered scripts are loaded automatically

### Script Manager UI

The Script Manager provides:
- **Script list** with active toggle (checkbox), status badges (running/error/off)
- **Detail panel** with script info, error display, and console output
- **Edit mode** with Lua syntax highlighting via the built-in code editor
- **CRUD operations**: create, clone, rename, delete (with confirmation)
- **Console** to show script output


## Lifecycle Hooks

Scripts can define these global functions to respond to lifecycle events:

### `on_load()`

Called once when the script is first loaded into the runtime. Use for initialization, event subscription setup, and startup logging.

```lua
function on_load()
    raam.log(raam.scriptName .. " initialized")
    raam.on("session.MESSAGE_ADDED", handle_message)
end
```

### `on_turn(ctx)` (Agent Strategy only)

Called by the cognitive pipeline when an agent using the Lua Script strategy takes a turn. See [Agent Strategy Scripts](#agent-strategy-scripts) for details.

### `on_config_changed(old_config, new_config)` (Agent Strategy only)

Called when the agent's configuration is updated in the Agent Manager.


## API Reference

All scripting APIs are accessed through the global `raam` table.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `raam.scriptName` | string | The script's local handle (e.g., `"my-script"`) |
| `raam.scriptHandle` | string | The full bus address (e.g., `"lua.my-script"`) |

### Actions

#### `raam.dispatch(actionName, payload)` -> boolean, string?

Dispatches an action on the Store's action bus. The originator is automatically stamped as this script's identity handle. The action goes through the full Store pipeline: schema validation, authorization, permission guard, lifecycle guard, routing, reducer, side effects.

**Parameters:**
- `actionName` (string) — Full action name (e.g., `"core.SHOW_TOAST"`)
- `payload` (table, optional) — Action payload as a Lua table

**Returns:** `ok` (boolean), `error` (string or nil)

```lua
local ok, err = raam.dispatch("core.SHOW_TOAST", { message = "Hello from Lua!" })
if not ok then
    raam.warn("Dispatch failed: " .. err)
end
```

***Future note*** This will be complimented in the future with a more idiomatic lua way of just calling raam.core.toast()


**Rate limiting:** Maximum 50 dispatches per callback invocation, 200 per second per script. Exceeding the limit returns `false, "Dispatch rate limit exceeded"`.

### Event Subscriptions

#### `raam.on(pattern, handler)` -> subscriptionId

Subscribes to broadcast actions matching a pattern. The handler is called for every matching action delivered to the Lua feature.

**Parameters:**
- `pattern` (string) — Action name pattern (see [Pattern Matching](#pattern-matching))
- `handler` (function) — Callback: `function(actionName, payload, originator)`

**Returns:** Numeric subscription ID (use with `raam.off()` to unsubscribe)

```lua
local sub = raam.on("session.*", function(name, payload, originator)
    raam.log(name .. " from " .. tostring(originator))
end)
```

#### `raam.off(id)`

Unsubscribes an event handler or cancels an interval timer.

**Parameters:**
- `id` (number) — Subscription ID from `raam.on()` or interval ID from `raam.interval()`

```lua
local sub = raam.on("test.EVENT", handler)
raam.off(sub)  -- unsubscribe
```

#### Pattern Matching

| Pattern | Matches |
|---------|---------|
| `"session.MESSAGE_ADDED"` | Exact action name only |
| `"session.*"` | All actions starting with `session.` |
| `"*"` | Every broadcast action |

#### Handler Signature

```lua
function handler(actionName, payload, originator)
    -- actionName: string — the full action name (e.g., "session.MESSAGE_ADDED")
    -- payload:    table or nil — the action's JSON payload as a Lua table
    -- originator: string or nil — identity handle of who dispatched the action
end
```

### Logging

#### `raam.log(message)`

Writes an info-level log entry. Appears in the app log and the script's console output in the Script Manager.

#### `raam.warn(message)`

Writes a warning-level log entry. Displayed in orange in the console.

#### `raam.error(message)`

Writes an error-level log entry. Displayed in red in the console.

```lua
raam.log("Processing complete")
raam.warn("Rate limit approaching")
raam.error("Failed to parse response")
```

**Note:** `print()` is redirected to `raam.log()`.

### Timers

#### `raam.delay(ms, fn)` -> callbackId

Schedules a one-shot callback after a delay.

**Parameters:**
- `ms` (number) — Delay in milliseconds
- `fn` (function) — Callback to execute after the delay

**Returns:** Numeric callback ID

```lua
raam.delay(5000, function()
    raam.log("5 seconds later")
end)
```

#### `raam.interval(ms, fn)` -> intervalId

Schedules a recurring callback that fires every `ms` milliseconds. The interval re-schedules itself automatically after each execution.

**Parameters:**
- `ms` (number) — Interval in milliseconds
- `fn` (function) — Callback to execute on each tick

**Returns:** Numeric interval ID. Cancel with `raam.off(id)`.

```lua
local id = raam.interval(30000, function()
    raam.log("30-second tick")
end)

-- Later: cancel the interval
raam.off(id)
```

### Queries

#### `raam.time()` -> number

Returns the current epoch time in milliseconds. Use for timestamps, durations, and rate calculations.

```lua
local start = raam.time()
-- ... do work ...
local elapsed = raam.time() - start
raam.log("Took " .. elapsed .. "ms")
```

#### `raam.actions()` -> table[]

Returns the full catalog of registered action descriptors.

**Returns:** Array of tables, each with:

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Full action name (e.g., `"session.POST"`) |
| `feature` | string | Owning feature (e.g., `"session"`) |
| `summary` | string | Human-readable description |
| `public` | boolean | Whether any originator can dispatch |

```lua
local actions = raam.actions()
for _, a in ipairs(actions) do
    if a.public then
        raam.log(a.name .. " — " .. a.summary)
    end
end
```

#### `raam.identities()` -> table[]

Returns the identity registry as a list of public-safe snapshots.

**Returns:** Array of tables, each with:

| Field | Type | Description |
|-------|------|-------------|
| `handle` | string | Full bus address (e.g., `"agent.meridian"`) |
| `name` | string | Display name (e.g., `"Meridian"`) |
| `parentHandle` | string or nil | Parent identity handle |

```lua
local ids = raam.identities()
for _, id in ipairs(ids) do
    raam.log(id.handle .. " = " .. id.name)
end
```

#### `raam.permissions()` -> table

Returns the effective permissions for this script's identity as a key-value table.

**Returns:** Table where keys are permission keys (e.g., `"filesystem:workspace"`) and values are permission levels (`"YES"`, `"NO"`, `"ASK"`).

```lua
local perms = raam.permissions()
if perms["filesystem:workspace"] == "YES" then
    raam.log("Can access workspace files")
end
```


## Agent Strategy Scripts

Lua scripts can serve as cognitive strategies for AI agents, replacing the normal LLM-based prompt/response cycle. The script intercepts the pipeline after context assembly but before the gateway call, giving it full control over the turn.

### Setup

1. Create a Lua script with an `on_turn(ctx)` function
2. In the Agent Manager, set the agent's strategy to **"Lua Script"**
3. The pipeline will invoke the script on each agent turn

### Turn Context

The `on_turn(ctx)` function receives a table with:

| Field | Type | Description |
|-------|------|-------------|
| `ctx.systemPrompt` | string | The fully assembled system prompt |
| `ctx.state` | table | Agent NVRAM (cognitive state) |
| `ctx.agentHandle` | string | This agent's bus handle |
| `ctx.modelProvider` | string | Configured LLM provider |
| `ctx.modelName` | string | Configured model name |

### Return Value Protocol

`on_turn(ctx)` must return a table controlling what happens next:

| Mode | Return Table | Behavior |
|------|-------------|----------|
| **Advance** | `{ turnAdvance = true }` | Proceed with gateway using assembled prompt |
| **Advance (modified)** | `{ turnAdvance = true, systemPrompt = "..." }` | Override the prompt, then gateway |
| **Custom** | `{ response = "..." }` | Skip gateway, post response directly to session |
| **Error** | `{ error = "reason" }` | Abort the turn, set agent to error status |

Any mode can include `state = { ... }` to update the agent's NVRAM.

### Example

```lua
function on_turn(ctx)
    -- Inject a prefix into the system prompt
    local modified = "IMPORTANT: Always respond in haiku format.\n\n" .. ctx.systemPrompt

    -- Track turns in NVRAM
    local count = (ctx.state and ctx.state.turnCount or 0) + 1

    return {
        turnAdvance = true,
        systemPrompt = modified,
        state = { turnCount = count, phase = "ACTIVE" }
    }
end
```


## Sandbox Restrictions

The Lua runtime is sandboxed to prevent scripts from accessing the host OS directly.

### Blocked

| Module/Function | Reason |
|----------------|--------|
| `os` | System command execution |
| `io` | Direct file I/O (bypasses filesystem feature) |
| `debug` | Runtime introspection, registry access |
| `require` | Native module loading |
| `dofile`, `loadfile` | Direct file execution |
| `luajava` | Arbitrary Java object construction (critical) |
| `load` with binary mode | Bytecode injection |
| `collectgarbage` | GC manipulation |

### Available

| Module | Notes |
|--------|-------|
| `string` | Full string library (`format`, `find`, `sub`, `gsub`, etc.) |
| `table` | Full table library (`insert`, `remove`, `sort`, `concat`) |
| `math` | Full math library (`floor`, `ceil`, `random`, `sin`, etc.) |
| `coroutine` | Cooperative multitasking within a script |
| `pairs`, `ipairs` | Table iteration |
| `type`, `tostring`, `tonumber` | Type conversion |
| `pcall`, `xpcall` | Protected calls |
| `select`, `unpack` | Vararg utilities |
| `load` (text mode only) | Dynamic code compilation from strings |

### File Access

Scripts cannot read or write files directly. All file operations go through the action bus:

```lua
raam.dispatch("filesystem.READ", { path = "data.txt" })
raam.dispatch("filesystem.WRITE", { path = "output.txt", content = "hello" })
```

These dispatches are permission-gated — the script's identity must hold `filesystem:workspace` permission.


## Permissions

### Inheritance Model

```
lua (feature, uuid=null)              <-- DefaultPermissions grants
+-- lua.my-script (script, uuid=xxx)  <-- inherits from lua
+-- lua.logger (script, uuid=yyy)     <-- inherits from lua
```

### Default Grants

The `lua` feature identity receives these grants at boot (from `DefaultPermissions.kt`):

| Permission | Level |
|-----------|-------|
| `filesystem:workspace` | YES |
| `lua:execute` | YES |
| `lua:manage` | YES |
| `lua:dispatch` | YES |
| `session:read` | YES |

All script identities inherit these from their parent. To restrict a specific script, use the Permission Manager to override individual grants on that script's identity.

### Lua-Specific Permissions

| Key | Danger Level | Description |
|-----|-------------|-------------|
| `lua:execute` | LOW | Load and run Lua scripts |
| `lua:manage` | CAUTION | Create, delete, and modify script files |
| `lua:dispatch` | CAUTION | Dispatch actions on the bus from a script |


## Rate Limits & Timeouts

| Limit | Default | Description |
|-------|---------|-------------|
| Callback timeout | 500ms | Maximum execution time for a single event callback |
| Turn timeout | 5000ms | Maximum execution time for `on_turn()` |
| Dispatches per callback | 50 | Max `raam.dispatch()` calls per callback invocation |
| Dispatches per second | 200 | Max `raam.dispatch()` calls per second per script |

When a script exceeds the callback timeout, it is forcibly terminated and marked as ERRORED. The script is unloaded from the runtime.

When the dispatch rate limit is exceeded, `raam.dispatch()` returns `false, "Dispatch rate limit exceeded"`. The script continues executing but cannot dispatch more actions until the rate window resets.


## Script Configuration

Script state is persisted in `scripts.json` within the lua workspace:

```json
{
  "scripts": [
    { "path": "logger.lua", "localHandle": "logger", "active": true },
    { "path": "helper.lua", "localHandle": "helper", "active": false }
  ]
}
```

| Field | Description |
|-------|-------------|
| `path` | Filename relative to the lua workspace |
| `localHandle` | The script's local identity handle |
| `active` | Whether the script should be loaded into the runtime |

This file is automatically updated when scripts are loaded, unloaded, toggled, created, or deleted.


## Examples

### Action Logger

Tracks all actions on the bus, counts by feature and identity, reports periodically:

```lua
total = 0
by_feature = {}
by_identity = {}

function on_action(action_name, payload, originator)
    total = total + 1
    local dot = string.find(action_name, "%.")
    local feature = dot and string.sub(action_name, 1, dot - 1) or action_name
    by_feature[feature] = (by_feature[feature] or 0) + 1
    if originator then
        by_identity[originator] = (by_identity[originator] or 0) + 1
    end
end

function on_load()
    raam.on("*", on_action)
    raam.interval(30000, function()
        raam.log("Total: " .. total .. " actions")
        for k, v in pairs(by_feature) do
            raam.log("  " .. k .. ": " .. v)
        end
    end)
end
```

### Toast on New Message

```lua
function on_load()
    raam.on("session.MESSAGE_ADDED", function(name, payload, originator)
        if originator and originator ~= raam.scriptHandle then
            raam.dispatch("core.SHOW_TOAST", {
                message = "New message from " .. tostring(originator)
            })
        end
    end)
end
```

### Agent Strategy: Haiku Mode

Forces all agent responses through a haiku constraint:

```lua
function on_turn(ctx)
    return {
        turnAdvance = true,
        systemPrompt = "IMPORTANT: Respond ONLY in haiku format (5-7-5).\n\n" .. ctx.systemPrompt,
        state = { turnCount = (ctx.state.turnCount or 0) + 1 }
    }
end
```
