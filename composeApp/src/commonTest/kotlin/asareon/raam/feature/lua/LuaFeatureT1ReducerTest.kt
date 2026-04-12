package asareon.raam.feature.lua

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for LuaFeature reducer.
 *
 * Mandate: Test pure reducer logic in isolation — state transitions for every action,
 * null state init, unknown action handling, field defaults, boundary conditions.
 */
class LuaFeatureT1ReducerTest {

    private val platform = FakePlatformDependencies("test")

    private fun createFeature() = LuaFeature(platform)

    // ========================================================================
    // Null state initialization
    // ========================================================================

    @Test
    fun `null state initializes to default LuaState`() {
        val feature = createFeature()
        val result = feature.reducer(null, Action("some.UNKNOWN"))
        assertNotNull(result)
        assertTrue(result is LuaState)
    }

    @Test
    fun `default LuaState has empty scripts and console buffers`() {
        val feature = createFeature()
        val result = feature.reducer(null, Action("some.UNKNOWN")) as LuaState
        assertTrue(result.scripts.isEmpty())
        assertTrue(result.consoleBuffers.isEmpty())
    }

    // ========================================================================
    // Unknown action
    // ========================================================================

    @Test
    fun `unknown action returns current state unchanged`() {
        val feature = createFeature()
        val initial = LuaState(runtimeAvailable = true)
        val result = feature.reducer(initial, Action("totally.UNKNOWN"))
        assertSame(initial, result)
    }

    // ========================================================================
    // LUA_LOAD_SCRIPT
    // ========================================================================

    @Test
    fun `LOAD_SCRIPT adds script with LOADING status`() {
        val feature = createFeature()
        val initial = LuaState(runtimeAvailable = true)
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "hello.lua")
            put("localHandle", "hello")
        })

        val result = feature.reducer(initial, action) as LuaState

        assertEquals(1, result.scripts.size)
        val script = result.scripts["lua.hello"]
        assertNotNull(script)
        assertEquals("lua.hello", script.handle)
        assertEquals("hello", script.localHandle)
        assertEquals("hello", script.name)
        assertEquals("hello.lua", script.path)
        assertEquals(ScriptStatus.LOADING, script.status)
    }

    @Test
    fun `LOAD_SCRIPT derives localHandle from filename when not provided`() {
        val feature = createFeature()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "my-cool-script.lua")
        })

        val result = feature.reducer(LuaState(), action) as LuaState

        val script = result.scripts["lua.my-cool-script"]
        assertNotNull(script, "Script should be derived from filename")
        assertEquals("my-cool-script", script.localHandle)
    }

    @Test
    fun `LOAD_SCRIPT sanitizes localHandle from complex filename`() {
        val feature = createFeature()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "My Script (v2).lua")
        })

        val result = feature.reducer(LuaState(), action) as LuaState

        // Should sanitize to lowercase, replace non-alnum with hyphens
        val keys = result.scripts.keys.toList()
        assertEquals(1, keys.size)
        val handle = keys[0]
        assertTrue(handle.startsWith("lua."), "Handle should start with lua.")
        assertFalse(handle.contains(" "), "Handle should not contain spaces")
        assertFalse(handle.contains("("), "Handle should not contain parens")
    }

    @Test
    fun `LOAD_SCRIPT with autostart flag`() {
        val feature = createFeature()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "test.lua")
            put("localHandle", "test")
            put("autostart", true)
        })

        val result = feature.reducer(LuaState(), action) as LuaState
        assertTrue(result.scripts["lua.test"]!!.autostart)
    }

    @Test
    fun `LOAD_SCRIPT without autostart defaults to false`() {
        val feature = createFeature()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "test.lua")
            put("localHandle", "test")
        })

        val result = feature.reducer(LuaState(), action) as LuaState
        assertFalse(result.scripts["lua.test"]!!.autostart)
    }

    @Test
    fun `LOAD_SCRIPT preserves existing scripts`() {
        val feature = createFeature()
        val existing = ScriptInfo(
            handle = "lua.existing", localHandle = "existing", name = "existing",
            path = "existing.lua", status = ScriptStatus.RUNNING
        )
        val initial = LuaState(scripts = mapOf("lua.existing" to existing))
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "new.lua")
            put("localHandle", "new")
        })

        val result = feature.reducer(initial, action) as LuaState

        assertEquals(2, result.scripts.size)
        assertNotNull(result.scripts["lua.existing"])
        assertNotNull(result.scripts["lua.new"])
    }

    @Test
    fun `LOAD_SCRIPT with missing payload returns state unchanged`() {
        val feature = createFeature()
        val initial = LuaState()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, payload = null)

        val result = feature.reducer(initial, action)
        assertSame(initial, result)
    }

    @Test
    fun `LOAD_SCRIPT with missing scriptPath returns state unchanged`() {
        val feature = createFeature()
        val initial = LuaState()
        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("localHandle", "test")
        })

        val result = feature.reducer(initial, action)
        assertSame(initial, result)
    }

    // ========================================================================
    // LUA_UNLOAD_SCRIPT / LUA_DELETE_SCRIPT
    // ========================================================================

    @Test
    fun `UNLOAD_SCRIPT removes script and its console buffer`() {
        val feature = createFeature()
        val initial = LuaState(
            scripts = mapOf("lua.test" to testScript("lua.test")),
            consoleBuffers = mapOf("lua.test" to listOf(ConsoleEntry("log", "hello", 0)))
        )
        val action = Action(ActionRegistry.Names.LUA_UNLOAD_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState

        assertTrue(result.scripts.isEmpty())
        assertTrue(result.consoleBuffers.isEmpty())
    }

    @Test
    fun `DELETE_SCRIPT removes script and its console buffer`() {
        val feature = createFeature()
        val initial = LuaState(
            scripts = mapOf("lua.test" to testScript("lua.test")),
            consoleBuffers = mapOf("lua.test" to listOf(ConsoleEntry("log", "hello", 0)))
        )
        val action = Action(ActionRegistry.Names.LUA_DELETE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState

        assertTrue(result.scripts.isEmpty())
        assertTrue(result.consoleBuffers.isEmpty())
    }

    @Test
    fun `UNLOAD_SCRIPT preserves other scripts`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.a" to testScript("lua.a"),
            "lua.b" to testScript("lua.b")
        ))
        val action = Action(ActionRegistry.Names.LUA_UNLOAD_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.a")
        })

        val result = feature.reducer(initial, action) as LuaState

        assertEquals(1, result.scripts.size)
        assertNotNull(result.scripts["lua.b"])
    }

    // ========================================================================
    // LUA_SCRIPT_LOADED
    // ========================================================================

    @Test
    fun `SCRIPT_LOADED transitions script to RUNNING and clears error`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", status = ScriptStatus.LOADING, lastError = "previous error")
        ))
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_LOADED, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("scriptName", "test")
            put("scriptPath", "test.lua")
            put("sourceContent", "-- test code")
        })

        val result = feature.reducer(initial, action) as LuaState
        val script = result.scripts["lua.test"]!!

        assertEquals(ScriptStatus.RUNNING, script.status)
        assertNull(script.lastError)
        assertEquals("-- test code", script.sourceContent)
    }

    @Test
    fun `SCRIPT_LOADED preserves existing sourceContent when not in payload`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", sourceContent = "existing code")
        ))
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_LOADED, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals("existing code", result.scripts["lua.test"]!!.sourceContent)
    }

    @Test
    fun `SCRIPT_LOADED for unknown handle returns state unchanged`() {
        val feature = createFeature()
        val initial = LuaState()
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_LOADED, buildJsonObject {
            put("scriptHandle", "lua.nonexistent")
        })

        val result = feature.reducer(initial, action)
        assertSame(initial, result)
    }

    // ========================================================================
    // LUA_SCRIPT_ERROR
    // ========================================================================

    @Test
    fun `SCRIPT_ERROR transitions script to ERRORED with error message`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", status = ScriptStatus.RUNNING)
        ))
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_ERROR, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("error", "timeout in callback")
        })

        val result = feature.reducer(initial, action) as LuaState
        val script = result.scripts["lua.test"]!!

        assertEquals(ScriptStatus.ERRORED, script.status)
        assertEquals("timeout in callback", script.lastError)
    }

    // ========================================================================
    // LUA_TOGGLE_SCRIPT
    // ========================================================================

    @Test
    fun `TOGGLE_SCRIPT from RUNNING transitions to STOPPED`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", status = ScriptStatus.RUNNING)
        ))
        val action = Action(ActionRegistry.Names.LUA_TOGGLE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals(ScriptStatus.STOPPED, result.scripts["lua.test"]!!.status)
    }

    @Test
    fun `TOGGLE_SCRIPT from STOPPED transitions to LOADING`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", status = ScriptStatus.STOPPED)
        ))
        val action = Action(ActionRegistry.Names.LUA_TOGGLE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals(ScriptStatus.LOADING, result.scripts["lua.test"]!!.status)
    }

    @Test
    fun `TOGGLE_SCRIPT from ERRORED transitions to LOADING`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", status = ScriptStatus.ERRORED)
        ))
        val action = Action(ActionRegistry.Names.LUA_TOGGLE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals(ScriptStatus.LOADING, result.scripts["lua.test"]!!.status)
    }

    // ========================================================================
    // LUA_SCRIPT_OUTPUT
    // ========================================================================

    @Test
    fun `SCRIPT_OUTPUT appends to console buffer`() {
        val feature = createFeature()
        val initial = LuaState(
            scripts = mapOf("lua.test" to testScript("lua.test")),
            consoleBuffers = mapOf("lua.test" to listOf(ConsoleEntry("log", "first", 100)))
        )
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_OUTPUT, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("level", "warn")
            put("message", "second")
            put("timestamp", 200)
        })

        val result = feature.reducer(initial, action) as LuaState
        val buffer = result.consoleBuffers["lua.test"]!!

        assertEquals(2, buffer.size)
        assertEquals("first", buffer[0].message)
        assertEquals("second", buffer[1].message)
        assertEquals("warn", buffer[1].level)
    }

    @Test
    fun `SCRIPT_OUTPUT creates buffer if none exists`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf("lua.test" to testScript("lua.test")))
        val action = Action(ActionRegistry.Names.LUA_SCRIPT_OUTPUT, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("level", "log")
            put("message", "first entry")
            put("timestamp", 100)
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals(1, result.consoleBuffers["lua.test"]!!.size)
    }

    // ========================================================================
    // LUA_SAVE_SCRIPT
    // ========================================================================

    @Test
    fun `SAVE_SCRIPT updates sourceContent in state`() {
        val feature = createFeature()
        val initial = LuaState(scripts = mapOf(
            "lua.test" to testScript("lua.test", sourceContent = "old code")
        ))
        val action = Action(ActionRegistry.Names.LUA_SAVE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("content", "new code")
        })

        val result = feature.reducer(initial, action) as LuaState
        assertEquals("new code", result.scripts["lua.test"]!!.sourceContent)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun testScript(
        handle: String,
        localHandle: String = handle.removePrefix("lua."),
        status: ScriptStatus = ScriptStatus.RUNNING,
        lastError: String? = null,
        sourceContent: String? = null
    ) = ScriptInfo(
        handle = handle,
        localHandle = localHandle,
        name = localHandle,
        path = "$localHandle.lua",
        status = status,
        lastError = lastError,
        sourceContent = sourceContent
    )
}
