package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.Identity
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * Pure, testable reducer logic for the synchronous CRUD operations of the
 * AgentRuntimeFeature. Isolates "administrative" state transitions from the
 * complex, asynchronous runtime logic.
 *
 * All ID extractions from JSON payloads are wrapped in typed value classes at
 * the boundary. Internal logic operates on [IdentityUUID].
 *
 * Strategy-specific operator configuration is stored in [AgentInstance.strategyConfig].
 * The CRUD logic routes the `strategyConfig` JSON object from the payload generically —
 * no strategy-specific field names appear here. The strategy's [CognitiveStrategy.getConfigFields]
 * declarations drive the UI; this reducer simply persists the resulting object.
 *
 * Strategy handle validation: AGENT_CREATE and AGENT_UPDATE_CONFIG reject
 * unknown `cognitiveStrategyId` handles with a clear error log.
 */
object AgentCrudLogic {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- Boundary helpers ----

    /** Extract an agent UUID from a JSON payload field. */
    private fun JsonObject.agentUUID(field: String = "agentId"): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    /** Extract a resource UUID from a JSON payload field. */
    private fun JsonObject.resourceUUID(field: String): IdentityUUID? =
        this[field]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }

    /**
     * Extracts `strategyConfig` from the payload if present.
     * Returns the payload value, or the fallback if not present.
     */
    private fun extractStrategyConfig(payload: JsonObject, fallback: JsonObject): JsonObject {
        val raw = payload["strategyConfig"] ?: return fallback
        return raw as? JsonObject ?: fallback
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

                // Reject unknown strategy handles rather than silently accepting.
                if (!CognitiveStrategyRegistry.isRegistered(strategyId)) {
                    platformDependencies.log(
                        app.auf.util.LogLevel.ERROR, "AgentCrudLogic",
                        "AGENT_CREATE rejected: cognitiveStrategyId '${strategyId.handle}' is not a registered strategy."
                    )
                    return state
                }

                val strategy = CognitiveStrategyRegistry.get(strategyId)

                // cognitiveState is pure NVRAM — agent-written runtime state.
                val cognitiveState = strategy.getInitialState()

                // strategyConfig holds operator-set config (e.g., knowledgeGraphId).
                val strategyConfig = extractStrategyConfig(payload, JsonObject(emptyMap()))

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
                    strategyConfig = strategyConfig,
                    subscribedSessionIds = payload["subscribedSessionIds"]?.jsonArray
                        ?.map { IdentityUUID(it.jsonPrimitive.content) } ?: emptyList(),
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
                        ?.map { IdentityUUID(it.jsonPrimitive.content) } ?: emptyList()
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

                // strategyConfig: use payload value if present, otherwise keep existing.
                val updatedStrategyConfig = extractStrategyConfig(payload, agentToUpdate.strategyConfig)

                // Validate strategy handle if one is being set.
                val resolvedStrategyId = payload["cognitiveStrategyId"]?.jsonPrimitive?.contentOrNull
                    ?.let { raw ->
                        val migrated = CognitiveStrategyRegistry.migrateStrategyId(raw)
                        if (!CognitiveStrategyRegistry.isRegistered(migrated)) {
                            platformDependencies.log(
                                app.auf.util.LogLevel.ERROR, "AgentCrudLogic",
                                "AGENT_UPDATE_CONFIG rejected for agent '${agentId.uuid}': " +
                                        "cognitiveStrategyId '${migrated.handle}' is not a registered strategy."
                            )
                            return state
                        }
                        migrated
                    }
                    ?: agentToUpdate.cognitiveStrategyId

                val updatedAgent = agentToUpdate.copy(
                    identity = agentToUpdate.identity.copy(
                        name = payload["name"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.identity.name
                    ),
                    outputSessionId = if ("outputSessionId" in payload) {
                        payload["outputSessionId"]?.jsonPrimitive?.contentOrNull?.let { IdentityUUID(it) }
                    } else agentToUpdate.outputSessionId,
                    modelProvider = payload["modelProvider"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelProvider,
                    modelName = payload["modelName"]?.jsonPrimitive?.contentOrNull ?: agentToUpdate.modelName,
                    cognitiveStrategyId = resolvedStrategyId,
                    strategyConfig = updatedStrategyConfig,
                    subscribedSessionIds = filteredSubscribedSessionIds,
                    automaticMode = payload["automaticMode"]?.jsonPrimitive?.booleanOrNull ?: agentToUpdate.automaticMode,
                    autoWaitTimeSeconds = payload["autoWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoWaitTimeSeconds,
                    autoMaxWaitTimeSeconds = payload["autoMaxWaitTimeSeconds"]?.jsonPrimitive?.intOrNull ?: agentToUpdate.autoMaxWaitTimeSeconds,
                    resources = updatedResources
                )

                // Strategy-owned config validation.
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
                val rawAgent = action.payload?.let { json.decodeFromJsonElement<AgentInstance>(it) } ?: return state
                val uuid = rawAgent.identityUUID

                // === Migration: legacy cognitiveState → strategyConfig ===
                // Old agent.json files may have strategy config keys (e.g., knowledgeGraphId)
                // stored in cognitiveState. Migrate them to strategyConfig generically using
                // the strategy's declared config field keys.
                val strategy = CognitiveStrategyRegistry.get(rawAgent.cognitiveStrategyId)
                val configFieldKeys = strategy.getConfigFields().map { it.key }.toSet()
                val cogStateObj = rawAgent.cognitiveState as? JsonObject

                val agent = if (cogStateObj != null && configFieldKeys.isNotEmpty() && rawAgent.strategyConfig.isEmpty()) {
                    val migratedConfig = buildJsonObject {
                        configFieldKeys.forEach { key ->
                            cogStateObj[key]?.let { put(key, it) }
                        }
                    }
                    val cleanedCogState = buildJsonObject {
                        cogStateObj.forEach { (k, v) ->
                            if (k !in configFieldKeys) put(k, v)
                        }
                    }
                    rawAgent.copy(
                        strategyConfig = migratedConfig,
                        cognitiveState = if (cleanedCogState.isEmpty()) JsonNull else cleanedCogState
                    )
                } else {
                    rawAgent
                }

                if (!state.agents.containsKey(uuid)) state.copy(agents = state.agents + (uuid to agent)) else state
            }
            // Handle loading resources from disk
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

                // Validate incoming keys against the strategy's declared NVRAM schema.
                val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
                val validKeys = strategy.getValidNvramKeys()
                val filteredUpdates = if (validKeys != null) {
                    val rejected = updates.keys - validKeys
                    if (rejected.isNotEmpty()) {
                        platformDependencies.log(
                            app.auf.util.LogLevel.WARN, "AgentCrudLogic",
                            "UPDATE_NVRAM for agent '${agentId.uuid}': Rejected unknown keys $rejected. " +
                                    "Valid keys for strategy '${strategy.identityHandle.handle}': $validKeys"
                        )
                    }
                    JsonObject(updates.filterKeys { it in validKeys })
                } else {
                    updates // Strategy imposes no schema restrictions (e.g., Minimal)
                }

                if (filteredUpdates.isEmpty()) return state

                // Merge validated updates into existing cognitiveState
                val currentState = agent.cognitiveState as? JsonObject ?: buildJsonObject {}
                val mergedState = buildJsonObject {
                    currentState.forEach { (k, v) -> put(k, v) }
                    filteredUpdates.forEach { (k, v) -> put(k, v) }
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