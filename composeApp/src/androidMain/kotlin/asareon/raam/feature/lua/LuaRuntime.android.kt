package asareon.raam.feature.lua

/**
 * Stub implementation for Android. Lua scripting is not available on this platform yet.
 * LuaJ could work here (it's pure Java) — this stub can be replaced with a real
 * implementation when Android support is needed.
 */
actual class LuaRuntime actual constructor(config: LuaRuntimeConfig) {

    actual val isAvailable: Boolean = false

    actual fun setBridgeListener(listener: LuaBridgeListener) {}

    actual fun loadScript(scriptHandle: String, scriptName: String, sourceCode: String): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (Android)")
    }

    actual fun unloadScript(scriptHandle: String) {}

    actual fun eval(scriptHandle: String, code: String): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (Android)")
    }

    actual fun deliverEvent(actionName: String, payload: Map<String, Any?>?): List<String> = emptyList()

    actual fun executeTurn(scriptHandle: String, context: Map<String, Any?>): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (Android)")
    }

    actual fun executeDelayedCallback(scriptHandle: String, callbackId: Long): LuaExecutionResult {
        return LuaExecutionResult(success = false, error = "Lua scripting is not available on this platform (Android)")
    }

    actual fun isScriptLoaded(scriptHandle: String): Boolean = false

    actual fun getSubscriptions(scriptHandle: String): List<String> = emptyList()
}
