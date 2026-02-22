package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.core.Version
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * To orchestrate the "Think" phase of the Agent's lifecycle.
 * It is the canonical handler for:
 * 1. Gathering Context (Ledger + HKG + Workspace)
 * 2. Formulating the Prompt (via Strategy)
 * 3. Processing the Response (via Strategy)
 *
 * [PHASE 1] Uses typed identity accessors (.identityUUID, .identityHandle) throughout.
 *
 * [PHASE 4] ALL implicit strategy checks removed. Context requests that were
 * previously hard-coded to SovereignHKGResourceLogic are now dispatched
 * polymorphically via CognitiveStrategy.requestAdditionalContext(). The context
 * gate check uses CognitiveStrategy.needsAdditionalContext() instead of
 * checking agent.knowledgeGraphId directly.
 */
object AgentCognitivePipeline {

    private val json = Json { ignoreUnknownKeys = true }
    private const val LOG_TAG = "AgentCognitivePipeline"

    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")

    fun startCognitiveCycle(agentId: IdentityUUID, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "startCognitiveCycle: Agent state missing.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "startCognitiveCycle: Agent '$agentId' not found.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: AgentStatusInfo()

        if (statusInfo.status == AgentStatus.PROCESSING && agent.isAgentActive) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "startCognitiveCycle: Agent '$agentId' is already processing. Ignoring.")
            return
        }

        val contextSessionId = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
            val msg = "Cannot start turn: Agent has no session for context."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        if (statusInfo.turnMode == TurnMode.DIRECT) {
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.PROCESSING)
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId.uuid); put("step", "Requesting Ledger")
        }))
        store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", contextSessionId.handle); put("correlationId", agentId.uuid)
        }))
    }

    /**
     * Handles targeted actions delivered to the agent feature by the Store.
     * Replaces the former handlePrivateData — action.name replaces envelope.type,
     * action.payload replaces envelope.payload.
     */
    fun handleTargetedAction(action: Action, store: Store) {
        val payload = action.payload ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleTargetedAction: Action '${action.name}' has no payload. Ignoring.")
            return
        }
        when (action.name) {
            ActionRegistry.Names.SESSION_RETURN_LEDGER -> handleLedgerResponse(payload, store)
            ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT -> handleHkgContextResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> handleWorkspaceListingResponse(payload, store)
            ActionRegistry.Names.GATEWAY_RETURN_RESPONSE -> handleGatewayResponse(payload, store)
            ActionRegistry.Names.GATEWAY_RETURN_PREVIEW -> handleGatewayPreviewResponse(payload, store)
            else -> {
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "handleTargetedAction: Received unrecognised action '${action.name}'. Ignoring."
                )
            }
        }
    }

    private fun handleLedgerResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleLedgerResponse: Missing correlationId.")
            return
        }
        val agentId = IdentityUUID(agentIdStr)

        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val msg = "Failed to parse ledger response: ${e.message}"
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleLedgerResponse: Agent feature state missing. Dropping ledger response for correlationId='$agentId'.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleLedgerResponse: Agent '$agentId' not found in state. Dropping ledger response.")
            return
        }

        val enrichedMessages = decoded.messages.mapNotNull { element ->
            try {
                val entryJson = element.jsonObject
                val senderId = entryJson["senderId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawContent = entryJson["rawContent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timestamp = entryJson["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null

                val user = state.userIdentities.find { it.handle == senderId }
                // Dual check: senderId may be UUID (old ledger entries) or handle (new entries)
                val isSelf = (senderId == agent.identityUUID.uuid || senderId == agent.identityHandle.handle)
                val (senderName, role) = when {
                    isSelf -> agent.identity.name to "model"
                    state.agents.values.any { it.identityUUID.uuid == senderId || it.identityHandle.handle == senderId } -> {
                        val otherAgent = state.agents.values.first { it.identityUUID.uuid == senderId || it.identityHandle.handle == senderId }
                        otherAgent.identity.name to "user"
                    }
                    user != null -> user.name to "user"
                    else -> "Unknown" to "user"
                }
                GatewayMessage(role, rawContent, senderId, senderName, timestamp)
            } catch (e: Exception) {
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "handleLedgerResponse: Failed to parse a ledger message for agent '$agentId': ${e.message}. Skipping."
                )
                null
            }
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agent.identityUUID.uuid)
            put("messages", json.encodeToJsonElement(enrichedMessages))
        }))
    }

    /**
     * [7b] Handles the workspace listing response from the filesystem feature.
     * Extracts the correlationId (agentId), formats the listing, and stages it via action.
     */
    private fun handleWorkspaceListingResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            // No correlationId means this listing wasn't requested by the pipeline — ignore.
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleWorkspaceListingResponse: No correlationId — not a pipeline-initiated listing. Ignoring.")
            return
        }

        val formattedContext = WorkspaceContextProvider.formatListingResponse(payload, store.platformDependencies)

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_CONTEXT, buildJsonObject {
            put("agentId", agentIdStr)
            put("context", formattedContext)
        }))
    }

    private fun handleHkgContextResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleHkgContextResponse: Missing correlationId in payload.")
            return
        }
        val hkgContext = payload["context"]?.jsonObject

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_HKG_CONTEXT, buildJsonObject {
            put("agentId", agentIdStr)
            put("context", hkgContext ?: buildJsonObject {})
        }))
    }

    /**
     * [7c] Called after the ledger is staged. Dispatches ALL context requests in parallel
     * and sets up a timeout. Does NOT call executeTurn directly — the gate handles it.
     */
    fun evaluateTurnContext(agentId: IdentityUUID, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateTurnContext: Agent feature state missing. Cannot evaluate context for '$agentId'.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateTurnContext: Agent '$agentId' not found.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateTurnContext: No status entry for agent '$agentId'. Turn may have been cancelled.")
            return
        }

        if (statusInfo.stagedTurnContext == null) {
            val msg = "Turn Context Missing for '$agentId'. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        // 1. Record the context gathering start time (for timeout validation)
        val startedAt = store.platformDependencies.currentTimeMillis()
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", startedAt)
        }))

        // 2. Dispatch workspace listing request (parallel)
        val safeAgentId = agentId.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        store.deferredDispatch("agent", Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "$safeAgentId/workspace")
            put("recursive", true)
            put("correlationId", agentId.uuid)
        }))

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId.uuid); put("step", "Gathering Context")
        }))

        // [PHASE 4] Polymorphic: let the strategy request any additional context it needs.
        // Replaces: SovereignHKGResourceLogic.requestContextIfSovereign(store, agent)
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        strategy.requestAdditionalContext(agent, store)

        // 4. Schedule timeout after 10 seconds
        store.scheduleDelayed(10_000L, "agent", Action(ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT, buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", startedAt)
        }))

        // Do NOT call executeTurn here — the gate (evaluateFullContext) will handle it
    }

    /**
     * [7d] The unified gate function. Called whenever a context response arrives or the timeout fires.
     * Proceeds to executeTurn only when all expected contexts are ready (or timeout forces it).
     */
    fun evaluateFullContext(agentId: IdentityUUID, store: Store, isTimeout: Boolean = false) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateFullContext: Agent feature state missing for '$agentId'. Context gate cannot proceed.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateFullContext: Agent '$agentId' not found. May have been deleted mid-turn.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateFullContext: No status entry for agent '$agentId'. Cannot evaluate context gate.")
            return
        }

        // Bail if not in an active turn.
        if (statusInfo.contextGatheringStartedAt == null) {
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG, "evaluateFullContext: contextGatheringStartedAt is null for '$agentId'. No active context-gathering phase — ignoring.")
            return
        }

        val ledgerContext = statusInfo.stagedTurnContext
        if (ledgerContext == null) {
            val msg = "Context arrived for '$agentId' without staged ledger context. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, "Context assembly failed.")
            return
        }

        val workspaceReady = statusInfo.transientWorkspaceContext != null

        // [PHASE 4] Polymorphic: ask the strategy if it expects additional context.
        // Replaces: val expectsHkg = !agent.knowledgeGraphId.isNullOrBlank()
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val expectsAdditionalContext = strategy.needsAdditionalContext(agent)
        val additionalContextReady = !expectsAdditionalContext || statusInfo.transientHkgContext != null

        if (workspaceReady && additionalContextReady) {
            // FIX: Close the gate immediately before dispatching executeTurn so that a concurrent
            // timeout callback cannot re-enter and produce a duplicate GATEWAY_GENERATE_CONTENT.
            // The timeout's stale-guard checks contextGatheringStartedAt; nulling it here ensures
            // the guard rejects the timeout even if it fires before SET_STATUS(IDLE) is processed.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
                put("agentId", agentId.uuid)
                put("startedAt", JsonNull)
            }))
            executeTurn(agent, ledgerContext, statusInfo.transientHkgContext, state, store)
        } else if (isTimeout) {
            val missing = mutableListOf<String>()
            if (!workspaceReady) missing.add("workspace")
            if (!additionalContextReady) missing.add("strategy-context")
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "Context gathering timeout for agent '$agentId'. Missing: ${missing.joinToString(", ")}. Proceeding without.")
            // FIX: Same gate-closing dispatch on the timeout path to prevent any subsequent
            // context-arrival callbacks from triggering a second executeTurn.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
                put("agentId", agentId.uuid)
                put("startedAt", JsonNull)
            }))
            executeTurn(agent, ledgerContext, statusInfo.transientHkgContext, state, store)
        }
    }

    /**
     * [7e] Kept as a thin wrapper for backward compatibility.
     */
    fun evaluateHkgContext(agentId: IdentityUUID, store: Store) {
        evaluateFullContext(agentId, store)
    }

    private fun executeTurn(
        agent: AgentInstance,
        ledgerContext: List<GatewayMessage>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val agentUuid = agent.identityUUID
        val statusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
        val platformDependencies = store.platformDependencies

        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
            "Assembling prompt for '${agentUuid}' using strategy '${strategy.identityHandle}' (State: ${abbreviate(cognitiveState.toString(),30)}).")

        // === RESOURCE RESOLUTION ===
        val resolvedResources = resolveAgentResources(agent, agentState.resources, strategy, platformDependencies, store)
        if (resolvedResources == null) {
            return
        }

        val contextMap = mutableMapOf<String, String>()

        if (hkgContext != null) {
            contextMap["HOLON_KNOWLEDGE_GRAPH"] = hkgContext.entries.joinToString("\n\n---\n\n") { (holonId, content) ->
                "--- START OF FILE $holonId.json ---\n${content.jsonPrimitive.content}\n--- END OF FILE $holonId.json ---"
            }
        }

        // === SESSION METADATA (with token usage context) ===
        val sessionId = agent.subscribedSessionIds.firstOrNull()
        val sessionName = sessionId?.let { store.state.value.identityRegistry["session.${it.handle}"]?.name } ?: "Unknown Session"
        val lastInput = statusInfo.lastInputTokens
        val lastOutput = statusInfo.lastOutputTokens
        val tokenUsageContext = if (lastInput != null || lastOutput != null) {
            val total = (lastInput ?: 0) + (lastOutput ?: 0)
            val inputStr = lastInput?.let { "$it" } ?: "N/A"
            val outputStr = lastOutput?.let { "$it" } ?: "N/A"
            "\nLast request token usage (your approximate context size): $total tokens ($inputStr input, $outputStr output). Your model token maximum and saturation point varies by model and context coherence/complexity — consult your provider documentation."
        } else {
            "\nLast request token usage: Not yet available (first turn or provider did not report usage)."
        }

        contextMap["SESSION_METADATA"] = """
            This data is provided for you to reason about your running environment and is updated on the moment of latest request to you.
            
            You are running on platform: 'AUF App ${Version.APP_VERSION} (Windows), a multi-agent, multi-session agent/chat platform.'
            Your Host LLM (API connection): '${agent.modelProvider}' / '${agent.modelName}'
            You are currently participating in a multi-party chat session: '${sessionName}', id: '${sessionId?.handle ?: "none"}'
            Your agent handle is: '${agent.identityHandle}'
            Your agent id (internal): '${agentUuid}'
            Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())}
        """.trimIndent()
        contextMap["SESSION_METADATA"]+= tokenUsageContext

        // ============================================================
        // Inject available system actions for agent tooling
        // ============================================================
        contextMap["AVAILABLE_ACTIONS"] = ExposedActionsContextProvider.generateContext()

        // ============================================================
        // Inject workspace file awareness context
        // ============================================================
        statusInfo.transientWorkspaceContext?.takeIf { it.isNotBlank() }?.let {
            contextMap["WORKSPACE_FILES"] = it
        }


        // ============================================================
        // Format messages with sender context for multi-agent clarity
        // ============================================================
        val formattedMessages = ledgerContext.map { msg ->
            val formattedTimestamp = platformDependencies.formatIsoTimestamp(msg.timestamp)
            val formattedContent = "${msg.senderName} (${msg.senderId}) @ $formattedTimestamp: ${msg.content}"
            msg.copy(content = formattedContent)
        }

        // ============================================================
        // Build multi-agent context for system prompt
        // ============================================================
        val participants = ledgerContext
            .map { it.senderId to it.senderName }
            .distinct()

        if (participants.size > 2) {
            val multiAgentContext = buildString {
                appendLine("\n--- MULTI-AGENT ENVIRONMENT ---")
                appendLine("This is a multi-agent conversation with the following participants:")
                participants.forEach { (id, name) ->
                    val isSelf = (id == agentUuid.uuid || id == agent.identityHandle.handle)
                    val type = when {
                        isSelf -> "YOU (this agent)"
                        agentState.agents.values.any { it.identityUUID.uuid == id || it.identityHandle.handle == id } -> "AI Agent"
                        agentState.userIdentities.any { it.handle == id } -> "Human User"
                        else -> "User/System"
                    }
                    appendLine("- $name ($id): $type")
                }
                appendLine()
                appendLine("IMPORTANT: Each message in the conversation is prefixed with 'SenderName (senderId) @ timestamp:'")
                appendLine("This helps you understand who said what and when.")
                appendLine("When YOU respond, do NOT include this prefix. Just write your response naturally.")
                appendLine("The system will automatically add your name and timestamp to your messages.")
            }
            contextMap["MULTI_AGENT_CONTEXT"] = multiAgentContext
        }

        // ============================================================
        // Build structured session subscription context
        // ============================================================
        val identityRegistry = store.state.value.identityRegistry
        val outputHandle = agent.outputSessionId?.handle
        val subscribedSessionInfos = agent.subscribedSessionIds.map { sessId ->
            val sessName = identityRegistry["session.${sessId.handle}"]?.name
                ?: agentState.subscribableSessionNames[sessId]
                ?: sessId.handle
            SessionInfo(
                handle = sessId.handle,
                name = sessName,
                isOutput = sessId.handle == outputHandle
            )
        }

        val context = AgentTurnContext(
            agentName = agent.identity.name,
            resolvedResources = resolvedResources,
            gatheredContexts = contextMap,
            subscribedSessions = subscribedSessionInfos,
            outputSessionHandle = outputHandle
        )

        val systemPrompt = strategy.prepareSystemPrompt(context, cognitiveState)

        val requestActionName = if (statusInfo.turnMode == TurnMode.PREVIEW) ActionRegistry.Names.GATEWAY_PREPARE_PREVIEW else ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
        val step = if (statusInfo.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentUuid.uuid); put("step", step)
        }))

        store.deferredDispatch("agent", Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agentUuid.uuid)
            put("contents", json.encodeToJsonElement(formattedMessages))
            put("systemPrompt", systemPrompt)
        }))
    }

    /**
     * Resolves agent.resources map (slotId → resourceId) to actual content.
     * Validates that all required resources are present.
     * Returns null on validation failure (error already reported).
     */
    private fun resolveAgentResources(
        agent: AgentInstance,
        loadedResources: List<AgentResource>,
        strategy: CognitiveStrategy,
        platformDeps: PlatformDependencies,
        store: Store
    ): Map<String, String>? {
        val resolved = mutableMapOf<String, String>()
        val missingRequired = mutableListOf<String>()

        for (slot in strategy.getResourceSlots()) {
            val resourceId = agent.resources[slot.slotId]

            if (resourceId == null) {
                if (slot.isRequired) {
                    missingRequired.add(slot.displayName)
                }
                continue
            }

            val resource = loadedResources.find { it.id == resourceId.uuid }
            if (resource == null) {
                platformDeps.log(LogLevel.ERROR, LOG_TAG,
                    "Agent '${agent.identityUUID}' references unknown resource '$resourceId' for slot '${slot.slotId}'.")
                missingRequired.add("${slot.displayName} (broken reference: $resourceId)")
                continue
            }

            resolved[slot.slotId] = resource.content
        }

        if (missingRequired.isNotEmpty()) {
            val errorMsg = "Missing required resources: ${missingRequired.joinToString(", ")}"
            platformDeps.log(LogLevel.ERROR, LOG_TAG, "Agent '${agent.identityUUID}': $errorMsg")
            AgentAvatarLogic.updateAgentAvatars(agent.identityUUID, store, AgentStatus.ERROR, errorMsg)
            store.dispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                put("message", "Agent '${agent.identity.name}': $errorMsg")
            }))
            return null
        }

        return resolved
    }

    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try {
            json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload)
        } catch (e: Exception) {
            store.platformDependencies.log(
                LogLevel.ERROR, LOG_TAG,
                "handleGatewayPreviewResponse: Failed to parse preview response: ${e.message}"
            )
            return
        }
        val agentId = IdentityUUID(decoded.correlationId)
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayPreviewResponse: Agent state missing.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleGatewayPreviewResponse: Agent '$agentId' not found. May have been deleted during preview.")
            return
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.identityUUID.uuid)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
            decoded.estimatedInputTokens?.let { put("estimatedInputTokens", it) }
        }))
        store.dispatch("ui.agent", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.context_viewer") }))
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentIdStr == null) {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Missing correlationId in gateway response payload.")
            return
        }
        val agentId = IdentityUUID(agentIdStr)
        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            store.platformDependencies.log(
                LogLevel.ERROR, LOG_TAG,
                "handleGatewayResponse: Failed to parse gateway response for agent '$agentId': ${e.message}"
            )
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, "Failed to parse gateway response.")
            return
        }
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Agent state missing.")
            return
        }
        val agent = agentState.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleGatewayResponse: Agent '$agentId' not found. May have been deleted during generation.")
            return
        }
        val agentUuid = agent.identityUUID
        val targetSessionId = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Agent '$agentUuid' has no target session to post response to.")
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, AgentStatus.ERROR, "No target session for response.")
            return
        }

        if (decoded.errorMessage != null) {
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
            return
        }

        val rawContent = decoded.rawContent ?: ""

        // Cognitive Strategy Post-Processing
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        val result = strategy.postProcessResponse(rawContent, cognitiveState)

        // 1. Handle State Updates
        if (result.newState != cognitiveState) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
                put("agentId", agentIdStr)
                put("state", result.newState)
            }))
        }

        // 2. Handle Sentinel Actions
        if (result.action == SentinelAction.HALT_AND_SILENCE) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "Agent '$agentUuid' halted by Cognitive Strategy (Sentinel Action).")
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, AgentStatus.IDLE, "Halted by Internal Sentinel.")
            return
        }

        // 4. Run system sentinels
        var contentToPost = rawContent
        val match = redundantHeaderRegex.find(contentToPost)
        if (match != null) {
            contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
            store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", targetSessionId.handle)
                put("senderId", "system")
                put("message", """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.identity.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. The host system adds this automatically.""")
            }))
        }

        // 4. Proceed to Post — senderId is now the agent's handle for bus addressing
        store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", targetSessionId.handle); put("senderId", agent.identityHandle.handle); put("message", contentToPost)
        }))
        AgentAvatarLogic.updateAgentAvatars(agentUuid, store, AgentStatus.IDLE)

        // Forward token usage to agent state for display on the avatar card
        if (decoded.inputTokens != null || decoded.outputTokens != null) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
                put("agentId", agentUuid.uuid)
                put("status", AgentStatus.IDLE.name)
                decoded.inputTokens?.let { put("lastInputTokens", it) }
                decoded.outputTokens?.let { put("lastOutputTokens", it) }
            }))
        } else {
            store.platformDependencies.log(
                LogLevel.WARN, LOG_TAG,
                "Gateway response for agent '$agentUuid' contained no token usage data. " +
                        "Provider '${agent.modelProvider}' may not support usage reporting or there is a deserialization issue."
            )
        }
    }
}