package app.auf.fakes

import app.auf.core.HolonHeader
import app.auf.model.Action
import app.auf.service.ActionExecutor
import app.auf.service.ActionExecutorResult
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json

/**
 * A fake implementation of the ActionExecutor for use in unit tests.
 * This allows us to control the outcome (success or failure) of the execute
 * function without performing any actual file I/O.
 */
class FakeActionExecutor(
    platform: PlatformDependencies,
    jsonParser: Json
) : ActionExecutor(platform, jsonParser) {

    // A configurable property to determine the result of the next execution.
    var nextResult: ActionExecutorResult = ActionExecutorResult.Success("Fake success")

    // A property to record the last manifest that was passed to execute().
    var lastExecutedManifest: List<Action>? = null

    override fun execute(
        manifest: List<Action>,
        personaId: String,
        currentGraph: List<HolonHeader>
    ): ActionExecutorResult {
        lastExecutedManifest = manifest
        return nextResult
    }
}