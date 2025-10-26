package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.filesystem.FileSystemFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 2 Core Tests for CoreFeature.
 *
 * Mandate (P-TEST-001, T2): To test the feature's reducer and onAction handlers working
 * together within a realistic TestEnvironment that includes the real Store.
 */
class CoreFeatureT2CoreTest {

    @Test
    fun `onAction for SYSTEM_PUBLISH_INITIALIZING dispatches ADD actions for its settings`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withInitialState("core", CoreState(lifecycle = AppLifecycle.BOOTING))
            .build(platform = platform)

        // ACT: Dispatch the lifecycle action that triggers the side-effect.
        harness.store.dispatch("system", Action(ActionNames.SYSTEM_PUBLISH_INITIALIZING))

        // ASSERT: Verify that the correct side-effects (dispatching ADD actions) occurred.
        val addActions = harness.processedActions.filter { it.name == ActionNames.SETTINGS_ADD }
        assertEquals(2, addActions.size, "Should dispatch two SETTINGS_ADD actions.")
        assertNotNull(addActions.find { it.payload?.get("key").toString().contains("width") })
        assertNotNull(addActions.find { it.payload?.get("key").toString().contains("height") })
    }

    @Test
    fun `onAction for CORE_COPY_TO_CLIPBOARD copies text and shows a toast`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .build(platform = platform) // Defaults to RUNNING lifecycle

        val textToCopy = "Hello, Clipboard!"
        val action = Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
            put("text", textToCopy)
        })

        // ACT
        harness.store.dispatch("ui", action)

        // ASSERT
        assertEquals(textToCopy, platform.clipboardContent, "The text should be copied to the platform's clipboard.")

        val toastAction = harness.processedActions.find { it.name == ActionNames.CORE_SHOW_TOAST }
        assertNotNull(toastAction, "A toast message should be shown.")
        assertEquals("Copied to clipboard.", toastAction.payload?.get("message").toString().trim('"'))
    }

    // NEW TEST: Verifies persistence and broadcasting side effects.
    @Test
    fun `onAction for ADD_USER_IDENTITY persists and broadcasts the new identity state`() = runTest {
        val platform = FakePlatformDependencies("test")
        val harness = TestEnvironment.create()
            .withFeature(CoreFeature(platform))
            .withFeature(FileSystemFeature(platform))
            .build(platform = platform)

        // ACT
        harness.store.dispatch("ui", Action(ActionNames.CORE_ADD_USER_IDENTITY, buildJsonObject {
            put("name", "Test User")
        }))

        // ASSERT: Persistence
        val writeAction = harness.processedActions.find { it.name == ActionNames.FILESYSTEM_SYSTEM_WRITE }
        assertNotNull(writeAction, "A write action to persist identities should be dispatched.")
        assertEquals("identities.json", writeAction.payload?.get("subpath").toString().trim('"'))
        assertTrue(writeAction.payload?.get("encrypt").toString().toBoolean(), "Persistence should be encrypted.")
        val content = writeAction.payload?.get("content").toString()
        assertTrue(content.contains("Test User"), "The new user's name should be in the persisted content.")

        // ASSERT: Broadcasting
        val broadcastAction = harness.processedActions.find { it.name == ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED }
        assertNotNull(broadcastAction, "A broadcast action should be dispatched.")
        val broadcastContent = broadcastAction.payload.toString()
        assertTrue(broadcastContent.contains("Test User"), "The new user's name should be in the broadcast payload.")
    }
}