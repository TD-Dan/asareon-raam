package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import app.auf.core.Version
import app.auf.util.abbreviate
import kotlinx.serialization.json.*

/**
 * ## Slice 4: The Thinker (Consolidated & Hardened)
 * The central orchestrator for the Agent's cognitive cycle.
 * It manages the asynchronous flow of:
 * 1. Starting a cycle (Status -> Processing)
 * 2. Gathering Context (Ledger -> HKG)
 * 3. Assembling the Prompt (System + HKG + Ledger)
 * 4. Dispatching to Gateway (Generate or Preview)
 */
object AgentCognitivePipeline {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Entry point: Initiates the cognitive cycle for an agent.
     */
    fun startCognitiveCycle(agentId: String, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return
        val statusInfo = state.agentStatuses[agentId] ?: AgentStatusInfo()

        // Guard: Prevent double-triggering
        if (statusInfo.status == AgentStatus.PROCESSING && agent.isAgentActive) return

        val contextSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Cannot start turn: Agent has no session for context.", store)
            return
        }

        // UI Update: Set status to PROCESSING (if Direct mode)
        if (statusInfo.turnMode == TurnMode.DIRECT) {
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)
        }

        // Step 1: Request Ledger Content (Always the first step)
        store.deferredDispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId); put("step", "Requesting Ledger")
        }))

        store.deferredDispatch("agent", Action(ActionNames.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
            put("sessionId", contextSessionId); put("correlationId", agentId)
        }))
    }

    /**
     * Handles all private data envelopes related to the cognitive cycle.
     */
    fun handlePrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER -> handleLedgerResponse(envelope.payload, store)
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> handleHkgContextResponse(envelope.payload, store)
            // Gateway responses are handled by the Feature because they might trigger Session posts directly
        }
    }

    private fun handleLedgerResponse(payload: JsonObject, store: Store) {
        // 1. Decode & Validate
        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
            if (agentId != null) AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Failed to parse ledger.", store)
            return
        }

        val agentId = decoded.correlationId
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return

        // 2. Enrich Messages
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
            } catch (e: Exception) {
                null
            }
        }

        // 3. Dispatch Action to Update State
        // BUG FIX: Race Condition. We DO NOT proceed to next step here.
        // We dispatch the action to update state. The onAction handler in Feature will call evaluateTurnContext.
        store.dispatch("agent", Action(ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT, buildJsonObject {
            put("agentId", agent.id)
            put("messages", json.encodeToJsonElement(enrichedMessages))
        }))
    }

    /**
     * [NEW] Called by AgentRuntimeFeature.onAction when AGENT_INTERNAL_STAGE_TURN_CONTEXT is processed.
     * This ensures state is consistent before making decisions.
     */
    fun evaluateTurnContext(agentId: String, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return
        val statusInfo = state.agentStatuses[agentId] ?: return

        // Guard: Ensure context exists
        if (statusInfo.stagedTurnContext == null) return

        // 4. Determine Next Step (Sovereign vs Vanilla)
        val isSovereign = SovereignAgentLogic.requestContextIfSovereign(store, agent)

        if (!isSovereign) {
            // Vanilla: Execute immediately
            executeTurn(agent, statusInfo.stagedTurnContext, null, state, store)
        }
        // If Sovereign, requestContextIfSovereign has dispatched the request.
        // We wait for handleHkgContextResponse -> AGENT_INTERNAL_SET_HKG_CONTEXT -> evaluateHkgContext
    }

    private fun handleHkgContextResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        val hkgContext = payload["context"]?.jsonObject

        // Dispatch action to update state
        store.dispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT, buildJsonObject {
            put("agentId", agentId)
            put("context", hkgContext ?: buildJsonObject {})
        }))
    }

    /**
     * [NEW] Called by AgentRuntimeFeature.onAction when AGENT_INTERNAL_SET_HKG_CONTEXT is processed.
     */
    fun evaluateHkgContext(agentId: String, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val agent = state.agents[agentId] ?: return
        val statusInfo = state.agentStatuses[agentId] ?: return

        // Validation: Ensure we have the prerequisite ledger context
        val ledgerContext = statusInfo.stagedTurnContext
        if (ledgerContext == null) {
            store.platformDependencies.log(LogLevel.ERROR, "agent", "HKG context received for '$agentId' without staged ledger context. Aborting.")
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "Context assembly failed.", store)
            return
        }

        // Execute
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
        assemblePromptAndRequestGeneration(
            agent = agent,
            statusInfo = statusInfo,
            ledgerContext = ledgerContext,
            hkgContext = hkgContext,
            agentState = agentState,
            store = store
        )
    }

    private fun assemblePromptAndRequestGeneration(
        agent: AgentInstance,
        statusInfo: AgentStatusInfo,
        ledgerContext: List<GatewayMessage>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val platformDependencies = store.platformDependencies

        val hkgContextContent = hkgContext?.entries?.joinToString("\n\n---\n\n") { (holonId, content) ->
            "--- START OF FILE $holonId.json ---\n${content.jsonPrimitive.content}\n--- END OF FILE $holonId.json ---"
        } ?: ""

        val sessionName = agent.subscribedSessionIds.firstOrNull()?.let { agentState.sessionNames[it] } ?: "Unknown Session"
        var systemPrompt = """
            --- SYSTEM BOOTSTRAP DIRECTIVES ---
            // You are an autonomous agent operating within the multi user and multi agent AUF App.
            // The following directives and context are provided for this turn.

            **OPERATIONAL DIRECTIVES:**
            *   **IDENTITY:** You are agent '${abbreviate(agent.name, 64)}' (ID: ${agent.id}).
            *   **FORMATTING:** Your response MUST be your direct reply only. DO NOT include prefixes (names, IDs, timestamps). The application handles all formatting.
            *   **DISCIPLINE:** You MUST NOT speak for or impersonate any other participant. Generate content only from your own perspective as "${abbreviate(agent.name, 64)}".

            **SITUATIONAL AWARENESS:**
            *   Platform: 'AUF App ${Version.APP_VERSION}'
            *   Host LLM: '${agent.modelProvider}'
            *   Host Model: '${agent.modelName}'
            *   Session: '${abbreviate(sessionName, 64)}'
            *   Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())}


        """.trimIndent()

        if (hkgContextContent.isNotEmpty()) {
            systemPrompt += """
            --- HOLON KNOWLEDGE GRAPH CONTEXT ---
            $hkgContextContent
            
            """.trimIndent()
        }

        val requestActionName = if (statusInfo.turnMode == TurnMode.PREVIEW) ActionNames.GATEWAY_PREPARE_PREVIEW else ActionNames.GATEWAY_GENERATE_CONTENT
        val step = if (statusInfo.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.dispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agent.id); put("step", step)
        }))

        store.dispatch("agent", Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", json.encodeToJsonElement(ledgerContext))
            put("systemPrompt", systemPrompt)
        }))
    }
}