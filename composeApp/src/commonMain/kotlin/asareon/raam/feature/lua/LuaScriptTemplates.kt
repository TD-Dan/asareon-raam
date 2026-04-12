package asareon.raam.feature.lua

/**
 * Default Lua source code templates for new scripts.
 * Kept in a single file so templates are easy to find and maintain.
 */
object LuaScriptTemplates {

    /**
     * Generates the default template for a standalone automation script.
     * Includes all raam.* entry points: lifecycle, events, dispatch, agent hooks, utilities.
     */
    fun appScript(name: String, localHandle: String): String = """
-- $name
-- Asareon Raam Lua Script
--
-- This script runs sandboxed with its own identity ("lua.$localHandle").
-- Permissions are inherited from the lua feature and can be
-- adjusted per-script in the Permission Manager.

-- ═══════════════════════════════════════════════════════════
-- LIFECYCLE HOOKS
-- ═══════════════════════════════════════════════════════════

function on_load()
    raam.log("$name loaded")
end

-- ═══════════════════════════════════════════════════════════
-- EVENT SUBSCRIPTIONS
-- ═══════════════════════════════════════════════════════════
-- raam.on(pattern, handler)  — subscribe to broadcast actions
-- raam.off(subscriptionId)   — unsubscribe
--
-- Patterns: exact match, wildcard suffix, or global:
--   "session.MESSAGE_ADDED"   — exact action name
--   "session.*"               — all session actions
--   "*"                       — everything

-- raam.on("session.MESSAGE_ADDED", function(actionName, payload)
--     raam.log("New message in " .. (payload.sessionHandle or "?"))
-- end)

-- ═══════════════════════════════════════════════════════════
-- DISPATCHING ACTIONS
-- ═══════════════════════════════════════════════════════════
-- local ok, err = raam.dispatch(actionName, payloadTable)
--
-- All dispatches go through the Store pipeline and are
-- permission-checked against this script's identity.

-- raam.dispatch("core.SHOW_TOAST", { message = "Hello from Lua!" })

-- ═══════════════════════════════════════════════════════════
-- UTILITIES REFERENCE
-- ═══════════════════════════════════════════════════════════
-- raam.log(msg)               — info log
-- raam.warn(msg)              — warning log
-- raam.error(msg)             — error log
-- raam.delay(ms, fn)          — one-shot timer
-- raam.identities()           — list all registered identities
-- raam.permissions()          — this script's effective permissions
-- raam.scriptName             — local handle ("$localHandle")
-- raam.scriptHandle           — full bus address ("lua.$localHandle")
""".trimIndent()

    /**
     * Default template for an agent CognitiveStrategy script.
     * Used as the built-in resource for agent.strategy.lua.
     */
    val agentStrategyScript: String = """
-- Lua Agent Strategy Script
-- This script serves as a CognitiveStrategy for an Asareon Raam agent.
-- Implement on_turn(ctx) to handle each agent turn.

-- ═══════════════════════════════════════════════════════════
-- LIFECYCLE HOOKS
-- ═══════════════════════════════════════════════════════════

function on_load()
    raam.log("Lua agent script loaded")
end

-- ═══════════════════════════════════════════════════════════
-- AGENT TURN — called each time the agent's turn fires
-- ═══════════════════════════════════════════════════════════

function on_turn(ctx)
    -- ctx.messages   — array of {senderId, content, timestamp}
    -- ctx.state      — table of NVRAM values (cognitive state)
    -- ctx.resources  — table of {slotId = content_string}
    -- ctx.sessions   — array of {handle, name, isOutput, messageCount}

    local msgCount = 0
    if ctx.messages then msgCount = #ctx.messages end

    return {
        response = "Processed " .. msgCount .. " messages.",
        -- state = { phase = "ACTIVE" },  -- optional NVRAM update
        -- actions = {                     -- optional action dispatches
        --     { name = "core.SHOW_TOAST", payload = { message = "Turn done" } }
        -- }
    }
end

-- ═══════════════════════════════════════════════════════════
-- CONFIG CHANGE — called after agent config is updated
-- ═══════════════════════════════════════════════════════════

function on_config_changed(old_config, new_config)
    raam.log("Agent config updated")
end

-- ═══════════════════════════════════════════════════════════
-- UTILITIES REFERENCE
-- ═══════════════════════════════════════════════════════════
-- raam.dispatch(name, payload) — dispatch an action on the bus
-- raam.on(pattern, handler)    — subscribe to broadcast events
-- raam.off(subscriptionId)     — unsubscribe
-- raam.log(msg)                — info log
-- raam.warn(msg)               — warning log
-- raam.error(msg)              — error log
-- raam.delay(ms, fn)           — one-shot timer
-- raam.identities()            — list all registered identities
-- raam.permissions()           — this script's effective permissions
-- raam.scriptName              — local handle
-- raam.scriptHandle            — full bus address
""".trimIndent()
}
