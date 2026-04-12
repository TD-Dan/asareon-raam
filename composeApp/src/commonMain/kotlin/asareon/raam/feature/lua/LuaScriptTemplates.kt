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
--
-- The pipeline assembles context partitions normally, then sends them here
-- via on_turn(ctx). You can inspect/modify the system prompt, then either:
--   return { turnAdvance = true }              — proceed with gateway
--   return { turnAdvance = true,               — modify prompt, then gateway
--            systemPrompt = "new prompt" }
--   return { response = "direct text" }        — skip gateway, post directly
--   return { error = "reason" }                — abort the turn

-- ═══════════════════════════════════════════════════════════
-- LIFECYCLE HOOKS
-- ═══════════════════════════════════════════════════════════

function on_load()
    raam.log("Lua agent strategy loaded")
end

-- ═══════════════════════════════════════════════════════════
-- AGENT TURN — called by the pipeline after context assembly
-- ═══════════════════════════════════════════════════════════

function on_turn(ctx)
    -- ctx.systemPrompt  — the fully assembled system prompt
    -- ctx.state         — table of NVRAM values (cognitive state)
    -- ctx.agentHandle   — this agent's bus handle
    -- ctx.modelProvider — configured LLM provider
    -- ctx.modelName     — configured model name

    raam.log("Turn received. Prompt length: " .. #(ctx.systemPrompt or ""))

    -- Option 1: Advance with the assembled prompt as-is
    return { turnAdvance = true }

    -- Option 2: Modify the prompt before gateway
    -- return {
    --     turnAdvance = true,
    --     systemPrompt = "You are a Lua-powered agent.\n" .. ctx.systemPrompt
    -- }

    -- Option 3: Skip gateway, post a direct response
    -- return { response = "I handled this turn locally." }

    -- Option 4: Update NVRAM state alongside any mode
    -- return {
    --     turnAdvance = true,
    --     state = { phase = "ACTIVE", turnCount = (ctx.state.turnCount or 0) + 1 }
    -- }
end

-- ═══════════════════════════════════════════════════════════
-- CONFIG CHANGE — called after agent config is updated
-- ═══════════════════════════════════════════════════════════

function on_config_changed(old_config, new_config)
    raam.log("Agent config updated")
end

-- ═══════════════════════════════════════════════════════════
-- TURN CONTROL REFERENCE
-- ═══════════════════════════════════════════════════════════
-- Return table from on_turn(ctx):
--   turnAdvance = true           — proceed with configured gateway
--   systemPrompt = "..."         — override assembled prompt (with turnAdvance)
--   response = "..."             — skip gateway, post directly (custom mode)
--   error = "..."                — abort the turn
--   state = { ... }              — update agent NVRAM (any mode)

-- ═══════════════════════════════════════════════════════════
-- UTILITIES REFERENCE
-- ═══════════════════════════════════════════════════════════
-- raam.dispatch(name, payload)  — dispatch an action on the bus
-- raam.on(pattern, handler)     — subscribe to broadcast events
-- raam.off(subscriptionId)      — unsubscribe
-- raam.log(msg)                 — info log
-- raam.warn(msg)                — warning log
-- raam.error(msg)               — error log
-- raam.delay(ms, fn)            — one-shot timer
-- raam.identities()             — list all registered identities
-- raam.permissions()            — this script's effective permissions
-- raam.scriptName               — local handle
-- raam.scriptHandle             — full bus address
""".trimIndent()
}
