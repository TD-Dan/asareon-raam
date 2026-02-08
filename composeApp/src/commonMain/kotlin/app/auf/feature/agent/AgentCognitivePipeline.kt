package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import app.auf.core.Version
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * To orchestrate the "Think" phase of the Agent's lifecycle.
 * It is the canonical handler for:
 * 1. Gathering Context (Ledger + HKG)
 * 2. Formulating the Prompt (via Strategy)
 * 3. Processing the Response (via Strategy)
 */
object AgentCognitivePipeline {

    private val json = Json { ignoreUnknownKeys = true }
    private const val LOG_TAG = "AgentCognitivePipeline"

    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")

    fun startCognitiveCycle(agentId: String, store: Store) {
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

        val contextSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
            val msg = "Cannot start turn: Agent has no session for context."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        if (statusInfo.turnMode == TurnMode.DIRECT) {
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.PROCESSING)
        }

        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId); put("step", "Requesting Ledger")
        }))
        store.deferredDispatch("agent", Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", contextSessionId); put("correlationId", agentId)
        }))
    }

    fun handlePrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleLedgerResponse(envelope.payload, store)
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> handleHkgContextResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> handleGatewayPreviewResponse(envelope.payload, store)
        }
    }

    private fun handleLedgerResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleLedgerResponse: Missing correlationId.")
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val msg = "Failed to parse ledger response: ${e.message}"
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return

        val enrichedMessages = decoded.messages.mapNotNull { element ->
            try {
                val entryJson = element.jsonObject
                val senderId = entryJson["senderId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawContent = entryJson["rawContent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timestamp = entryJson["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null

                val user = state.userIdentities.find { it.id == senderId }
                val (senderName, role) = when {
                    senderId == agent.id -> agent.name to "model"
                    state.agents.containsKey(senderId) -> state.agents[senderId]!!.name to "user"
                    user != null -> user.name to "user"
                    else -> "Unknown" to "user"
                }
                GatewayMessage(role, rawContent, senderId, senderName, timestamp)
            } catch (e: Exception) { null }
        }

        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agent.id)
            put("messages", json.encodeToJsonElement(enrichedMessages))
        }))
    }

    fun evaluateTurnContext(agentId: String, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateTurnContext: Agent '$agentId' not found.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: return

        if (statusInfo.stagedTurnContext == null) {
            val msg = "Turn Context Missing for '$agentId'. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, msg)
            return
        }

        val isSovereign = SovereignHKGResourceLogic.requestContextIfSovereign(store, agent)
        if (!isSovereign) {
            executeTurn(agent, statusInfo.stagedTurnContext, null, state, store)
        }
    }

    private fun handleHkgContextResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val hkgContext = payload["context"]?.jsonObject

        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT, buildJsonObject {
            put("agentId", agentId)
            put("context", hkgContext ?: buildJsonObject {})
        }))
    }

    fun evaluateHkgContext(agentId: String, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return
        val statusInfo = state.agentStatuses[agentId] ?: return
        val ledgerContext = statusInfo.stagedTurnContext

        if (ledgerContext == null) {
            val msg = "HKG context received for '$agentId' without staged ledger context. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.ERROR, "Context assembly failed.")
            return
        }

        executeTurn(agent, ledgerContext, statusInfo.transientHkgContext, state, store)
    }

    private fun executeTurn(
        agent: AgentInstance,
        ledgerContext: List<GatewayMessage>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val statusInfo = agentState.agentStatuses[agent.id] ?: AgentStatusInfo()
        val platformDependencies = store.platformDependencies

        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
            "Assembling prompt for '${agent.id}' using strategy '${strategy.id}' (State: ${abbreviate(cognitiveState.toString(),30)}).")

        // === RESOURCE RESOLUTION ===
        val resolvedResources = resolveAgentResources(agent, agentState.resources, strategy, platformDependencies, store)
        if (resolvedResources == null) {
            // Error already logged and status updated
            return
        }

        val contextMap = mutableMapOf<String, String>()

        if (hkgContext != null) {
            contextMap["HOLON_KNOWLEDGE_GRAPH"] = hkgContext.entries.joinToString("\n\n---\n\n") { (holonId, content) ->
                "--- START OF FILE $holonId.json ---\n${content.jsonPrimitive.content}\n--- END OF FILE $holonId.json ---"
            }
        }

        val sessionName = agent.subscribedSessionIds.firstOrNull()?.let { agentState.sessionNames[it] } ?: "Unknown Session"
        contextMap["SESSION_METADATA"] = """
            This data is provided for you to reason about your running environment and is updated on the moment of latest request to you.
            
            You are running on platform: 'AUF App ${Version.APP_VERSION} (Windows), a multi-agent, multi-session agent/chat platform.'
            Your Host LLM (API connection): '${agent.modelProvider}' / '${agent.modelName}'
            You are currently participating in a multi-party chat session: '${sessionName}'
            Your chat id is: '${agent.id}
            Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())}
        """.trimIndent()

        // ============================================================
        // Inject available system actions for agent tooling
        // ============================================================
        contextMap["AVAILABLE_ACTIONS"] = ExposedActionsContextProvider.generateContext()


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
                    val type = when {
                        id == agent.id -> "YOU (this agent)"
                        agentState.agents.containsKey(id) -> "AI Agent"
                        agentState.userIdentities.any { it.id == id } -> "Human User"
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

        val context = AgentTurnContext(
            agentName = agent.name,
            resolvedResources = resolvedResources,
            gatheredContexts = contextMap
        )

        val systemPrompt = strategy.prepareSystemPrompt(context, cognitiveState)

        val requestActionName = if (statusInfo.turnMode == TurnMode.PREVIEW) ActionNames.GATEWAY_PREPARE_PREVIEW else ActionNames.GATEWAY_GENERATE_CONTENT
        val step = if (statusInfo.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agent.id); put("step", step)
        }))

        store.deferredDispatch("agent", Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", json.encodeToJsonElement(formattedMessages))  // CHANGED: Use formatted messages
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

            if (resourceId.isNullOrBlank()) {
                if (slot.isRequired) {
                    missingRequired.add(slot.displayName)
                }
                continue
            }

            val resource = loadedResources.find { it.id == resourceId }
            if (resource == null) {
                platformDeps.log(LogLevel.ERROR, LOG_TAG,
                    "Agent '${agent.id}' references unknown resource '$resourceId' for slot '${slot.slotId}'.")
                missingRequired.add("${slot.displayName} (broken reference: $resourceId)")
                continue
            }

            resolved[slot.slotId] = resource.content
        }

        if (missingRequired.isNotEmpty()) {
            val errorMsg = "Missing required resources: ${missingRequired.joinToString(", ")}"
            platformDeps.log(LogLevel.ERROR, LOG_TAG, "Agent '${agent.id}': $errorMsg")
            AgentAvatarLogic.updateAgentAvatars(agent.id, store, AgentStatus.ERROR, errorMsg)
            store.dispatch("agent", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                put("message", "Agent '${agent.name}': $errorMsg")
            }))
            return null
        }

        return resolved
    }

    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload) } catch (e: Exception) { return }
        val agentId = decoded.correlationId
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return

        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.id)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
            decoded.estimatedInputTokens?.let { put("estimatedInputTokens", it) }
        }))
        store.dispatch("ui.agent", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.context_viewer") }))
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentId == null) return
        val decoded = try { json.decodeFromJsonElement<GatewayResponsePayload>(payload) } catch (e: Exception) { return }
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return
        val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: return

        if (decoded.errorMessage != null) {
            AgentAvatarLogic.updateAgentAvatars(agent.id, store, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
            return
        }

        val rawContent = decoded.rawContent ?: ""

        // Cognitive Strategy Post-Processing
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        val result = strategy.postProcessResponse(rawContent, cognitiveState)

        // 1. Handle State Updates
        if (result.newState != cognitiveState) {
            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_NVRAM_LOADED, buildJsonObject {
                put("agentId", agentId)
                put("state", result.newState)
            }))
        }

        // 2. Handle Sentinel Actions
        if (result.action == SentinelAction.HALT_AND_SILENCE) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "Agent '${agent.id}' halted by Cognitive Strategy (Sentinel Action).")
            AgentAvatarLogic.updateAgentAvatars(agent.id, store, AgentStatus.IDLE, "Halted by Internal Sentinel.")
            return
        }

        // 4. Run system sentinels
        var contentToPost = rawContent
        val match = redundantHeaderRegex.find(contentToPost)
        if (match != null) {
            contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
            store.deferredDispatch("agent", Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", targetSessionId)
                put("senderId", "system")
                put("message", """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. The host system adds this automatically.""")
            }))
        }

        // 4. Proceed to Post
        store.deferredDispatch("agent", Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", targetSessionId); put("senderId", agent.id); put("message", contentToPost)
        }))
        AgentAvatarLogic.updateAgentAvatars(agent.id, store, AgentStatus.IDLE)

        // Forward token usage to agent state for display on the avatar card
        if (decoded.inputTokens != null || decoded.outputTokens != null) {
            store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_STATUS, buildJsonObject {
                put("agentId", agent.id)
                put("status", AgentStatus.IDLE.name)
                decoded.inputTokens?.let { put("lastInputTokens", it) }
                decoded.outputTokens?.let { put("lastOutputTokens", it) }
            }))
        }
    }
}