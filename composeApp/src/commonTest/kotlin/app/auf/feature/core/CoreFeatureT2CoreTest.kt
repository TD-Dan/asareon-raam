package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}