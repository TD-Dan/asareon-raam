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

    // --- Payloads for Type-Safe Decoding ---
    @Serializable private data class RequestContextPayload(val personaId: String, val correlationId: String)
    @Serializable private data class SetViewPersonaPayload(val personaId: String?)
    @Serializable private data class SetViewHolonPayload(val holonId: String?)
    @Serializable private data class ReadPayload(val subpath: String, val content: String?, val parentId: String? = null, val depth: Int = 0)

    override fun onAction(action: Action, store: Store, previousState: AppState) {
        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                // Kick off the discovery of all available HKG personas
                store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestContextPayload>(it) } ?: return
                val originator = action.originator ?: return
                val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState ?: return

                val contextMap = buildContextForPersona(payload.personaId, kgState)

                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    put("personaId", payload.personaId)
                    put("context", Json.encodeToJsonElement(contextMap))
                }
                val responseEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, responsePayload)
                store.deliverPrivateData(this.name, originator, responseEnvelope)
            }
        }
    }

    private fun buildContextForPersona(personaId: String, kgState: KnowledgeGraphState): Map<String, String> {
        val rootHolon = kgState.holons[personaId] ?: return emptyMap()
        val contextMap = mutableMapOf<String, String>()
        val holonsToProcess = mutableListOf(rootHolon)
        val processedIds = mutableSetOf<String>()

        while (holonsToProcess.isNotEmpty()) {
            val currentHolon = holonsToProcess.removeAt(0)
            if (processedIds.contains(currentHolon.header.id)) continue

            contextMap[currentHolon.header.id] = currentHolon.content
            processedIds.add(currentHolon.header.id)

            currentHolon.header.subHolons.forEach { subRef ->
                kgState.holons[subRef.id]?.let { holonsToProcess.add(it) }
            }
        }
        return contextMap
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val (stateWithFeature, currentFeatureState) = state.featureStates[name]
            ?.let { state to (it as KnowledgeGraphState) }
            ?: (state.copy(featureStates = state.featureStates + (name to KnowledgeGraphState())) to KnowledgeGraphState())

        var newFeatureState: KnowledgeGraphState? = null
        when (action.name) {
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_DISCOVERED -> {
                val personaHeader = action.payload?.let { json.decodeFromJsonElement<HolonHeader>(it) } ?: return state
                if (!currentFeatureState.personaRoots.containsKey(personaHeader.name)) {
                    val newRoots = currentFeatureState.personaRoots + (personaHeader.name to personaHeader.id)
                    newFeatureState = currentFeatureState.copy(personaRoots = newRoots)
                }
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED -> {
                val holon = action.payload?.let { json.decodeFromJsonElement<Holon>(it) } ?: return state
                val newHolons = currentFeatureState.holons + (holon.header.id to holon)
                newFeatureState = currentFeatureState.copy(holons = newHolons, isLoading = false)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED -> {
                val error = action.payload?.get("error")?.jsonPrimitive?.content ?: "Unknown loading error."
                newFeatureState = currentFeatureState.copy(isLoading = false, fatalError = error)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetViewPersonaPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(
                    activePersonaIdForView = payload.personaId,
                    activeHolonIdForView = null // Reset holon selection when persona changes
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetViewHolonPayload>(it) } ?: return state
                newFeatureState = currentFeatureState.copy(activeHolonIdForView = payload.holonId)
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
        }
    }

    private fun handleFileSystemList(payload: JsonObject, store: Store) {
        val listing = payload["listing"]?.let { json.decodeFromJsonElement<List<FileEntry>>(it) } ?: return
        // This is the initial discovery: find all potential persona directories.
        listing.filter { it.isDirectory }.forEach { dir ->
            val personaId = platformDependencies.getFileName(dir.path)
            // Request the root holon file to verify it's a valid persona and start the loading cascade.
            store.dispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_READ, buildJsonObject {
                put("subpath", "$personaId/$personaId.json")
                put("parentId", JsonNull)
                put("depth", 0)
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

            // If it's a root persona, dispatch a discovery event for the UI selector.
            if (enrichedHeader.type == "AI_Persona_Root") {
                store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_DISCOVERED, json.encodeToJsonElement(enrichedHeader) as JsonObject))
            }

            // Dispatch the loaded holon to the reducer to add it to the master map.
            store.dispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_HOLON_LOADED, json.encodeToJsonElement(enrichedHolon) as JsonObject))

            // --- RECURSIVE TRAVERSAL ---
            // For each sub_holon reference, dispatch a new read request for its file.
            val currentDir = platformDependencies.getParentDirectory(enrichedHeader.filePath) ?: ""
            enrichedHeader.subHolons.forEach { subRef ->
                val subHolonDir = "$currentDir/${subRef.id}"
                val subHolonPath = "$subHolonDir/${subRef.id}.json"
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