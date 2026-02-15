package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * To provide pure, testable reducer logic for the synchronous CRUD operations
 * of the AgentRuntimeFeature. This isolates the "administrative" state transitions
 * from the complex, asynchronous runtime logic.
 */
object AgentCrudLogic {

    private val json = Json { ignoreUnknownKeys = true }

    fun reduce(
        state: AgentRuntimeState,
        action: Action,
        platformDependencies: PlatformDependencies
    ): AgentRuntimeState {
        return when (action.name) {
            ActionRegistry.Names.AGENT_CREATE -> {
                val payload = action.payload ?: return state
                val uuid = platformDependencies.generateUUID()
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "New Agent"
                val newAgent = AgentInstance(
                    identity = Identity(
                        uuid = uuid,
                        localHandle = "",    // placeholder — filled on RESPONSE_REGISTER_IDENTITY
                        handle = "",         // placeholder — filled on RESPONSE_REGISTER_IDENTITY
                        name = name,
                        parentHandle = "agent"
                    ),
                    knowledgeGraphId = payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini",
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-pro",
                    cognitiveStrategyId = payload["cognitiveStrategyId"]?.jsonPrimitive?.contentOrNull ?: "vanilla_v1",
                    subscribedSessionIds = payload["subscribedSessionIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 5,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 30
                )
                state.copy(agents = state.agents + (uuid to newAgent), editingAgentId = uuid)
            }
            ActionRegistry.Names.AGENT_UPDATE_CONFIG -> {
                val payload = action.payload ?: return state
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state

                // Filtering logic to prevent subscription to private sessions
                val newSubscribedSessionIds = if ("subscribedSessionIds" in payload) {
                    payload["subscribedSessionIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } else {
                    agentToUpdate.subscribedSessionIds
                }
                val filteredSubscribedSessionIds = newSubscribedSessionIds.filter { sessionId ->
                    sessionId !in state.agentPrivateSessionIds
                }

                val updatedResources = if ("resources" in payload) {
                    payload["resources"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: agentToUpdate.resources
                } else {
                    agentToUpdate.resources
                }

                val updatedAgent = agentToUpdate.copy(
                    identity = agentToUpdate.identity.copy(
                        name = payload["name"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.identity.name
                    ),
                    knowledgeGraphId = if ("knowledgeGraphId" in payload) payload["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.knowledgeGraphId,
                    privateSessionId = if ("privateSessionId" in payload) payload["privateSessionId"]?.jsonPrimitive?.contentOrNull else agentToUpdate.privateSessionId,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelProvider,
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelName,
                    cognitiveStrategyId = payload["cognitiveStrategyId"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.cognitiveStrategyId,
                    subscribedSessionIds = filteredSubscribedSessionIds,
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoWaitTimeSeconds,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoMaxWaitTimeSeconds,
                    resources = updatedResources
                )
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(automaticMode = !agentToUpdate.automaticMode)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(isAgentActive = !agentToUpdate.isAgentActive)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_SET_EDITING -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull
                state.copy(editingAgentId = if (agentId == state.editingAgentId) null else agentId)
            }
            ActionRegistry.Names.AGENT_SET_MANAGER_TAB -> {
                val tabIndex = action.payload?.get("tabIndex")?.jsonPrimitive?.intOrNull ?: 0
                state.copy(activeManagerTab = tabIndex)
            }
            ActionRegistry.Names.AGENT_CONFIRM_DELETE -> {
                val agentId = action.payload?.get("agentId")?.jsonPrimitive?.contentOrNull ?: return state
                state.copy(
                    agents = state.agents - agentId,
                    agentAvatarCardIds = state.agentAvatarCardIds - agentId
                )
            }
            ActionRegistry.Names.AGENT_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return state
                val uuid = agent.identity.uuid ?: return state
                if (!state.agents.containsKey(uuid)) state.copy(agents = state.agents + (uuid to agent)) else state
            }
            // [NEW] Handle loading resources from disk
            ActionRegistry.Names.AGENT_RESOURCE_LOADED -> {
                val resource = action.payload?.let { json.decodeFromJsonElement<AgentResource>(it) } ?: return state
                // Merge logic: Add if new, replace if exists
                val updatedResources = state.resources.filter { it.id != resource.id } + resource
                state.copy(resources = updatedResources)
            }
            ActionRegistry.Names.AGENT_SELECT_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull
                state.copy(editingResourceId = resourceId)
            }
            ActionRegistry.Names.AGENT_CREATE_RESOURCE -> {
                val payload = action.payload ?: return state
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: return state
                val typeString = payload["type"]?.jsonPrimitive?.contentOrNull ?: return state
                val type = AgentResourceType.entries.find { it.name == typeString } ?: return state
                // [NEW] Support initial content for cloning
                val initialContent = payload["initialContent"]?.jsonPrimitive?.contentOrNull

                val newResource = AgentResource(
                    id = platformDependencies.generateUUID(),
                    type = type,
                    name = name,
                    content = initialContent ?: "",
                    isBuiltIn = false,
                    path = "resources/${platformDependencies.generateUUID()}.json" // Consistent file naming
                )

                state.copy(
                    resources = state.resources + newResource,
                    editingResourceId = newResource.id
                )
            }
            ActionRegistry.Names.AGENT_SAVE_RESOURCE -> {
                val payload = action.payload ?: return state
                val resourceId = payload["resourceId"]?.jsonPrimitive?.contentOrNull ?: return state
                val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return state

                val updatedResources = state.resources.map { res ->
                    if (res.id == resourceId) res.copy(content = content) else res
                }

                val resourceToSave = updatedResources.find { it.id == resourceId }
                if (resourceToSave?.isBuiltIn == true) return state // UI should handle clone before save

                state.copy(resources = updatedResources)
            }
            // [NEW] Rename Logic
            ActionRegistry.Names.AGENT_RENAME_RESOURCE -> {
                val payload = action.payload ?: return state
                val resourceId = payload["resourceId"]?.jsonPrimitive?.contentOrNull ?: return state
                val newName = payload["newName"]?.jsonPrimitive?.contentOrNull ?: return state

                val updatedResources = state.resources.map { res ->
                    if (res.id == resourceId && !res.isBuiltIn) res.copy(name = newName) else res
                }
                state.copy(resources = updatedResources)
            }
            ActionRegistry.Names.AGENT_DELETE_RESOURCE -> {
                val resourceId = action.payload?.get("resourceId")?.jsonPrimitive?.contentOrNull ?: return state
                val resourceToDelete = state.resources.find { it.id == resourceId }
                if (resourceToDelete?.isBuiltIn == true) return state

                state.copy(
                    resources = state.resources.filter { it.id != resourceId },
                    editingResourceId = if (state.editingResourceId == resourceId) null else state.editingResourceId
                )
            }
            ActionRegistry.Names.AGENT_UPDATE_NVRAM -> {
                val payload = action.payload ?: return state
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
                val updates = payload["updates"]?.jsonObject ?: return state

                val agent = state.agents[agentId] ?: return state

                // Merge updates into existing cognitiveState (for UPDATE_NVRAM)
                val currentState = agent.cognitiveState as? JsonObject ?: buildJsonObject {}
                val mergedState = buildJsonObject {
                    currentState.forEach { (k, v) -> put(k, v) }
                    updates.forEach { (k, v) -> put(k, v) }
                }

                val updatedAgent = agent.copy(cognitiveState = mergedState)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_NVRAM_LOADED -> {
                val payload = action.payload ?: return state
                val agentId = payload["agentId"]?.jsonPrimitive?.contentOrNull ?: return state
                val newState = payload["state"] ?: return state

                val agent = state.agents[agentId] ?: return state
                val updatedAgent = agent.copy(cognitiveState = newState)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            else -> state
        }
    }
}