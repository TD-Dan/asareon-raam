package asareon.raam.feature.lua

/**
 * Stub implementation for WebAssembly. Lua scripting is not available on this platform.
 */
actual class LuaRuntime actual constructor(config: LuaRuntimeConfig) {

    actual val isAvailable: Boolean = false

    actual fun setBridgeListener(listener: LuaBridgeListener) {}

    actual fun loadScript(scriptHandle: String, scriptName: String, sourceCode: String): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (WASM)")
    }

    actual fun unloadScript(scriptHandle: String) {}

    actual fun eval(scriptHandle: String, code: String): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (WASM)")
    }

    actual fun deliverEvent(actionName: String, payload: Map<String, Any?>?): List<String> = emptyList()

    actual fun executeTurn(scriptHandle: String, context: Map<String, Any?>): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (WASM)")
    }

    actual fun executeDelayedCallback(scriptHandle: String, callbackId: Long): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (WASM)")
    }

    actual fun isScriptLoaded(scriptHandle: String): Boolean = false

    actual fun getSubscriptions(scriptHandle: String): List<String> = emptyList()
}
