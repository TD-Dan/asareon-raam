package asareon.raam.feature.lua

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.feature.agent.CognitiveStrategyRegistry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * Hosts and manages sandboxed Lua scripting environments. Scripts can dispatch actions,
 * subscribe to broadcast events, and serve as cognitive strategies for agents.
 *
 * ## Layer
 * L3 (Actors) — alongside AgentRuntime and CommandBot.
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
        displayColor = "#7C4DFF",  // Deep purple
        displayIcon = "code"
    )

    private val runtime = LuaRuntime()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Maximum console entries per script before oldest are dropped. */
    private val maxConsoleEntries = 200

    /** Re-entrancy guard: tracks scripts currently executing callbacks. */
    private val scriptsInCallback = mutableSetOf<String>()

    /** Cascade depth tracker for recursive dispatch detection. */
    private var currentCascadeDepth = 0
    private val maxCascadeDepth = 3

    private lateinit var store: Store

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        private val viewKey = "lua.script-manager"

        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { s, f -> LuaScriptManagerView(s, f) }
        )

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            IconButton(onClick = {
                store.dispatch("lua", Action(
                    ActionRegistry.Names.CORE_SET_ACTIVE_VIEW,
                    buildJsonObject { put("key", viewKey) }
                ))
            }) {
                Icon(
                    Icons.Default.Code,
                    "Lua Scripts",
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    override fun init(store: Store) {
        this.store = store
        runtime.setBridgeListener(createBridgeListener())

        // Register the Lua cognitive strategy for agents
        CognitiveStrategyRegistry.register(LuaStrategy(this))
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
                val localHandle = payload["localHandle"]?.jsonPrimitive?.contentOrNull
                    ?: scriptPath.substringAfterLast("/").substringBeforeLast(".lua")
                        .lowercase().replace(Regex("[^a-z0-9-]"), "-")
                        .replace(Regex("-+"), "-").trimStart('-').trimEnd('-')
                        .ifEmpty { "unnamed" }
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

            ActionRegistry.Names.LUA_UNLOAD_SCRIPT -> {
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
                val message = payload["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val timestamp = payload["timestamp"]?.jsonPrimitive?.longOrNull ?: 0

                val buffer = luaState.consoleBuffers.getOrElse(handle) { emptyList() }
                val newBuffer = (buffer + ConsoleEntry(level, message, timestamp)).takeLast(maxConsoleEntries)
                luaState.copy(consoleBuffers = luaState.consoleBuffers + (handle to newBuffer))
            }

            ActionRegistry.Names.LUA_SCRIPT_ERROR -> {
                val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return luaState
                val error = action.payload?.get("error")?.jsonPrimitive?.contentOrNull
                val script = luaState.scripts[handle] ?: return luaState
                luaState.copy(
                    scripts = luaState.scripts + (handle to script.copy(
                        status = ScriptStatus.ERRORED,
                        lastError = error
                    ))
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
            else -> {
                // Deliver broadcast events to scripts (if this action is broadcast)
                deliverBroadcastToScripts(action)
            }
        }
    }

    private fun handleLoadScript(action: Action, store: Store) {
        val payload = action.payload ?: return
        val scriptPath = payload["scriptPath"]?.jsonPrimitive?.contentOrNull ?: return
        val localHandle = payload["localHandle"]?.jsonPrimitive?.contentOrNull
            ?: scriptPath.substringAfterLast("/").substringBeforeLast(".lua")
                .lowercase().replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-").trimStart('-').trimEnd('-')
                .ifEmpty { "unnamed" }
        val handle = "lua.$localHandle"

        // Read the script file from workspace
        val sourceCode = try {
            platformDependencies.readFileContent(
                platformDependencies.getBasePathFor(asareon.raam.util.BasePath.APP_ZONE) +
                        "${platformDependencies.pathSeparator}lua${platformDependencies.pathSeparator}$scriptPath"
            )
        } catch (e: Exception) {
            dispatchScriptError(handle, "Failed to read script file: ${e.message}", "load")
            return
        }

        // Register script identity
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.CORE_REGISTER_IDENTITY,
            payload = buildJsonObject {
                put("name", localHandle)
                put("localHandle", localHandle)
            }
        ))

        // Execute in the Lua runtime
        val result = runtime.loadScript(handle, localHandle, sourceCode)
        if (result.success) {
            // Update state to RUNNING
            val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return
            val script = currentState.scripts[handle] ?: return
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_SCRIPT_LOADED,
                payload = buildJsonObject {
                    put("scriptHandle", handle)
                    put("scriptName", localHandle)
                    put("scriptPath", scriptPath)
                }
            ))

            platformDependencies.log(LogLevel.INFO, identity.handle, "Script loaded: $handle")
        } else {
            dispatchScriptError(handle, result.error ?: "Unknown error", "load")
        }
    }

    private fun handleUnloadScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return
        runtime.unloadScript(handle)

        // Unregister identity
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

    private fun handleReloadScript(action: Action, store: Store) {
        val handle = action.payload?.get("scriptHandle")?.jsonPrimitive?.contentOrNull ?: return
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return
        val script = currentState.scripts[handle] ?: return

        // Read fresh source
        val sourceCode = try {
            platformDependencies.readFileContent(
                platformDependencies.getBasePathFor(asareon.raam.util.BasePath.APP_ZONE) +
                        "${platformDependencies.pathSeparator}lua${platformDependencies.pathSeparator}${script.path}"
            )
        } catch (e: Exception) {
            dispatchScriptError(handle, "Failed to read script file: ${e.message}", "load")
            return
        }

        // Reload: unload then load fresh
        runtime.unloadScript(handle)
        val result = runtime.loadScript(handle, script.localHandle, sourceCode)
        if (result.success) {
            platformDependencies.log(LogLevel.INFO, identity.handle, "Script reloaded: $handle")
        } else {
            dispatchScriptError(handle, result.error ?: "Unknown error", "load")
        }
    }

    private fun handleEval(action: Action, store: Store) {
        val payload = action.payload ?: return
        val handle = payload["scriptHandle"]?.jsonPrimitive?.contentOrNull ?: return
        val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: return

        val result = runtime.eval(handle, code)
        if (!result.success) {
            dispatchScriptError(handle, result.error ?: "Eval error", "eval")
        } else if (result.returnValue != null) {
            // Log the return value
            val returnStr = result.returnValue.toString()
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                payload = buildJsonObject {
                    put("scriptHandle", handle)
                    put("level", "log")
                    put("message", "=> $returnStr")
                    put("timestamp", platformDependencies.currentTimeMillis())
                }
            ))
        }
    }

    private fun handleListScripts(action: Action, store: Store) {
        val currentState = store.state.value.featureStates[identity.handle] as? LuaState ?: return
        val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
        val originator = action.originator ?: return

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
    // Event delivery to scripts
    // ========================================================================

    private fun deliverBroadcastToScripts(action: Action) {
        // Only deliver broadcast actions (the Store already filtered non-broadcasts)
        // Skip lua's own internal actions to avoid feedback loops
        if (action.name.startsWith("lua.")) return

        // Re-entrancy / cascade guard
        if (currentCascadeDepth >= maxCascadeDepth) {
            platformDependencies.log(
                LogLevel.WARN, identity.handle,
                "Cascade depth limit ($maxCascadeDepth) reached, skipping event delivery for ${action.name}"
            )
            return
        }

        // Convert payload to a Kotlin map for the runtime
        val payloadMap = action.payload?.let { jsonObjectToMap(it) }

        currentCascadeDepth++
        try {
            val errors = runtime.deliverEvent(action.name, payloadMap)
            for (errorHandle in errors) {
                dispatchScriptError(errorHandle, "Callback timed out or errored for ${action.name}", "callback")
                runtime.unloadScript(errorHandle)
            }
        } finally {
            currentCascadeDepth--
        }
    }

    // ========================================================================
    // Bridge Listener (Lua → Kotlin)
    // ========================================================================

    private fun createBridgeListener(): LuaBridgeListener {
        return object : LuaBridgeListener {
            override fun onScriptDispatch(
                scriptHandle: String,
                actionName: String,
                payload: Map<String, Any?>?
            ): Pair<Boolean, String?> {
                // Re-entrancy guard
                if (scriptHandle in scriptsInCallback) {
                    // Script is dispatching from within a callback — use deferred dispatch
                }

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
                    LuaIdentitySnapshot(
                        handle = it.handle,
                        name = it.name,
                        parentHandle = it.parentHandle
                    )
                }
            }

            override fun getScriptPermissions(scriptHandle: String): Map<String, String> {
                val identity = store.state.value.identityRegistry[scriptHandle] ?: return emptyMap()
                val effective = store.resolveEffectivePermissions(identity)
                return effective.mapValues { (_, grant) -> grant.level.name }
            }
        }
    }

    // ========================================================================
    // Public API for LuaStrategy
    // ========================================================================

    /**
     * Execute a script's on_turn() function. Called by LuaStrategy.
     */
    fun executeTurn(scriptHandle: String, context: Map<String, Any?>): LuaExecutionResult {
        return runtime.executeTurn(scriptHandle, context)
    }

    /**
     * Load a script programmatically (used by LuaStrategy for agent scripts).
     */
    fun loadScriptDirect(handle: String, name: String, sourceCode: String): LuaExecutionResult {
        return runtime.loadScript(handle, name, sourceCode)
    }

    /**
     * Unload a script programmatically.
     */
    fun unloadScriptDirect(handle: String) {
        runtime.unloadScript(handle)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

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
                for (item in value) {
                    add(anyToJsonElement(item))
                }
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
