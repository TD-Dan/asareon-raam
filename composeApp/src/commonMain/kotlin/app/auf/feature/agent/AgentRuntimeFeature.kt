package app.auf.feature.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.auf.core.*
import app.auf.core.Feature.ComposableProvider
import app.auf.core.generated.ActionNames
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * ## The Executor (Refined)
 * A pure switchboard feature.
 * - Config/Persistence -> AgentCrudLogic
 * - Runtime State -> AgentRuntimeReducer
 * - Cognition -> AgentCognitivePipeline
 * - Side Effects -> AgentAvatarLogic / Self
 */
class AgentRuntimeFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "agent"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val agentConfigFILENAME = "agent.json"
    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")
    private var agentLoadCount = 0

    override fun init(store: Store) {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                val state = store.state.value.featureStates[name] as? AgentRuntimeState
                if (state != null) {
                    AgentAutoTriggerLogic.checkAndDispatchTriggers(store, state, platformDependencies, name)
                }
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? AgentRuntimeState ?: AgentRuntimeState()

        // 1. CRUD Logic (Persistence)
        val crudState = AgentCrudLogic.reduce(currentFeatureState, action, platformDependencies)
        if (crudState !== currentFeatureState) {
            return crudState
        }

        // 2. Runtime Logic (Ephemeral)
        return AgentRuntimeReducer.reduce(currentFeatureState, action, platformDependencies)
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val agentState = newState as? AgentRuntimeState ?: return
        when (action.name) {
            // --- Startup ---
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionNames.AGENT_INTERNAL_AGENTS_LOADED -> {
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_VALIDATE_SOVEREIGN_STATE))
            }
            ActionNames.AGENT_INTERNAL_VALIDATE_SOVEREIGN_STATE -> {
                SovereignAgentLogic.validateAndCorrectStartupState(store, agentState)
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()
                if (targetSessionId != null) {
                    AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
                }
                broadcastAgentNames(agentState, store)
            }

            // --- CRUD Side Effects ---
            ActionNames.AGENT_CREATE -> {
                val agentToSave = agentState.agents.values.lastOrNull() ?: return
                saveAgentConfig(agentToSave, store)
                broadcastAgentNames(agentState, store)
            }
            ActionNames.AGENT_CLONE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agentToClone = agentState.agents[agentId] ?: return
                val createPayload = buildJsonObject {
                    put("name", "${agentToClone.name} (Copy)")
                    agentToClone.knowledgeGraphId?.let { put("knowledgeGraphId", it) }
                    put("modelProvider", agentToClone.modelProvider)
                    put("modelName", agentToClone.modelName)
                    put("subscribedSessionIds", buildJsonArray { agentToClone.subscribedSessionIds.forEach { add(it) } })
                    put("automaticMode", agentToClone.automaticMode)
                    put("autoWaitTimeSeconds", agentToClone.autoWaitTimeSeconds)
                    put("autoMaxWaitTimeSeconds", agentToClone.autoMaxWaitTimeSeconds)
                }
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_CREATE, createPayload))
            }
            ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, ActionNames.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                saveAgentConfig(agent, store)
                AgentAvatarLogic.touchAgentAvatarCard(agent, agentState, store)
            }
            ActionNames.AGENT_UPDATE_CONFIG -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val oldAgent = (previousState as? AgentRuntimeState)?.agents?.get(agentId)
                val newAgent = agentState.agents[agentId] ?: return

                SovereignAgentLogic.handleSovereignAssignment(store, oldAgent, newAgent)
                SovereignAgentLogic.handleSovereignRevocation(store, oldAgent, newAgent)

                saveAgentConfig(newAgent, store)
                broadcastAgentNames(agentState, store)

                val currentStatus = agentState.agentStatuses[agentId]?.status ?: AgentStatus.IDLE
                if (oldAgent != null && (oldAgent.subscribedSessionIds != newAgent.subscribedSessionIds || oldAgent.privateSessionId != newAgent.privateSessionId)) {
                    AgentAvatarLogic.updateAgentAvatarCard(agentId, currentStatus, null, store)
                }
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agentAvatarCardIds[agentId]?.let { cardInfo ->
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", cardInfo.sessionId)
                        put("messageId", cardInfo.messageId)
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
                broadcastAgentNames(agentState, store)
            }

            // --- Cognitive Pipeline Entry Points ---
            ActionNames.AGENT_INITIATE_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
            }
            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> {
                // BUG FIX: Race condition resolution.
                // This logic now runs AFTER the reducer has updated the state.
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateTurnContext(agentId, store)
            }
            ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT -> {
                // As above, run logic after state update.
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateHkgContext(agentId, store)
            }
            ActionNames.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                val previewData = statusInfo?.stagedPreviewData ?: return

                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.PROCESSING, null, store)

                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                    put("providerId", agent.modelProvider)
                    put("modelName", previewData.agnosticRequest.modelName)
                    put("correlationId", previewData.agnosticRequest.correlationId)
                    put("contents", json.encodeToJsonElement(previewData.agnosticRequest.contents))
                    previewData.agnosticRequest.systemPrompt?.let { put("systemPrompt", it) }
                }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_DISCARD_PREVIEW, buildJsonObject { put("agentId", agentId) }))
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_DISCARD_PREVIEW -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                if (statusInfo?.status != AgentStatus.PROCESSING) {
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
                        put("agentId", agentId); put("step", JsonNull)
                    }))
                }
                store.dispatch("ui.agent", Action(ActionNames.CORE_SHOW_DEFAULT_VIEW))
            }
            ActionNames.AGENT_CANCEL_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_CANCEL_REQUEST, buildJsonObject {
                    put("correlationId", agentId)
                }))
                activeTurnJobs[agentId]?.cancel()
                activeTurnJobs.remove(agentId)
                AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.IDLE, "Turn cancelled by user.", store)
            }

            // --- Peer Updates ---
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                // Note: Reducer handles the state updates (waiting time, etc.)
                // We only need to check if we need to update the UI card to "WAITING" visually.
                val payload = action.payload?.let { json.decodeFromJsonElement<MessagePostedPayload>(it) } ?: return
                val entry = payload.entry
                val senderId = entry["senderId"]?.jsonPrimitive?.contentOrNull

                // If Sentinel system message, ignore.
                if (senderId == "system") return

                val metadata = entry["metadata"]?.jsonObject
                val isAvatar = metadata?.get("render_as_partial")?.jsonPrimitive?.booleanOrNull ?: false
                if (isAvatar) return

                agentState.agents.values.forEach { agent ->
                    // Logic for "Do we need to update the avatar card to show 'WAITING'?"
                    // This duplicates some reducer logic but is necessary to trigger the Side Effect (updateAgentAvatarCard)
                    if ((agent.subscribedSessionIds.contains(payload.sessionId) || agent.privateSessionId == payload.sessionId) && agent.id != senderId) {
                        val status = agentState.agentStatuses[agent.id]?.status ?: AgentStatus.IDLE
                        if (status == AgentStatus.IDLE) {
                            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.WAITING, null, store)
                        }
                    }
                }
            }
            ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS -> {
                AgentAutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, name)
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                SovereignAgentLogic.linkPrivateSessionOnCreation(store, agentState)
            }
        }
    }

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.id}/$agentConfigFILENAME")
            put("content", json.encodeToString(agent))
        }))
    }

    private fun broadcastAgentNames(state: AgentRuntimeState, store: Store) {
        val nameMap = state.agents.mapValues { it.value.name }
        store.deferredDispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(nameMap))
        }))
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.SESSION_RESPONSE_LEDGER,
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT -> {
                AgentCognitivePipeline.handlePrivateData(envelope, store)
            }

            ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE -> handleGatewayResponse(envelope.payload, store)
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> handleGatewayPreviewResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemListResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try { json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload) } catch (e: Exception) { return }
        val agent = (store.state.value.featureStates[name] as? AgentRuntimeState)?.agents?.get(decoded.correlationId) ?: return

        store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.id)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
        }))
        store.dispatch("ui.agent", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject {
            put("key", "feature.agent.context_viewer")
        }))
    }

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val fileList = payload["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
        agentLoadCount = fileList.count { it.isDirectory }
        if (agentLoadCount == 0) {
            store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
        } else {
            fileList.forEach { entry ->
                if (entry.isDirectory) {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                        put("subpath", "${platformDependencies.getFileName(entry.path)}/$agentConfigFILENAME")
                    }))
                }
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return
        try {
            val agent = json.decodeFromString<AgentInstance>(content)
            store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config from file: ${payload["subpath"]}. Error: ${e.message}")
        } finally {
            agentLoadCount--
            if (agentLoadCount <= 0) {
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
            }
        }
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentId = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentId == null || ("rawContent" !in payload && "errorMessage" !in payload)) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Received corrupted gateway response payload. Payload: $payload")
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "FATAL: Failed to parse gateway response. Error: ${e.message}")
            AgentAvatarLogic.updateAgentAvatarCard(agentId, AgentStatus.ERROR, "FATAL: Could not parse gateway response.", store)
            return
        }

        val agentState = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
        val agent = agentState.agents[decoded.correlationId] ?: return

        val targetSessionId = agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()

        if (targetSessionId == null) {
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "No session to post response.", store)
            return
        }

        if (decoded.errorMessage != null) {
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}", store)
        } else {
            var contentToPost = decoded.rawContent ?: ""
            val match = redundantHeaderRegex.find(contentToPost)
            if (match != null) {
                contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
                val warningMessage = """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. This is added automatically by the application."""
                store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", "system")
                    put("message", warningMessage)
                }))
            }

            store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                put("session", targetSessionId); put("senderId", agent.id); put("message", contentToPost)
            }))
            AgentAvatarLogic.updateAgentAvatarCard(agent.id, AgentStatus.IDLE, null, store)
        }
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf(
                "feature.agent.manager" to { store, _, -> AgentManagerView(store, platformDependencies) },
                "feature.agent.context_viewer" to { store, _ -> AgentContextView(store) }
            )
        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.agent.manager"
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("ui.ribbon", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Bolt, "Agent Manager", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        override fun PartialView(store: Store, partId: String, context: Any?) {
            if (partId != "agent.avatar") return
            val agentId = context as? String ?: return
            val appState by store.state.collectAsState()
            val state = appState.featureStates[name] as? AgentRuntimeState ?: return
            val agent = state.agents[agentId] ?: return
            AgentAvatarCard(agent = agent, store = store, platformDependencies = platformDependencies)
        }
    }
}