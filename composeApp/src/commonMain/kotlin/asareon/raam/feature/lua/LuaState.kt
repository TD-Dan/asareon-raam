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
    val loadedAt: Long = 0,
    /** The last-known source code of the script (populated on load/save). */
    val sourceContent: String? = null
)

/**
 * A single console output entry from a script.
 */
@Serializable
data class ConsoleEntry(
    val level: String,  // "log", "warn", "error"
    val message: String,
    val timestamp: Long,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val color: String? = null  // hex color override (e.g., "#FF5500")
)

/**
 * Tracks a pending filesystem operation so that when the async response arrives,
 * LuaFeature knows what to do with the result. Stored in-memory on the feature
 * instance (not in FeatureState) since it is transient runtime tracking.
 */
data class PendingFileOp(
    val correlationId: String,
    /** The type of operation: "load", "reload", "clone-read" */
    val opType: String,
    /** The script handle this operation is for. */
    val scriptHandle: String,
    /** Extra context needed to complete the operation. */
    val extra: Map<String, String> = emptyMap()
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
