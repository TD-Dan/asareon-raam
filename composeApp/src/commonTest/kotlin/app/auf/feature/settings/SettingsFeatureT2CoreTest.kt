package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.feature.core.AppLifecycle
import app.auf.feature.core.CoreState
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Test for SettingsFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and handleSideEffects working
 * together within a realistic TestEnvironment that includes the real Store.
 */
class SettingsFeatureT2CoreTest {

    @Test
    fun `handleSideEffects for INITIALIZING dispatches filesystem SYSTEM_READ`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(SettingsFeature(FakePlatformDependencies("test")))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.BOOTING))
            .build()

        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_INITIALIZING))

        val readAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_READ }
        assertNotNull(readAction)
        assertEquals("settings", readAction.originator)
        assertEquals("settings.json", readAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSideEffects for targeted FILESYSTEM_RESPONSE_READ dispatches settings LOADED`() = runTest {
        val platform = FakePlatformDependencies("test")
        val feature = SettingsFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        // Phase 3: Dispatch targeted action through the store instead of calling onPrivateData directly.
        // FilesystemFeature dispatches FILESYSTEM_RESPONSE_READ as a targeted action with
        // targetRecipient set to the requesting feature's handle.
        val responseAction = Action(
            name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "settings.json")
                put("content", """{ "file.key": "file.value" }""")
            },
            originator = "filesystem",
            targetRecipient = "settings"
        )
        harness.store.dispatch("filesystem", responseAction)

        val loadedAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_LOADED }
        assertNotNull(loadedAction)
        assertEquals("settings", loadedAction.originator)
        assertEquals("file.value", loadedAction.payload?.get("file.key")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSideEffects for FILESYSTEM_RESPONSE_READ ignores non-settings files`() = runTest {
        val platform = FakePlatformDependencies("test")
        val feature = SettingsFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)

        val responseAction = Action(
            name = ActionRegistry.Names.FILESYSTEM_RESPONSE_READ,
            payload = buildJsonObject {
                put("subpath", "other_file.json")
                put("content", """{ "other.key": "other.value" }""")
            },
            originator = "filesystem",
            targetRecipient = "settings"
        )
        harness.store.dispatch("filesystem", responseAction)

        val loadedAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_LOADED }
        assertEquals(null, loadedAction, "LOADED should not be dispatched for non-settings files.")
    }

    @Test
    fun `handleSideEffects for UPDATE dispatches SYSTEM_WRITE with encryption`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(SettingsFeature(FakePlatformDependencies("test")))
            .withInitialState("settings", SettingsState(values = mapOf("key1" to "new_value")))
            .build()
        val action = Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
            put("key", "key1")
            put("value", "new_value")
        })

        harness.store.dispatch("settings", action)

        val writeAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction)
        assertEquals("settings", writeAction.originator)
        assertEquals("settings.json", writeAction.payload?.get("subpath")?.jsonPrimitive?.content)
        assertTrue(writeAction.payload?.get("encrypt")?.jsonPrimitive?.booleanOrNull == true, "Encryption must be enabled.")
    }

    @Test
    fun `handleSideEffects for UPDATE broadcasts VALUE_CHANGED`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(SettingsFeature(FakePlatformDependencies("test")))
            .withInitialState("settings", SettingsState(values = mapOf("key1" to "old_value")))
            .build()
        val action = Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject {
            put("key", "key1")
            put("value", "new_value")
        })

        harness.store.dispatch("settings", action)

        val changedAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_VALUE_CHANGED }
        assertNotNull(changedAction, "VALUE_CHANGED should be broadcast after UPDATE.")
        assertEquals("settings", changedAction.originator)
        assertEquals("key1", changedAction.payload?.get("key")?.jsonPrimitive?.content)
        assertEquals("new_value", changedAction.payload?.get("value")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleSideEffects for OPEN_FOLDER dispatches FILESYSTEM_OPEN_SYSTEM_FOLDER`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(SettingsFeature(FakePlatformDependencies("test")))
            .build()

        harness.store.dispatch("settings.ui", Action(ActionRegistry.Names.SETTINGS_OPEN_FOLDER))

        val openAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_OPEN_SYSTEM_FOLDER }
        assertNotNull(openAction, "OPEN_FOLDER should dispatch FILESYSTEM_OPEN_SYSTEM_FOLDER.")
        assertEquals("settings", openAction.originator)
    }
}