package asareon.raam.feature.lua

import asareon.raam.core.Action
import asareon.raam.core.AppState
import asareon.raam.core.Identity
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.fakes.FakeStore
import asareon.raam.util.LogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for LuaFeature side effects.
 *
 * Mandate: Test side effect dispatch behavior in isolation using FakeStore.
 * Verifies what actions are dispatched (intent) without running the full Store pipeline.
 */
class LuaFeatureT1SideEffectsTest {

    private val platform = FakePlatformDependencies("test")
    private val feature = LuaFeature(platform)

    private fun createFakeStore(luaState: LuaState? = null): FakeStore {
        val featureStates = mutableMapOf<String, asareon.raam.core.FeatureState>()
        if (luaState != null) featureStates["lua"] = luaState
        val state = AppState(
            featureStates = featureStates,
            identityRegistry = mapOf(
                "lua" to Identity(uuid = null, handle = "lua", localHandle = "lua", name = "Lua Scripting")
            )
        )
        return FakeStore(state, platform, features = listOf(feature))
    }

    // ========================================================================
    // SYSTEM_RUNNING — External strategy registration + autodiscovery
    // ========================================================================

    @Test
    fun `SYSTEM_RUNNING dispatches REGISTER_EXTERNAL_STRATEGY for Lua`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.SYSTEM_RUNNING)
        feature.handleSideEffects(action, store, null, LuaState(runtimeAvailable = true))

        val regAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY
        }
        assertNotNull(regAction, "SYSTEM_RUNNING should dispatch strategy registration")
        assertEquals("lua", regAction.originator)
        assertEquals("agent.strategy.lua", regAction.payload?.get("strategyId")?.asString())
        assertEquals("Lua Script", regAction.payload?.get("displayName")?.asString())
        assertEquals("lua", regAction.payload?.get("featureHandle")?.asString())
    }

    // ========================================================================
    // LOAD_SCRIPT — dispatches identity registration + filesystem read
    // ========================================================================

    @Test
    fun `LOAD_SCRIPT dispatches REGISTER_IDENTITY and FILESYSTEM_READ`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, buildJsonObject {
            put("scriptPath", "test.lua")
            put("localHandle", "test")
        })

        feature.handleSideEffects(action, store, null, LuaState(runtimeAvailable = true))

        val registerAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.CORE_REGISTER_IDENTITY
        }
        assertNotNull(registerAction, "Should register script identity")

        val readAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_READ
        }
        assertNotNull(readAction, "Should dispatch filesystem read for script content")
        assertEquals("test.lua", readAction.payload?.get("path")?.asString())
    }

    @Test
    fun `LOAD_SCRIPT with missing payload logs error`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)

        val action = Action(ActionRegistry.Names.LUA_LOAD_SCRIPT, payload = null)
        feature.handleSideEffects(action, store, null, LuaState())

        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.ERROR && it.message.contains("missing payload") },
            "Should log missing payload error"
        )
    }

    // ========================================================================
    // UNLOAD_SCRIPT — dispatches identity unregister + script unloaded
    // ========================================================================

    @Test
    fun `UNLOAD_SCRIPT dispatches UNREGISTER_IDENTITY and SCRIPT_UNLOADED`() {
        val store = createFakeStore()
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_UNLOAD_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        feature.handleSideEffects(action, store, null, null)

        val unregAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.CORE_UNREGISTER_IDENTITY
        }
        assertNotNull(unregAction, "Should unregister identity")
        assertEquals("lua.test", unregAction.payload?.get("handle")?.asString())

        val unloadedAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.LUA_SCRIPT_UNLOADED
        }
        assertNotNull(unloadedAction, "Should broadcast script unloaded")
        assertEquals("manual", unloadedAction.payload?.get("reason")?.asString())
    }

    // ========================================================================
    // CREATE_SCRIPT — dispatches filesystem write + load
    // ========================================================================

    @Test
    fun `CREATE_SCRIPT dispatches FILESYSTEM_WRITE then LOAD_SCRIPT`() {
        val store = createFakeStore()
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_CREATE_SCRIPT, buildJsonObject {
            put("name", "My New Script")
        })

        feature.handleSideEffects(action, store, null, null)

        val writeAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_WRITE
        }
        assertNotNull(writeAction, "Should write template to filesystem")
        val writtenContent = writeAction.payload?.get("content")?.asString() ?: ""
        assertTrue(writtenContent.contains("My New Script"), "Template should contain script name")
        assertTrue(writtenContent.contains("on_load"), "Template should contain on_load")

        val loadAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.LUA_LOAD_SCRIPT
        }
        assertNotNull(loadAction, "Should auto-load the created script")
    }

    @Test
    fun `CREATE_SCRIPT with missing name logs error`() {
        val store = createFakeStore()
        feature.init(store)

        val action = Action(ActionRegistry.Names.LUA_CREATE_SCRIPT, buildJsonObject {})
        feature.handleSideEffects(action, store, null, null)

        assertTrue(
            platform.capturedLogs.any { it.level == LogLevel.ERROR && it.message.contains("name") },
            "Should log missing name error"
        )
    }

    // ========================================================================
    // DELETE_SCRIPT — dispatches filesystem delete + identity unregister
    // ========================================================================

    @Test
    fun `DELETE_SCRIPT dispatches DELETE_FILE and UNREGISTER_IDENTITY`() {
        val luaState = LuaState(scripts = mapOf(
            "lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.RUNNING
            )
        ))
        val store = createFakeStore(luaState)
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_DELETE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        // previousState has the script (pre-reducer), newState has it removed (post-reducer)
        feature.handleSideEffects(action, store, luaState, LuaState())

        val deleteAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_DELETE_FILE
        }
        assertNotNull(deleteAction, "Should delete file from disk")
        assertEquals("test.lua", deleteAction.payload?.get("path")?.asString())

        val unregAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.CORE_UNREGISTER_IDENTITY
        }
        assertNotNull(unregAction, "Should unregister identity")
    }

    // ========================================================================
    // SAVE_SCRIPT — dispatches filesystem write
    // ========================================================================

    @Test
    fun `SAVE_SCRIPT dispatches FILESYSTEM_WRITE with content`() {
        val luaState = LuaState(scripts = mapOf(
            "lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.STOPPED
            )
        ))
        val store = createFakeStore(luaState)
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_SAVE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
            put("content", "-- updated code")
        })

        feature.handleSideEffects(action, store, null, luaState)

        val writeAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_WRITE
        }
        assertNotNull(writeAction, "Should write to filesystem")
        assertEquals("test.lua", writeAction.payload?.get("path")?.asString())
        assertEquals("-- updated code", writeAction.payload?.get("content")?.asString())
    }

    // ========================================================================
    // TOGGLE_SCRIPT — dispatches load or unload based on previous state
    // ========================================================================

    @Test
    fun `TOGGLE_SCRIPT from RUNNING dispatches SCRIPT_UNLOADED`() {
        val prevState = LuaState(scripts = mapOf(
            "lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.RUNNING
            )
        ))
        val store = createFakeStore()
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_TOGGLE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        feature.handleSideEffects(action, store, prevState, null)

        val unloadedAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.LUA_SCRIPT_UNLOADED
        }
        assertNotNull(unloadedAction, "Should broadcast unloaded when toggling off")
    }

    @Test
    fun `TOGGLE_SCRIPT from STOPPED dispatches LOAD_SCRIPT`() {
        val prevState = LuaState(scripts = mapOf(
            "lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.STOPPED
            )
        ))
        val store = createFakeStore()
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.LUA_TOGGLE_SCRIPT, buildJsonObject {
            put("scriptHandle", "lua.test")
        })

        feature.handleSideEffects(action, store, prevState, null)

        val loadAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.LUA_LOAD_SCRIPT
        }
        assertNotNull(loadAction, "Should dispatch load when toggling on")
    }

    // ========================================================================
    // EXTERNAL_TURN_REQUEST — dispatches EXTERNAL_TURN_RESULT
    // ========================================================================

    @Test
    fun `EXTERNAL_TURN_REQUEST without strategyConfig returns error mentioning selection`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.AGENT_EXTERNAL_TURN_REQUEST, buildJsonObject {
            put("correlationId", "test-agent-uuid")
            put("systemPrompt", "You are helpful.")
            put("agentHandle", "agent.test")
        })

        feature.handleSideEffects(action, store, null, LuaState(runtimeAvailable = true))

        val resultAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT
        }
        assertNotNull(resultAction, "Should dispatch turn result")
        assertEquals("error", resultAction.payload?.get("mode")?.asString())
        assertEquals("test-agent-uuid", resultAction.payload?.get("correlationId")?.asString())
        val msg = resultAction.payload?.get("error")?.asString() ?: ""
        assertTrue(msg.contains("strategy script", ignoreCase = true),
            "Error should explain that no strategy script was selected; was: $msg")
    }

    @Test
    fun `EXTERNAL_TURN_REQUEST with unknown strategyScriptHandle errors with not-found`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.AGENT_EXTERNAL_TURN_REQUEST, buildJsonObject {
            put("correlationId", "test-agent-uuid")
            put("systemPrompt", "")
            put("strategyConfig", buildJsonObject { put("strategyScriptHandle", "lua.ghost") })
        })

        feature.handleSideEffects(action, store, null, LuaState(runtimeAvailable = true))

        val resultAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT
        }
        assertNotNull(resultAction)
        assertEquals("error", resultAction.payload?.get("mode")?.asString())
        val msg = resultAction.payload?.get("error")?.asString() ?: ""
        assertTrue(msg.contains("lua.ghost"),
            "Error should reference the missing handle; was: $msg")
        assertTrue(msg.contains("not found", ignoreCase = true) || msg.contains("deleted", ignoreCase = true))
    }

    @Test
    fun `EXTERNAL_TURN_REQUEST with stopped strategy script errors with status`() {
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf(
                "lua.haiku" to ScriptInfo(
                    handle = "lua.haiku", localHandle = "haiku", name = "Haiku",
                    path = "haiku.lua", status = ScriptStatus.STOPPED,
                    sourceContent = "function on_turn(ctx) return { turnAdvance = true } end",
                ),
            ),
        )
        val store = createFakeStore(luaState)
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.AGENT_EXTERNAL_TURN_REQUEST, buildJsonObject {
            put("correlationId", "test-agent-uuid")
            put("systemPrompt", "")
            put("strategyConfig", buildJsonObject { put("strategyScriptHandle", "lua.haiku") })
        })

        feature.handleSideEffects(action, store, null, luaState)

        val resultAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.AGENT_EXTERNAL_TURN_RESULT
        }
        assertNotNull(resultAction)
        assertEquals("error", resultAction.payload?.get("mode")?.asString())
        val msg = resultAction.payload?.get("error")?.asString() ?: ""
        assertTrue(msg.contains("not running", ignoreCase = true) || msg.contains("STOPPED", ignoreCase = true),
            "Error should mention the script is not running; was: $msg")
    }

    // ========================================================================
    // LIST_STRATEGY_SCRIPT_OPTIONS — agent-manager dropdown source
    // ========================================================================

    @Test
    fun `LIST_STRATEGY_SCRIPT_OPTIONS returns only scripts defining on_turn`() {
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf(
                "lua.app-only" to ScriptInfo(
                    handle = "lua.app-only", localHandle = "app-only", name = "App Only",
                    path = "app-only.lua", status = ScriptStatus.RUNNING,
                    sourceContent = "function on_load() raam.log('hi') end",
                ),
                "lua.haiku" to ScriptInfo(
                    handle = "lua.haiku", localHandle = "haiku", name = "Haiku",
                    path = "haiku.lua", status = ScriptStatus.RUNNING,
                    sourceContent = "function on_turn(ctx) return { turnAdvance = true } end",
                ),
                "lua.silent" to ScriptInfo(
                    handle = "lua.silent", localHandle = "silent", name = "Silent",
                    path = "silent.lua", status = ScriptStatus.STOPPED,
                    sourceContent = "function on_turn(ctx) return { response = 'hi' } end",
                ),
            ),
        )
        val store = createFakeStore(luaState)
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(
            ActionRegistry.Names.LUA_LIST_STRATEGY_SCRIPT_OPTIONS,
            buildJsonObject {},
        ).copy(originator = "agent")

        feature.handleSideEffects(action, store, null, luaState)

        val response = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.LUA_RETURN_STRATEGY_SCRIPT_OPTIONS
        }
        assertNotNull(response, "Should dispatch RETURN_STRATEGY_SCRIPT_OPTIONS")
        assertEquals("agent", response.targetRecipient)
        val options = response.payload?.get("options")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
        assertNotNull(options)
        val handles = options.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("handle")?.asString() }
        assertTrue("lua.haiku" in handles, "Strategy script should be included")
        assertTrue("lua.silent" in handles, "Stopped strategy scripts must still be listed (with hint)")
        assertTrue("lua.app-only" !in handles, "App scripts must be filtered out")
    }

    // ========================================================================
    // APP STARTUP — dispatches config read then autodiscovery
    // ========================================================================

    @Test
    fun `SYSTEM_RUNNING dispatches FILESYSTEM_READ for scripts config`() {
        val store = createFakeStore(LuaState(runtimeAvailable = true))
        feature.init(store)
        store.dispatchedActions.clear()

        val action = Action(ActionRegistry.Names.SYSTEM_RUNNING)
        feature.handleSideEffects(action, store, null, LuaState(runtimeAvailable = true))

        val readAction = store.dispatchedActions.find {
            it.name == ActionRegistry.Names.FILESYSTEM_READ
        }
        assertNotNull(readAction, "Should dispatch filesystem read for scripts.json")
        assertEquals("scripts.json", readAction.payload?.get("path")?.asString())
    }

    // Note: "runtime not available" cannot be tested from commonTest because
    // the JVM LuaRuntime always reports isAvailable=true. This condition is
    // only reachable on WASM/iOS stubs where the actual class returns false.
    // The guard is tested implicitly by the stub platform tests.

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content
}
