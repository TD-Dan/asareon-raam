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

@Serializable
data class CoreState(
    val toastMessage: String? = null,
    val activeViewKey: String = "feature.session.main",
    val defaultViewKey: String = "feature.session.main",
    val lifecycle: AppLifecycle = AppLifecycle.BOOTING,
    // Add window dimensions to the state with sensible defaults.
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    // User Identity Management State
    val userIdentities: List<Identity> = emptyList(),
    val activeUserId: String? = null,

    // --- Transient State for Global UI ---
    @Transient
    val confirmationRequest: ConfirmationDialogRequest? = null
) : FeatureState