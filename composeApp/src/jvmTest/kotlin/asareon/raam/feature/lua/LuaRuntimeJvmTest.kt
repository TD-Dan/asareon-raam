package asareon.raam.feature.lua

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Tier 5 Platform Tests for LuaRuntime JVM implementation.
 *
 * Mandate: Test the LuaJ sandbox, execution timeout, rate limiting,
 * raam.* bridge API, and type conversion. These tests exercise real
 * Lua execution on the JVM.
 */
class LuaRuntimeJvmTest {

    private lateinit var runtime: LuaRuntime
    private val capturedDispatches = mutableListOf<Triple<String, String, Map<String, Any?>?>>()
    private val capturedLogs = mutableListOf<Triple<String, String, String>>()
    private val capturedDelays = mutableListOf<Triple<String, Long, Long>>()

    private val testListener = object : LuaBridgeListener {
        override fun onScriptDispatch(scriptHandle: String, actionName: String, payload: Map<String, Any?>?): Pair<Boolean, String?> {
            capturedDispatches.add(Triple(scriptHandle, actionName, payload))
            return true to null
        }
        override fun onScriptLog(scriptHandle: String, level: String, message: String) {
            capturedLogs.add(Triple(scriptHandle, level, message))
        }
        override fun onScriptDelay(scriptHandle: String, delayMs: Long, callbackId: Long) {
            capturedDelays.add(Triple(scriptHandle, delayMs, callbackId))
        }
        override fun getIdentities(): List<LuaIdentitySnapshot> = listOf(
            LuaIdentitySnapshot("core", "Core", null),
            LuaIdentitySnapshot("lua", "Lua", null),
            LuaIdentitySnapshot("lua.test", "Test Script", "lua")
        )
        override fun getScriptPermissions(scriptHandle: String): Map<String, String> = mapOf(
            "lua:execute" to "YES",
            "filesystem:workspace" to "YES"
        )
    }

    @Before
    fun setup() {
        runtime = LuaRuntime(LuaRuntimeConfig(
            callbackTimeoutMs = 2000,  // generous for CI
            turnTimeoutMs = 5000,
            maxDispatchesPerCallback = 10,
            maxDispatchesPerSecond = 50
        ))
        runtime.setBridgeListener(testListener)
        capturedDispatches.clear()
        capturedLogs.clear()
        capturedDelays.clear()
    }

    @After
    fun teardown() {
        runtime.unloadScript("lua.test")
    }

    // ========================================================================
    // Runtime availability
    // ========================================================================

    @Test
    fun `runtime is available on JVM`() {
        assertTrue(runtime.isAvailable)
    }

    // ========================================================================
    // Script loading
    // ========================================================================

    @Test
    fun `loadScript executes code and calls on_load`() {
        val result = runtime.loadScript("lua.test", "test", """
            function on_load()
                raam.log("loaded!")
            end
        """.trimIndent())

        assertTrue(result.success, "Script should load successfully: ${result.error}")
        assertTrue(runtime.isScriptLoaded("lua.test"))
        assertTrue(capturedLogs.any { it.third == "loaded!" }, "on_load should have been called")
    }

    @Test
    fun `loadScript with syntax error fails`() {
        val result = runtime.loadScript("lua.test", "test", """
            function broken(
            -- missing closing paren and end
        """.trimIndent())

        assertFalse(result.success)
        assertNotNull(result.error)
        assertFalse(runtime.isScriptLoaded("lua.test"))
    }

    @Test
    fun `unloadScript removes script`() {
        runtime.loadScript("lua.test", "test", "raam.log('hi')")
        assertTrue(runtime.isScriptLoaded("lua.test"))

        runtime.unloadScript("lua.test")
        assertFalse(runtime.isScriptLoaded("lua.test"))
    }

    // ========================================================================
    // Sandbox: dangerous modules removed
    // ========================================================================

    @Test
    fun `sandbox blocks os module`() {
        val result = runtime.loadScript("lua.test", "test", """
            os.execute("echo pwned")
        """.trimIndent())

        assertFalse(result.success, "os.execute should be blocked")
    }

    @Test
    fun `sandbox blocks io module`() {
        val result = runtime.loadScript("lua.test", "test", """
            io.open("/etc/passwd", "r")
        """.trimIndent())

        assertFalse(result.success, "io.open should be blocked")
    }

    @Test
    fun `sandbox blocks require`() {
        val result = runtime.loadScript("lua.test", "test", """
            require("os")
        """.trimIndent())

        assertFalse(result.success, "require should be blocked")
    }

    @Test
    fun `sandbox blocks dofile`() {
        val result = runtime.loadScript("lua.test", "test", """
            dofile("/etc/passwd")
        """.trimIndent())

        assertFalse(result.success, "dofile should be blocked")
    }

    @Test
    fun `sandbox blocks loadfile`() {
        val result = runtime.loadScript("lua.test", "test", """
            loadfile("/etc/passwd")
        """.trimIndent())

        assertFalse(result.success, "loadfile should be blocked")
    }

    @Test
    fun `sandbox blocks debug module`() {
        val result = runtime.loadScript("lua.test", "test", """
            debug.getregistry()
        """.trimIndent())

        assertFalse(result.success, "debug module should be blocked")
    }

    @Test
    fun `sandbox allows safe stdlib`() {
        val result = runtime.loadScript("lua.test", "test", """
            -- string, table, math should all work
            local s = string.format("hello %s", "world")
            local t = {1, 2, 3}
            table.insert(t, 4)
            local n = math.floor(3.7)
            raam.log(s .. " " .. tostring(#t) .. " " .. tostring(n))
        """.trimIndent())

        assertTrue(result.success, "Safe stdlib should work: ${result.error}")
        assertTrue(capturedLogs.any { it.third == "hello world 4 3" })
    }

    // ========================================================================
    // Bridge API: raam.dispatch
    // ========================================================================

    @Test
    fun `raam_dispatch calls bridge listener`() {
        runtime.loadScript("lua.test", "test", """
            raam.dispatch("core.SHOW_TOAST", { message = "hello" })
        """.trimIndent())

        assertEquals(1, capturedDispatches.size)
        val (handle, action, payload) = capturedDispatches[0]
        assertEquals("lua.test", handle)
        assertEquals("core.SHOW_TOAST", action)
        assertEquals("hello", payload?.get("message"))
    }

    @Test
    fun `raam_dispatch returns success and error`() {
        val result = runtime.loadScript("lua.test", "test", """
            local ok, err = raam.dispatch("test.ACTION", {})
            raam.log("ok=" .. tostring(ok) .. " err=" .. tostring(err))
        """.trimIndent())

        assertTrue(result.success)
        assertTrue(capturedLogs.any { it.third == "ok=true err=nil" })
    }

    // ========================================================================
    // Bridge API: raam.on / raam.off
    // ========================================================================

    @Test
    fun `raam_on registers subscription`() {
        runtime.loadScript("lua.test", "test", """
            raam.on("session.MESSAGE_ADDED", function(name, payload)
                raam.log("got: " .. name)
            end)
        """.trimIndent())

        val subs = runtime.getSubscriptions("lua.test")
        assertEquals(1, subs.size)
        assertEquals("session.MESSAGE_ADDED", subs[0])
    }

    @Test
    fun `event delivery invokes matching handlers`() {
        runtime.loadScript("lua.test", "test", """
            raam.on("session.MESSAGE_ADDED", function(name, payload)
                raam.log("event: " .. name)
            end)
        """.trimIndent())

        val errors = runtime.deliverEvent("session.MESSAGE_ADDED", mapOf("content" to "hi"), "core.alice")
        assertTrue(errors.isEmpty())
        assertTrue(capturedLogs.any { it.third == "event: session.MESSAGE_ADDED" })
    }

    @Test
    fun `wildcard subscription matches`() {
        runtime.loadScript("lua.test", "test", """
            raam.on("session.*", function(name, payload)
                raam.log("wildcard: " .. name)
            end)
        """.trimIndent())

        runtime.deliverEvent("session.MESSAGE_ADDED", null, "session")
        runtime.deliverEvent("session.CREATED", null, "session")
        runtime.deliverEvent("agent.CREATED", null, "agent")  // should NOT match

        val wildcardLogs = capturedLogs.filter { it.third.startsWith("wildcard:") }
        assertEquals(2, wildcardLogs.size)
    }

    @Test
    fun `raam_off removes subscription`() {
        runtime.loadScript("lua.test", "test", """
            local sub = raam.on("test.EVENT", function() raam.log("called") end)
            raam.off(sub)
        """.trimIndent())

        val subs = runtime.getSubscriptions("lua.test")
        assertTrue(subs.isEmpty(), "Subscription should be removed after off()")
    }

    // ========================================================================
    // Bridge API: raam.log / warn / error
    // ========================================================================

    @Test
    fun `raam_log_warn_error produce correct levels`() {
        runtime.loadScript("lua.test", "test", """
            raam.log("info msg")
            raam.warn("warn msg")
            raam.error("error msg")
        """.trimIndent())

        assertEquals(3, capturedLogs.size)
        assertEquals("log", capturedLogs[0].second)
        assertEquals("warn", capturedLogs[1].second)
        assertEquals("error", capturedLogs[2].second)
    }

    @Test
    fun `print redirects to raam_log`() {
        runtime.loadScript("lua.test", "test", """
            print("printed message")
        """.trimIndent())

        assertTrue(capturedLogs.any { it.second == "log" && it.third == "printed message" })
    }

    // ========================================================================
    // Bridge API: raam.delay
    // ========================================================================

    @Test
    fun `raam_delay schedules callback`() {
        runtime.loadScript("lua.test", "test", """
            raam.delay(5000, function()
                raam.log("delayed")
            end)
        """.trimIndent())

        assertEquals(1, capturedDelays.size)
        assertEquals(5000L, capturedDelays[0].second)
    }

    // ========================================================================
    // Bridge API: raam.identities / raam.permissions
    // ========================================================================

    @Test
    fun `raam_identities returns identity list`() {
        runtime.loadScript("lua.test", "test", """
            local ids = raam.identities()
            raam.log("count=" .. tostring(#ids))
            raam.log("first=" .. ids[1].handle)
        """.trimIndent())

        assertTrue(capturedLogs.any { it.third == "count=3" })
        assertTrue(capturedLogs.any { it.third == "first=core" })
    }

    @Test
    fun `raam_permissions returns permission map`() {
        runtime.loadScript("lua.test", "test", """
            local perms = raam.permissions()
            raam.log("execute=" .. (perms["lua:execute"] or "nil"))
        """.trimIndent())

        assertTrue(capturedLogs.any { it.third == "execute=YES" })
    }

    // ========================================================================
    // Bridge API: raam.scriptName / raam.scriptHandle
    // ========================================================================

    @Test
    fun `raam_scriptName and scriptHandle are set correctly`() {
        runtime.loadScript("lua.test", "test", """
            raam.log("name=" .. raam.scriptName)
            raam.log("handle=" .. raam.scriptHandle)
        """.trimIndent())

        assertTrue(capturedLogs.any { it.third == "name=test" })
        assertTrue(capturedLogs.any { it.third == "handle=lua.test" })
    }

    // ========================================================================
    // Eval
    // ========================================================================

    @Test
    fun `eval executes in script context`() {
        runtime.loadScript("lua.test", "test", """
            myGlobal = 42
        """.trimIndent())

        val result = runtime.eval("lua.test", "return myGlobal")
        assertTrue(result.success)
        assertEquals(42, result.returnValue?.get("result"))
    }

    @Test
    fun `eval on unloaded script returns error`() {
        val result = runtime.eval("lua.nonexistent", "return 1")
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    // ========================================================================
    // executeTurn
    // ========================================================================

    @Test
    fun `executeTurn calls on_turn and returns result`() {
        runtime.loadScript("lua.test", "test", """
            function on_turn(ctx)
                return {
                    turnAdvance = true,
                    systemPrompt = "modified: " .. (ctx.systemPrompt or "")
                }
            end
        """.trimIndent())

        val result = runtime.executeTurn("lua.test", mapOf(
            "systemPrompt" to "original prompt",
            "state" to mapOf("phase" to "READY")
        ))

        assertTrue(result.success, "on_turn should succeed: ${result.error}")
        assertNotNull(result.returnValue)
        assertEquals(true, result.returnValue!!["turnAdvance"])
        assertEquals("modified: original prompt", result.returnValue!!["systemPrompt"])
    }

    @Test
    fun `executeTurn without on_turn function returns error`() {
        runtime.loadScript("lua.test", "test", "raam.log('no on_turn here')")

        val result = runtime.executeTurn("lua.test", mapOf("systemPrompt" to "test"))
        assertFalse(result.success)
        assertTrue(result.error?.contains("on_turn") == true)
    }

    // ========================================================================
    // Execution timeout
    // ========================================================================

    @Test
    fun `infinite loop is killed by timeout`() {
        val fastRuntime = LuaRuntime(LuaRuntimeConfig(callbackTimeoutMs = 500))
        fastRuntime.setBridgeListener(testListener)

        val result = fastRuntime.loadScript("lua.test", "test", """
            while true do end
        """.trimIndent())

        // Should either fail on load (timeout) or return error
        assertFalse(result.success, "Infinite loop should be killed by timeout")
    }

    // ========================================================================
    // Rate limiting
    // ========================================================================

    @Test
    fun `dispatch rate limit is enforced`() {
        val limitedRuntime = LuaRuntime(LuaRuntimeConfig(maxDispatchesPerCallback = 3))
        limitedRuntime.setBridgeListener(testListener)
        capturedDispatches.clear()

        val result = limitedRuntime.loadScript("lua.test", "test", """
            for i = 1, 10 do
                local ok, err = raam.dispatch("test.ACTION", { i = i })
                if not ok then
                    raam.log("blocked at " .. tostring(i) .. ": " .. tostring(err))
                    break
                end
            end
        """.trimIndent())

        assertTrue(result.success)
        // Should have been blocked after 3 dispatches
        assertTrue(capturedDispatches.size <= 3,
            "Rate limit should cap dispatches at 3, got ${capturedDispatches.size}")
        assertTrue(capturedLogs.any { it.third.contains("blocked at") },
            "Script should see rate limit error")
    }

    // ========================================================================
    // Type conversion
    // ========================================================================

    @Test
    fun `Lua tables convert to Kotlin maps correctly`() {
        runtime.loadScript("lua.test", "test", """
            raam.dispatch("test.ACTION", {
                str = "hello",
                num = 42,
                flag = true,
                nested = { a = 1, b = 2 }
            })
        """.trimIndent())

        assertEquals(1, capturedDispatches.size)
        val payload = capturedDispatches[0].third!!
        assertEquals("hello", payload["str"])
        assertEquals(42, payload["num"])
        assertEquals(true, payload["flag"])
        assertTrue(payload["nested"] is Map<*, *>)
    }
}
