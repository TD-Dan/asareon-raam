package asareon.raam.feature.lua

/**
 * The result of executing a Lua script or callback.
 */
data class LuaExecutionResult(
    val success: Boolean,
    val returnValue: Map<String, Any?>? = null,
    val error: String? = null
)

/**
 * A callback registration handle, used for unsubscription.
 */
data class LuaSubscription(
    val id: Long,
    val scriptHandle: String,
    val actionPattern: String
)

/**
 * Configuration for the sandboxed Lua runtime.
 */
data class LuaRuntimeConfig(
    /** Maximum milliseconds a single callback invocation may run. */
    val callbackTimeoutMs: Long = 500L,
    /** Maximum milliseconds an on_turn invocation may run. */
    val turnTimeoutMs: Long = 5000L,
    /** Maximum dispatches allowed per single callback invocation. */
    val maxDispatchesPerCallback: Int = 50,
    /** Maximum dispatches allowed per second per script. */
    val maxDispatchesPerSecond: Int = 200
)

/**
 * Listener interface for the Lua bridge to communicate back to the feature layer.
 * Implemented by LuaFeature to handle dispatches, logging, and timer requests
 * originating from Lua scripts.
 */
interface LuaBridgeListener {
    /**
     * Called when a script invokes raam.dispatch(actionName, payload).
     * The feature layer stamps the originator and calls store.deferredDispatch().
     * @return Pair(success, errorMessage?)
     */
    fun onScriptDispatch(scriptHandle: String, actionName: String, payload: Map<String, Any?>?): Pair<Boolean, String?>

    /**
     * Called when a script invokes raam.log/warn/error.
     */
    fun onScriptLog(scriptHandle: String, level: String, message: String)

    /**
     * Called when a script invokes raam.delay(ms, callback).
     * The feature layer schedules the callback execution.
     */
    fun onScriptDelay(scriptHandle: String, delayMs: Long, callbackId: Long)

    /**
     * Returns the identity registry as a list of public-safe identity snapshots.
     */
    fun getIdentities(): List<LuaIdentitySnapshot>

    /**
     * Returns the effective permissions for a script identity.
     */
    fun getScriptPermissions(scriptHandle: String): Map<String, String>
}

/**
 * A public-safe snapshot of an identity for Lua scripts.
 */
data class LuaIdentitySnapshot(
    val handle: String,
    val name: String,
    val parentHandle: String?
)

/**
 * Platform-agnostic contract for the Lua scripting runtime.
 * Each platform provides its own actual implementation.
 *
 * The runtime manages sandboxed Lua environments, one per loaded script,
 * with the `raam.*` API table injected for action bus interaction.
 *
 * ## Security Contract
 * Implementations MUST:
 * - Exclude os, io, debug, luajava, package standard libraries
 * - Enforce execution time limits on all Lua invocations
 * - Convert all data to Lua-native types (no Java/platform object leakage)
 * - Enforce dispatch rate limits via the bridge listener
 */
expect class LuaRuntime(config: LuaRuntimeConfig = LuaRuntimeConfig()) {

    /** Whether this platform supports Lua execution. */
    val isAvailable: Boolean

    /**
     * Sets the bridge listener that handles dispatches, logging, and timers.
     * Must be set before loading any scripts.
     */
    fun setBridgeListener(listener: LuaBridgeListener)

    /**
     * Load and execute a Lua script, creating a new sandboxed environment.
     * The script's global scope receives the `raam.*` table.
     *
     * @param scriptHandle The full bus handle (e.g., "lua.my-script")
     * @param scriptName The display name of the script
     * @param sourceCode The Lua source code to load
     * @return Result indicating success or failure with error message
     */
    fun loadScript(scriptHandle: String, scriptName: String, sourceCode: String): LuaExecutionResult

    /**
     * Unload a script, destroying its environment and removing all subscriptions.
     */
    fun unloadScript(scriptHandle: String)

    /**
     * Execute a code string in an existing script's environment (REPL/eval).
     */
    fun eval(scriptHandle: String, code: String): LuaExecutionResult

    /**
     * Deliver a broadcast action to all scripts that have registered handlers
     * matching the given action name.
     *
     * @param actionName The full action name (e.g., "session.MESSAGE_ADDED")
     * @param payload The action payload as a string-keyed map
     * @return List of script handles that errored during delivery
     */
    fun deliverEvent(actionName: String, payload: Map<String, Any?>?): List<String>

    /**
     * Execute a script's on_turn() function for the CognitiveStrategy use case.
     *
     * @param scriptHandle The script to invoke
     * @param context The turn context (messages, state, resources, sessions)
     * @return Execution result containing response, state updates, and optional actions
     */
    fun executeTurn(scriptHandle: String, context: Map<String, Any?>): LuaExecutionResult

    /**
     * Execute a delayed callback that was scheduled via raam.delay().
     */
    fun executeDelayedCallback(scriptHandle: String, callbackId: Long): LuaExecutionResult

    /**
     * Check if a script is currently loaded.
     */
    fun isScriptLoaded(scriptHandle: String): Boolean

    /**
     * Get the list of action patterns a script has subscribed to.
     */
    fun getSubscriptions(scriptHandle: String): List<String>
}
