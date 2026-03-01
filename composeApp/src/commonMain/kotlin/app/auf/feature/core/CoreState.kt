package app.auf.feature.core

import app.auf.core.Action
import app.auf.core.FeatureState
import app.auf.core.Identity
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The explicit lifecycle state of the application.
 * Managed exclusively by the CoreFeature in response to Actions from the main host.
 */
enum class AppLifecycle {
    BOOTING,                // The initial state before any actions are dispatched.
    INITIALIZING,           // The stage for registering settings and loading from disk.
    RUNNING,                // The application is fully hydrated and operational.
    CLOSING                 // The application is shutting down.
}

/**
 * A generic, serializable request to show a confirmation dialog.
 * This is used by the CoreFeature to display modal dialogs on behalf of other features.
 */
@Serializable
data class ConfirmationDialogRequest(
    val title: String,
    val text: String,
    val confirmButtonText: String,
    val requestId: String,
    val cancelButtonText: String? = "Cancel",
    val isDestructive: Boolean = false,
    @Transient val originator: String = "" // THE FIX: Capture the original requester.
)

/**
 * Tracks a user command dispatched via ACTION_CREATED so that CoreFeature can
 * route targeted RETURN_* data back to the originating session via
 * commandbot.DELIVER_TO_SESSION.
 */
@Serializable
data class PendingCommand(
    val correlationId: String,
    val sessionId: String,
    val actionName: String,
    val originatorId: String,
    val createdAt: Long
)

@Serializable
data class CoreState(
    val toastMessage: String? = null,
    val activeViewKey: String = "feature.session.main",
    val defaultViewKey: String = "feature.session.main",
    val lifecycle: AppLifecycle = AppLifecycle.BOOTING,
    // Add window dimensions to the state with sensible defaults.
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,

    // --- User Identity Management State (DEPRECATED — Phase 2.2) ---
    // userIdentities is kept for backward compatibility during the migration period.
    // New code should read from AppState.identityRegistry filtered by parentHandle == "core".
    // Will be removed in Phase 4.
    @Deprecated(
        message = "Use AppState.identityRegistry filtered by parentHandle == \"core\" instead.",
        replaceWith = ReplaceWith("AppState.identityRegistry")
    )
    val userIdentities: List<Identity> = emptyList(),
    val activeUserId: String? = null,

    // --- Transient State for Global UI ---
    @Transient
    val confirmationRequest: ConfirmationDialogRequest? = null,

    // --- Transient State for Command Result Routing ---
    /** Maps correlationId → PendingCommand for in-flight user commands.
     *  Used to route targeted RETURN_* data to the session via DELIVER_TO_SESSION. */
    @Transient
    val pendingCommands: Map<String, PendingCommand> = emptyMap()
) : FeatureState