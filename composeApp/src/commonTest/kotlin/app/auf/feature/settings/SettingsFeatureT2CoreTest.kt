package app.auf.feature.settings

import app.auf.core.Action
import app.auf.core.PrivateDataEnvelope
import app.auf.core.generated.ActionNames
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
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 */
class SettingsFeatureT2CoreTest {

    @Test
    fun `onAction for INITIALIZING dispatches filesystem SYSTEM_READ`() = runTest {
        val harness = TestEnvironment.create()
            .withFeature(SettingsFeature(FakePlatformDependencies("test")))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.BOOTING))
            .build()

        harness.store.dispatch("system", Action(ActionRegistry.Names.SYSTEM_PUBLISH_INITIALIZING))

        val readAction = harness.processedActions.find { it.name == ActionRegistry.Names.FILESYSTEM_SYSTEM_READ }
        assertNotNull(readAction)
        assertEquals("settings", readAction.originator)
        assertEquals("settings.json", readAction.payload?.get("subpath")?.jsonPrimitive?.content)
    }

    @Test
    fun `onPrivateData with file content dispatches settings LOADED`() = runTest {
        val platform = FakePlatformDependencies("test")
        val feature = SettingsFeature(platform)
        val harness = TestEnvironment.create().withFeature(feature).build(platform = platform)
        val privateDataPayload = buildJsonObject {
            put("subpath", "settings.json")
            put("content", """{ "file.key": "file.value" }""")
        }
        val envelope = PrivateDataEnvelope(ActionRegistry.Names.Envelopes.FILESYSTEM_RESPONSE_READ, privateDataPayload)

        feature.onPrivateData(envelope, harness.store)

        val loadedAction = harness.processedActions.find { it.name == ActionRegistry.Names.SETTINGS_PUBLISH_LOADED }
        assertNotNull(loadedAction)
        assertEquals("settings", loadedAction.originator)
        assertEquals("file.value", loadedAction.payload?.get("file.key")?.jsonPrimitive?.content)
    }

    @Test
    fun `onAction for UPDATE dispatches SYSTEM_WRITE with encryption`() = runTest {
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
}