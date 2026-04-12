package asareon.raam.feature.lua

import asareon.raam.core.IdentityHandle
import asareon.raam.core.Store
import asareon.raam.feature.agent.*
import kotlinx.serialization.json.*

/**
 * A CognitiveStrategy that uses a Lua script as the agent's "brain" instead of an LLM.
 *
 * The script must define an `on_turn(ctx)` function that receives the turn context
 * and returns a table with `response` (string), optional `state` (table for NVRAM update),
 * and optional `actions` (array of {name, payload} to dispatch).
 *
 * ## Pipeline Integration
 * Unlike LLM-based strategies, this strategy short-circuits the normal pipeline:
 * - `buildPrompt()` returns a minimal prompt (the pipeline still needs something for validation)
 * - The actual cognition happens when LuaFeature executes the script's `on_turn()`
 * - `postProcessResponse()` parses the script's return value
 *
 * ## Identity
 * Agent Lua scripts use identities under "lua.agent-{uuid}" — children of the
 * "lua" feature, subject to the full permission system.
 */
class LuaStrategy(
    private val luaFeature: LuaFeature
) : CognitiveStrategy {

    override val identityHandle: IdentityHandle = IdentityHandle("agent.strategy.lua")
    override val displayName: String = "Lua Script"

    companion object {
        const val SLOT_LUA_SCRIPT = "lua_script"
        const val SLOT_SYSTEM_INSTRUCTION = "system_instruction"

        val DEFAULT_LUA_SCRIPT = LuaScriptTemplates.agentStrategyScript
    }

    override fun getResourceSlots(): List<ResourceSlot> = listOf(
        ResourceSlot(
            slotId = SLOT_LUA_SCRIPT,
            type = AgentResourceType.LUA_SCRIPT,
            displayName = "Lua Script",
            description = "The Lua script that implements this agent's cognition via on_turn().",
            isRequired = true
        ),
        ResourceSlot(
            slotId = SLOT_SYSTEM_INSTRUCTION,
            type = AgentResourceType.SYSTEM_INSTRUCTION,
            displayName = "Context Notes",
            description = "Optional context passed to the script via ctx.resources.system_instruction.",
            isRequired = false
        )
    )

    override fun getConfigFields(): List<StrategyConfigField> = listOf(
        StrategyConfigField(
            key = "outputSessionId",
            type = StrategyConfigFieldType.OUTPUT_SESSION,
            displayName = "Output Session",
            description = "The session where the Lua agent's responses are posted."
        )
    )

    override fun getInitialState(): JsonElement = buildJsonObject {
        put("phase", "READY")
        put("turnCount", 0)
    }

    override fun buildPrompt(context: AgentTurnContext, state: JsonElement): PromptBuilder {
        // Minimal prompt — the real cognition is in Lua, not in an LLM.
        // This is used by the pipeline for structural validation only.
        return PromptBuilder(context).apply {
            instructions()
        }
    }

    override fun postProcessResponse(response: String, currentState: JsonElement): PostProcessResult {
        // For Lua strategy, the response is the direct output from on_turn().
        // State updates are handled separately via the return value.
        val stateObj = currentState as? JsonObject ?: buildJsonObject { put("phase", "READY") }
        val turnCount = stateObj["turnCount"]?.jsonPrimitive?.intOrNull ?: 0
        val newState = buildJsonObject {
            stateObj.forEach { (k, v) -> put(k, v) }
            put("turnCount", turnCount + 1)
            put("phase", "ACTIVE")
        }
        return PostProcessResult(newState, SentinelAction.PROCEED)
    }

    // =========================================================================
    // Lifecycle hooks
    // =========================================================================

    override fun getBuiltInResources(): List<AgentResource> = listOf(
        AgentResource(
            id = "res-lua-default-script",
            type = AgentResourceType.LUA_SCRIPT,
            name = "Default Lua Agent Script",
            content = DEFAULT_LUA_SCRIPT,
            isBuiltIn = true
        )
    )

    override fun onAgentRegistered(agent: AgentInstance, store: Store) {
        // Load the agent's Lua script into the runtime
        val scriptContent = resolveScriptContent(agent)
        if (scriptContent != null) {
            val scriptHandle = agentScriptHandle(agent)
            luaFeature.loadScriptDirect(scriptHandle, agent.identity.name, scriptContent)
        }
    }

    override fun onAgentConfigChanged(old: AgentInstance, new: AgentInstance, store: Store) {
        // If the Lua script resource changed, reload
        val oldContent = resolveScriptContent(old)
        val newContent = resolveScriptContent(new)
        if (oldContent != newContent && newContent != null) {
            val scriptHandle = agentScriptHandle(new)
            luaFeature.unloadScriptDirect(scriptHandle)
            luaFeature.loadScriptDirect(scriptHandle, new.identity.name, newContent)
        }
    }

    override fun validateConfig(agent: AgentInstance): AgentInstance {
        // Ensure outputSessionId is within subscribed sessions
        val outputId = agent.outputSessionId
        if (outputId != null && outputId !in agent.subscribedSessionIds) {
            return agent.copy(outputSessionId = agent.subscribedSessionIds.firstOrNull())
        }
        return agent
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Derives the Lua runtime script handle for an agent.
     */
    fun agentScriptHandle(agent: AgentInstance): String {
        return "lua.agent-${agent.identity.uuid ?: agent.identity.handle}"
    }

    /**
     * Resolves the Lua script content from the agent's resource assignments.
     */
    private fun resolveScriptContent(agent: AgentInstance): String? {
        val resourceId = agent.resources[SLOT_LUA_SCRIPT] ?: return null
        // Resource content is resolved by the pipeline via context.resolvedResources.
        // At registration time, we don't have the content yet — return null.
        // The actual script loading happens when the pipeline invokes executeTurn().
        return null
    }
}
