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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class KnowledgeGraphFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "knowledgegraph"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val GRAPH_MANIFEST_FILENAME = "graph.json"

    // Payloads for type-safe decoding
    @Serializable private data class GraphNamesPayload(val names: Map<String, String>)
    @Serializable private data class GraphIdPayload(val graphId: String?)
    @Serializable private data class HolonIdPayload(val holonId: String?)
    @Serializable private data class CreateGraphPayload(val name: String)
    @Serializable private data class UpdateHolonPayload(val graphId: String, val holonId: String, val content: String)
    @Serializable private data class DeleteHolonPayload(val graphId: String, val holonId: String)
    @Serializable private data class FileListPayload(val listing: List<FileEntry>)
    @Serializable private data class FileReadPayload(val subpath: String, val content: String?)
    @Serializable private data class AgentContextRequest(val correlationId: String, val knowledgeGraphId: String)

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        // FIX: Removed the overly-aggressive global state guard.
        // The when block now handles state requirements on a per-action basis.
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_CREATE_GRAPH -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<CreateGraphPayload>(it) } ?: return
                val newGraphId = platformDependencies.generateUUID()
                val newGraph = HolonKnowledgeGraph(id = newGraphId, name = payload.name)
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", "$newGraphId/$GRAPH_MANIFEST_FILENAME")
                    put("content", json.encodeToString(newGraph))
                }))
                // Optimistically update UI
                store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED, json.encodeToJsonElement(newGraph) as JsonObject))
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_GRAPH -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<GraphIdPayload>(it) } ?: return
                payload.graphId?.let {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject { put("subpath", it) }))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<UpdateHolonPayload>(it) } ?: return
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", "${payload.graphId}/${payload.holonId}.json")
                    put("content", payload.content)
                }))
                // Optimistically update UI
                parseHolon(payload.holonId, payload.content)?.let {
                    store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(it) as JsonObject))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeleteHolonPayload>(it) } ?: return
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE, buildJsonObject {
                    put("subpath", "${payload.graphId}/${payload.holonId}.json")
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_GRAPH_SCOPE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<GraphIdPayload>(it) } ?: return
                payload.graphId?.let {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST, buildJsonObject { put("subpath", it) }))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_HOLON -> {
                // FIX: The state guard is now localized to the only action that needs it.
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return
                val payload = action.payload?.let { json.decodeFromJsonElement<HolonIdPayload>(it) } ?: return
                val holonId = payload.holonId ?: return
                val graphId = kgState.activeGraphId ?: return
                val holon = kgState.graphs.find { it.id == graphId }?.holons?.find { it.id == holonId }
                if (holon != null && holon.content == null) {
                    store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                        put("subpath", "$graphId/$holonId.json")
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
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<GraphNamesPayload>(it) }
                newFeatureState = currentFeatureState.copy(agentNames = payload?.names ?: emptyMap())
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED -> {
                val graph = action.payload?.let { json.decodeFromJsonElement<HolonKnowledgeGraph>(it) } ?: return state
                val existingGraphs = currentFeatureState.graphs.filterNot { it.id == graph.id }
                newFeatureState = currentFeatureState.copy(graphs = existingGraphs + graph)
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_GRAPH -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<GraphIdPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(graphs = currentFeatureState.graphs.filterNot { it.id == payload.graphId })
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED -> {
                val holon = action.payload?.let { json.decodeFromJsonElement<Holon>(it) } ?: return state
                val graphId = currentFeatureState.activeGraphId ?: return state
                newFeatureState = currentFeatureState.copy(graphs = currentFeatureState.graphs.map { graph ->
                    if (graph.id == graphId) {
                        graph.copy(holons = graph.holons.filterNot { it.id == holon.id } + holon)
                    } else graph
                })
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeleteHolonPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(graphs = currentFeatureState.graphs.map { graph ->
                    if (graph.id == payload.graphId) {
                        graph.copy(holons = graph.holons.filterNot { it.id == payload.holonId })
                    } else graph
                })
            }
            ActionNames.KNOWLEDGEGRAPH_SELECT_GRAPH_SCOPE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<GraphIdPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(activeGraphId = payload.graphId, activeHolonId = null)
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
        val graph = kgState.graphs.find { it.id == request.knowledgeGraphId } ?: return

        // For now, we return all holons. A future enhancement could use the `activeHolonIds` from the request.
        val context = buildJsonObject {
            graph.holons.forEach { holon ->
                holon.content?.let { content ->
                    put(holon.id, content)
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
        val subpath = payload["subpath"]?.jsonPrimitive?.contentOrNull

        if (subpath == null) { // Root listing of all graphs
            listing.filter { it.isDirectory }.forEach { dir ->
                val graphId = platformDependencies.getFileName(dir.path)
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                    put("subpath", "$graphId/$GRAPH_MANIFEST_FILENAME")
                }))
            }
        } else { // Listing of holons within a graph
            val graphId = subpath
            listing.filter { !it.isDirectory && it.path.endsWith(".json") }.forEach { file ->
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                    put("subpath", "$graphId/${platformDependencies.getFileName(file.path)}")
                }))
            }
        }
    }

    private fun handleFileSystemRead(payload: JsonObject, store: Store) {
        val subpath = payload["subpath"]?.jsonPrimitive?.contentOrNull ?: return
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: return

        if (subpath.endsWith(GRAPH_MANIFEST_FILENAME)) {
            try {
                val graph = json.decodeFromString<HolonKnowledgeGraph>(content)
                store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_GRAPH_LOADED, json.encodeToJsonElement(graph) as JsonObject))
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to parse graph manifest '$subpath': ${e.message}")
            }
        } else if (subpath.endsWith(".json")) {
            val holonId = platformDependencies.getFileName(subpath).removeSuffix(".json")
            parseHolon(holonId, content)?.let { holon ->
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState
                // If this is the currently selected holon, load the full content into the state model
                val finalHolon = if (kgState?.activeHolonId == holonId) holon.copy(content = content) else holon
                store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(finalHolon) as JsonObject))
            }
        }
    }

    private fun parseHolon(id: String, content: String): Holon? {
        return try {
            val jsonElement = json.parseToJsonElement(content).jsonObject
            val header = jsonElement["header"]?.jsonObject ?: return Holon(id, "Unknown", "Parse Error", parseError = "Missing 'header' object.")
            val type = header["type"]?.jsonPrimitive?.content ?: "Unknown"
            val name = header["name"]?.jsonPrimitive?.content ?: id
            val summary = header["summary"]?.jsonPrimitive?.content
            Holon(id, type, name, summary)
        } catch (e: Exception) {
            Holon(id, "Unknown", "Parse Error", parseError = "Invalid JSON: ${e.message}")
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