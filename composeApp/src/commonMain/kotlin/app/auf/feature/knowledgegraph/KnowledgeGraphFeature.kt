package app.auf.feature.knowledgegraph

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class KnowledgeGraphFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "knowledgegraph"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    // Payloads for type-safe decoding
    @Serializable private data class PersonaIdPayload(val personaId: String?)
    @Serializable private data class HolonIdPayload(val holonId: String?)
    @Serializable private data class AgentContextRequest(val correlationId: String, val knowledgeGraphId: String)
    @Serializable private data class ReadPayload(val subpath: String, val content: String?, val parentId: String? = null, val depth: Int = 0)

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                // Begin discovery of available personas
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PersonaIdPayload>(it) } ?: return
                val personaId = payload.personaId ?: return
                // Start the recursive load from the root of the selected persona's tree
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                    put("subpath", "$personaId/$personaId.json")
                    // Root holon has no parent and is at depth 0
                    put("parentId", JsonNull)
                    put("depth", 0)
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON -> {
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return
                val payload = action.payload?.let { json.decodeFromJsonElement<HolonIdPayload>(it) } ?: return
                val holon = kgState.holons[payload.holonId]
                // If holon content hasn't been loaded yet, fetch it
                if (holon != null && holon.content == null) {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                        put("subpath", holon.header.filePath)
                        put("parentId", holon.header.parentId)
                        put("depth", holon.header.depth)
                    }))
                }
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val (stateWithFeature, currentFeatureState) = state.featureStates[name]
            ?.let { state to (it as KnowledgeGraphState) }
            ?: (state.copy(featureStates = state.featureStates + (name to KnowledgeGraphState())) to KnowledgeGraphState())

        var newFeatureState: KnowledgeGraphState? = null
        when (action.name) {
            ActionNames.KNOWLEDGEGRAPH_SELECT_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PersonaIdPayload>(it) } ?: return state
                // Clear the old graph and set loading state when a new persona is selected
                newFeatureState = KnowledgeGraphState(
                    isLoading = true,
                    activePersonaId = payload.personaId,
                    availablePersonas = currentFeatureState.availablePersonas
                )
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_DISCOVERED -> {
                val personaHeader = action.payload?.let { json.decodeFromJsonElement<HolonHeader>(it) } ?: return state
                if (currentFeatureState.availablePersonas.none { it.id == personaHeader.id }) {
                    newFeatureState = currentFeatureState.copy(
                        availablePersonas = currentFeatureState.availablePersonas + personaHeader
                    )
                }
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED -> {
                val holon = action.payload?.let { json.decodeFromJsonElement<Holon>(it) } ?: return state
                val newHolons = currentFeatureState.holons + (holon.header.id to holon)
                // If this is the root holon (no parent), set the rootHolonId
                val newRootId = if (holon.header.parentId == null) holon.header.id else currentFeatureState.rootHolonId
                newFeatureState = currentFeatureState.copy(
                    holons = newHolons,
                    rootHolonId = newRootId,
                    isLoading = false // A holon loaded, so we are no longer in a pure loading state
                )
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED -> {
                val error = action.payload?.get("error")?.jsonPrimitive?.content ?: "Unknown loading error."
                newFeatureState = currentFeatureState.copy(isLoading = false, fatalError = error)
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<HolonIdPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(activeHolonId = payload.holonId)
            }
        }

        return newFeatureState?.let {
            if (it != currentFeatureState) stateWithFeature.copy(featureStates = stateWithFeature.featureStates + (name to it)) else stateWithFeature
        } ?: stateWithFeature
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        when (envelope.type) {
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> handleFileSystemList(envelope.payload, store)
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_READ -> handleFileSystemRead(envelope.payload, store)
            ActionNames.Envelopes.AGENT_REQUEST_CONTEXT -> handleAgentContextRequest(envelope.payload, store)
        }
    }

    private fun handleAgentContextRequest(payload: JsonObject, store: Store) {
        val request = try { json.decodeFromJsonElement<AgentContextRequest>(payload) } catch (e: Exception) { return }
        val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return
        val personaId = request.knowledgeGraphId // In v2, the agent's KG ID is the persona ID
        val graph = kgState.holons

        if (kgState.activePersonaId != personaId) {
            platformDependencies.log(LogLevel.WARN, name, "Agent requested context for KG '$personaId', but active persona is '${kgState.activePersonaId}'. Providing context anyway.")
        }

        val context = buildJsonObject {
            graph.values.forEach { holon ->
                holon.content?.let { content ->
                    put(holon.header.id, content)
                }
            }
        }

        val responsePayload = buildJsonObject {
            put("correlationId", request.correlationId)
            put("context", context)
        }
        val responseEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, responsePayload)
        store.deliverPrivateData(this.name, "agent", responseEnvelope)
    }

    private fun handleFileSystemList(payload: JsonObject, store: Store) {
        val listing = payload["listing"]?.let { json.decodeFromJsonElement<List<FileEntry>>(it) } ?: return
        // This is the initial discovery: find all potential persona directories.
        listing.filter { it.isDirectory }.forEach { dir ->
            val personaId = platformDependencies.getFileName(dir.path)
            // Request the root holon file to verify it's a valid persona.
            store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                put("subpath", "$personaId/$personaId.json")
            }))
        }
    }

    private fun handleFileSystemRead(payload: JsonObject, store: Store) {
        val fileData = try { json.decodeFromJsonElement<ReadPayload>(payload) } catch (e: Exception) { return }
        val content = fileData.content ?: return

        try {
            val holon = json.decodeFromString<Holon>(content)
            val enrichedHeader = holon.header.copy(
                filePath = fileData.subpath,
                parentId = fileData.parentId,
                depth = fileData.depth
            )
            val enrichedHolon = holon.copy(header = enrichedHeader, content = content)

            // If it's a root persona, dispatch a discovery event.
            if (enrichedHeader.type == "AI_Persona_Root") {
                store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_DISCOVERED, json.encodeToJsonElement(enrichedHeader) as JsonObject))
            }

            // Dispatch the loaded holon to the reducer to add it to the state map.
            store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(enrichedHolon) as JsonObject))

            // --- RECURSIVE TRAVERSAL ---
            // For each sub_holon reference, dispatch a new read request for its file.
            val currentDir = platformDependencies.getParentDirectory(enrichedHeader.filePath) ?: ""
            enrichedHeader.subHolons.forEach { subRef ->
                val subHolonPath = "$currentDir/${subRef.id}/${subRef.id}.json"
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                    put("subpath", subHolonPath)
                    put("parentId", enrichedHeader.id)
                    put("depth", enrichedHeader.depth + 1)
                }))
            }
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, name, "Failed to parse holon at '${fileData.subpath}': ${e.message}")
        }
    }

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf("feature.knowledgegraph.main" to { store, _ -> KnowledgeGraphView(store) })

        @Composable
        override fun RibbonContent(store: Store, activeViewKey: String?) {
            val viewKey = "feature.knowledgegraph.main"
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("ui.ribbon", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = "Knowledge Graph Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}