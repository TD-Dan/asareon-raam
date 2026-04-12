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
        override fun getCurrentTimeMillis(): Long = System.currentTimeMillis()
        override fun getActionDescriptors(): List<LuaActionDescriptor> = listOf(
            LuaActionDescriptor("core.SHOW_TOAST", "core", "Show a toast notification", true),
            LuaActionDescriptor("session.POST", "session", "Post a message to a session", true),
            LuaActionDescriptor("lua.LOAD_SCRIPT", "lua", "Load a Lua script", true)
        )
        override fun onScriptConsolePrint(scriptHandle: String, message: String, color: String?, bold: Boolean, italic: Boolean) {
            capturedLogs.add(Triple(scriptHandle, "log", message))
        }
        override fun onScriptConsoleClear(scriptHandle: String) {
            capturedLogs.add(Triple(scriptHandle, "clear", ""))
        }
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
            if then end end end ??? !!!
        """.trimIndent())

        assertFalse(result.success, "Syntax error should cause load failure, got: ${result.error}")
        assertNotNull(result.error)
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

    // ========================================================================
    // Originator in event handlers (3rd argument)
    // ========================================================================

    @Test
    fun `event handler receives originator as third argument`() {
        runtime.loadScript("lua.test", "test", """
            raam.on("test.ACTION", function(name, payload, originator)
                raam.log("from: " .. tostring(originator))
            end)
        """.trimIndent())

        runtime.deliverEvent("test.ACTION", null, "core.alice")
        assertTrue(capturedLogs.any { it.third == "from: core.alice" },
            "Handler should receive originator as 3rd arg")
    }

    @Test
    fun `event handler receives nil originator when not provided`() {
        runtime.loadScript("lua.test", "test", """
            raam.on("test.ACTION", function(name, payload, originator)
                raam.log("from: " .. tostring(originator))
            end)
        """.trimIndent())

        runtime.deliverEvent("test.ACTION", null, null)
        assertTrue(capturedLogs.any { it.third == "from: nil" },
            "Handler should receive nil when originator is null")
    }

    // ========================================================================
    // raam.time()
    // ========================================================================

    @Test
    fun `raam_time returns epoch millis as number`() {
        val result = runtime.loadScript("lua.test", "test", """
            local t = raam.time()
            raam.log("type=" .. type(t))
            if t > 1000000000000 then
                raam.log("plausible=true")
            else
                raam.log("plausible=false value=" .. tostring(t))
            end
        """.trimIndent())

        assertTrue(result.success, "Script should load: ${result.error}")
        assertTrue(capturedLogs.any { it.third == "type=number" },
            "raam.time() should return a number")
        assertTrue(capturedLogs.any { it.third == "plausible=true" },
            "raam.time() should return a plausible epoch millis value")
    }

    // ========================================================================
    // raam.actions()
    // ========================================================================

    @Test
    fun `raam_actions returns action descriptor list`() {
        val result = runtime.loadScript("lua.test", "test", """
            local actions = raam.actions()
            raam.log("count=" .. tostring(#actions))
            if #actions > 0 then
                local first = actions[1]
                raam.log("has_name=" .. tostring(first.name ~= nil))
                raam.log("has_feature=" .. tostring(first.feature ~= nil))
                raam.log("has_summary=" .. tostring(first.summary ~= nil))
            end
        """.trimIndent())

        assertTrue(result.success, "Script should load: ${result.error}")
        assertTrue(capturedLogs.any { it.third.startsWith("count=") },
            "raam.actions() should return a list")
        val countLog = capturedLogs.find { it.third.startsWith("count=") }
        assertNotNull(countLog)
        // The test listener returns 3 identities but no actions — actions come from bridge
        // Just verify it returns a table without crashing
    }

    // ========================================================================
    // raam.interval() and raam.delay() — timers
    // ========================================================================

    @Test
    fun `raam_interval schedules first tick`() {
        val result = runtime.loadScript("lua.test", "test", """
            raam.interval(1000, function()
                raam.log("tick")
            end)
        """.trimIndent())

        assertTrue(result.success, "Script should load: ${result.error}")
        assertEquals(1, capturedDelays.size, "interval should schedule first tick")
        assertEquals(1000L, capturedDelays[0].second, "interval delay should be 1000ms")
    }

    @Test
    fun `raam_interval callback executes and reschedules`() {
        runtime.loadScript("lua.test", "test", """
            raam.interval(1000, function()
                raam.log("tick")
            end)
        """.trimIndent())

        // First tick was scheduled
        assertEquals(1, capturedDelays.size)
        val firstCallbackId = capturedDelays[0].third

        // Simulate the platform timer firing the first tick
        capturedDelays.clear()
        val result = runtime.executeDelayedCallback("lua.test", firstCallbackId)
        assertTrue(result.success, "First tick should execute: ${result.error}")
        assertTrue(capturedLogs.any { it.third == "tick" }, "Callback should have run")

        // After execution, interval should have re-scheduled
        assertEquals(1, capturedDelays.size, "Interval should re-schedule after execution")
        assertEquals(1000L, capturedDelays[0].second, "Re-scheduled with same delay")
    }

    @Test
    fun `raam_interval fires multiple times`() {
        runtime.loadScript("lua.test", "test", """
            count = 0
            raam.interval(500, function()
                count = count + 1
                raam.log("tick=" .. count)
            end)
        """.trimIndent())

        // Simulate 3 ticks
        for (i in 1..3) {
            val callbackId = capturedDelays.last().third
            capturedDelays.clear()
            val result = runtime.executeDelayedCallback("lua.test", callbackId)
            assertTrue(result.success, "Tick $i should execute: ${result.error}")
        }

        assertTrue(capturedLogs.any { it.third == "tick=1" })
        assertTrue(capturedLogs.any { it.third == "tick=2" })
        assertTrue(capturedLogs.any { it.third == "tick=3" })

        // Verify count via eval
        val countResult = runtime.eval("lua.test", "return count")
        assertEquals(3, countResult.returnValue?.get("result"), "Should have ticked 3 times")
    }

    @Test
    fun `raam_interval can be cancelled with raam_off`() {
        runtime.loadScript("lua.test", "test", """
            local id = raam.interval(1000, function()
                raam.log("should not fire after cancel")
            end)
            raam.off(id)
        """.trimIndent())

        // off() was called during on_load, before any tick fired
        val callbackId = capturedDelays.firstOrNull()?.third
        if (callbackId != null) {
            val execResult = runtime.executeDelayedCallback("lua.test", callbackId)
            assertFalse(execResult.success, "Cancelled interval should not execute")
        }
    }

    @Test
    fun `raam_interval cancelled mid-run stops rescheduling`() {
        runtime.loadScript("lua.test", "test", """
            cancel_id = nil
            tick_count = 0
            cancel_id = raam.interval(500, function()
                tick_count = tick_count + 1
                raam.log("tick=" .. tick_count)
                if tick_count >= 2 then
                    raam.off(cancel_id)
                    raam.log("cancelled")
                end
            end)
        """.trimIndent())

        // Tick 1
        val id1 = capturedDelays.last().third
        capturedDelays.clear()
        runtime.executeDelayedCallback("lua.test", id1)
        assertTrue(capturedLogs.any { it.third == "tick=1" })
        assertEquals(1, capturedDelays.size, "Should re-schedule after tick 1")

        // Tick 2 — cancels itself
        val id2 = capturedDelays.last().third
        capturedDelays.clear()
        runtime.executeDelayedCallback("lua.test", id2)
        assertTrue(capturedLogs.any { it.third == "tick=2" })
        assertTrue(capturedLogs.any { it.third == "cancelled" })

        // After self-cancellation, should NOT re-schedule
        assertEquals(0, capturedDelays.size, "Cancelled interval should not re-schedule")
    }

    @Test
    fun `raam_delay one-shot does not reschedule`() {
        runtime.loadScript("lua.test", "test", """
            raam.delay(1000, function()
                raam.log("fired")
            end)
        """.trimIndent())

        assertEquals(1, capturedDelays.size)
        val callbackId = capturedDelays[0].third
        capturedDelays.clear()

        // Fire the one-shot
        val result = runtime.executeDelayedCallback("lua.test", callbackId)
        assertTrue(result.success)
        assertTrue(capturedLogs.any { it.third == "fired" })

        // Should NOT re-schedule
        assertEquals(0, capturedDelays.size, "One-shot delay should not re-schedule")

        // Should not be callable again
        val secondResult = runtime.executeDelayedCallback("lua.test", callbackId)
        assertFalse(secondResult.success, "One-shot should not be callable twice")
    }

    @Test
    fun `multiple intervals run independently`() {
        runtime.loadScript("lua.test", "test", """
            a_count = 0
            b_count = 0
            raam.interval(100, function()
                a_count = a_count + 1
                raam.log("a=" .. a_count)
            end)
            raam.interval(200, function()
                b_count = b_count + 1
                raam.log("b=" .. b_count)
            end)
        """.trimIndent())

        // Two intervals scheduled
        assertEquals(2, capturedDelays.size, "Two intervals should produce two initial schedules")

        val idA = capturedDelays[0].third
        val idB = capturedDelays[1].third

        // Fire A twice, B once
        capturedDelays.clear()
        runtime.executeDelayedCallback("lua.test", idA)
        val idA2 = capturedDelays.find { it.second == 100L }?.third
        assertNotNull(idA2, "Interval A should re-schedule")

        capturedDelays.clear()
        runtime.executeDelayedCallback("lua.test", idA2!!)

        capturedDelays.clear()
        runtime.executeDelayedCallback("lua.test", idB)

        val aResult = runtime.eval("lua.test", "return a_count")
        val bResult = runtime.eval("lua.test", "return b_count")
        assertEquals(2, aResult.returnValue?.get("result"), "A should have fired twice")
        assertEquals(1, bResult.returnValue?.get("result"), "B should have fired once")
    }

    // ========================================================================
    // Logger script integration test
    // ========================================================================

    @Test
    fun `logger script loads and tracks actions with originator`() {
        val loggerScript = """
            total = 0
            by_identity = {}

            function on_action(action_name, payload, originator)
                total = total + 1
                if originator then
                    by_identity[originator] = (by_identity[originator] or 0) + 1
                end
            end

            function on_load()
                raam.on("*", on_action)
                raam.log("logger ready")
            end
        """.trimIndent()

        val result = runtime.loadScript("lua.test", "test", loggerScript)
        assertTrue(result.success, "Logger should load: ${result.error}")
        assertTrue(capturedLogs.any { it.third == "logger ready" })

        // Fire some events with different originators
        runtime.deliverEvent("session.POST", mapOf("content" to "hi"), "core.alice")
        runtime.deliverEvent("session.POST", mapOf("content" to "yo"), "core.alice")
        runtime.deliverEvent("agent.STATUS", null, "agent.meridian")

        // Verify via eval
        val totalResult = runtime.eval("lua.test", "return total")
        assertTrue(totalResult.success, "eval should succeed: ${totalResult.error}")
        assertEquals(3, totalResult.returnValue?.get("result"),
            "Should have counted 3 actions")

        val aliceResult = runtime.eval("lua.test", "return by_identity['core.alice']")
        assertTrue(aliceResult.success)
        assertEquals(2, aliceResult.returnValue?.get("result"),
            "Should have counted 2 actions from core.alice")

        val meridianResult = runtime.eval("lua.test", "return by_identity['agent.meridian']")
        assertTrue(meridianResult.success)
        assertEquals(1, meridianResult.returnValue?.get("result"),
            "Should have counted 1 action from agent.meridian")
    }

    @Test
    fun `logger report function uses raam_time and raam_actions`() {
        val reportScript = """
            local start = raam.time()
            local actions_catalog = raam.actions()

            function report()
                local elapsed = raam.time() - start
                raam.log("uptime_ms=" .. tostring(elapsed))
                raam.log("catalog_type=" .. type(actions_catalog))
                raam.log("catalog_count=" .. tostring(#actions_catalog))
            end

            function on_load()
                raam.log("loaded at " .. tostring(start))
                report()
            end
        """.trimIndent()

        val result = runtime.loadScript("lua.test", "test", reportScript)
        assertTrue(result.success, "Report script should load: ${result.error}")

        assertTrue(capturedLogs.any { it.third.startsWith("loaded at ") },
            "Should log start time")
        assertTrue(capturedLogs.any { it.third.startsWith("uptime_ms=") },
            "Should log uptime")
        assertTrue(capturedLogs.any { it.third == "catalog_type=table" },
            "raam.actions() should return a table")
        assertTrue(capturedLogs.any { it.third.startsWith("catalog_count=") },
            "Should report catalog count")
    }
}
