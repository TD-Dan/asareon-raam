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

    @Serializable data class FilesContentPayload(val correlationId: String?, val contents: Map<String, String>)
    @Serializable data class AnalysisCompletePayload(val items: List<ImportItem>, val selectedActions: Map<String, ImportAction>, val contents: Map<String, String>)

    /**
     * [GUARDRAIL] The canonical gate for all modification actions. It checks if a holon
     * or persona is part of a reserved HKG and if the action's originator is not the owner.
     *
     * @return True if the action should be blocked, false otherwise.
     */
    private fun isModificationLocked(
        holonId: String? = null,
        personaId: String? = null,
        originator: String,
        kgState: KnowledgeGraphState,
        store: Store
    ): Boolean {
        // Find the root persona ID for the targeted entity.
        val rootId = personaId ?: holonId?.let { findRootPersonaId(it, kgState) }

        if (rootId != null && kgState.reservations.containsKey(rootId)) {
            val owner = kgState.reservations[rootId]
            if (owner != originator) {
                val errorMsg = "Blocked modification on reserved HKG '$rootId' by non-owner '$originator'."
                platformDependencies.log(LogLevel.WARN, name, errorMsg)
                store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                    put("message", "Action failed: Knowledge Graph is locked by another agent.")
                }))
                return true // Locked
            }
        }
        return false // Not locked
    }

    private fun findRootPersonaId(holonId: String, kgState: KnowledgeGraphState): String? {
        var current = kgState.holons[holonId]
        while (current != null) {
            if (current.header.type == "AI_Persona_Root") {
                return current.header.id
            }
            current = current.header.parentId?.let { kgState.holons[it] }
        }
        return null
    }


    override fun onAction(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val originator = action.originator ?: return
        val kgState = newState as? KnowledgeGraphState ?: return
        val prevKgState = previousState as? KnowledgeGraphState
        val payload = action.payload

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
            // [FIX] Consolidated logic into a single block.
            ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG, ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG -> {
                // First, handle the guardrail specifically for RESERVE_HKG.
                if (action.name == ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG) {
                    val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return
                    if (prevKgState?.reservations?.containsKey(personaId) == true) {
                        val owner = prevKgState.reservations[personaId]
                        val errorMsg = "Agent '$originator' failed to reserve HKG '$personaId': already reserved by '$owner'."
                        platformDependencies.log(LogLevel.WARN, name, errorMsg)
                        store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                            put("message", "Failed to lock HKG: Already locked.")
                        }))
                    }
                }

                // Then, for both actions, broadcast if the state has changed.
                if (prevKgState?.reservations != kgState.reservations) {
                    store.deferredDispatch(this.name, Action(
                        name = ActionNames.KNOWLEDGEGRAPH_PUBLISH_RESERVATIONS_UPDATED,
                        payload = buildJsonObject {
                            put("reservedIds", json.encodeToJsonElement(kgState.reservations.keys.toList()))
                        }
                    ))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST, buildJsonObject {
                    put("subpath", personaId)
                    put("recursive", true)
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_REQUEST_CONTEXT -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return
                val correlationId = payload["correlationId"]?.jsonPrimitive?.content ?: return
                val contextMap = buildContextForPersona(personaId, kgState)
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("personaId", personaId)
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
                    val analysisPayload = runImportAnalysis(kgState.importFileContents, kgState, kgState.importUserOverrides, kgState.isImportRecursive, platformDependencies)
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
                    executeImportWrites(emptyMap(), kgState, store, platformDependencies)
                }
            }
            ActionNames.KNOWLEDGEGRAPH_COPY_ANALYSIS_TO_CLIPBOARD -> {
                val report = buildString {
                    appendLine("## HKG Import Analysis Report")
                    appendLine("Found ${kgState.importItems.size} files.")
                    appendLine()
                    kgState.importItems.forEach { item ->
                        val action = kgState.importSelectedActions[item.sourcePath]
                        if (action != null) {
                            val fileName = platformDependencies.getFileName(item.sourcePath)
                            appendLine("- **$fileName**")
                            appendLine("  - Action: `${action.summary}`")
                            item.statusReason?.let { appendLine("  - Reason: $it") }
                        }
                    }
                }
                store.dispatch(this.name, Action(ActionNames.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
                    put("text", report)
                }))
                store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
                    put("message", "Analysis report copied to clipboard.")
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_CREATE_PERSONA -> {
                val name = payload?.get("name")?.jsonPrimitive?.content ?: return
                val timestamp = platformDependencies.getSystemTimeMillis()
                val isoTimestamp = platformDependencies.formatIsoTimestamp(timestamp)
                val fileSafeTimestamp = isoTimestamp.replace(":", "").replace("-", "")
                val newId = normalizeHolonId("${name.lowercase().replace(" ", "-")}-${fileSafeTimestamp}")

                val newHolonHeader = HolonHeader(
                    id = newId, type = "AI_Persona_Root", name = name,
                    summary = "A newly created Holon Knowledge Graph.", version = "1.0.0",
                    createdAt = isoTimestamp, modifiedAt = isoTimestamp
                )
                val newHolon = Holon(header = newHolonHeader, payload = buildJsonObject {})
                val fullContent = prepareHolonForWriting(newHolon)

                val destSubpath = "$newId/$newId.json"
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", destSubpath); put("content", fullContent)
                }))

                store.dispatch("ui.kgView", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Created persona '$name'.") }))
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
            }
            ActionNames.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) return

                val holonToUpdate = kgState.holons[holonId] ?: return
                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())
                val updatedHeader = holonToUpdate.header.copy(modifiedAt = newTimestamp)
                val intermediateHolon = holonToUpdate.copy(
                    header = updatedHeader,
                    payload = payload["payload"] ?: holonToUpdate.payload,
                    execute = payload["execute"] ?: holonToUpdate.execute
                )
                val finalSyncedHolon = synchronizeRawContent(intermediateHolon)
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_RENAME_HOLON -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) return

                val newName = payload["newName"]?.jsonPrimitive?.content ?: return
                val holonToUpdate = kgState.holons[holonId] ?: return
                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())
                val updatedHeader = holonToUpdate.header.copy(name = newName, modifiedAt = newTimestamp)
                val intermediateHolon = holonToUpdate.copy(header = updatedHeader)
                val finalSyncedHolon = synchronizeRawContent(intermediateHolon)
                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                    put("subpath", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_PERSONA -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return
                if (isModificationLocked(personaId = personaId, originator = originator, kgState = kgState, store = store)) return

                store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
                    put("subpath", personaId)
                }))
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA, buildJsonObject {
                    put("personaId", personaId)
                }))
            }
            ActionNames.KNOWLEDGEGRAPH_DELETE_HOLON -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) return

                val holonToDelete = kgState.holons[holonId] ?: return
                val parentId = holonToDelete.header.parentId
                val parentHolon = parentId?.let { kgState.holons[it] }

                platformDependencies.getParentDirectory(holonToDelete.header.filePath)?.let { holonDir ->
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_DELETE_DIRECTORY, buildJsonObject {
                        put("subpath", holonDir)
                    }))
                }

                if (parentHolon != null) {
                    val updatedSubHolons = parentHolon.header.subHolons.filter { it.id != holonId }
                    val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())
                    val updatedParentHeader = parentHolon.header.copy(subHolons = updatedSubHolons, modifiedAt = newTimestamp)
                    val intermediateParent = parentHolon.copy(header = updatedParentHeader)
                    val finalSyncedParent = synchronizeRawContent(intermediateParent)
                    store.deferredDispatch(this.name, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                        put("subpath", finalSyncedParent.header.filePath)
                        put("content", prepareHolonForWriting(finalSyncedParent))
                    }))
                }
                store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON, buildJsonObject {
                    put("holonId", holonId)
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

            contextMap[currentHolon.header.id] = currentHolon.rawContent
            processedIds.add(currentHolon.header.id)

            currentHolon.header.subHolons.forEach { subRef ->
                kgState.holons[subRef.id]?.let { holonsToProcess.add(it) }
            }
        }
        return contextMap
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? KnowledgeGraphState ?: KnowledgeGraphState()
        val payload = action.payload

        when (action.name) {
            ActionNames.KNOWLEDGEGRAPH_RESERVE_HKG -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return currentFeatureState
                val originator = action.originator ?: return currentFeatureState

                // [FIX] Guardrail logic moved to the reducer to prevent invalid state changes.
                if (currentFeatureState.reservations.containsKey(personaId)) {
                    // Log/toast is handled by onAction which can see the previous state.
                    return currentFeatureState // Return original state, blocking the change.
                }

                return currentFeatureState.copy(
                    reservations = currentFeatureState.reservations + (personaId to originator)
                )
            }
            ActionNames.KNOWLEDGEGRAPH_RELEASE_HKG -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return currentFeatureState
                return currentFeatureState.copy(
                    reservations = currentFeatureState.reservations - personaId
                )
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_SET_PENDING_IMPORT_ID -> {
                val id = payload?.get("id")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(pendingImportCorrelationId = id)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_PERSONA_LOADED -> {
                val holons = payload?.get("holons")?.let { json.decodeFromJsonElement<Map<String, Holon>>(it) } ?: return currentFeatureState
                val newHolons = currentFeatureState.holons + holons
                val newRoots = newHolons.values
                    .filter { it.header.type == "AI_Persona_Root" }
                    .associate { it.header.name to it.header.id }
                return currentFeatureState.copy(holons = newHolons, personaRoots = newRoots, isLoading = false)
            }
            ActionNames.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                return currentFeatureState.copy(isLoading = true, fatalError = null)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED -> {
                val error = payload?.get("error")?.jsonPrimitive?.content ?: "Unknown loading error."
                return currentFeatureState.copy(isLoading = false, fatalError = error)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(activeHolonIdForView = holonId, holonIdToEdit = null)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToEdit = holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToRename = holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_TOGGLE_HOLON_EXPANDED -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return currentFeatureState
                val newSet = if (currentFeatureState.collapsedHolonIds.contains(holonId)) {
                    currentFeatureState.collapsedHolonIds - holonId
                } else {
                    currentFeatureState.collapsedHolonIds + holonId
                }
                return currentFeatureState.copy(collapsedHolonIds = newSet)
            }
            ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES -> {
                return currentFeatureState.copy(showSummariesInTreeView = !currentFeatureState.showSummariesInTreeView)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_TYPE_FILTERS -> {
                val types = payload?.get("types")?.let { json.decodeFromJsonElement<Set<String>>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(activeTypeFilters = types)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE -> {
                val modeStr = payload?.get("mode")?.jsonPrimitive?.content ?: return currentFeatureState
                val mode = KnowledgeGraphViewMode.valueOf(modeStr)
                if (mode == KnowledgeGraphViewMode.INSPECTOR) {
                    return currentFeatureState.copy(
                        viewMode = mode,
                        importItems = emptyList(),
                        importSelectedActions = emptyMap(),
                        importUserOverrides = emptyMap(),
                        importFileContents = emptyMap(),
                        pendingImportCorrelationId = null
                    )
                }
                return currentFeatureState.copy(viewMode = mode)
            }
            ActionNames.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS -> {
                return currentFeatureState.copy(
                    isLoading = true,
                    importItems = emptyList(),
                    importSelectedActions = emptyMap(),
                    importUserOverrides = emptyMap(),
                    importFileContents = emptyMap()
                )
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE -> {

                val analysis = payload?.let { json.decodeFromJsonElement<AnalysisCompletePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(
                    isLoading = false,
                    importItems = analysis.items,
                    importSelectedActions = analysis.selectedActions,
                    importFileContents = analysis.contents,
                    pendingImportCorrelationId = null
                )
            }
            ActionNames.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION -> {
                val sourcePath = payload?.get("sourcePath")?.jsonPrimitive?.content ?: return currentFeatureState
                val actionObj = payload["action"]?.let { json.decodeFromJsonElement<ImportAction>(it) } ?: return currentFeatureState

                val newOverrides = currentFeatureState.importUserOverrides + (sourcePath to actionObj)
                val analysisPayload = runImportAnalysis(currentFeatureState.importFileContents, currentFeatureState, newOverrides, currentFeatureState.isImportRecursive, platformDependencies)

                val newAnalysis = json.decodeFromJsonElement<AnalysisCompletePayload>(analysisPayload)
                return currentFeatureState.copy(
                    importUserOverrides = newOverrides,
                    importItems = newAnalysis.items,
                    importSelectedActions = newAnalysis.selectedActions
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE -> {
                val recursive = payload?.get("recursive")?.jsonPrimitive?.booleanOrNull ?: return currentFeatureState
                return currentFeatureState.copy(isImportRecursive = recursive, isLoading = true)
            }
            ActionNames.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED -> {
                return currentFeatureState.copy(showOnlyChangedImportItems = !currentFeatureState.showOnlyChangedImportItems)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(personaIdToDelete = personaId)
            }
            ActionNames.KNOWLEDGEGRAPH_SET_CREATING_PERSONA -> {
                val isCreating = payload?.get("isCreating")?.jsonPrimitive?.booleanOrNull ?: return currentFeatureState
                return currentFeatureState.copy(isCreatingPersona = isCreating)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_PERSONA -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return currentFeatureState
                val rootHolon = currentFeatureState.holons[personaId] ?: return currentFeatureState
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
                val newRoots = currentFeatureState.personaRoots.filterValues { it != personaId }
                return currentFeatureState.copy(
                    holons = newHolons,
                    personaRoots = newRoots,
                    activeHolonIdForView = null,
                    personaIdToDelete = null
                )
            }
            ActionNames.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToDelete = holonId)
            }
            ActionNames.KNOWLEDGEGRAPH_INTERNAL_CONFIRM_DELETE_HOLON -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return currentFeatureState
                val holonToDelete = currentFeatureState.holons[holonId] ?: return currentFeatureState
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
                        val updatedSubHolons = parentHolon.header.subHolons.filter { it.id != holonId }
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
                try {
                    val fileData = json.decodeFromJsonElement<FilesContentPayload>(envelope.payload)
                    if (fileData.correlationId != null && fileData.correlationId == kgState?.pendingImportCorrelationId) {
                        val analysisPayload = runImportAnalysis(fileData.contents, kgState, kgState.importUserOverrides, kgState.isImportRecursive, platformDependencies)
                        store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_ANALYSIS_COMPLETE, analysisPayload))
                    } else {
                        handleFilesContentForLoad(fileData, store)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Failed to process file content response."
                    platformDependencies.log(LogLevel.ERROR, name, errorMsg, e)
                    store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
                    store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED, buildJsonObject {
                        put("error", errorMsg)
                    }))
                }
            }
        }
    }

    private fun handleFilesContentForLoad(fileData: FilesContentPayload, store: Store) {
        val holonsById = mutableMapOf<String, Holon>()
        var hasErrors = false

        for ((path, rawContent) in fileData.contents) {
            try {
                // [REFACTOR] Use the canonical gateway for holon creation and validation.
                // This single call replaces manual JSON parsing and ID checking.
                val holon = createHolonFromString(rawContent, path, platformDependencies)
                holonsById[holon.header.id] = holon
            } catch (e: HolonValidationException) {
                platformDependencies.log(LogLevel.ERROR, name, "Failed to load holon at '$path': ${e.message}")
                hasErrors = true
            } catch (e: Exception) {
                // Catch any other unexpected errors.
                platformDependencies.log(LogLevel.ERROR, name, "An unexpected error occurred parsing '$path'", e)
                hasErrors = true
            }
        }

        if (holonsById.isEmpty() && fileData.contents.isNotEmpty()) {
            val errorMsg = "Loading failed: All holon files in the persona were malformed or had ID mismatches."
            store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
            store.deferredDispatch(this.name, Action(ActionNames.KNOWLEDGEGRAPH_INTERNAL_LOAD_FAILED, buildJsonObject {
                put("error", errorMsg)
            }))
            return
        }

        if(hasErrors) {
            store.dispatch(this.name, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Warning: Some holon files failed to load. Check logs.") }))
        }


        val enrichedHolons = mutableMapOf<String, Holon>()
        val allSubHolonIds = holonsById.values.flatMap { it.header.subHolons }.map { it.id }.toSet()
        val rootHolons = holonsById.values.filter { it.header.id !in allSubHolonIds }


        fun enrichRecursively(holon: Holon, parentId: String?, depth: Int) {
            val enrichedHeader = holon.header.copy(parentId = parentId, depth = depth)
            var enrichedHolon = holon.copy(header = enrichedHeader)

            // [THE FIX] After enriching, immediately synchronize the rawContent cache.
            enrichedHolon = synchronizeRawContent(enrichedHolon)

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