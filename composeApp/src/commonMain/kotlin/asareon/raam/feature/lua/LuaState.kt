package asareon.raam.feature.lua

import asareon.raam.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * Status of a loaded Lua script.
 */
enum class ScriptStatus {
    /** Script is loaded and running normally. */
    RUNNING,
    /** Script encountered an error and was stopped. */
    ERRORED,
    /** Script is in the process of loading. */
    LOADING,
    /** Script was explicitly stopped by the user. */
    STOPPED
}

/**
 * Metadata for a single loaded Lua script.
 */
@Serializable
data class ScriptInfo(
    val handle: String,
    val localHandle: String,
    val name: String,
    val path: String,
    val status: ScriptStatus,
    val autostart: Boolean = false,
    val lastError: String? = null,
    val loadedAt: Long = 0
)

/**
 * A single console output entry from a script.
 */
@Serializable
data class ConsoleEntry(
    val level: String,  // "log", "warn", "error"
    val message: String,
    val timestamp: Long
)

/**
 * The feature state for the Lua scripting feature.
 */
@Serializable
data class LuaState(
    /** All known scripts (loaded, errored, or stopped). Keyed by script handle. */
    val scripts: Map<String, ScriptInfo> = emptyMap(),
    /** Console output buffer per script. Keyed by script handle. */
    val consoleBuffers: Map<String, List<ConsoleEntry>> = emptyMap(),
    /** Whether the Lua runtime is available on this platform. */
    val runtimeAvailable: Boolean = false
) : FeatureState
