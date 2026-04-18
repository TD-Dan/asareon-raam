package asareon.raam.feature.lua

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.LogBufferEntry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Lightweight entry for the action history buffer (no payloads). */
data class ActionBufferEntry(
    val name: String,
    val originator: String?,
    val timestamp: Long
)

/** Tracks a script's log subscription for live forwarding. */
data class LogSubscriptionEntry(
    val scriptHandle: String,
    val minLevel: LogLevel,
    val callbackId: Long
)

/**
 * ## Mandate
 * Hosts and manages sandboxed Lua scripting environments. Scripts can dispatch actions,
 * subscribe to broadcast events, and serve as cognitive strategies for agents.
 *
 * ## Layer
 * L3 (Actors) — alongside AgentRuntime and CommandBot.
 *
 * ## File I/O
 * All filesystem access goes through the FileSystem feature via action dispatch.
 * Async responses are correlated via [LuaState.pendingFileOps].
 *
 * ## Security
 * Each script is a registered identity in the "lua.*" namespace, subject to the full
 * permission system. The Lua runtime is sandboxed: no OS/IO/debug/luajava access,
 * time-bounded execution, rate-limited dispatches.
 */
class LuaFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {

    override val identity: Identity = Identity(
        uuid = null,
        handle = "lua",
        localHandle = "lua",
        name = "Lua Scripting",
        displayColor = "#7C4DFF",
        displayIcon = "code"
    )

    private val runtime = LuaRuntime()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val maxConsoleEntries = 200
    private val scriptsInCallback = mutableSetOf<String>()
    private var currentCascadeDepth = 0
    private val maxCascadeDepth = 3
    private var correlationCounter = 0L

    private lateinit var store: Store

    private val scriptsConfigFile = "scripts.json"
    private val actionBufferMax = 2000
    private val actionBuffer = ArrayDeque<ActionBufferEntry>(actionBufferMax)
    /** Log subscriptions from scripts: scriptHandle → list of (minLevel, callbackId) */
    private val logSubscriptions = mutableListOf<LogSubscriptionEntry>()

    private fun nextCorrelationId(prefix: String): String = "lua:$prefix:${++correlationCounter}"

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        private val viewKey = "lua.script-manager"

        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { s, f -> LuaScriptManagerView(s, f) }
        )

        override fun ribbonEntries(store: Store, activeViewKey: String?): List<RibbonEntry> = listOf(
            RibbonEntry(
                id = "lua.script-manager",
                label = "Lua Scripts",
                icon = Icons.Default.Code,
                priority = 30,
                isActive = activeViewKey == viewKey,
                onClick = {
                    store.dispatch(
                        "lua",
                        Action(
                            ActionRegistry.Names.CORE_SET_ACTIVE_VIEW,
                            buildJsonObject { put("key", viewKey) },
                        ),
                    )
                },
            ),
        )
    }

    override fun init(store: Store) {
        this.store = store
        runtime.setBridgeListener(createBridgeListener())

        // Register log listener for live forwarding to scripts via raam.applog.listen()
        platformDependencies.addLogListener("lua") { level, tag, message, timestamp ->
            forwardLogToSubscribedScripts(level, tag, message, timestamp)
        }
    }

    // ========================================================================
    // Reducer
    // ========================================================================

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val luaState = state as? LuaState ?: LuaState(runtimeAvailable = runtime.isAvailable)

        return when (action.name) {
            ActionRegistry.Names.LUA_LOAD_SCRIPT -> {
                val payload = action.payload ?: return luaState
                val scriptPath = payload["scriptPath"]?.jsonPrimitive?.contentOrNull ?: return luaState
                val localHandle = deriveLocalHandle(
                    payload["localHandle"]?.jsonPrimitive?.contentOrNull, scriptPath
                )
                val autostart = payload["autostart"]?.jsonPrimitive?.booleanOrNull ?: false
                val handle = "lua.$localHandle"

                luaState.copy(
                    scripts = luaState.scripts + (handle to ScriptInfo(
                        handle = handle,
                        localHandle = localHandle,
                        name = localHandle,
                        path = scriptPath,
                        status = ScriptStatus.LOADING,
                        autostart = autostart,
                        loadedAt = platformDependencies.currentTimeMillis()
                    ))
                )
            }

            ActionRegistry.Names.LUA_UNLOAD_SCRIPT,
            ActionRegistry.Names.LUA_DELETE_SCRIPT -> {
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                luaState.copy(
                    scripts = luaState.scripts - handle,
                    consoleBuffers = luaState.consoleBuffers - handle
                )
            }

            ActionRegistry.Names.LUA_SCRIPT_OUTPUT -> {
                val payload = action.payload ?: return luaState
                val handle = payload["scriptHandle"]?.jsonPrimitive?.contentOrNull ?: return luaState
                val level = payload["level"]?.jsonPrimitive?.contentOrNull ?: "log"

                // "clear" level = wipe the console buffer
                if (level == "clear") {
                    return luaState.copy(consoleBuffers = luaState.consoleBuffers + (handle to emptyList()))
                }

                val message = payload["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val timestamp = payload["timestamp"]?.jsonPrimitive?.longOrNull ?: 0
                val bold = payload["bold"]?.jsonPrimitive?.booleanOrNull
                val italic = payload["italic"]?.jsonPrimitive?.booleanOrNull
                val color = payload["color"]?.jsonPrimitive?.contentOrNull

                val entry = ConsoleEntry(level, message, timestamp, bold, italic, color)
                val buffer = luaState.consoleBuffers.getOrElse(handle) { emptyList() }
                val newBuffer = (buffer + entry).takeLast(maxConsoleEntries)
                luaState.copy(consoleBuffers = luaState.consoleBuffers + (handle to newBuffer))
            }

            ActionRegistry.Names.LUA_SCRIPT_ERROR -> {
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                val error = action.payload?.get("error")?.jsonPrimitive?.contentOrNull
                val sourceContent = action.payload?.get("sourceContent")?.jsonPrimitive?.contentOrNull
                val script = luaState.scripts[handle] ?: return luaState
                luaState.copy(
                    scripts = luaState.scripts + (handle to script.copy(
                        status = ScriptStatus.ERRORED,
                        lastError = error,
                        sourceContent = sourceContent ?: script.sourceContent
                    ))
                )
            }

            ActionRegistry.Names.LUA_TOGGLE_SCRIPT -> {
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                val script = luaState.scripts[handle] ?: return luaState
                val newStatus = if (script.status == ScriptStatus.RUNNING || script.status == ScriptStatus.LOADING) {
                    ScriptStatus.STOPPED
                } else {
                    ScriptStatus.LOADING
                }
                luaState.copy(
                    scripts = luaState.scripts + (handle to script.copy(status = newStatus))
                )
            }

            ActionRegistry.Names.LUA_SCRIPT_LOADED -> {
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                val sourceContent = action.payload?.get("sourceContent")?.jsonPrimitive?.contentOrNull
                val script = luaState.scripts[handle] ?: return luaState
                luaState.copy(
                    scripts = luaState.scripts + (handle to script.copy(
                        status = ScriptStatus.RUNNING,
                        lastError = null,
                        sourceContent = sourceContent ?: script.sourceContent
                    ))
                )
            }

            ActionRegistry.Names.LUA_SAVE_SCRIPT -> {
                // Update sourceContent in state when saved
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                val content = action.payload?.get("content")?.jsonPrimitive?.contentOrNull ?: return luaState
                val script = luaState.scripts[handle] ?: return luaState
                luaState.copy(
                    scripts = luaState.scripts + (handle to script.copy(sourceContent = content))
                )
            }

            else -> luaState
        }
    }

    // ========================================================================
    // Side Effects
    // ========================================================================

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        when (action.name) {
            ActionRegistry.Names.LUA_LOAD_SCRIPT -> handleLoadScript(action, store)
            ActionRegistry.Names.LUA_UNLOAD_SCRIPT -> handleUnloadScript(action, store)
            ActionRegistry.Names.LUA_RELOAD_SCRIPT -> handleReloadScript(action, store)
            ActionRegistry.Names.LUA_EVAL -> handleEval(action, store)
            ActionRegistry.Names.LUA_LIST_SCRIPTS -> handleListScripts(action, store)
            ActionRegistry.Names.LUA_CREATE_SCRIPT -> handleCreateScript(action, store)
            ActionRegistry.Names.LUA_DELETE_SCRIPT -> { handleDeleteScript(action, store, previousState); persistScriptsConfig(store) }
            ActionRegistry.Names.LUA_CLONE_SCRIPT -> handleCloneScript(action, store)
            ActionRegistry.Names.LUA_TOGGLE_SCRIPT -> { handleToggleScript(action, store, previousState); persistScriptsConfig(store) }
            ActionRegistry.Names.LUA_SAVE_SCRIPT -> handleSaveScript(action, store)
            ActionRegistry.Names.LUA_SCRIPT_LOADED -> persistScriptsConfig(store)
            ActionRegistry.Names.LUA_SCRIPT_UNLOADED -> persistScriptsConfig(store)

            // External strategy turn request from the agent pipeline
            ActionRegistry.Names.AGENT_EXTERNAL_TURN_REQUEST -> handleExternalTurnRequest(action, store)

            // App lifecycle: discover existing scripts on startup
            ActionRegistry.Names.SYSTEM_RUNNING -> handleAppStartup(store)

            // Async filesystem responses
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> handleFileReadResponse(action, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> handleFileListResponse(action, store)

            else -> deliverBroadcastToScripts(action)
        }
    }

    // ========================================================================
    // LOAD_SCRIPT: dispatch filesystem.READ → wait for RETURN_READ
    // ========================================================================

    private fun handleLoadScript(action: Action, store: Store) {
        val payload = action.payload ?: return logMissingPayload("LOAD_SCRIPT")
        val scriptPath = payload["scriptPath"]?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("LOAD_SCRIPT", "scriptPath")
        val localHandle = deriveLocalHandle(
            payload["localHandle"]?.jsonPrimitive?.contentOrNull, scriptPath
        )
        val handle = "lua.$localHandle"

        // Register script identity
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            payload = buildJsonObject {
                put("name", localHandle)
                put("localHandle", localHandle)
            }
        ))

        // Request the file content from FileSystem
        val correlationId = nextCorrelationId("load")
        trackPendingOp(store, PendingFileOp(
            correlationId = correlationId,
            opType = "load",
            scriptHandle = handle,
            extra = mapOf("localHandle" to localHandle, "scriptPath" to scriptPath)
        ))

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_READ,
            payload = buildJsonObject {
                put("path", scriptPath)
            }
        ))
    }

    // ========================================================================
    // UNLOAD_SCRIPT
    // ========================================================================

    private fun handleUnloadScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("UNLOAD_SCRIPT", "scriptHandle")
        runtime.unloadScript(handle)
        logSubscriptions.removeAll { it.scriptHandle == handle }

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            payload = buildJsonObject { put("handle", handle) }
        ))
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_SCRIPT_UNLOADED,
            payload = buildJsonObject {
                put("scriptHandle", handle)
                put("reason", "manual")
            }
        ))
        platformDependencies.log(LogLevel.INFO, identity.handle, "Script unloaded: $handle")
    }

    // ========================================================================
    // RELOAD_SCRIPT: dispatch filesystem.READ → wait for RETURN_READ
    // ========================================================================

    private fun handleReloadScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("RELOAD_SCRIPT", "scriptHandle")
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState
            ?: return logMissingState("RELOAD_SCRIPT")
        val script = currentState.scripts[handle]
            ?: return logUnknownScript("RELOAD_SCRIPT", handle)

        val correlationId = nextCorrelationId("reload")
        trackPendingOp(store, PendingFileOp(
            correlationId = correlationId,
            opType = "reload",
            scriptHandle = handle,
            extra = mapOf("localHandle" to script.localHandle)
        ))

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_READ,
            payload = buildJsonObject { put("path", script.path) }
        ))
    }

    // ========================================================================
    // CREATE_SCRIPT: dispatch filesystem.WRITE → then LOAD_SCRIPT
    // ========================================================================

    private fun handleCreateScript(action: Action, store: Store) {
        val name = action.payload?.get("name")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("CREATE_SCRIPT", "name")
        val localHandle = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-").trimStart('-').trimEnd('-').ifEmpty { "unnamed" }
        val fileName = "$localHandle.lua"

        val template = LuaScriptTemplates.appScript(name, localHandle)

        // Write via FileSystem feature
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_WRITE,
            payload = buildJsonObject {
                put("path", fileName)
                put("content", template)
            }
        ))

        // Auto-load the new script (FileSystem WRITE is synchronous within the
        // Store processing loop — by the time LOAD_SCRIPT is processed, the file exists)
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
            payload = buildJsonObject {
                put("scriptPath", fileName)
                put("localHandle", localHandle)
            }
        ))
    }

    // ========================================================================
    // DELETE_SCRIPT: unload + filesystem.DELETE_FILE + unregister
    // ========================================================================

    private fun handleDeleteScript(action: Action, store: Store, previousState: FeatureState?) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("DELETE_SCRIPT", "scriptHandle")
        // Use previousState because the reducer already removed the script from newState
        val prevLuaState = previousState as? LuaState
            ?: return logMissingState("DELETE_SCRIPT")
        val script = prevLuaState.scripts[handle]
            ?: return logUnknownScript("DELETE_SCRIPT", handle)

        runtime.unloadScript(handle)

        // Delete the file via FileSystem
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_DELETE_FILE,
            payload = buildJsonObject { put("path", script.path) }
        ))

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
            payload = buildJsonObject { put("handle", handle) }
        ))
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_SCRIPT_UNLOADED,
            payload = buildJsonObject {
                put("scriptHandle", handle)
                put("reason", "deleted")
            }
        ))
    }

    // ========================================================================
    // CLONE_SCRIPT: filesystem.READ source → on response, WRITE clone + LOAD
    // ========================================================================

    private fun handleCloneScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("CLONE_SCRIPT", "scriptHandle")
        val newName = action.payload?.get("newName")?.jsonPrimitive?.contentOrNull
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState
            ?: return logMissingState("CLONE_SCRIPT")
        val script = currentState.scripts[handle]
            ?: return logUnknownScript("CLONE_SCRIPT", handle)

        val cloneName = newName ?: "copy-of-${script.localHandle}"
        val cloneLocalHandle = cloneName.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-").trimStart('-').trimEnd('-').ifEmpty { "unnamed" }
        val cloneFileName = "$cloneLocalHandle.lua"

        val correlationId = nextCorrelationId("clone")
        trackPendingOp(store, PendingFileOp(
            correlationId = correlationId,
            opType = "clone-read",
            scriptHandle = handle,
            extra = mapOf(
                "cloneLocalHandle" to cloneLocalHandle,
                "cloneFileName" to cloneFileName
            )
        ))

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_READ,
            payload = buildJsonObject { put("path", script.path) }
        ))
    }

    // ========================================================================
    // TOGGLE_SCRIPT: stop or load based on previous state
    // ========================================================================

    private fun handleToggleScript(action: Action, store: Store, previousState: FeatureState?) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("TOGGLE_SCRIPT", "scriptHandle")
        val prevLuaState = previousState as? LuaState
            ?: return logMissingState("TOGGLE_SCRIPT")
        val prevScript = prevLuaState.scripts[handle]
            ?: return logUnknownScript("TOGGLE_SCRIPT", handle)

        if (prevScript.status == ScriptStatus.RUNNING || prevScript.status == ScriptStatus.LOADING) {
            runtime.unloadScript(handle)
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_SCRIPT_UNLOADED,
                payload = buildJsonObject {
                    put("scriptHandle", handle)
                    put("reason", "manual")
                }
            ))
        } else {
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
                payload = buildJsonObject {
                    put("scriptPath", prevScript.path)
                    put("localHandle", prevScript.localHandle)
                }
            ))
        }
    }

    // ========================================================================
    // SAVE_SCRIPT: filesystem.WRITE + hot-reload from content in payload
    // ========================================================================

    private fun handleSaveScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("SAVE_SCRIPT", "scriptHandle")
        val content = action.payload?.get("content")?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("SAVE_SCRIPT", "content")
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState
            ?: return logMissingState("SAVE_SCRIPT")
        val script = currentState.scripts[handle]
            ?: return logUnknownScript("SAVE_SCRIPT", handle)

        // Persist via FileSystem
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_WRITE,
            payload = buildJsonObject {
                put("path", script.path)
                put("content", content)
            }
        ))

        // Hot-reload if running (we have the content in hand, no need to re-read)
        if (script.status == ScriptStatus.RUNNING) {
            runtime.unloadScript(handle)
            val result = runtime.loadScript(handle, script.name, content)
            if (result.success) {
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.LUA_SCRIPT_LOADED,
                    payload = buildJsonObject {
                        put("scriptHandle", handle)
                        put("scriptName", script.name)
                        put("scriptPath", script.path)
                    }
                ))
            } else {
                dispatchScriptError(handle, result.error ?: "Reload failed", "load")
            }
        }

        platformDependencies.log(LogLevel.INFO, identity.handle, "Script saved: $handle")
    }

    // ========================================================================
    // APP STARTUP: discover .lua files in workspace
    // ========================================================================

    private companion object {
        const val DISCOVER_CORRELATION_ID = "lua:discover"
    }

    private fun handleAppStartup(store: Store) {
        // Register Lua as an external cognitive strategy via the action bus.
        // Done here (not in init()) because init() runs during BOOTING when
        // cross-feature dispatches are blocked by the lifecycle guard.
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY,
            payload = buildJsonObject {
                put("strategyId", "agent.strategy.lua")
                put("displayName", "Lua Script")
                put("featureHandle", identity.handle)
                put("resourceSlots", buildJsonArray {
                    add(buildJsonObject {
                        put("slotId", "system_instruction")
                        put("type", "SYSTEM_INSTRUCTION")
                        put("displayName", "System Instructions")
                        put("description", "Context instructions passed to the Lua script via ctx.resources.")
                        put("isRequired", false)
                    })
                })
                put("configFields", buildJsonArray {
                    add(buildJsonObject {
                        put("key", "outputSessionId")
                        put("type", "OUTPUT_SESSION")
                        put("displayName", "Output Session")
                        put("description", "The session where the Lua agent's responses are posted.")
                    })
                })
                put("initialState", buildJsonObject {
                    put("phase", "READY")
                    put("turnCount", 0)
                })
            }
        ))

        if (!runtime.isAvailable) return

        // Load persisted script config (which scripts exist, which are active)
        // The config response handler triggers autodiscovery after loading.
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_READ,
            payload = buildJsonObject { put("path", scriptsConfigFile) }
        ))
    }

    private fun handleFileListResponse(action: Action, store: Store) {
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        if (correlationId != DISCOVER_CORRELATION_ID) return // Not our response

        val listing = action.payload?.get("listing")?.jsonArray ?: run {
            platformDependencies.log(LogLevel.WARN, identity.handle, "Autodiscovery: RETURN_LIST missing 'listing' array")
            return
        }
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: run {
            platformDependencies.log(LogLevel.WARN, identity.handle, "Autodiscovery: LuaState not available")
            return
        }

        for (entry in listing) {
            val obj = entry.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val isDir = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isDir) continue
            if (!path.endsWith(".lua", ignoreCase = true)) continue

            // Derive handle and check if already loaded
            val localHandle = deriveLocalHandle(null, path)
            val handle = "lua.$localHandle"
            if (handle in currentState.scripts) continue

            // Auto-load this script
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
                payload = buildJsonObject {
                    put("scriptPath", path)
                    put("localHandle", localHandle)
                }
            ))
        }
    }

    // ========================================================================
    // EXTERNAL STRATEGY: handle turn requests from the agent pipeline
    // ========================================================================

    private fun handleExternalTurnRequest(action: Action, store: Store) {
        val payload = action.payload ?: return logMissingPayload("EXTERNAL_TURN_REQUEST")
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("EXTERNAL_TURN_REQUEST", "correlationId")
        val systemPrompt = payload["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val state = payload["state"]
        val agentHandle = payload["agentHandle"]?.jsonPrimitive?.contentOrNull

        // Build the context table for on_turn(ctx)
        val ctx = mutableMapOf<String, Any?>(
            "systemPrompt" to systemPrompt,
            "state" to state?.let { jsonElementToAny(it) },
            "agentHandle" to agentHandle,
            "modelProvider" to payload["modelProvider"]?.jsonPrimitive?.contentOrNull,
            "modelName" to payload["modelName"]?.jsonPrimitive?.contentOrNull
        )

        // Find a loaded script that can handle this turn.
        // For now, look for a script whose handle matches "lua.agent-*" or
        // use the first script that defines on_turn().
        // TODO: The agent's resource assignment should specify which script to use.
        val scriptHandle = findAgentScript(correlationId)

        if (scriptHandle == null) {
            // No script available — return error
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("mode", "error")
                    put("error", "No Lua script loaded for this agent. Load a script with on_turn() defined.")
                }
            ))
            return
        }

        // Execute the script's on_turn(ctx)
        val result = runtime.executeTurn(scriptHandle, ctx)
        if (!result.success) {
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT,
                payload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("mode", "error")
                    put("error", result.error ?: "on_turn() execution failed")
                }
            ))
            return
        }

        // Parse the script's return value to determine mode
        val returnMap = result.returnValue ?: emptyMap()
        val mode = when {
            returnMap["turnAdvance"] == true -> "advance"
            returnMap["response"] is String -> "custom"
            returnMap["error"] is String -> "error"
            else -> "advance" // Default: proceed with gateway
        }

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT,
            payload = buildJsonObject {
                put("correlationId", correlationId)
                put("mode", mode)
                when (mode) {
                    "advance" -> {
                        val modifiedPrompt = returnMap["systemPrompt"] as? String
                        if (modifiedPrompt != null) put("systemPrompt", modifiedPrompt)
                        else put("systemPrompt", systemPrompt)
                    }
                    "custom" -> {
                        put("response", (returnMap["response"] as? String) ?: "")
                    }
                    "error" -> {
                        put("error", (returnMap["error"] as? String) ?: "Script error")
                    }
                }
                // Pass through state updates
                val stateUpdate = returnMap["state"]
                if (stateUpdate != null) {
                    put("state", anyToJsonElement(stateUpdate))
                }
            }
        ))
    }

    /**
     * Finds a loaded script that can serve as the cognitive strategy for an agent.
     * Looks for scripts with handle "lua.agent-{agentUUID}" first, then any script
     * that has on_turn defined.
     */
    private fun findAgentScript(agentUUID: String): String? {
        val directHandle = "lua.agent-$agentUUID"
        if (runtime.isScriptLoaded(directHandle)) return directHandle

        // Fallback: find any loaded script (TODO: improve with resource-based mapping)
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return null
        return currentState.scripts.values
            .firstOrNull { it.status == ScriptStatus.RUNNING }
            ?.handle
    }

    // ========================================================================
    // EVAL
    // ========================================================================

    private fun handleEval(action: Action, store: Store) {
        val payload = action.payload ?: return logMissingPayload("EVAL")
        val handle = payload["scriptHandle"]?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("EVAL", "scriptHandle")
        val code = payload["code"]?.jsonPrimitive?.contentOrNull
            ?: return logMissingField("EVAL", "code")

        val result = runtime.eval(handle, code)
        if (!result.success) {
            dispatchScriptError(handle, result.error ?: "Eval error", "eval")
        } else if (result.returnValue != null) {
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                payload = buildJsonObject {
                    put("scriptHandle", handle)
                    put("level", "log")
                    put("message", "=> ${result.returnValue}")
                    put("timestamp", platformDependencies.currentTimeMillis())
                }
            ))
        }
    }

    // ========================================================================
    // LIST_SCRIPTS
    // ========================================================================

    private fun handleListScripts(action: Action, store: Store) {
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState
            ?: return logMissingState("LIST_SCRIPTS")
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val originator = action.originator
            ?: return logMissingField("LIST_SCRIPTS", "originator")

        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_RETURN_LIST,
            payload = buildJsonObject {
                correlationId?.let { put("correlationId", it) }
                put("scripts", buildJsonArray {
                    currentState.scripts.values.forEach { script ->
                        add(buildJsonObject {
                            put("handle", script.handle)
                            put("name", script.name)
                            put("path", script.path)
                            put("status", script.status.name)
                            put("autostart", script.autostart)
                        })
                    }
                })
            },
            targetRecipient = originator
        ))
    }

    // ========================================================================
    // Filesystem response handler (async completion)
    // ========================================================================

    private fun handleFileReadResponse(action: Action, store: Store) {
        val path = action.payload?.get("path")?.jsonPrimitive?.contentOrNull ?: return // Not our response (no path)
        val content = action.payload?.get("content")?.jsonPrimitive?.contentOrNull

        // Handle scripts config file response
        if (path == scriptsConfigFile) {
            handleScriptsConfigLoaded(content, store)
            return
        }

        // Find the pending op that matches this file path — if none, this response isn't for us
        val pendingOp = findPendingOpByPath(path, store) ?: return

        // Clean up the pending op
        removePendingOp(store, pendingOp.correlationId)

        if (content == null) {
            dispatchScriptError(pendingOp.scriptHandle, "File not found: $path", pendingOp.opType)
            return
        }

        when (pendingOp.opType) {
            "load" -> {
                val localHandle = pendingOp.extra["localHandle"]
                    ?: return logMissingField("FILESYSTEM_RETURN_READ[load]", "localHandle in pendingOp")
                val handle = pendingOp.scriptHandle

                // Check if script was toggled off between the READ dispatch and response
                val currentState = store.state.value.featureStates[identity.handle] as? LuaState
                val scriptInfo = currentState?.scripts?.get(handle)
                if (scriptInfo?.status == ScriptStatus.STOPPED) {
                    // Script toggled inactive — store source content but don't load into runtime
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.LUA_SCRIPT_LOADED,
                        payload = buildJsonObject {
                            put("scriptHandle", handle)
                            put("scriptName", localHandle)
                            put("scriptPath", path)
                            put("sourceContent", content)
                        }
                    ))
                    // Immediately set back to STOPPED (SCRIPT_LOADED sets RUNNING)
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.LUA_TOGGLE_SCRIPT,
                        payload = buildJsonObject { put("scriptHandle", handle) }
                    ))
                    platformDependencies.log(LogLevel.INFO, identity.handle, "Script discovered (inactive): $handle")
                } else {
                    val result = runtime.loadScript(handle, localHandle, content)
                    if (result.success) {
                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.LUA_SCRIPT_LOADED,
                            payload = buildJsonObject {
                                put("scriptHandle", handle)
                                put("scriptName", localHandle)
                                put("scriptPath", path)
                                put("sourceContent", content)
                            }
                        ))
                        platformDependencies.log(LogLevel.INFO, identity.handle, "Script loaded: $handle")
                    } else {
                        dispatchScriptErrorWithSource(handle, result.error ?: "Unknown error", "load", content)
                    }
                }
            }

            "reload" -> {
                val handle = pendingOp.scriptHandle
                val localHandle = pendingOp.extra["localHandle"]
                    ?: return logMissingField("FILESYSTEM_RETURN_READ[reload]", "localHandle in pendingOp")
                runtime.unloadScript(handle)
                val result = runtime.loadScript(handle, localHandle, content)
                if (result.success) {
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.LUA_SCRIPT_LOADED,
                        payload = buildJsonObject {
                            put("scriptHandle", handle)
                            put("scriptName", localHandle)
                            put("scriptPath", path)
                            put("sourceContent", content)
                        }
                    ))
                    platformDependencies.log(LogLevel.INFO, identity.handle, "Script reloaded: $handle")
                } else {
                    dispatchScriptErrorWithSource(handle, result.error ?: "Reload error", "load", content)
                }
            }

            "clone-read" -> {
                val cloneLocalHandle = pendingOp.extra["cloneLocalHandle"]
                    ?: return logMissingField("FILESYSTEM_RETURN_READ[clone]", "cloneLocalHandle in pendingOp")
                val cloneFileName = pendingOp.extra["cloneFileName"]
                    ?: return logMissingField("FILESYSTEM_RETURN_READ[clone]", "cloneFileName in pendingOp")

                // Write clone via FileSystem
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.FILESYSTEM_WRITE,
                    payload = buildJsonObject {
                        put("path", cloneFileName)
                        put("content", content)
                    }
                ))

                // Load the clone
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
                    payload = buildJsonObject {
                        put("scriptPath", cloneFileName)
                        put("localHandle", cloneLocalHandle)
                    }
                ))
            }
        }
    }

    // ========================================================================
    // Script config persistence
    // ========================================================================

    /**
     * Config file format:
     * ```json
     * {
     *   "scripts": [
     *     { "path": "logger.lua", "localHandle": "logger", "active": true },
     *     { "path": "helper.lua", "localHandle": "helper", "active": false }
     *   ]
     * }
     * ```
     */
    private fun handleScriptsConfigLoaded(content: String?, store: Store) {
        if (content != null) {
            try {
                val configJson = json.parseToJsonElement(content).jsonObject
                val scriptsArray = configJson["scripts"]?.jsonArray ?: JsonArray(emptyList())

                for (entry in scriptsArray) {
                    val obj = entry.jsonObject
                    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
                    val localHandle = obj["localHandle"]?.jsonPrimitive?.contentOrNull
                        ?: deriveLocalHandle(null, path)
                    val active = obj["active"]?.jsonPrimitive?.booleanOrNull ?: true

                    if (active) {
                        // Active scripts: full load (creates state entry + reads file + loads into runtime)
                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
                            payload = buildJsonObject {
                                put("scriptPath", path)
                                put("localHandle", localHandle)
                            }
                        ))
                    } else {
                        // Inactive scripts: add to state only (STOPPED status, no runtime load)
                        // We dispatch LOAD_SCRIPT to get the ScriptInfo into state, then the
                        // reducer sets it to LOADING. We immediately follow with a reducer-only
                        // state update to STOPPED. The LOAD_SCRIPT side effect will dispatch
                        // filesystem.READ, but we skip loading into runtime when toggle catches it.
                        //
                        // Simpler approach: directly build the ScriptInfo via an internal action.
                        // For now, use LOAD_SCRIPT which creates the state entry as LOADING,
                        // then the side effect fires filesystem.READ. When the file response
                        // arrives, we check the toggle state and skip runtime loading if STOPPED.
                        //
                        // Actually cleanest: just add to state as STOPPED without triggering load.
                        // We use a direct state manipulation via a synthetic action the reducer handles.
                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.LUA_LOAD_SCRIPT,
                            payload = buildJsonObject {
                                put("scriptPath", path)
                                put("localHandle", localHandle)
                                put("autostart", false)
                            }
                        ))
                        // Immediately toggle to STOPPED (reducer handles this synchronously
                        // before the async filesystem.READ response arrives)
                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.LUA_TOGGLE_SCRIPT,
                            payload = buildJsonObject { put("scriptHandle", "lua.$localHandle") }
                        ))
                    }
                }
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, identity.handle,
                    "Failed to parse $scriptsConfigFile: ${e.message}")
            }
        }

        // After loading config (or if no config file), discover new scripts on disk
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_LIST,
            payload = buildJsonObject {
                put("path", "")
                put("recursive", false)
                put("correlationId", DISCOVER_CORRELATION_ID)
            }
        ))
    }

    /**
     * Persists the current script configuration to disk.
     * Called after any change that affects the script list or active state.
     */
    private fun persistScriptsConfig(store: Store) {
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return
        val configJson = buildJsonObject {
            put("scripts", buildJsonArray {
                currentState.scripts.values.forEach { script ->
                    add(buildJsonObject {
                        put("path", script.path)
                        put("localHandle", script.localHandle)
                        put("active", script.status == ScriptStatus.RUNNING || script.status == ScriptStatus.LOADING)
                    })
                }
            })
        }
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.FILESYSTEM_WRITE,
            payload = buildJsonObject {
                put("path", scriptsConfigFile)
                put("content", json.encodeToString(configJson))
            }
        ))
    }

    // ========================================================================
    // Event delivery to scripts
    // ========================================================================

    private fun deliverBroadcastToScripts(action: Action) {
        // Buffer ALL broadcast actions for raam.actionbus.retrieve() — even lua.* ones
        actionBuffer.addLast(ActionBufferEntry(
            name = action.name,
            originator = action.originator,
            timestamp = platformDependencies.currentTimeMillis()
        ))
        if (actionBuffer.size > actionBufferMax) actionBuffer.removeFirst()

        if (action.name.startsWith("lua.")) return
        if (currentCascadeDepth >= maxCascadeDepth) {
            platformDependencies.log(
                LogLevel.WARN, identity.handle,
                "Cascade depth limit ($maxCascadeDepth) reached, skipping event delivery for ${action.name}"
            )
            return
        }

        val payloadMap = action.payload?.let { jsonObjectToMap(it) }
        currentCascadeDepth++
        try {
            val errors = runtime.deliverEvent(action.name, payloadMap, action.originator)
            for (errorHandle in errors) {
                dispatchScriptError(errorHandle, "Callback timed out or errored for ${action.name}", "callback")
                runtime.unloadScript(errorHandle)
                logSubscriptions.removeAll { it.scriptHandle == errorHandle }
            }
        } finally {
            currentCascadeDepth--
        }
    }

    // ========================================================================
    // Bridge Listener (Lua → Kotlin)
    // ========================================================================

    private fun forwardLogToSubscribedScripts(level: LogLevel, tag: String, message: String, timestamp: Long) {
        if (logSubscriptions.isEmpty()) return
        // Check if any subscription would match this level before scheduling
        val hasMatch = logSubscriptions.any { level >= it.minLevel }
        if (!hasMatch) return
        // Marshal off the log-listener thread via scheduleDelayed(0) — same pattern as onScriptDelay.
        // This avoids routing through the action bus (which would require a registered action descriptor).
        val levelName = level.name
        platformDependencies.scheduleDelayed(0) {
            val errors = runtime.deliverLogEvent(levelName, tag, message, timestamp)
            for (errorHandle in errors) {
                dispatchScriptError(errorHandle, "Log listener callback timed out or errored", "callback")
                runtime.unloadScript(errorHandle)
                logSubscriptions.removeAll { it.scriptHandle == errorHandle }
            }
        }
    }

    private fun createBridgeListener(): LuaBridgeListener {
        return object : LuaBridgeListener {
            override fun onScriptDispatch(
                scriptHandle: String,
                actionName: String,
                payload: Map<String, Any?>?
            ): Pair<Boolean, String?> {
                val jsonPayload = payload?.let { mapToJsonObject(it) }
                store.deferredDispatch(scriptHandle, Action(
                    name = actionName,
                    payload = jsonPayload
                ))
                return true to null
            }

            override fun onScriptLog(scriptHandle: String, level: String, message: String) {
                platformDependencies.log(
                    when (level) {
                        "error" -> LogLevel.ERROR
                        "warn" -> LogLevel.WARN
                        else -> LogLevel.INFO
                    },
                    scriptHandle,
                    message
                )
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                    payload = buildJsonObject {
                        put("scriptHandle", scriptHandle)
                        put("level", level)
                        put("message", message)
                        put("timestamp", platformDependencies.currentTimeMillis())
                    }
                ))
            }

            override fun onScriptLogSubscribe(scriptHandle: String, minLevel: String, callbackId: Long) {
                val level = try { LogLevel.valueOf(minLevel) } catch (_: Exception) { LogLevel.DEBUG }
                logSubscriptions.add(LogSubscriptionEntry(scriptHandle, level, callbackId))
            }

            override fun onScriptDelay(scriptHandle: String, delayMs: Long, callbackId: Long) {
                platformDependencies.scheduleDelayed(delayMs) {
                    val result = runtime.executeDelayedCallback(scriptHandle, callbackId)
                    if (!result.success) {
                        dispatchScriptError(scriptHandle, result.error ?: "Delayed callback error", "callback")
                    }
                }
            }

            override fun getIdentities(): List<LuaIdentitySnapshot> {
                return store.state.value.identityRegistry.values.map {
                    LuaIdentitySnapshot(handle = it.handle, name = it.name, parentHandle = it.parentHandle)
                }
            }

            override fun getScriptPermissions(scriptHandle: String): Map<String, String> {
                val id = store.state.value.identityRegistry[scriptHandle] ?: return emptyMap()
                return store.resolveEffectivePermissions(id).mapValues { (_, grant) -> grant.level.name }
            }

            override fun getCurrentTimeMillis(): Long {
                return platformDependencies.currentTimeMillis()
            }

            override fun getActionDescriptors(): List<LuaActionDescriptor> {
                return store.state.value.actionDescriptors.values.map { desc ->
                    LuaActionDescriptor(
                        name = desc.fullName,
                        featureName = desc.featureName,
                        summary = desc.summary,
                        isPublic = desc.`public`
                    )
                }
            }

            override fun onScriptConsolePrint(scriptHandle: String, message: String, color: String?, bold: Boolean, italic: Boolean) {
                // Log to application log
                platformDependencies.log(LogLevel.INFO, scriptHandle, message)

                // Write styled entry to console buffer via action
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                    payload = buildJsonObject {
                        put("scriptHandle", scriptHandle)
                        put("level", "log")
                        put("message", message)
                        put("timestamp", platformDependencies.currentTimeMillis())
                        if (bold) put("bold", true)
                        if (italic) put("italic", true)
                        if (color != null) put("color", color)
                    }
                ))
            }

            override fun onScriptConsoleClear(scriptHandle: String) {
                platformDependencies.log(LogLevel.DEBUG, scriptHandle, "Console cleared")
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                    payload = buildJsonObject {
                        put("scriptHandle", scriptHandle)
                        put("level", "clear")
                        put("message", "")
                        put("timestamp", platformDependencies.currentTimeMillis())
                    }
                ))
            }

            override fun getActionHistory(limit: Int, feature: String?): List<ActionBufferEntry> {
                val filtered = if (feature != null) {
                    actionBuffer.filter { it.name.startsWith("$feature.") }
                } else {
                    actionBuffer.toList()
                }
                return filtered.takeLast(limit)
            }

            override fun getLogHistory(limit: Int, minLevel: String, tag: String?): List<LogBufferEntry> {
                val level = try { LogLevel.valueOf(minLevel) } catch (_: Exception) { LogLevel.DEBUG }
                val logs = platformDependencies.getRecentLogs(limit = 1000, minLevel = level)
                val filtered = if (tag != null) {
                    logs.filter { it.tag.contains(tag, ignoreCase = true) }
                } else {
                    logs
                }
                return filtered.takeLast(limit)
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun deriveLocalHandle(explicit: String?, scriptPath: String): String {
        return explicit ?: scriptPath.substringAfterLast("/").substringBeforeLast(".lua")
            .lowercase().replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-").trimStart('-').trimEnd('-')
            .ifEmpty { "unnamed" }
    }

    private fun trackPendingOp(store: Store, op: PendingFileOp) {
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return
        // Direct state update via a deferred internal mechanism —
        // pendingFileOps is transient tracking, managed in side effects.
        // We store it in-memory on the feature instance instead.
        pendingOps[op.correlationId] = op
    }

    private fun removePendingOp(store: Store, correlationId: String) {
        pendingOps.remove(correlationId)
    }

    /** In-memory pending operation tracking (not persisted in FeatureState). */
    private val pendingOps = mutableMapOf<String, PendingFileOp>()

    /** Override to look up pending ops from in-memory map instead of state. */
    private fun findPendingOpByPath(path: String, store: Store): PendingFileOp? {
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState
        return pendingOps.values.find { op ->
            when (op.opType) {
                "load" -> op.extra["scriptPath"] == path
                "reload" -> currentState?.scripts?.get(op.scriptHandle)?.path == path
                "clone-read" -> currentState?.scripts?.get(op.scriptHandle)?.path == path
                else -> false
            }
        }
    }

    private fun logMissingPayload(action: String) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "$action: missing payload")
    }

    private fun logMissingField(action: String, field: String) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "$action: missing or invalid field '$field'")
    }

    private fun logMissingState(action: String) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "$action: LuaState not available")
    }

    private fun logUnknownScript(action: String, handle: String) {
        platformDependencies.log(LogLevel.WARN, identity.handle, "$action: unknown script '$handle'")
    }

    private fun dispatchScriptError(handle: String, error: String, context: String) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "Script error [$handle]: $error")
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_SCRIPT_ERROR,
            payload = buildJsonObject {
                put("scriptHandle", handle)
                put("error", error)
                put("context", context)
            }
        ))
    }

    private fun dispatchScriptErrorWithSource(handle: String, error: String, context: String, sourceContent: String) {
        platformDependencies.log(LogLevel.ERROR, identity.handle, "Script error [$handle]: $error")
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.LUA_SCRIPT_ERROR,
            payload = buildJsonObject {
                put("scriptHandle", handle)
                put("error", error)
                put("context", context)
                put("sourceContent", sourceContent)
            }
        ))
    }

    private fun jsonObjectToMap(json: JsonObject): Map<String, Any?> {
        return json.mapValues { (_, element) -> jsonElementToAny(element) }
    }

    private fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                element.content.contains('.') -> element.content.toDoubleOrNull()
                else -> element.content.toLongOrNull() ?: element.content
            }
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        }
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            for ((key, value) in map) {
                put(key, anyToJsonElement(value))
            }
        }
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                @Suppress("UNCHECKED_CAST")
                for ((k, v) in value as Map<String, Any?>) {
                    put(k, anyToJsonElement(v))
                }
            }
            is List<*> -> buildJsonArray {
                for (item in value) { add(anyToJsonElement(item)) }
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
