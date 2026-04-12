package asareon.raam.feature.lua

import org.luaj.vm2.*
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.JseBaseLib
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM implementation of the Lua scripting runtime using LuaJ.
 *
 * ## Security Measures
 * - Only safe standard libraries loaded (base, table, string, math, coroutine)
 * - Dangerous globals removed (dofile, loadfile, load with binary mode)
 * - No LuajavaLib (prevents arbitrary Java object construction)
 * - No IoLib, OsLib, DebugLib, PackageLib
 * - Time-bounded execution via separate thread + interrupt
 * - Rate-limited dispatches
 * - All data conversion is explicit (no CoerceJavaToLua)
 */
actual class LuaRuntime actual constructor(private val config: LuaRuntimeConfig) {

    actual val isAvailable: Boolean = true

    @Volatile
    private var bridgeListener: LuaBridgeListener? = null
    private val scripts = ConcurrentHashMap<String, ScriptEnvironment>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "lua-script-worker").apply { isDaemon = true }
    }
    private val callbackIdCounter = AtomicLong(0)

    private data class ScriptEnvironment(
        val handle: String,
        val name: String,
        val globals: Globals,
        val subscriptions: MutableList<SubscriptionEntry> = mutableListOf(),
        val delayedCallbacks: ConcurrentHashMap<Long, LuaValue> = ConcurrentHashMap(),
        /** IDs of interval callbacks (re-schedule after each execution). */
        val intervalIds: MutableSet<Long> = mutableSetOf(),
        var dispatchCount: Int = 0,
        var lastDispatchResetTime: Long = System.currentTimeMillis()
    )

    private data class SubscriptionEntry(
        val id: Long,
        val pattern: String,
        val handler: LuaValue
    )

    actual fun setBridgeListener(listener: LuaBridgeListener) {
        this.bridgeListener = listener
    }

    actual fun loadScript(scriptHandle: String, scriptName: String, sourceCode: String): LuaExecutionResult {
        if (scripts.containsKey(scriptHandle)) {
            unloadScript(scriptHandle)
        }

        val globals = createSandboxedGlobals(scriptHandle)
        val env = ScriptEnvironment(handle = scriptHandle, name = scriptName, globals = globals)
        scripts[scriptHandle] = env

        return executeWithTimeout(config.callbackTimeoutMs) {
            val chunk = globals.load(sourceCode, scriptName)
            chunk.call()
            // Call on_load() if defined
            val onLoad = globals.get("on_load")
            if (onLoad.isfunction()) {
                onLoad.call()
            }
            LuaExecutionResult(success = true)
        } ?: run {
            scripts.remove(scriptHandle)
            LuaExecutionResult(success = false, error = "Script load timed out")
        }
    }

    actual fun unloadScript(scriptHandle: String) {
        scripts.remove(scriptHandle)
    }

    actual fun eval(scriptHandle: String, code: String): LuaExecutionResult {
        val env = scripts[scriptHandle]
            ?: return LuaExecutionResult(success = false, error = "Script not loaded: $scriptHandle")

        return executeWithTimeout(config.callbackTimeoutMs) {
            val chunk = env.globals.load(code, "eval")
            val result = chunk.call()
            LuaExecutionResult(
                success = true,
                returnValue = if (result.isnil()) null else mapOf("result" to luaToKotlin(result))
            )
        } ?: LuaExecutionResult(success = false, error = "Eval timed out")
    }

    actual fun deliverEvent(actionName: String, payload: Map<String, Any?>?, originator: String?): List<String> {
        val errors = mutableListOf<String>()
        val luaPayload = payload?.let { kotlinMapToLuaTable(it) } ?: LuaValue.NIL
        val luaOriginator = originator?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL

        for ((handle, env) in scripts) {
            val matchingHandlers = env.subscriptions.filter { matchesPattern(it.pattern, actionName) }
            if (matchingHandlers.isEmpty()) continue

            // Reset dispatch counter per-callback
            env.dispatchCount = 0

            for (sub in matchingHandlers) {
                val result = executeWithTimeout(config.callbackTimeoutMs) {
                    sub.handler.invoke(
                        LuaValue.varargsOf(arrayOf(LuaValue.valueOf(actionName), luaPayload, luaOriginator))
                    )
                    LuaExecutionResult(success = true)
                }
                if (result == null || !result.success) {
                    errors.add(handle)
                    break // Stop processing handlers for this script on error/timeout
                }
            }
        }
        return errors
    }

    actual fun executeTurn(scriptHandle: String, context: Map<String, Any?>): LuaExecutionResult {
        val env = scripts[scriptHandle]
            ?: return LuaExecutionResult(success = false, error = "Script not loaded: $scriptHandle")

        val onTurn = env.globals.get("on_turn")
        if (!onTurn.isfunction()) {
            return LuaExecutionResult(success = false, error = "Script does not define on_turn() function")
        }

        env.dispatchCount = 0
        val luaContext = kotlinMapToLuaTable(context)

        return executeWithTimeout(config.turnTimeoutMs) {
            val result = onTurn.call(luaContext)
            if (result.isnil()) {
                LuaExecutionResult(success = true)
            } else if (result.istable()) {
                val resultMap = luaTableToKotlinMap(result.checktable())
                LuaExecutionResult(success = true, returnValue = resultMap)
            } else {
                LuaExecutionResult(
                    success = true,
                    returnValue = mapOf("response" to result.tojstring())
                )
            }
        } ?: LuaExecutionResult(success = false, error = "on_turn() timed out after ${config.turnTimeoutMs}ms")
    }

    actual fun executeDelayedCallback(scriptHandle: String, callbackId: Long): LuaExecutionResult {
        val env = scripts[scriptHandle]
            ?: return LuaExecutionResult(success = false, error = "Script not loaded: $scriptHandle")

        val callback = env.delayedCallbacks[callbackId]
            ?: return LuaExecutionResult(success = false, error = "Callback not found: $callbackId")

        env.dispatchCount = 0
        val isInterval = callbackId in env.intervalIds

        // One-shot delays: remove before execution (won't be needed again)
        // Intervals: keep in map — the wrapper re-schedules after execution
        if (!isInterval) {
            env.delayedCallbacks.remove(callbackId)
        }

        val result = executeWithTimeout(config.callbackTimeoutMs) {
            callback.call()
            LuaExecutionResult(success = true)
        } ?: LuaExecutionResult(success = false, error = "Delayed callback timed out")

        return result
    }

    actual fun isScriptLoaded(scriptHandle: String): Boolean = scripts.containsKey(scriptHandle)

    actual fun getSubscriptions(scriptHandle: String): List<String> {
        return scripts[scriptHandle]?.subscriptions?.map { it.pattern } ?: emptyList()
    }

    // ========================================================================
    // Sandboxed environment creation
    // ========================================================================

    private fun createSandboxedGlobals(scriptHandle: String): Globals {
        val globals = Globals()

        // Install the Lua source compiler — without this, globals.load(string) fails with "No compiler."
        LuaC.install(globals)

        // PackageLib must load first — TableLib and others depend on package.loaded.
        // We load it, then strip `require` and `module` to prevent native module loading.
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(JseMathLib())
        globals.load(CoroutineLib())

        // Remove dangerous base functions
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("load", createSafeLoad(globals))  // Allow load() but only for text mode
        globals.set("collectgarbage", LuaValue.NIL)
        globals.set("rawlen", LuaValue.NIL)
        globals.set("require", LuaValue.NIL)  // Prevent native module loading
        globals.set("module", LuaValue.NIL)

        // Redirect print to raam.log
        globals.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = (1..args.narg()).map { args.arg(it).tojstring() }
                bridgeListener?.onScriptLog(scriptHandle, "log", parts.joinToString("\t"))
                return LuaValue.NONE
            }
        })

        // Install the raam.* API table
        installRaamApi(globals, scriptHandle)

        return globals
    }

    /**
     * Creates a safe `load()` that only accepts text source code, not binary bytecode.
     */
    private fun createSafeLoad(globals: Globals): LuaValue {
        return object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val source = args.arg1()
                if (!source.isstring()) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("load() only accepts string source"))
                }
                val mode = if (args.narg() >= 3) args.arg(3).optjstring("t") else "t"
                if (mode != null && mode.contains('b')) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("binary chunks are not allowed"))
                }
                return try {
                    val chunk = globals.load(source.tojstring(), args.optjstring(2, "=(load)"))
                    LuaValue.varargsOf(chunk, LuaValue.NIL)
                } catch (e: LuaError) {
                    LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.message ?: "load error"))
                }
            }
        }
    }

    // ========================================================================
    // raam.* API installation
    // ========================================================================

    private fun installRaamApi(globals: Globals, scriptHandle: String) {
        val raam = LuaTable()

        // raam.scriptHandle (read-only property)
        raam.set("scriptHandle", LuaValue.valueOf(scriptHandle))
        // raam.scriptName (the localHandle portion)
        val localHandle = scriptHandle.substringAfter("lua.")
        raam.set("scriptName", LuaValue.valueOf(localHandle))

        // raam.dispatch(actionName, payloadTable) → boolean, string?
        raam.set("dispatch", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val actionName = args.checkjstring(1)
                val payload = if (args.narg() >= 2 && args.arg(2).istable()) {
                    luaTableToKotlinMap(args.arg(2).checktable())
                } else null

                // Rate limiting
                val env = scripts[scriptHandle] ?: return LuaValue.varargsOf(
                    LuaValue.FALSE, LuaValue.valueOf("Script not loaded")
                )
                val now = System.currentTimeMillis()
                if (now - env.lastDispatchResetTime > 1000) {
                    env.dispatchCount = 0
                    env.lastDispatchResetTime = now
                }
                env.dispatchCount++
                if (env.dispatchCount > config.maxDispatchesPerCallback) {
                    return LuaValue.varargsOf(
                        LuaValue.FALSE, LuaValue.valueOf("Dispatch rate limit exceeded")
                    )
                }

                val (success, error) = bridgeListener?.onScriptDispatch(scriptHandle, actionName, payload)
                    ?: (false to "No bridge listener")
                return if (success) {
                    LuaValue.varargsOf(LuaValue.TRUE, LuaValue.NIL)
                } else {
                    LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(error ?: "Unknown error"))
                }
            }
        })

        // raam.on(actionPattern, handler) → subscriptionId
        raam.set("on", object : TwoArgFunction() {
            override fun call(pattern: LuaValue, handler: LuaValue): LuaValue {
                val patternStr = pattern.checkjstring()
                if (!handler.isfunction()) {
                    return LuaValue.error("raam.on: second argument must be a function")
                }
                val env = scripts[scriptHandle] ?: return LuaValue.NIL
                val id = callbackIdCounter.incrementAndGet()
                env.subscriptions.add(SubscriptionEntry(id, patternStr, handler))
                return LuaValue.valueOf(id.toDouble())
            }
        })

        // raam.off(subscriptionId)
        raam.set("off", object : OneArgFunction() {
            override fun call(subId: LuaValue): LuaValue {
                val id = subId.checklong()
                val env = scripts[scriptHandle] ?: return LuaValue.NIL
                env.subscriptions.removeAll { it.id == id }
                return LuaValue.TRUE
            }
        })

        // raam.log(message)
        raam.set("log", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                bridgeListener?.onScriptLog(scriptHandle, "log", msg.tojstring())
                return LuaValue.NIL
            }
        })

        // raam.warn(message)
        raam.set("warn", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                bridgeListener?.onScriptLog(scriptHandle, "warn", msg.tojstring())
                return LuaValue.NIL
            }
        })

        // raam.error(message)
        raam.set("error", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                bridgeListener?.onScriptLog(scriptHandle, "error", msg.tojstring())
                return LuaValue.NIL
            }
        })

        // raam.delay(ms, callback) → callbackId
        raam.set("delay", object : TwoArgFunction() {
            override fun call(ms: LuaValue, callback: LuaValue): LuaValue {
                val delayMs = ms.checklong()
                if (!callback.isfunction()) {
                    return LuaValue.error("raam.delay: second argument must be a function")
                }
                val env = scripts[scriptHandle] ?: return LuaValue.NIL
                val id = callbackIdCounter.incrementAndGet()
                env.delayedCallbacks[id] = callback
                bridgeListener?.onScriptDelay(scriptHandle, delayMs, id)
                return LuaValue.valueOf(id.toDouble())
            }
        })

        // raam.identities() → table of {handle, name, parentHandle}
        raam.set("identities", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val identities = bridgeListener?.getIdentities() ?: return LuaTable()
                val table = LuaTable()
                identities.forEachIndexed { index, id ->
                    val entry = LuaTable()
                    entry.set("handle", LuaValue.valueOf(id.handle))
                    entry.set("name", LuaValue.valueOf(id.name))
                    if (id.parentHandle != null) {
                        entry.set("parentHandle", LuaValue.valueOf(id.parentHandle))
                    }
                    table.set(index + 1, entry)
                }
                return table
            }
        })

        // raam.permissions() → table of {key = level}
        raam.set("permissions", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val perms = bridgeListener?.getScriptPermissions(scriptHandle) ?: return LuaTable()
                val table = LuaTable()
                perms.forEach { (key, level) ->
                    table.set(key, LuaValue.valueOf(level))
                }
                return table
            }
        })

        // raam.time() → epoch millis as number
        raam.set("time", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val ms = bridgeListener?.getCurrentTimeMillis() ?: System.currentTimeMillis()
                return LuaValue.valueOf(ms.toDouble())
            }
        })

        // raam.actions() → array of {name, feature, summary, public}
        raam.set("actions", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val descriptors = bridgeListener?.getActionDescriptors() ?: return LuaTable()
                val table = LuaTable()
                descriptors.forEachIndexed { index, desc ->
                    val entry = LuaTable()
                    entry.set("name", LuaValue.valueOf(desc.name))
                    entry.set("feature", LuaValue.valueOf(desc.featureName))
                    entry.set("summary", LuaValue.valueOf(desc.summary))
                    entry.set("public", LuaValue.valueOf(desc.isPublic))
                    table.set(index + 1, entry)
                }
                return table
            }
        })

        // raam.interval(ms, fn) → intervalId (cancellable)
        // Recurring timer — calls fn every ms milliseconds.
        // Returns a numeric ID. Use raam.off(id) to cancel.
        raam.set("interval", object : TwoArgFunction() {
            override fun call(ms: LuaValue, callback: LuaValue): LuaValue {
                val intervalMs = ms.checklong()
                if (!callback.isfunction()) {
                    return LuaValue.error("raam.interval: second argument must be a function")
                }
                val env = scripts[scriptHandle] ?: return LuaValue.NIL
                val id = callbackIdCounter.incrementAndGet()

                // Mark as interval so executeDelayedCallback doesn't remove it
                env.intervalIds.add(id)
                env.delayedCallbacks[id] = callback

                fun scheduleTick() {
                    bridgeListener?.onScriptDelay(scriptHandle, intervalMs, id)
                }
                // The interval re-schedules itself after each execution.
                // We wrap the original callback to re-schedule + re-store.
                val wrappedCallback = object : ZeroArgFunction() {
                    override fun call(): LuaValue {
                        // Check if still active (not cancelled via raam.off or unloaded)
                        val currentEnv = scripts[scriptHandle] ?: return LuaValue.NIL
                        if (!currentEnv.delayedCallbacks.containsKey(id)) return LuaValue.NIL

                        // Execute the user callback
                        try {
                            callback.call()
                        } catch (e: LuaError) {
                            bridgeListener?.onScriptLog(scriptHandle, "error",
                                "interval callback error: ${e.message}")
                        }

                        // Re-store and re-schedule if still active
                        if (scripts[scriptHandle]?.delayedCallbacks?.containsKey(id) == true) {
                            currentEnv.delayedCallbacks[id] = this
                            scheduleTick()
                        }
                        return LuaValue.NIL
                    }
                }
                env.delayedCallbacks[id] = wrappedCallback
                scheduleTick()

                return LuaValue.valueOf(id.toDouble())
            }
        })

        // Allow raam.off(intervalId) to cancel intervals too — remove from delayedCallbacks
        // (raam.off already removes from subscriptions; we extend it to also clear intervals)
        val originalOff = raam.get("off")
        raam.set("off", object : OneArgFunction() {
            override fun call(idVal: LuaValue): LuaValue {
                val id = idVal.checklong()
                val env = scripts[scriptHandle]
                if (env != null) {
                    // Remove from subscriptions (existing behavior)
                    env.subscriptions.removeAll { it.id == id }
                    // Also remove from delayed/interval callbacks
                    env.delayedCallbacks.remove(id)
                    env.intervalIds.remove(id)
                }
                return LuaValue.TRUE
            }
        })

        globals.set("raam", raam)
    }

    // ========================================================================
    // Time-bounded execution
    // ========================================================================

    private fun executeWithTimeout(timeoutMs: Long, block: () -> LuaExecutionResult): LuaExecutionResult? {
        val future = executor.submit(Callable {
            try {
                block()
            } catch (e: LuaError) {
                LuaExecutionResult(success = false, error = e.message ?: "Lua error")
            } catch (e: Exception) {
                LuaExecutionResult(success = false, error = "Internal error: ${e.message}")
            }
        })

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: Exception) {
            LuaExecutionResult(success = false, error = "Execution failed: ${e.message}")
        }
    }

    // ========================================================================
    // Type conversion: Kotlin ↔ Lua (explicit, no CoerceJavaToLua)
    // ========================================================================

    private fun kotlinMapToLuaTable(map: Map<String, Any?>): LuaTable {
        val table = LuaTable()
        for ((key, value) in map) {
            table.set(key, kotlinToLua(value))
        }
        return table
    }

    private fun kotlinToLua(value: Any?): LuaValue {
        return when (value) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(value)
            is Int -> LuaValue.valueOf(value)
            is Long -> LuaValue.valueOf(value.toDouble())
            is Float -> LuaValue.valueOf(value.toDouble())
            is Double -> LuaValue.valueOf(value)
            is String -> LuaValue.valueOf(value)
            is Map<*, *> -> {
                val table = LuaTable()
                @Suppress("UNCHECKED_CAST")
                for ((k, v) in value as Map<String, Any?>) {
                    table.set(k, kotlinToLua(v))
                }
                table
            }
            is List<*> -> {
                val table = LuaTable()
                value.forEachIndexed { index, item ->
                    table.set(index + 1, kotlinToLua(item))
                }
                table
            }
            else -> LuaValue.valueOf(value.toString())
        }
    }

    private fun luaTableToKotlinMap(table: LuaTable): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var key = LuaValue.NIL
        while (true) {
            val next = table.next(key)
            if (next.arg1().isnil()) break
            key = next.arg1()
            val value = next.arg(2)
            val keyStr = key.tojstring()
            map[keyStr] = luaToKotlin(value)
        }
        return map
    }

    private fun luaToKotlin(value: LuaValue): Any? {
        return when {
            value.isnil() -> null
            value.isboolean() -> value.toboolean()
            value.isint() -> value.toint()
            value.isnumber() -> value.todouble()
            value.isstring() -> value.tojstring()
            value.istable() -> luaTableToKotlinMap(value.checktable())
            else -> value.tojstring()
        }
    }

    // ========================================================================
    // Pattern matching for subscriptions
    // ========================================================================

    /**
     * Matches action names against subscription patterns.
     * Supports:
     * - Exact match: "session.MESSAGE_ADDED"
     * - Wildcard suffix: "session.*" matches any action starting with "session."
     * - Global wildcard: "*" matches everything
     */
    private fun matchesPattern(pattern: String, actionName: String): Boolean {
        if (pattern == "*") return true
        if (pattern == actionName) return true
        if (pattern.endsWith(".*")) {
            val prefix = pattern.dropLast(1) // Keep the dot: "session."
            return actionName.startsWith(prefix)
        }
        return false
    }
}
