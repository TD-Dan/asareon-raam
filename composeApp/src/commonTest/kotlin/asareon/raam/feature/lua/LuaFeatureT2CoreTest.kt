package asareon.raam.feature.lua

import asareon.raam.core.Action
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.fakes.FakePlatformDependencies
import asareon.raam.feature.core.CoreFeature
import asareon.raam.feature.core.CoreState
import asareon.raam.feature.core.AppLifecycle
import asareon.raam.feature.filesystem.FileSystemFeature
import asareon.raam.test.TestEnvironment
import asareon.raam.util.BasePath
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 2 Contract Tests for LuaFeature.
 *
 * Mandate: Test LuaFeature integrated with the real Store, CoreFeature, and
 * FileSystemFeature. Verifies the full action pipeline: dispatch → guard →
 * reduce → side effects → follow-up dispatch.
 */
class LuaFeatureT2CoreTest {

    private fun createHarness(
        platform: FakePlatformDependencies = FakePlatformDependencies("test"),
        luaState: LuaState? = null
    ): asareon.raam.test.TestHarness {
        val builder = TestEnvironment.create()
            .withFeature(LuaFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.RUNNING))

        if (luaState != null) {
            builder.withInitialState("lua", luaState)
        }

        return builder.build(platform = platform)
    }

    // ========================================================================
    // Script creation end-to-end
    // ========================================================================

    @Test
    fun `CREATE_SCRIPT writes template file and triggers load`() {
        val platform = FakePlatformDependencies("test")
        val harness = createHarness(platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.LUA_CREATE_SCRIPT,
                payload = buildJsonObject { put("name", "Logger") }
            ))

            // Should have dispatched FILESYSTEM_WRITE
            val writeAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_WRITE
            }
            assertNotNull(writeAction, "Should write template file")
            val content = writeAction.payload?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(content.contains("Logger"), "Template should contain script name")
            assertTrue(content.contains("on_load"), "Template should contain lifecycle hooks")

            // Should have dispatched LOAD_SCRIPT
            val loadAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.LUA_LOAD_SCRIPT
            }
            assertNotNull(loadAction, "Should auto-load the created script")

            // Verify state has the script in LOADING
            val luaState = harness.store.state.value.featureStates["lua"] as? LuaState
            assertNotNull(luaState)
            assertTrue(luaState.scripts.isNotEmpty(), "Script should be in state")
        }
    }

    // ========================================================================
    // Script deletion end-to-end
    // ========================================================================

    @Test
    fun `DELETE_SCRIPT removes script from state and dispatches file delete`() {
        val platform = FakePlatformDependencies("test")
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf("lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.RUNNING
            ))
        )
        val harness = createHarness(platform, luaState)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.LUA_DELETE_SCRIPT,
                payload = buildJsonObject { put("scriptHandle", "lua.test") }
            ))

            // State should no longer have the script
            val updatedState = harness.store.state.value.featureStates["lua"] as? LuaState
            assertNotNull(updatedState)
            assertFalse("lua.test" in updatedState.scripts, "Script should be removed from state")

            // Should have dispatched filesystem delete
            val deleteAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_DELETE_FILE
            }
            assertNotNull(deleteAction, "Should dispatch filesystem delete")

            // Should have dispatched identity unregister
            val unregAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.CORE_UNREGISTER_IDENTITY
            }
            assertNotNull(unregAction, "Should unregister identity")
        }
    }

    // ========================================================================
    // Script autodiscovery on SYSTEM_RUNNING
    // ========================================================================

    @Test
    fun `SYSTEM_RUNNING dispatches strategy registration and config read`() {
        val platform = FakePlatformDependencies("test")
        // Start in INITIALIZING so we can transition to RUNNING
        val harness = TestEnvironment.create()
            .withFeature(LuaFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.INITIALIZING))
            .build(platform = platform)

        harness.runAndLogOnFailure {
            harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_RUNNING))

            // Should register the Lua strategy
            val regAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.AGENT_REGISTER_EXTERNAL_STRATEGY
            }
            assertNotNull(regAction, "Should dispatch strategy registration")

            // Should read scripts.json config (autodiscovery follows in response handler)
            val readAction = harness.processedActions.find {
                it.name == ActionRegistry.Names.FILESYSTEM_READ &&
                        it.payload?.get("path")?.jsonPrimitive?.contentOrNull == "scripts.json"
            }
            assertNotNull(readAction, "Should dispatch filesystem read for scripts.json")
        }
    }

    @Test
    fun `autodiscovery RETURN_LIST triggers LOAD_SCRIPT for each lua file`() {
        val platform = FakePlatformDependencies("test")
        val harness = createHarness(platform)

        // Pre-populate the lua workspace with files
        val luaSandbox = platform.getBasePathFor(BasePath.APP_ZONE) + "/lua"
        platform.createDirectories(luaSandbox)
        platform.writeFileContent("$luaSandbox/script-a.lua", "-- script a")
        platform.writeFileContent("$luaSandbox/script-b.lua", "-- script b")
        platform.writeFileContent("$luaSandbox/readme.txt", "not a script")

        harness.runAndLogOnFailure {
            // Simulate RETURN_LIST from filesystem (targeted to "lua")
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = buildJsonObject {
                    put("correlationId", "lua:discover")
                    put("listing", buildJsonArray {
                        add(buildJsonObject { put("path", "script-a.lua"); put("isDirectory", false) })
                        add(buildJsonObject { put("path", "script-b.lua"); put("isDirectory", false) })
                        add(buildJsonObject { put("path", "readme.txt"); put("isDirectory", false) })
                        add(buildJsonObject { put("path", "subdir"); put("isDirectory", true) })
                    })
                },
                targetRecipient = "lua"
            ))

            // Should trigger LOAD_SCRIPT for .lua files only (not readme.txt, not subdir)
            val loadActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.LUA_LOAD_SCRIPT
            }
            assertEquals(2, loadActions.size, "Should load exactly 2 .lua files (not readme.txt or subdir)")

            val loadedPaths = loadActions.map {
                it.payload?.get("scriptPath")?.jsonPrimitive?.contentOrNull
            }.toSet()
            assertTrue("script-a.lua" in loadedPaths)
            assertTrue("script-b.lua" in loadedPaths)
        }
    }

    @Test
    fun `autodiscovery skips already loaded scripts`() {
        val platform = FakePlatformDependencies("test")
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf("lua.script-a" to ScriptInfo(
                handle = "lua.script-a", localHandle = "script-a", name = "script-a",
                path = "script-a.lua", status = ScriptStatus.RUNNING
            ))
        )
        val harness = createHarness(platform, luaState)

        harness.runAndLogOnFailure {
            harness.store.dispatch("filesystem", Action(
                name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,
                payload = buildJsonObject {
                    put("correlationId", "lua:discover")
                    put("listing", buildJsonArray {
                        add(buildJsonObject { put("path", "script-a.lua"); put("isDirectory", false) })
                        add(buildJsonObject { put("path", "script-b.lua"); put("isDirectory", false) })
                    })
                },
                targetRecipient = "lua"
            ))

            val loadActions = harness.processedActions.filter {
                it.name == ActionRegistry.Names.LUA_LOAD_SCRIPT
            }
            assertEquals(1, loadActions.size, "Should only load script-b (script-a is already loaded)")
            assertEquals("script-b.lua", loadActions[0].payload?.get("scriptPath")?.jsonPrimitive?.contentOrNull)
        }
    }

    // ========================================================================
    // Console output accumulation
    // ========================================================================

    @Test
    fun `SCRIPT_OUTPUT accumulates in console buffer`() {
        val platform = FakePlatformDependencies("test")
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf("lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.RUNNING
            ))
        )
        val harness = createHarness(platform, luaState)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.LUA_SCRIPT_OUTPUT,
                payload = buildJsonObject {
                    put("scriptHandle", "lua.test")
                    put("level", "log")
                    put("message", "hello world")
                    put("timestamp", 12345)
                }
            ))

            val state = harness.store.state.value.featureStates["lua"] as LuaState
            val buffer = state.consoleBuffers["lua.test"]
            assertNotNull(buffer)
            assertEquals(1, buffer.size)
            assertEquals("hello world", buffer[0].message)
            assertEquals("log", buffer[0].level)
        }
    }

    // ========================================================================
    // Error state transitions
    // ========================================================================

    @Test
    fun `SCRIPT_ERROR transitions to ERRORED status`() {
        val platform = FakePlatformDependencies("test")
        val luaState = LuaState(
            runtimeAvailable = true,
            scripts = mapOf("lua.test" to ScriptInfo(
                handle = "lua.test", localHandle = "test", name = "test",
                path = "test.lua", status = ScriptStatus.RUNNING
            ))
        )
        val harness = createHarness(platform, luaState)

        harness.runAndLogOnFailure {
            harness.store.dispatch("lua", Action(
                name = ActionRegistry.Names.LUA_SCRIPT_ERROR,
                payload = buildJsonObject {
                    put("scriptHandle", "lua.test")
                    put("error", "infinite loop detected")
                    put("context", "callback")
                }
            ))

            val state = harness.store.state.value.featureStates["lua"] as LuaState
            val script = state.scripts["lua.test"]!!
            assertEquals(ScriptStatus.ERRORED, script.status)
            assertEquals("infinite loop detected", script.lastError)
        }
    }
}
