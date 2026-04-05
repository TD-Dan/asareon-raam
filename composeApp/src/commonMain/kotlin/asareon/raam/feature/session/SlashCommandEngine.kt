package asareon.raam.feature.session

import asareon.raam.core.Identity
import asareon.raam.core.Version
import asareon.raam.core.generated.ActionRegistry.ActionDescriptor
import asareon.raam.core.generated.ActionRegistry.FeatureDescriptor
import kotlinx.serialization.json.*

/**
 * Pure-logic engine for the slash-command autocomplete. No Compose or Store dependency.
 *
 * Reads from ActionRegistry.features and AppState.identityRegistry to provide:
 * - Feature and action candidate filtering with prefix/substring matching
 * - Context auto-fill for session handle and user identity
 * - raam_ code block generation
 * - Three-stage state machine (FEATURE → ACTION → PARAMS)
 *
 * Two modes:
 * - **Normal** (single `/`): Only shows `public: true` actions the user can invoke.
 * - **Admin** (double `//`): Shows ALL actions regardless of flags. Debug tool.
 */
class SlashCommandEngine(
    private val featureDescriptors: Map<String, FeatureDescriptor>,
    private val identityRegistry: Map<String, Identity>,
    private val activeSessionLocalHandle: String?,
    private val activeUserId: String?
) {

    // ========================================================================
    // Public Types
    // ========================================================================

    enum class Stage { FEATURE, ACTION, PARAMS }
    enum class MatchType { PREFIX, SUBSTRING }

    data class AutocompleteState(
        val stage: Stage,
        val adminMode: Boolean,
        val query: String = "",
        val selectedFeature: String? = null,
        val selectedAction: ActionDescriptor? = null,
        val highlightedIndex: Int = 0,
        val paramValues: Map<String, String> = emptyMap()
    )

    data class FeatureCandidate(
        val name: String,
        val summary: String,
        val actionCount: Int,
        val matchType: MatchType
    )

    data class ActionCandidate(
        val descriptor: ActionDescriptor,
        val matchType: MatchType
    )

    // ========================================================================
    // State Machine
    // ========================================================================

    /** Creates the initial autocomplete state. */
    fun initialState(adminMode: Boolean): AutocompleteState = AutocompleteState(
        stage = Stage.FEATURE,
        adminMode = adminMode
    )

    /** Advances from FEATURE → ACTION stage. */
    fun selectFeature(state: AutocompleteState, featureName: String): AutocompleteState =
        state.copy(
            stage = Stage.ACTION,
            selectedFeature = featureName,
            query = "",
            highlightedIndex = 0
        )

    /** Advances from ACTION → PARAMS stage, applying auto-fill. */
    fun selectAction(state: AutocompleteState, descriptor: ActionDescriptor): AutocompleteState =
        state.copy(
            stage = Stage.PARAMS,
            selectedAction = descriptor,
            query = "",
            highlightedIndex = 0,
            paramValues = autoFillParams(descriptor)
        )

    /**
     * Regresses one stage. Returns null if already at FEATURE (signals dismiss).
     */
    fun regressStage(state: AutocompleteState): AutocompleteState? = when (state.stage) {
        Stage.FEATURE -> null // Signal: dismiss the popup
        Stage.ACTION -> state.copy(
            stage = Stage.FEATURE,
            selectedFeature = null,
            query = "",
            highlightedIndex = 0
        )
        Stage.PARAMS -> state.copy(
            stage = Stage.ACTION,
            selectedAction = null,
            query = "",
            highlightedIndex = 0,
            paramValues = emptyMap()
        )
    }

    /** Updates the filter query (Stages 1 and 2). */
    fun updateQuery(state: AutocompleteState, query: String): AutocompleteState =
        state.copy(query = query, highlightedIndex = 0)

    /** Moves the highlight up (-1) or down (+1), clamped to [0, candidateCount). */
    fun moveHighlight(state: AutocompleteState, delta: Int, candidateCount: Int): AutocompleteState {
        if (candidateCount <= 0) return state
        val newIndex = (state.highlightedIndex + delta).coerceIn(0, candidateCount - 1)
        return state.copy(highlightedIndex = newIndex)
    }

    /** Updates a single param value in Stage 3. */
    fun updateParamValue(state: AutocompleteState, fieldName: String, value: String): AutocompleteState =
        state.copy(paramValues = state.paramValues + (fieldName to value))

    // ========================================================================
    // Candidate Generation
    // ========================================================================

    /**
     * Returns features that have at least one visible action, filtered by [query].
     * In admin mode, all features are shown. In normal mode, only features with
     * at least one `public: true` action are shown.
     */
    fun featureCandidates(query: String, adminMode: Boolean): List<FeatureCandidate> {
        return featureDescriptors.values
            .mapNotNull { feature ->
                val visibleCount = countVisibleActions(feature, adminMode)
                if (visibleCount == 0) return@mapNotNull null

                val matchType = matchName(feature.name, query) ?: return@mapNotNull null

                FeatureCandidate(
                    name = feature.name,
                    summary = feature.summary,
                    actionCount = visibleCount,
                    matchType = matchType
                )
            }
            .sortedWith(
                compareBy<FeatureCandidate> { it.matchType.ordinal } // PREFIX first
                    .thenBy { it.name }
            )
    }

    /**
     * Returns actions within [featureName] that are visible, filtered by [query]
     * against the action suffix. In admin mode, all actions are shown.
     */
    fun actionCandidates(featureName: String, query: String, adminMode: Boolean): List<ActionCandidate> {
        val feature = featureDescriptors[featureName] ?: return emptyList()

        return feature.actions.values
            .filter { adminMode || isUserInvocable(it) }
            .mapNotNull { descriptor ->
                val matchType = matchName(descriptor.suffix, query) ?: return@mapNotNull null
                ActionCandidate(descriptor = descriptor, matchType = matchType)
            }
            .sortedWith(
                compareBy<ActionCandidate> { it.matchType.ordinal } // PREFIX first
                    .thenBy { it.descriptor.suffix }
            )
    }

    // ========================================================================
    // Auto-Fill
    // ========================================================================

    /**
     * Returns pre-filled param values for the given action based on current context.
     * Only fills fields that actually exist in the action's payload schema.
     */
    fun autoFillParams(descriptor: ActionDescriptor): Map<String, String> {
        val fieldNames = descriptor.payloadFields.map { it.name }.toSet()
        val result = mutableMapOf<String, String>()

        if ("session" in fieldNames && activeSessionLocalHandle != null) {
            result["session"] = activeSessionLocalHandle
        }
        if ("sessionId" in fieldNames && activeSessionLocalHandle != null) {
            result["sessionId"] = activeSessionLocalHandle
        }
        if ("senderId" in fieldNames && activeUserId != null) {
            result["senderId"] = activeUserId
        }

        return result
    }

    // ========================================================================
    // Code Generation
    // ========================================================================

    /**
     * Generates a fenced code block from the completed parameter values.
     * Blank values are omitted. Booleans and numbers are serialized without quotes.
     */
    fun generateCodeBlock(
        descriptor: ActionDescriptor,
        paramValues: Map<String, String>
    ): String {
        val nonBlank = paramValues.filterValues { it.isNotBlank() }

        if (nonBlank.isEmpty()) {
            return "```${Version.APP_TOOL_PREFIX}${descriptor.fullName}\n```"
        }

        val payload = buildJsonObject {
            nonBlank.forEach { (key, value) ->
                when {
                    value == "true" || value == "false" -> put(key, value.toBoolean())
                    value.toLongOrNull() != null -> put(key, value.toLong())
                    value.toDoubleOrNull() != null -> put(key, value.toDouble())
                    else -> put(key, value)
                }
            }
        }

        val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
        return "```${Version.APP_TOOL_PREFIX}${descriptor.fullName}\n$pretty\n```"
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    /**
     * Determines whether an action should be visible in normal (non-admin) mode.
     * Only open (command) actions are user-invocable.
     */
    private fun isUserInvocable(descriptor: ActionDescriptor): Boolean = descriptor.public

    /**
     * Counts actions visible to the user within a feature.
     */
    private fun countVisibleActions(feature: FeatureDescriptor, adminMode: Boolean): Int =
        if (adminMode) feature.actions.size
        else feature.actions.values.count { isUserInvocable(it) }

    /**
     * Matches [query] against [name] with case-insensitive prefix and substring matching.
     * Returns null if no match, PREFIX if name starts with query, SUBSTRING otherwise.
     */
    private fun matchName(name: String, query: String): MatchType? {
        if (query.isEmpty()) return MatchType.PREFIX // Empty query matches everything as "prefix"
        val lowerName = name.lowercase()
        val lowerQuery = query.lowercase()
        return when {
            lowerName.startsWith(lowerQuery) -> MatchType.PREFIX
            lowerQuery in lowerName -> MatchType.SUBSTRING
            else -> null
        }
    }
}