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
    private val nvramFILENAME = "nvram.json"

    private val workspaceSubpathMarker = "/workspace/"

    private val commandBotSenderId = "commandbot"

    private val activeTurnJobs = mutableMapOf<String, Job>()
    private val avatarUpdateJobs = mutableMapOf<String, Job>()
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
        val crudState = AgentCrudLogic.reduce(currentFeatureState, action, platformDependencies)
        if (crudState !== currentFeatureState) return crudState
        return AgentRuntimeReducer.reduce(currentFeatureState, action, platformDependencies)
    }

    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val agentState = newState as? AgentRuntimeState ?: return
        when (action.name) {
            // --- Startup ---
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                // Inject built-in resources FIRST (ensures they're always available)
                AgentDefaults.builtInResources.forEach { resource ->
                    store.deferredDispatch(this.name, Action(
                        ActionNames.AGENT_INTERNAL_RESOURCE_LOADED,
                        json.encodeToJsonElement(resource) as JsonObject
                    ))
                }
                // Then load user-defined resources from disk
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST, buildJsonObject {
                    put("subpath", "resources")
                }))
                store.deferredDispatch(this.name, Action(ActionNames.GATEWAY_REQUEST_AVAILABLE_MODELS))
            }
            ActionNames.AGENT_INTERNAL_AGENTS_LOADED -> {
                // Seed nvram.json for any agent that doesn't have one yet
                agentState.agents.values.forEach { agent ->
                    if (agent.cognitiveState is JsonNull || agent.cognitiveState == null) {
                        saveAgentNvram(agent, store)
                    }
                }
                if (agentState.sessionNames.isNotEmpty()) {
                    SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
                }
            }
            ActionNames.AGENT_INTERNAL_VALIDATE_SOVEREIGN_STATE -> {
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }
            ActionNames.AGENT_INTERNAL_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return
                AgentAvatarLogic.updateAgentAvatars(agent.id, store, AgentStatus.IDLE)
                broadcastAgentNames(agentState, store)
            }
            ActionNames.AGENT_INTERNAL_RESOURCE_LOADED -> {
                // No side effects needed, pure reducer handles state merge
            }

            // --- Agent CRUD Side Effects ---
            ActionNames.AGENT_CREATE -> {
                val agentToSave = agentState.agents.values.lastOrNull() ?: return
                saveAgentConfig(agentToSave, store)
                broadcastAgentNames(agentState, store)
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
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

                SovereignHKGResourceLogic.handleSovereignAssignment(store, oldAgent, newAgent)
                SovereignHKGResourceLogic.handleSovereignRevocation(store, oldAgent, newAgent)

                saveAgentConfig(newAgent, store)
                broadcastAgentNames(agentState, store)
                AgentAvatarLogic.updateAgentAvatars(agentId, store)
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }
            ActionNames.AGENT_INTERNAL_NVRAM_LOADED -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                // Reducer replaced cognitiveState
                // Save to disk (handles both: strategy transitions AND redundant save from disk loading)
                saveAgentNvram(agent, store)
            }
            ActionNames.AGENT_UPDATE_NVRAM -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                // Reducer already merged updates into cognitiveState
                // Save directly to disk
                saveAgentNvram(agent, store)
            }
            ActionNames.AGENT_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                agentState.agentAvatarCardIds[agentId]?.forEach { (sessionId, messageId) ->
                    store.deferredDispatch(this.name, Action(ActionNames.SESSION_DELETE_MESSAGE, buildJsonObject {
                        put("session", sessionId)
                        put("messageId", messageId)
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", agentId) }))
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_PUBLISH_AGENT_DELETED, buildJsonObject { put("agentId", agentId) }))
                broadcastAgentNames(agentState, store)
            }

            // --- Resource CRUD Side Effects ---
            ActionNames.AGENT_CREATE_RESOURCE -> {
                val newResource = agentState.resources.lastOrNull() ?: return
                saveResourceConfig(newResource, store)
            }
            ActionNames.AGENT_SAVE_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToSave = agentState.resources.find { it.id == resourceId } ?: return
                saveResourceConfig(resourceToSave, store)
            }
            ActionNames.AGENT_DELETE_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return
                val resourceToDelete = (previousState as? AgentRuntimeState)?.resources?.find { it.id == resourceId } ?: return
                resourceToDelete.path?.let { path ->
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE, buildJsonObject {
                        put("subpath", path)
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.AGENT_SELECT_RESOURCE, buildJsonObject { put("resourceId", null as String?) }))
            }

            // --- Cognitive Pipeline & Peer Updates (Delegated) ---
            ActionNames.AGENT_INITIATE_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.startCognitiveCycle(agentId, store)
            }
            ActionNames.AGENT_INTERNAL_STAGE_TURN_CONTEXT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateTurnContext(agentId, store)
            }
            ActionNames.AGENT_INTERNAL_SET_HKG_CONTEXT -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                AgentCognitivePipeline.evaluateHkgContext(agentId, store)
            }
            ActionNames.AGENT_EXECUTE_PREVIEWED_TURN -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return
                val agent = agentState.agents[agentId] ?: return
                val statusInfo = agentState.agentStatuses[agentId]
                val previewData = statusInfo?.stagedPreviewData ?: return

                AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.PROCESSING)

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
                AgentAvatarLogic.updateAgentAvatars(agentId, store, AgentStatus.IDLE, "Turn cancelled by user.")
            }
            ActionNames.SESSION_PUBLISH_MESSAGE_POSTED -> {
                val prevAgentState = previousState as? AgentRuntimeState ?: return
                agentState.agents.keys.forEach { agentId ->
                    val prevStatus = prevAgentState.agentStatuses[agentId] ?: AgentStatusInfo()
                    val newStatus = agentState.agentStatuses[agentId] ?: AgentStatusInfo()

                    val statusChanged = prevStatus.status != newStatus.status
                    val frontierMoved = prevStatus.lastSeenMessageId != newStatus.lastSeenMessageId

                    if (statusChanged || frontierMoved) {
                        avatarUpdateJobs[agentId]?.cancel()
                        avatarUpdateJobs[agentId] = coroutineScope.launch {
                            delay(50)
                            AgentAvatarLogic.updateAgentAvatars(agentId, store)
                        }
                    }
                }
            }
            ActionNames.AGENT_INTERNAL_CHECK_AUTOMATIC_TRIGGERS -> {
                AgentAutoTriggerLogic.checkAndDispatchTriggers(store, agentState, platformDependencies, name)
            }
            ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED -> {
                SovereignHKGResourceLogic.ensureSovereignSessions(store, agentState)
            }
        }
    }

    private fun saveAgentConfig(agent: AgentInstance, store: Store) {
        // Exclude cognitiveState - it's saved separately in nvram.json
        val agentWithoutNvram = agent.copy(cognitiveState = JsonNull)
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.id}/$agentConfigFILENAME")
            put("content", json.encodeToString(agentWithoutNvram))
        }))
    }

    private fun saveAgentNvram(agent: AgentInstance, store: Store) {
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${agent.id}/$nvramFILENAME")
            put("content", json.encodeToString(agent.cognitiveState))
        }))
    }

    private fun saveResourceConfig(resource: AgentResource, store: Store) {
        resource.path?.let { path ->
            store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                put("subpath", path)
                put("content", json.encodeToString(resource))
            }))
        }
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
            ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT,
            ActionNames.Envelopes.GATEWAY_RESPONSE_RESPONSE,
            ActionNames.Envelopes.GATEWAY_RESPONSE_PREVIEW -> {
                AgentCognitivePipeline.handlePrivateData(envelope, store)
            }
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemListResponse(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemReadResponse(envelope.payload, store)
        }
    }

    private fun handleFileSystemListResponse(payload: JsonObject, store: Store) {
        val path = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: ""
        val listing = payload["listing"]?.jsonArray

        // Normalize for matching
        val normalizedPath = path.replace("\\", "/")

        when {
            // Root listing — discover agent directories
            normalizedPath == "" || normalizedPath == "." -> {
                val fileList = listing?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
                agentLoadCount = fileList.count { it.isDirectory && it.path != "resources" }

                if (agentLoadCount == 0) {
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
                } else {
                    fileList.forEach { entry ->
                        if (entry.isDirectory && entry.path != "resources") {
                            val agentDir = platformDependencies.getFileName(entry.path)
                            store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                                put("subpath", "$agentDir/$agentConfigFILENAME")
                            }))
                            store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                                put("subpath", "$agentDir/$nvramFILENAME")
                            }))
                        }
                    }
                }
            }

            // Resource listing
            normalizedPath == "resources" -> {
                listing?.forEach { element ->
                    val entry = json.decodeFromJsonElement<FileEntry>(element)
                    if (!entry.isDirectory && entry.path.endsWith(".json")) {
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                            val fileName = platformDependencies.getFileName(entry.path)
                            val canonicalPath = "resources/$fileName"
                            put("subpath", canonicalPath)
                        }))
                    }
                }
            }

            // ====== NEW: Agent workspace directory listings ======
            normalizedPath.contains("/workspace") -> {
                // Extract agent ID: "agent-xyz/workspace" or "agent-xyz/workspace/subdir"
                val agentId = normalizedPath.substringBefore("/workspace")
                val state = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agent = state.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, name,
                        "Workspace list response for unknown agent '$agentId'. Ignoring.")
                    return
                }
                val targetSessionId = getAgentResponseSessionId(agent) ?: run {
                    platformDependencies.log(LogLevel.WARN, name,
                        "Agent '${agent.id}' has no session for workspace list response.")
                    return
                }

                // Format as JSON code block for machine readability
                val workspaceRelativePath = normalizedPath.substringAfter("$agentId/workspace")
                    .removePrefix("/")
                    .ifBlank { "." }

                val listingJson = listing?.map { element ->
                    val entry = json.decodeFromJsonElement<FileEntry>(element)
                    // Strip the agent+workspace prefix so the agent sees workspace-relative paths
                    val relativePath = entry.path.replace("\\", "/")
                        .removePrefix("$agentId/workspace/")
                        .removePrefix("$agentId/workspace")
                    buildJsonObject {
                        put("path", relativePath)
                        put("isDirectory", entry.isDirectory)
                    }
                } ?: emptyList()

                val message = buildString {
                    appendLine("```json")
                    appendLine("{")
                    appendLine("  \"workspace_path\": \"$workspaceRelativePath\",")
                    appendLine("  \"entries\": ${Json.encodeToString(kotlinx.serialization.json.JsonArray(listingJson))}")
                    appendLine("}")
                    appendLine("```")
                }

                store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
        }
    }

    private fun handleFileSystemReadResponse(payload: JsonObject, store: Store) {
        // Normalize separators to '/' to ensure cross-platform matching logic
        val subpath = (payload["subpath"]?.jsonPrimitive?.contentOrNull ?: "").replace("\\", "/")
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return

        when {
            // ====== Agent workspace file responses ======
            subpath.contains(workspaceSubpathMarker) -> {
                val agentId = subpath.substringBefore(workspaceSubpathMarker)
                val relativeSubpath = subpath.substringAfter(workspaceSubpathMarker)
                val state = store.state.value.featureStates[name] as? AgentRuntimeState ?: return
                val agent = state.agents[agentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, name,
                        "Workspace read response for unknown agent '$agentId'. Ignoring.")
                    return
                }
                val targetSessionId = getAgentResponseSessionId(agent) ?: run {
                    platformDependencies.log(LogLevel.WARN, name,
                        "Agent '${agent.id}' has no session for workspace read response.")
                    return
                }

                val message = if (content != null) {
                    "```text\n[WORKSPACE FILE: $relativeSubpath]\n$content\n```"
                } else {
                    "```text\n[WORKSPACE ERROR] File not found: $relativeSubpath\n```"
                }

                store.deferredDispatch(this.name, Action(ActionNames.SESSION_POST, buildJsonObject {
                    put("session", targetSessionId)
                    put("senderId", commandBotSenderId)
                    put("message", message)
                }))
            }
            // Shared Resource files live under the "resources/" directory
            subpath.startsWith("resources/") -> {
                try {
                    val resource = json.decodeFromString<AgentResource>(content)
                    // Ensure the in-memory resource has a normalized path
                    val resWithPath = resource.copy(path = subpath)
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_RESOURCE_LOADED, json.encodeToJsonElement(resWithPath) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse resource: $subpath. Error: ${e.message}")
                }
            }
            // Agent config files
            subpath.endsWith("/$agentConfigFILENAME") -> {
                try {
                    val agent = json.decodeFromString<AgentInstance>(content)
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENT_LOADED, json.encodeToJsonElement(agent) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, name, "Failed to parse agent config from file: $subpath. Error: ${e.message}")
                } finally {
                    agentLoadCount--
                    if (agentLoadCount <= 0) {
                        store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_AGENTS_LOADED))
                    }
                }
            }
            // NVRAM files
            subpath.endsWith("/$nvramFILENAME") -> {
                try {
                    val nvramState = json.decodeFromString<JsonElement>(content)
                    // Extract agent ID from path (e.g., "agent-xyz/nvram.json" -> "agent-xyz")
                    val agentId = subpath.substringBeforeLast("/")

                    // Dispatch to merge NVRAM into the agent's state
                    store.deferredDispatch(this.name, Action(ActionNames.AGENT_INTERNAL_NVRAM_LOADED, buildJsonObject {
                        put("agentId", agentId)
                        put("state", nvramState)
                    }))
                } catch (e: Exception) {
                    // NVRAM file missing or corrupted is non-fatal - agent will use initial state
                    platformDependencies.log(LogLevel.WARN, name, "Failed to load NVRAM from $subpath (agent will use initial state): ${e.message}")
                }
            }
            // Unknown file — log and ignore, don't corrupt agent load tracking
            else -> {
                platformDependencies.log(LogLevel.WARN, name, "Received unexpected file read response for: $subpath. Ignoring.")
            }
        }
    }

    override val composableProvider: ComposableProvider = object : ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf(
                "feature.agent.manager" to { store, _ -> AgentManagerView(store, platformDependencies) },
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

/**
 * Determines the session where workspace operation responses should be posted.
 * For sovereign agents: their private session (isolated cognition).
 * For vanilla agents: their first subscribed session (where they participate).
 */
private fun getAgentResponseSessionId(agent: AgentInstance): String? {
    return agent.privateSessionId ?: agent.subscribedSessionIds.firstOrNull()
}