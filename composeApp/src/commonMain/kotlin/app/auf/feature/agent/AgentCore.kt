package app.auf.agent

import app.auf.core.AppAction
import app.auf.core.AppState

/**
 * A stimulus is a discrete event or state change that an AgentCapability can observe and react to.
 * This is the core of the "stimulus-driven" architecture, replacing monolithic loops.
 */
sealed interface Stimulus {
    /**
     * Dispatched when a new entry is added to the session transcript.
     * @param newEntryId The ID of the newly posted ledger entry.
     */
    data class TranscriptUpdated(val newEntryId: Long) : Stimulus

    /**
     * Dispatched by the AgentRuntimeFeature when a period of user inactivity has passed,
     * granting an agent processing time for autonomous tasks.
     * @param timestamp The time the idle cycle was granted.
     */
    data class IdleCycleAvailable(val timestamp: Long) : Stimulus
}

/**
 * Represents the sovereign decision an AgentCapability makes after observing a stimulus.
 * It can choose to do nothing or to propose a single, specific AppAction for the
 * runtime to dispatch.
 */
sealed interface AgentDecision {
    /**
     * The capability has observed the stimulus and decided no action is necessary.
     */
    data object NoAction : AgentDecision

    /**
     * The capability proposes that the runtime dispatch the given action. This is the primary
     * mechanism for an agent to affect the application state or trigger side effects.
     * @param action The AppAction to be dispatched.
     */
    data class ProposeAction(val action: AppAction) : AgentDecision
}

/**
 * The universal contract for a modular, autonomous agent capability.
 *
 * A capability is a stateless observer that contains pure business logic. It receives the
 * complete AppState and a specific Stimulus, and returns a decision. All complex state
 * and lifecycle management is handled by the hosting AgentRuntimeFeature.
 */
interface AgentCapability {
    /**
     * A unique identifier for the capability, used for registration and logging.
     * E.g., "auf.capability.conversational_responder"
     */
    val id: String

    /**
     * The core logic function of the capability. It is called by the AgentRuntimeFeature
     * whenever a relevant stimulus occurs.
     *
     * @param stimulus The specific event or state change that occurred.
     * @param state The complete, immutable AppState at the time of the stimulus.
     * @return An [AgentDecision] telling the runtime what to do next.
     */
    fun onStimulus(stimulus: Stimulus, state: AppState): AgentDecision
}