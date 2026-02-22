package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * To provide pure, testable reducer logic for the synchronous CRUD operations
 * of the AgentRuntimeFeature. This isolates the "administrative" state transitions
 * from the complex, asynchronous runtime logic.
 *
 * [PHASE 1] All ID extractions from JSON payloads are wrapped in typed value classes
 * at the boundary. Internal logic operates on [IdentityUUID] and [IdentityHandle].
 *
 * [PHASE 4] `knowledgeGraphId` removed from AgentInstance. When the payload contains
 * `knowledgeGraphId`, it is merged into `cognitiveState` as a strategy-owned key.
 * `validateConfig` is called via the strategy after every config update.
 */
object AgentCrudLogic {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- Phase 1 boundary helpers ----

    /** Extract an agent UUID from a JSON payload field. */
    private fun JsonObject.agentUUID(field: String = "agentId"): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    /** Extract a session handle from a JSON payload field. */
    private fun JsonObject.sessionHandle(field: String): IdentityHandle? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityHandle(it) }

    /** Extract a resource UUID from a JSON payload field. */
    private fun JsonObject.resourceUUID(field: String): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    /**
     * [PHASE 4] Merges a `knowledgeGraphId` value from the payload into the agent's
     * cognitiveState. This is how the UI/command pipeline sets or clears the KG
     * assignment now that `knowledgeGraphId` is no longer a top-level AgentInstance field.
     *
     * If the payload contains `"knowledgeGraphId"`, the value is merged into
     * `cognitiveState` under the `"knowledgeGraphId"` key. If the value is null/JsonNull,
     * the key is set to JsonNull (KG revocation).
     */
    private fun mergeCognitiveStateFromPayload(
        payload: JsonObject,
        currentCognitiveState: JsonElement
    ): JsonElement {
        // Check if payload has knowledgeGraphId to migrate into cognitiveState
        if ("knowledgeGraphId" !in payload) return currentCognitiveState

        val kgValue = payload["knowledgeGraphId"]
        val kgJsonValue: JsonElement = when {
            kgValue == null || kgValue is JsonNull -> JsonNull
            else -> kgValue
        }

        val currentObj = currentCognitiveState as? JsonObject ?: buildJsonObject {}
        return buildJsonObject {
            currentObj.forEach { (k, v) -> put(k, v) }
            put("knowledgeGraphId", kgJsonValue)
        }
    }

    fun reduce(
        state: AgentRuntimeState,
        action: Action,
        platformDependencies: PlatformDependencies
    ): AgentRuntimeState {
        return when (action.name) {
            ActionRegistry.Names.AGENT_CREATE -> {
                val payload = action.payload ?: return state
                val uuid = IdentityUUID(platformDependencies.generateUUID())
                val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "New Agent"

                val strategyId = payload["cognitiveStrategyId"]?.jsonPrimitive?.contentOrNull
                    ?.let { CognitiveStrategyRegistry.migrateStrategyId(it) }
                    ?: CognitiveStrategyRegistry.getDefault().identityHandle
                val strategy = CognitiveStrategyRegistry.get(strategyId)

                // [PHASE 4] Initial cognitiveState comes from the strategy.
                // If the payload contains `knowledgeGraphId`, merge it in.
                val initialState = strategy.getInitialState()
                val cognitiveState = mergeCognitiveStateFromPayload(payload, initialState)

                val newAgent = AgentInstance(
                    identity = Identity(
                        uuid = uuid.uuid,
                        localHandle = "",    // placeholder — filled on RETURN_REGISTER_IDENTITY
                        handle = "",         // placeholder — filled on RETURN_REGISTER_IDENTITY
                        name = name,
                        parentHandle = "agent"
                    ),
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: "gemini",
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: "gemini-pro",
                    cognitiveStrategyId = strategyId,
                    cognitiveState = cognitiveState,
                    subscribedSessionIds = payload["subscribedSessionIds"]?.jsonArray
                        ?.map { IdentityHandle(it.jsonPrimitive.content) } ?: emptyList(),
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: false,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 5,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: 30
                )
                state.copy(agents = state.agents + (uuid to newAgent), editingAgentId = uuid)
            }
            ActionRegistry.Names.AGENT_UPDATE_CONFIG -> {
                val payload = action.payload ?: return state
                val agentId = payload.agentUUID() ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state

                // Filtering logic to prevent subscription to private sessions
                val newSubscribedSessionIds = if ("subscribedSessionIds" in payload) {
                    payload["subscribedSessionIds"]?.jsonArray
                        ?.map { IdentityHandle(it.jsonPrimitive.content) } ?: emptyList()
                } else {
                    agentToUpdate.subscribedSessionIds
                }
                val filteredSubscribedSessionIds = newSubscribedSessionIds.filter { sessionId ->
                    sessionId in state.subscribableSessionNames
                }

                val updatedResources = if ("resources" in payload) {
                    payload["resources"]?.jsonObject
                        ?.mapValues { IdentityUUID(it.value.jsonPrimitive.content) }
                        ?: agentToUpdate.resources
                } else {
                    agentToUpdate.resources
                }

                // [PHASE 4] Merge knowledgeGraphId from payload into cognitiveState
                val updatedCognitiveState = mergeCognitiveStateFromPayload(payload, agentToUpdate.cognitiveState)

                val updatedAgent = agentToUpdate.copy(
                    identity = agentToUpdate.identity.copy(
                        name = payload["name"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.identity.name
                    ),
                    outputSessionId = if ("outputSessionId" in payload) payload.sessionHandle("outputSessionId") else agentToUpdate.outputSessionId,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelProvider,
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelName,
                    cognitiveStrategyId = payload["cognitiveStrategyId"]?.jsonPrimitive?.contentOrNull
                        ?.let { CognitiveStrategyRegistry.migrateStrategyId(it) }
                        ?: agentToUpdate.cognitiveStrategyId,
                    cognitiveState = updatedCognitiveState,
                    subscribedSessionIds = filteredSubscribedSessionIds,
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoWaitTimeSeconds,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoMaxWaitTimeSeconds,
                    resources = updatedResources
                )

                // [PHASE 4 / E7 / E8] Strategy-owned config validation.
                // Each strategy defines its own rules (e.g., Vanilla enforces
                // outputSessionId ∈ subscribedSessionIds; Sovereign permits out-of-band).
                val strategy = CognitiveStrategyRegistry.get(updatedAgent.cognitiveStrategyId)
                val validatedAgent = strategy.validateConfig(updatedAgent)

                state.copy(agents = state.agents + (agentId to validatedAgent))
            }
            ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE -> {
                val agentId = action.payload?.agentUUID() ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(automaticMode = !agentToUpdate.automaticMode)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_TOGGLE_ACTIVE -> {
                val agentId = action.payload?.agentUUID() ?: return state
                val agentToUpdate = state.agents[agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(isAgentActive = !agentToUpdate.isAgentActive)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            ActionRegistry.Names.AGENT_SET_EDITING -> {
                val agentId = action.payload?.agentUUID()
                state.copy(editingAgentId = if (agentId == state.editingAgentId) null else agentId)
            }
            ActionRegistry.Names.AGENT_SET_MANAGER_TAB -> {
                val tabIndex = action.payload?.get("tabIndex")?.jsonPrimitive?.intOrNull ?: 0
                state.copy(activeManagerTab = tabIndex)
            }
            ActionRegistry.Names.AGENT_CONFIRM_DELETE -> {
                val agentId = action.payload?.agentUUID() ?: return state
                state.copy(
                    agents = state.agents - agentId,
                    agentAvatarCardIds = state.agentAvatarCardIds - agentId
                )
            }
            ActionRegistry.Names.AGENT_AGENT_LOADED -> {
                val agent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return state
                val uuid = agent.identityUUID
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
                val agentId = payload.agentUUID() ?: return state
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
                val agentId = payload.agentUUID() ?: return state
                val newState = payload["state"] ?: return state

                val agent = state.agents[agentId] ?: return state
                val updatedAgent = agent.copy(cognitiveState = newState)
                state.copy(agents = state.agents + (agentId to updatedAgent))
            }
            else -> state
        }
    }
}