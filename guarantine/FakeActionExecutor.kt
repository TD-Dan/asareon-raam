package app.auf

import app.auf.core.HolonHeader
import app.auf.model.Action
import app.auf.service.ActionExecutor
import app.auf.service.ActionExecutorResult
import app.auf.util.JsonProvider
import app.auf.util.PlatformDependencies

/**
 * ---
 * ## Mandate
 * A test-only, in-memory implementation of the `ActionExecutor`. This fake allows us to
 * test the `StateManager`'s action-handling logic without modifying the actual file system.
 * It provides methods to verify which actions were "executed" and allows us to control
 * the success or failure result of the execution.
 *
 * ---
 * ## Dependencies
 * - Inherits from `app.auf.ActionExecutor`.
 * - Requires `PlatformDependencies` for its superclass constructor.
 *
 * @version 1.0
 * @since 2025-08-15
 */
class FakeActionExecutor(
    platform: PlatformDependencies
) : ActionExecutor(platform, JsonProvider.appJson) {

    /**
     * The canned response that this fake will return when `execute` is called.
     * Set to `Success` by default. Change to `Failure` to test error handling.
     */
    var resultToReturn: ActionExecutorResult = ActionExecutorResult.Success("Fake execution successful.")

    /**
     * Records the manifest that was passed to the `execute` method, allowing tests
     * to assert that the correct actions were sent.
     */
    var executedManifest: List<Action>? = null
        private set

    /**
     * Overrides the real `execute` to capture the incoming manifest and immediately
     * return the pre-configured `resultToReturn`, bypassing all file system interaction.
     */
    override fun execute(
        manifest: List<Action>,
        personaId: String,
        currentGraph: List<HolonHeader>
    ): ActionExecutorResult {
        executedManifest = manifest
        return resultToReturn
    }

    /**
     * A test utility to reset the state of the fake between tests.
     */
    fun reset() {
        executedManifest = null
        resultToReturn = ActionExecutorResult.Success("Fake execution successful.")
    }
}