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
    @Serializable private data class LoadPersonaPayload(val personaId: String)
    @Serializable private data class RequestContextPayload(val personaId: String, val correlationId: String)
    @Serializable private data class SetViewPersonaPayload(val personaId: String?)
    @Serializable private data class SetViewHolonPayload(val holonId: String?)
    @Serializable private data class SetHolonToEditPayload(val holonId: String?)
    @Serializable private data class SetHolonToRenamePayload(val holonId: String?)
    @Serializable private data class SetViewModePayload(val mode: KnowledgeGraphViewMode)
    @Serializable private data class SetTypeFiltersPayload(val types: Set<String>)
    @Serializable private data class AnalysisCompletePayload(val items: List<ImportItem>, val contents: Map<String, String>)
    @Serializable private data class SetImportRecursivePayload(val recursive: Boolean)
    @Serializable private data class UpdateImportActionPayload(val sourcePath: String, val action: ImportAction)
    @Serializable private data class CreatePersonaPayload(val name: String)
    @Serializable private data class DeletePersonaPayload(val personaId: String)
    @Serializable private data class SetPersonaToDeletePayload(val personaId: String?)
    @Serializable private data class SetCreatingPersonaPayload(val isCreating: Boolean)
    @Serializable private data class UpdateHolonContentPayload(val holonId: String, val newContent: String)
    @Serializable private data class RenameHolonPayload(val holonId: String, val newName: String)
    @Serializable private data class DeleteHolonPayload(val holonId: String)
    @Serializable private data class SetHolonToDeletePayload(val holonId: String?)
    @Serializable private data class FilesContentPayload(val correlationId: String?, val contents: Map<String, String>)
    @Serializable private data class PersonaLoadedPayload(val holons: Map<String, Holon>)
    @Serializable private data class SetPendingImportIdPayload(val id: String?)


    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val originator = action.originator ?: return
        val kgState = newState as? KnowledgeGraphState ?: return
        val prevKgState = previousState as? KnowledgeGraphState

        when (action.name) {
            ActionNames.SYSTEM_PUBLISH_STARTING -> {
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED -> {
                if (prevKgState?.personaRoots != kgState.personaRoots) {
                    val idToNameMap = kgState.personaRoots.entries.associate { (name, id) -> id to name }
                    store.deferredDispatch(this.name, Action(
                        name = ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED,
                        payload = buildJsonObject {
                            put("names", json.encodeToJsonElement(idToNameMap))
                        }
                    ))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<LoadPersonaPayload>(it) } ?: return
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST, buildJsonObject {
                    put("subpath", payload.personaId)
                    put("recursive", true)
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestContextPayload>(it) } ?: return
                val contextMap = buildContextForPersona(payload.personaId, kgState)
                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    put("personaId", payload.personaId)
                    put("context", Json.encodeToJsonElement(contextMap))
                }
                val responseEnvelope = PrivateDataEnvelope(ActionNames.Envelopes.KNOWLEDGEGRAPH_RESPONSE_CONTEXT, responsePayload)
                store.deliverPrivateData(this.name, originator, responseEnvelope)
            }
            ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS -> {
                val correlationId = platformDependencies.generateUUID()
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_SET_PENDING_IMPORT_ID, buildJsonObject { put("id", correlationId) }))
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_REQUEST_SCOPED_READ_UI, buildJsonObject {
                    put("correlationId", correlationId)
                    put("recursive", true)
                    putJsonArray("fileExtensions") { add("json") }
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE -> {
                if (kgState.importFileContents.isNotEmpty()) {
                    val analysisPayload = runImportAnalysis(kgState.importFileContents, kgState, kgState.isImportRecursive)
                    store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, analysisPayload))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_EXECUTE_IMPORT -> {
                val parentHolonIdsToRead = kgState.importSelectedActions.values.mapNotNull { importAction ->
                    when (importAction) {
                        is Integrate -> importAction.parentHolonId
                        is AssignParent -> importAction.assignedParentId
                        else -> null
                    }
                }.toSet()

                val parentHolonFilePaths = parentHolonIdsToRead.mapNotNull { kgState.holons[it]?.header?.filePath }

                if (parentHolonFilePaths.isNotEmpty()) {
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_READ_FILES_CONTENT, buildJsonObject {
                        put("subpaths", Json.encodeToJsonElement(parentHolonFilePaths))
                    }))
                } else {
                    executeImportWrites(emptyMap(), kgState, store)
                }
            }
            ActionNames.KNOWLEDGEGRAPH_CREATE_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<CreatePersonaPayload>(it) } ?: return
                val timestamp = platformDependencies.getSystemTimeMillis()
                val isoTimestamp = platformDependencies.formatIsoTimestamp(timestamp)
                val fileSafeTimestamp = isoTimestamp.replace(":", "").replace("-", "").replace("Z", "").replace("T", "T")
                val newId = "${payload.name.lowercase().replace(" ", "-")}-${fileSafeTimestamp}"

                val newHolonHeader = HolonHeader(
                    id = newId, type = "AI_Persona_Root", name = payload.name,
                    summary = "A newly created Holon Knowledge Graph.", version = "1.0.0",
                    createdAt = isoTimestamp, modifiedAt = isoTimestamp
                )
                val newHolon = Holon(header = newHolonHeader, payload = buildJsonObject {})
                val fullContent = json.encodeToString(Holon.serializer(), newHolon)

                val destSubpath = "$newId/$newId.json"
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", destSubpath); put("content", fullContent)
                }))

                store.dispatch("ui.kgView", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Created persona '${payload.name}'.") }))
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<UpdateHolonContentPayload>(it) } ?: return
                val holonToUpdate = kgState.holons[payload.holonId] ?: return
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", holonToUpdate.header.filePath)
                    put("content", payload.newContent)
                }))
                holonToUpdate.header.parentId?.let { parentId ->
                    kgState.holons[parentId]?.let { parent ->
                        if (parent.header.type == "AI_Persona_Root") {
                            store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", parent.header.id) }))
                        }
                    }
                } ?: store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", holonToUpdate.header.id) }))
            }
            ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RenameHolonPayload>(it) } ?: return
                val holonToUpdate = kgState.holons[payload.holonId] ?: return
                val updatedHeader = holonToUpdate.header.copy(name = payload.newName)
                val updatedHolon = holonToUpdate.copy(header = updatedHeader)
                val newContent = json.encodeToString(Holon.serializer(), updatedHolon)
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", holonToUpdate.header.filePath)
                    put("content", newContent)
                }))
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", kgState.activePersonaIdForView ?: "") }))
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeletePersonaPayload>(it) } ?: return
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
                    put("subpath", payload.personaId)
                }))
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA, buildJsonObject {
                    put("personaId", payload.personaId)
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeleteHolonPayload>(it) } ?: return
                val holonToDelete = kgState.holons[payload.holonId] ?: return
                val parentId = holonToDelete.header.parentId
                val parentHolon = parentId?.let { kgState.holons[it] }

                platformDependencies.getParentDirectory(holonToDelete.header.filePath)?.let { holonDir ->
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
                        put("subpath", holonDir)
                    }))
                }

                if (parentHolon != null) {
                    val updatedSubHolons = parentHolon.header.subHolons.filter { it.id != payload.holonId }
                    val updatedParentHeader = parentHolon.header.copy(subHolons = updatedSubHolons)
                    val updatedParent = parentHolon.copy(header = updatedParentHeader)
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                        put("subpath", updatedParent.header.filePath)
                        put("content", json.encodeToString(Holon.serializer(), updatedParent))
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON, buildJsonObject {
                    put("holonId", payload.holonId)
                }))
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

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? KnowledgeGraphState ?: KnowledgeGraphState()

        when (action.name) {
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_SET_PENDING_IMPORT_ID -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetPendingImportIdPayload>(it) }
                return currentFeatureState.copy(pendingImportCorrelationId = payload?.id)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PersonaLoadedPayload>(it) } ?: return currentFeatureState
                val newHolons = currentFeatureState.holons + payload.holons
                val newRoots = newHolons.values
                    .filter { it.header.type == "AI_Persona_Root" }
                    .associate { it.header.name to it.header.id }
                return currentFeatureState.copy(holons = newHolons, personaRoots = newRoots, isLoading = false)
            }
            ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                return currentFeatureState.copy(isLoading = true)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED -> {
                val error = action.payload?.get("error")?.jsonPrimitive?.content ?: "Unknown loading error."
                return currentFeatureState.copy(isLoading = false, fatalError = error)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetViewPersonaPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(
                    activePersonaIdForView = payload.personaId,
                    activeHolonIdForView = null,
                    activeTypeFilters = emptySet()
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetViewHolonPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(activeHolonIdForView = payload.holonId, holonIdToEdit = null)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHolonToEditPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(holonIdToEdit = payload.holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHolonToRenamePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(holonIdToRename = payload.holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES -> {
                return currentFeatureState.copy(showSummariesInTreeView = !currentFeatureState.showSummariesInTreeView)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_TYPE_FILTERS -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetTypeFiltersPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(activeTypeFilters = payload.types)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetViewModePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(viewMode = payload.mode)
            }
            ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS -> {
                return currentFeatureState.copy(
                    isLoading = true,
                    importItems = emptyList(),
                    importSelectedActions = emptyMap(),
                    importFileContents = emptyMap()
                )
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<AnalysisCompletePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(
                    isLoading = false,
                    importItems = payload.items,
                    importSelectedActions = payload.items.associate { it.sourcePath to it.initialAction },
                    importFileContents = payload.contents,
                    pendingImportCorrelationId = null
                )
            }
            ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<UpdateImportActionPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(
                    importSelectedActions = currentFeatureState.importSelectedActions + (payload.sourcePath to payload.action)
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetImportRecursivePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(isImportRecursive = payload.recursive, isLoading = true)
            }
            ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED -> {
                return currentFeatureState.copy(showOnlyChangedImportItems = !currentFeatureState.showOnlyChangedImportItems)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetPersonaToDeletePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(personaIdToDelete = payload.personaId)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetCreatingPersonaPayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(isCreatingPersona = payload.isCreating)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeletePersonaPayload>(it) } ?: return currentFeatureState
                val rootHolon = currentFeatureState.holons[payload.personaId] ?: return currentFeatureState
                val idsToDelete = mutableSetOf<String>()
                val queue = mutableListOf(rootHolon)
                while (queue.isNotEmpty()) {
                    val current = queue.removeAt(0)
                    idsToDelete.add(current.header.id)
                    current.header.subHolons.forEach { subRef ->
                        currentFeatureState.holons[subRef.id]?.let { queue.add(it) }
                    }
                }
                val newHolons = currentFeatureState.holons - idsToDelete
                val newRoots = currentFeatureState.personaRoots.filterValues { it != payload.personaId }
                return currentFeatureState.copy(
                    holons = newHolons,
                    personaRoots = newRoots,
                    activePersonaIdForView = if (currentFeatureState.activePersonaIdForView == payload.personaId) null else currentFeatureState.activePersonaIdForView,
                    activeHolonIdForView = null,
                    personaIdToDelete = null
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SetHolonToDeletePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(holonIdToDelete = payload.holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DeleteHolonPayload>(it) } ?: return currentFeatureState
                val holonToDelete = currentFeatureState.holons[payload.holonId] ?: return currentFeatureState
                val idsToDelete = mutableSetOf<String>()
                val queue = mutableListOf(holonToDelete)
                while (queue.isNotEmpty()) {
                    val current = queue.removeAt(0)
                    idsToDelete.add(current.header.id)
                    current.header.subHolons.forEach { subRef ->
                        currentFeatureState.holons[subRef.id]?.let { queue.add(it) }
                    }
                }
                var newHolons = currentFeatureState.holons - idsToDelete
                holonToDelete.header.parentId?.let { parentId ->
                    currentFeatureState.holons[parentId]?.let { parentHolon ->
                        val updatedSubHolons = parentHolon.header.subHolons.filter { it.id != payload.holonId }
                        val updatedParent = parentHolon.copy(header = parentHolon.header.copy(subHolons = updatedSubHolons))
                        newHolons = newHolons + (parentId to updatedParent)
                    }
                }
                return currentFeatureState.copy(
                    holons = newHolons,
                    holonIdToDelete = null,
                    activeHolonIdForView = if (currentFeatureState.activeHolonIdForView in idsToDelete) null else currentFeatureState.activeHolonIdForView
                )
            }
            else -> return currentFeatureState
        }
    }

    override fun onPrivateData(envelope: PrivateDataEnvelope, store: Store) {
        val kgState = store.state.value.featureStates[name] as? KnowledgeGraphState
        when (envelope.type) {
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_LIST -> {
                val listing = envelope.payload["listing"]?.let { json.decodeFromJsonElement<List<FileEntry>>(it) } ?: return
                val isRecursiveResponse = envelope.payload["subpath"]?.jsonPrimitive?.content?.isNotEmpty() == true

                if (isRecursiveResponse) {
                    val fileSubpaths = listing.filter { !it.isDirectory }.map { it.path }
                    if (fileSubpaths.isNotEmpty()) {
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_READ_FILES_CONTENT, buildJsonObject {
                            put("subpaths", Json.encodeToJsonElement(fileSubpaths))
                        }))
                    }
                } else {
                    listing.filter { it.isDirectory }.forEach { dir ->
                        val personaId = platformDependencies.getFileName(dir.path)
                        store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", personaId) }))
                    }
                }
            }
            ActionNames.Envelopes.FILESYSTEM_RESPONSE_FILES_CONTENT -> {
                val fileData = try { json.decodeFromJsonElement<FilesContentPayload>(envelope.payload) } catch (e: Exception) { return }

                if (fileData.correlationId != null && fileData.correlationId == kgState?.pendingImportCorrelationId) {
                    val analysisPayload = runImportAnalysis(fileData.contents, kgState, kgState.isImportRecursive)
                    store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, analysisPayload))
                } else {
                    handleFilesContentForLoad(envelope.payload, store)
                }
            }
        }
    }

    private fun runImportAnalysis(
        fileContents: Map<String, String>,
        kgState: KnowledgeGraphState,
        isRecursive: Boolean
    ): JsonObject {
        val sourceHolons = fileContents.mapNotNull { (path, content) ->
            try { path to json.decodeFromString<Holon>(content) } catch (e: Exception) { null }
        }.toMap()

        val sourceParentMap = sourceHolons.values.flatMap { holon -> holon.header.subHolons.map { child -> child.id to holon.header.id } }.toMap()

        val importItems = fileContents.keys.mapNotNull { path ->
            val sourceHolon = sourceHolons[path]
            val holonId = platformDependencies.getFileName(path).removeSuffix(".json")

            when {
                sourceHolon == null -> ImportItem(path, Quarantine("Malformed JSON"), null)
                kgState.holons.containsKey(holonId) -> ImportItem(path, Update(holonId), kgState.holons[holonId]!!.header.filePath)
                sourceHolon.header.type == "AI_Persona_Root" -> ImportItem(path, CreateRoot(), null)
                sourceParentMap.containsKey(holonId) -> ImportItem(path, Integrate(sourceParentMap[holonId]!!), null)
                else -> ImportItem(path, Quarantine("Unknown top-level holon."), null)
            }
        }

        val filteredItems = if (isRecursive) {
            importItems
        } else {
            importItems.filter { !it.sourcePath.contains(platformDependencies.pathSeparator) }
        }

        return buildJsonObject {
            put("items", Json.encodeToJsonElement(filteredItems));
            put("contents", Json.encodeToJsonElement(fileContents))
        }
    }

    private fun handleFilesContentForLoad(payload: JsonObject, store: Store) {
        val fileData = try { json.decodeFromJsonElement<FilesContentPayload>(payload) } catch (e: Exception) { return }
        val holonsById = mutableMapOf<String, Holon>()

        for ((path, rawContent) in fileData.contents) {
            try {
                val holon = json.decodeFromString<Holon>(rawContent)
                val expectedId = platformDependencies.getFileName(path).removeSuffix(".json")
                if (holon.header.id != expectedId) {
                    platformDependencies.log(LogLevel.ERROR, name, "ID mismatch in '$path': expected '$expectedId', found '${holon.header.id}'. Skipping.")
                    continue
                }
                holonsById[holon.header.id] = holon.copy(header = holon.header.copy(filePath = path), content = rawContent)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to parse JSON for holon at '$path': ${e.message}")
            }
        }

        val enrichedHolons = mutableMapOf<String, Holon>()
        val rootHolons = holonsById.values.filter { holon -> holonsById.values.none { parent -> parent.header.subHolons.any { it.id == holon.header.id } } }

        fun enrichRecursively(holon: Holon, parentId: String?, depth: Int) {
            val enrichedHeader = holon.header.copy(parentId = parentId, depth = depth)
            val enrichedHolon = holon.copy(header = enrichedHeader)
            enrichedHolons[holon.header.id] = enrichedHolon
            enrichedHolon.header.subHolons.forEach { subRef ->
                holonsById[subRef.id]?.let { childHolon ->
                    enrichRecursively(childHolon, holon.header.id, depth + 1)
                }
            }
        }

        rootHolons.forEach { enrichRecursively(it, null, 0) }

        store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED, buildJsonObject {
            put("holons", json.encodeToJsonElement(enrichedHolons))
        }))
    }

    private fun executeImportWrites(parentContents: Map<String, String>, kgState: KnowledgeGraphState, store: Store) {
        val updatedParentContents = parentContents.toMutableMap()
        val processedHolonPaths = mutableMapOf<String, String>()

        var remainingActions = kgState.importSelectedActions.toMutableMap()
        var processedInPass: Int
        do {
            processedInPass = 0
            val actionsThisPass = remainingActions.toMap()
            remainingActions.clear()

            for ((sourcePath, action) in actionsThisPass) {
                var wasProcessed = true
                val sourceContent = kgState.importFileContents[sourcePath] ?: continue
                val sourceHolon = try { json.decodeFromString<Holon>(sourceContent) } catch (e: Exception) { continue }
                val holonId = sourceHolon.header.id

                when (action) {
                    is CreateRoot -> {
                        val destSubpath = "$holonId/$holonId.json"
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                            put("subpath", destSubpath); put("content", sourceContent)
                        }))
                        processedHolonPaths[holonId] = destSubpath
                    }
                    is Update -> {
                        val destSubpath = kgState.holons[action.targetHolonId]?.header?.filePath ?: continue
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                            put("subpath", destSubpath); put("content", sourceContent)
                        }))
                    }
                    is Integrate, is AssignParent -> {
                        val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                        if (parentId == null) { wasProcessed = false; remainingActions[sourcePath] = action; continue }

                        val parentSubpath = kgState.holons[parentId]?.header?.filePath ?: processedHolonPaths[parentId]
                        if (parentSubpath == null) { wasProcessed = false; remainingActions[sourcePath] = action; continue }

                        val parentDir = platformDependencies.getParentDirectory(parentSubpath)
                        if (parentDir == null) { wasProcessed = false; remainingActions[sourcePath] = action; continue }

                        val destSubpath = "$parentDir/$holonId/$holonId.json"
                        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                            put("subpath", destSubpath); put("content", sourceContent)
                        }))
                        processedHolonPaths[holonId] = destSubpath

                        val parentContentStr = updatedParentContents[parentSubpath] ?: parentContents[parentSubpath]
                        if (parentContentStr != null) {
                            val parentFileHolon = json.decodeFromString<Holon>(parentContentStr)
                            val newSubRef = SubHolonRef(holonId, sourceHolon.header.type, sourceHolon.header.summary ?: "")
                            if (parentFileHolon.header.subHolons.none { it.id == holonId }) {
                                val updatedHeader = parentFileHolon.header.copy(subHolons = parentFileHolon.header.subHolons + newSubRef)
                                val updatedParentHolon = parentFileHolon.copy(header = updatedHeader)
                                updatedParentContents[parentSubpath] = json.encodeToString(Holon.serializer(), updatedParentHolon)
                            }
                        }
                    }
                    is Quarantine -> { /* TODO: needs to be moved to a special quarantine folder for further editing. */ }
                    is Ignore -> { /* No-op */ }
                }
                if (wasProcessed) processedInPass++
            }
        } while (processedInPass > 0 && remainingActions.isNotEmpty())

        updatedParentContents.forEach { (subpath, content) ->
            if (parentContents[subpath] != content) {
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", subpath); put("content", content)
                }))
            }
        }
        store.dispatch("ui.kgView", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Import complete. Reloading Knowledge Graph...") }))
        store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) }))
        store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
    }

    override val composableProvider: Feature.ComposableProvider = object : Feature.ComposableProvider {
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> =
            mapOf("feature.knowledgegraph.main" to { store, _ -> KnowledgeGraphView(store, platformDependencies) })

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