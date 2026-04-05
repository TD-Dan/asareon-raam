package asareon.raam.feature.knowledgegraph

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.FileEntry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.text.get
import kotlin.text.iterator

class KnowledgeGraphFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "knowledgegraph", localHandle = "knowledgegraph", name="Knowledge Graph")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    @Serializable data class FilesContentPayload(val correlationId: String? = null, val contents: Map<String, String>)
    @Serializable data class AnalysisCompletePayload(val items: List<ImportItem>, val selectedActions: Map<String, ImportAction>, val contents: Map<String, String>)

    private fun isModificationLocked(
        holonId: String? = null,
        personaId: String? = null,
        originator: String,
        kgState: KnowledgeGraphState,
        store: Store
    ): Boolean {
        val rootId = personaId ?: holonId?.let { findRootPersonaId(it, kgState) }

        if (rootId != null && kgState.reservations.containsKey(rootId)) {
            val owner = kgState.reservations[rootId]
            if (owner != originator) {
                val errorMsg = "Blocked modification on reserved HKG '$rootId' by non-owner '$originator'."
                platformDependencies.log(LogLevel.WARN, identity.handle, errorMsg)
                store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                    put("message", "Action failed: Knowledge Graph is locked by another user.")
                }))
                return true
            }
        }
        return false
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


    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val originator = action.originator ?: run {
            platformDependencies.log(LogLevel.WARN, identity.handle, "${action.name}: Received action with null originator. Ignoring.")
            return
        }
        val kgState = newState as? KnowledgeGraphState ?: return
        val prevKgState = previousState as? KnowledgeGraphState
        val payload = action.payload

        when (action.name) {
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val listing = action.payload?.get("listing")?.let { json.decodeFromJsonElement<List<FileEntry>>(it) } ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RETURN_LIST: Missing or malformed 'listing' field. Ignoring.")
                    return
                }
                val isRecursiveResponse = action.payload["path"]?.jsonPrimitive?.content?.isNotEmpty() == true

                if (isRecursiveResponse) {
                    val filePaths = listing.filter { !it.isDirectory }.map { it.path }
                    if (filePaths.isNotEmpty()) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE, buildJsonObject {
                            put("paths", Json.encodeToJsonElement(filePaths))
                        }))
                    } else {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_FAILED, buildJsonObject {
                            put("error", "No holon files found in persona directory.")
                        }))
                    }
                } else {
                    listing.filter { it.isDirectory }.forEach { dir ->
                        val personaId = platformDependencies.getFileName(dir.path)
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", personaId) }))
                    }
                }
            }
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                try {
                    val rawPayload = action.payload ?: run {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "RETURN_FILES_CONTENT: Received action with null payload. Ignoring.")
                        return
                    }
                    val fileData = json.decodeFromJsonElement<FilesContentPayload>(rawPayload)

                    if (kgState.isExecutingImport == true) {
                        store.dispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_EXECUTION_STATUS, buildJsonObject { put("isExecuting", false) }))
                        executeImportWrites(fileData.contents, kgState, store, platformDependencies)
                    } else if (fileData.correlationId != null && fileData.correlationId == kgState.pendingImportCorrelationId) {
                        val analysisPayload = runImportAnalysis(fileData.contents, kgState, kgState.importUserOverrides, kgState.isImportRecursive, platformDependencies)
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_ANALYSIS_COMPLETE, analysisPayload))
                    } else {
                        handleFilesContentForLoad(fileData, store)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Failed to process file content response."
                    platformDependencies.log(LogLevel.ERROR, identity.handle, errorMsg, e)
                    store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_FAILED, buildJsonObject {
                        put("error", errorMsg)
                    }))
                }
            }
            ActionRegistry.Names.SYSTEM_RUNNING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST))
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_PERSONA_LOADED -> {
                if (prevKgState?.personaRoots != kgState.personaRoots) {
                    val idToNameMap = kgState.personaRoots.entries.associate { (name, id) -> id to name }
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.KNOWLEDGEGRAPH_AVAILABLE_PERSONAS_UPDATED,
                        payload = buildJsonObject {
                            put("names", json.encodeToJsonElement(idToNameMap))
                        }
                    ))
                }
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG, ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull

                if (action.name == ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG) {
                    val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: run {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "RESERVE_HKG: Missing required 'personaId' field. Ignoring.")
                        publishActionResult(store, correlationId, action.name, false, error = "Missing required 'personaId' field.")
                        return
                    }
                    if (prevKgState?.reservations?.containsKey(personaId) == true) {
                        val owner = prevKgState.reservations[personaId]
                        if (owner == originator) {
                            platformDependencies.log(LogLevel.DEBUG, identity.handle, "'$originator' re-reserved own HKG '$personaId'. No-op.")
                            publishActionResult(store, correlationId, action.name, true, summary = "HKG '$personaId' already reserved by '$originator'.")
                        } else {
                            val errorMsg = "'$originator' failed to reserve HKG '$personaId': already reserved by '$owner'."
                            platformDependencies.log(LogLevel.WARN, identity.handle, errorMsg)
                            store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                                put("message", "Failed to lock HKG: Already locked.")
                            }))
                            publishActionResult(store, correlationId, action.name, false, error = errorMsg)
                        }
                    } else {
                        publishActionResult(store, correlationId, action.name, true, summary = "HKG '$personaId' reserved by '$originator'.")
                    }
                } else {
                    // RELEASE_HKG
                    val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: run {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "RELEASE_HKG: Missing required 'personaId' field. Ignoring.")
                        publishActionResult(store, correlationId, action.name, false, error = "Missing required 'personaId' field.")
                        return
                    }
                    publishActionResult(store, correlationId, action.name, true, summary = "HKG '$personaId' released.")
                }

                if (prevKgState?.reservations != kgState.reservations) {
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVATIONS_UPDATED,
                        payload = buildJsonObject {
                            put("reservedIds", json.encodeToJsonElement(kgState.reservations.keys.toList()))
                        }
                    ))
                }
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "LOAD_PERSONA: Missing required 'personaId' field. Ignoring.")
                    return
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
                    put("path", personaId)
                    put("recursive", true)
                }))
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_REQUEST_CONTEXT -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REQUEST_CONTEXT: Missing required 'personaId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'personaId' field.")
                    return
                }
                if (correlationId == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REQUEST_CONTEXT: Missing required 'correlationId' field. Ignoring.")
                    publishActionResult(store, null, action.name, false, error = "Missing required 'correlationId' field.")
                    return
                }
                val contextMap = buildContextForPersona(personaId, kgState)
                val responsePayload = buildJsonObject {
                    put("correlationId", correlationId)
                    put("personaId", personaId)
                    put("context", Json.encodeToJsonElement(contextMap))
                }
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT,
                    payload = responsePayload,
                    targetRecipient = originator
                ))
                publishActionResult(store, correlationId, action.name, true, summary = "Context delivered for persona '$personaId'.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS -> {
                val correlationId = platformDependencies.generateUUID()
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PENDING_IMPORT_ID, buildJsonObject { put("id", correlationId) }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_REQUEST_SCOPED_READ_UI, buildJsonObject {
                    put("correlationId", correlationId)
                    put("recursive", true)
                    putJsonArray("fileExtensions") { add("json") }
                }))
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE -> {
                if (kgState.importFileContents.isNotEmpty()) {
                    val analysisPayload = runImportAnalysis(kgState.importFileContents, kgState, kgState.importUserOverrides, kgState.isImportRecursive, platformDependencies)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_ANALYSIS_COMPLETE, analysisPayload))
                }
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_EXECUTE_IMPORT -> {
                val parentHolonIdsToRead = kgState.importSelectedActions.values.mapNotNull { importAction ->
                    when (importAction) {
                        is Integrate -> importAction.parentHolonId
                        is AssignParent -> importAction.assignedParentId
                        else -> null
                    }
                }.toSet()

                val parentHolonFilePaths = parentHolonIdsToRead.mapNotNull { kgState.holons[it]?.header?.filePath }

                if (parentHolonFilePaths.isNotEmpty()) {
                    // [FIX] Explicitly signal that the next read response is for execution context.
                    store.dispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_EXECUTION_STATUS, buildJsonObject { put("isExecuting", true) }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE, buildJsonObject {
                        put("paths", Json.encodeToJsonElement(parentHolonFilePaths))
                    }))
                } else {
                    executeImportWrites(emptyMap(), kgState, store, platformDependencies)
                }
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_COPY_ANALYSIS_TO_CLIPBOARD -> {
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
                store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject {
                    put("text", report)
                }))
                store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                    put("message", "Analysis report copied to clipboard.")
                }))
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_PERSONA -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val name = payload?.get("name")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_PERSONA: Missing required 'name' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'name' field.")
                    return
                }
                val timestamp = platformDependencies.currentTimeMillis()
                val isoTimestamp = platformDependencies.formatIsoTimestamp(timestamp)
                val fileSafeTimestamp = isoTimestamp.replace(":", "").replace("-", "")
                val newId = try {
                    normalizeHolonId("${name.lowercase().replace(" ", "-")}-${fileSafeTimestamp}")
                } catch (e: IllegalArgumentException) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_PERSONA: Invalid name for ID generation: ${e.message}")
                    publishActionResult(store, correlationId, action.name, false, error = "Invalid name for ID generation: ${e.message}")
                    return
                }

                val newHolonHeader = HolonHeader(
                    id = newId, type = "AI_Persona_Root", name = name,
                    summary = "A newly created Holon Knowledge Graph.", version = "1.0.0",
                    createdAt = isoTimestamp, modifiedAt = isoTimestamp
                )
                val newHolon = Holon(header = newHolonHeader, payload = buildJsonObject {})
                val fullContent = prepareHolonForWriting(newHolon)

                val destPath = "$newId/$newId.json"
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", destPath); put("content", fullContent)
                }))

                store.dispatch("knowledgegraph", Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Created persona '$name'.") }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST))

                publishActionResult(store, correlationId, action.name, true, summary = "Persona '$name' ($newId) created.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_HOLON_CONTENT -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "UPDATE_HOLON_CONTENT: Missing required 'holonId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'holonId' field.")
                    return
                }
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                val holonToUpdate = kgState.holons[holonId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "UPDATE_HOLON_CONTENT: Holon '$holonId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Holon '$holonId' not found in state.")
                    return
                }
                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
                val updatedHeader = holonToUpdate.header.copy(modifiedAt = newTimestamp)
                val intermediateHolon = holonToUpdate.copy(
                    header = updatedHeader,
                    payload = payload["payload"] ?: holonToUpdate.payload,
                    execute = payload["execute"] ?: holonToUpdate.execute
                )
                val finalSyncedHolon = synchronizeRawContent(intermediateHolon)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$holonId' content updated.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_CREATE_HOLON -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val parentId = payload?.get("parentId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Missing required 'parentId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'parentId' field.")
                    return
                }
                val typeName = payload["type"]?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Missing required 'type' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'type' field.")
                    return
                }
                val name = payload["name"]?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Missing required 'name' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'name' field.")
                    return
                }
                val newPayload = payload["payload"] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Missing required 'payload' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'payload' field.")
                    return
                }
                val summary = payload["summary"]?.jsonPrimitive?.contentOrNull
                val newExecute = payload["execute"]

                if (isModificationLocked(holonId = parentId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                val parentHolon = kgState.holons[parentId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Parent holon '$parentId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Parent holon '$parentId' not found in state.")
                    return
                }

                // --- Generate ID and timestamps ---
                val timestamp = platformDependencies.currentTimeMillis()
                val isoTimestamp = platformDependencies.formatIsoTimestamp(timestamp)
                val fileSafeTimestamp = isoTimestamp.replace(":", "").replace("-", "")
                val newId = try {
                    normalizeHolonId("${name.lowercase().replace(" ", "-")}-${fileSafeTimestamp}")
                } catch (e: IllegalArgumentException) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Invalid name for ID generation: ${e.message}")
                    publishActionResult(store, correlationId, action.name, false, error = "Invalid name for ID generation: ${e.message}")
                    return
                }

                // --- Derive file path from parent's path ---
                // Parent is at: {personaId}/.../parentId/parentId.json
                // New child at: {personaId}/.../parentId/newId/newId.json
                val parentDir = platformDependencies.getParentDirectory(parentHolon.header.filePath) ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "CREATE_HOLON: Could not resolve parent directory for '$parentId'. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Could not resolve parent directory for '$parentId'.")
                    return
                }
                val newFilePath = "$parentDir/$newId/$newId.json"

                // --- Build the new holon ---
                val newHeader = HolonHeader(
                    id = newId,
                    type = typeName,
                    name = name,
                    summary = summary,
                    version = "1.0.0",
                    createdAt = isoTimestamp,
                    modifiedAt = isoTimestamp,
                    filePath = newFilePath,
                    parentId = parentId,
                    depth = parentHolon.header.depth + 1
                )
                val newHolon = Holon(header = newHeader, payload = newPayload, execute = newExecute)
                val newHolonContent = prepareHolonForWriting(newHolon)

                // --- Update parent's sub_holons list ---
                val updatedParentSubHolons = parentHolon.header.subHolons + SubHolonRef(
                    id = newId,
                    type = typeName,
                    summary = summary ?: name
                )
                val newParentTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
                val updatedParentHeader = parentHolon.header.copy(
                    subHolons = updatedParentSubHolons,
                    modifiedAt = newParentTimestamp
                )
                val updatedParent = parentHolon.copy(header = updatedParentHeader)
                val finalSyncedParent = synchronizeRawContent(updatedParent)

                // --- Write both files ---
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", newFilePath)
                    put("content", newHolonContent)
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", finalSyncedParent.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedParent))
                }))

                // --- Reload the persona ---
                findRootPersonaId(parentId, kgState)?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$name' ($newId) created under '$parentId'.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_PATCH_HOLON -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PATCH_HOLON: Missing required 'holonId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'holonId' field.")
                    return
                }
                val operationsArray = payload["operations"] as? JsonArray ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PATCH_HOLON: Missing or invalid 'operations' array. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing or invalid 'operations' array.")
                    return
                }
                if (operationsArray.isEmpty()) {
                    publishActionResult(store, correlationId, action.name, false, error = "'operations' array must not be empty.")
                    return
                }

                // --- Lock check ---
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                // --- Fetch existing holon ---
                val existingHolon = kgState.holons[holonId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PATCH_HOLON: Holon '$holonId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Holon '$holonId' not found in state.")
                    return
                }

                // --- Build the navigable JSON tree from the holon ---
                val holonTree = buildHolonJsonTree(existingHolon, json)

                // --- PASS 1: Validate all operations ---
                val (validatedOps, validationErrors) = validatePatchOperations(operationsArray, holonTree)

                if (validationErrors.isNotEmpty()) {
                    val errorMsg = "Patch validation failed (${validationErrors.size} error(s)). No changes applied:\n" +
                            validationErrors.joinToString("\n") { "  • $it" }
                    platformDependencies.log(LogLevel.WARN, identity.handle, "PATCH_HOLON on '$holonId': $errorMsg")
                    publishActionResult(store, correlationId, action.name, false, error = errorMsg)
                    return
                }

                // --- PASS 2: Apply all operations sequentially ---
                var currentTree: JsonElement = holonTree
                try {
                    for (patchOp in validatedOps) {
                        currentTree = applyPatchOp(currentTree, patchOp.path, patchOp.op, patchOp.value)
                    }
                } catch (e: Exception) {
                    val errorMsg = "Patch application failed unexpectedly: ${e.message}. No changes were persisted."
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "PATCH_HOLON on '$holonId': $errorMsg", e)
                    publishActionResult(store, correlationId, action.name, false, error = errorMsg)
                    return
                }

                val patchedTree = currentTree as? JsonObject ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "PATCH_HOLON: Result is not a JsonObject after patching. This should never happen.")
                    publishActionResult(store, correlationId, action.name, false, error = "Internal error: patched result is not a valid holon structure.")
                    return
                }

                // --- Extract components and rebuild the holon ---
                val (patchedHeader, patchedPayload, patchedExecute) = try {
                    extractPatchedComponents(patchedTree, existingHolon, json)
                } catch (e: Exception) {
                    val errorMsg = "Failed to reconstruct holon after patching: ${e.message}"
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "PATCH_HOLON on '$holonId': $errorMsg", e)
                    publishActionResult(store, correlationId, action.name, false, error = errorMsg)
                    return
                }

                // --- Stamp modified_at ---
                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
                val finalHeader = patchedHeader.copy(modifiedAt = newTimestamp)

                val patchedHolon = existingHolon.copy(
                    header = finalHeader,
                    payload = patchedPayload,
                    execute = patchedExecute
                )
                val finalSyncedHolon = synchronizeRawContent(patchedHolon)

                // --- Update parent's sub_holon ref if name/type/summary changed ---
                existingHolon.header.parentId?.let { parentId ->
                    kgState.holons[parentId]?.let { parentHolon ->
                        val updatedSubHolons = parentHolon.header.subHolons.map { ref ->
                            if (ref.id == holonId) {
                                ref.copy(
                                    type = finalHeader.type,
                                    summary = finalHeader.summary ?: finalHeader.name
                                )
                            } else ref
                        }
                        if (updatedSubHolons != parentHolon.header.subHolons) {
                            val updatedParent = parentHolon.copy(
                                header = parentHolon.header.copy(
                                    subHolons = updatedSubHolons,
                                    modifiedAt = newTimestamp
                                )
                            )
                            val syncedParent = synchronizeRawContent(updatedParent)
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                                put("path", syncedParent.header.filePath)
                                put("content", prepareHolonForWriting(syncedParent))
                            }))
                        }
                    }
                }

                // --- Write the patched holon ---
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))

                // --- Reload the persona ---
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }

                val opSummary = validatedOps.joinToString(", ") { "${it.op} ${it.path.joinToString("/", "/")}" }
                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$holonId' patched (${validatedOps.size} op(s): $opSummary).")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_REPLACE_HOLON -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REPLACE_HOLON: Missing required 'holonId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'holonId' field.")
                    return
                }
                val newPayload = payload["payload"] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REPLACE_HOLON: Missing required 'payload' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'payload' field.")
                    return
                }

                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                val existingHolon = kgState.holons[holonId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "REPLACE_HOLON: Holon '$holonId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Holon '$holonId' not found in state.")
                    return
                }

                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())

                // --- Build replaced header, preserving structural fields ---
                val replacedHeader = existingHolon.header.copy(
                    type = payload["type"]?.jsonPrimitive?.contentOrNull ?: existingHolon.header.type,
                    name = payload["name"]?.jsonPrimitive?.contentOrNull ?: existingHolon.header.name,
                    summary = payload["summary"]?.jsonPrimitive?.contentOrNull ?: existingHolon.header.summary,
                    version = payload["version"]?.jsonPrimitive?.contentOrNull ?: existingHolon.header.version,
                    relationships = payload["relationships"]?.let { json.decodeFromJsonElement<List<Relationship>>(it) }
                        ?: existingHolon.header.relationships,
                    modifiedAt = newTimestamp
                )

                // --- Handle execute: omitted key = preserve, explicit null = remove ---
                val replacedExecute = if (payload.containsKey("execute")) {
                    val execValue = payload["execute"]
                    if (execValue is JsonNull) null else execValue
                } else {
                    existingHolon.execute
                }

                val replacedHolon = existingHolon.copy(
                    header = replacedHeader,
                    payload = newPayload,
                    execute = replacedExecute
                )
                val finalSyncedHolon = synchronizeRawContent(replacedHolon)

                // --- Update parent's sub_holon ref if name/type/summary changed ---
                existingHolon.header.parentId?.let { parentId ->
                    kgState.holons[parentId]?.let { parentHolon ->
                        val updatedSubHolons = parentHolon.header.subHolons.map { ref ->
                            if (ref.id == holonId) {
                                ref.copy(
                                    type = replacedHeader.type,
                                    summary = replacedHeader.summary ?: replacedHeader.name
                                )
                            } else ref
                        }
                        if (updatedSubHolons != parentHolon.header.subHolons) {
                            val updatedParent = parentHolon.copy(
                                header = parentHolon.header.copy(
                                    subHolons = updatedSubHolons,
                                    modifiedAt = newTimestamp
                                )
                            )
                            val syncedParent = synchronizeRawContent(updatedParent)
                            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                                put("path", syncedParent.header.filePath)
                                put("content", prepareHolonForWriting(syncedParent))
                            }))
                        }
                    }
                }

                // --- Write the replaced holon ---
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))

                // --- Reload the persona ---
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$holonId' replaced.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_RENAME_HOLON -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RENAME_HOLON: Missing required 'holonId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'holonId' field.")
                    return
                }
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                val newName = payload["newName"]?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RENAME_HOLON: Missing required 'newName' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'newName' field.")
                    return
                }
                val holonToUpdate = kgState.holons[holonId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "RENAME_HOLON: Holon '$holonId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Holon '$holonId' not found in state.")
                    return
                }
                val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
                val updatedHeader = holonToUpdate.header.copy(name = newName, modifiedAt = newTimestamp)
                val intermediateHolon = holonToUpdate.copy(header = updatedHeader)
                val finalSyncedHolon = synchronizeRawContent(intermediateHolon)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                    put("path", finalSyncedHolon.header.filePath)
                    put("content", prepareHolonForWriting(finalSyncedHolon))
                }))
                findRootPersonaId(holonId, kgState)?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA, buildJsonObject { put("personaId", it) }))
                }

                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$holonId' renamed to '$newName'.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_PERSONA -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_PERSONA: Missing required 'personaId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'personaId' field.")
                    return
                }
                if (isModificationLocked(personaId = personaId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject {
                    put("path", personaId)
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_PERSONA, buildJsonObject {
                    put("personaId", personaId)
                }))

                publishActionResult(store, correlationId, action.name, true, summary = "Persona '$personaId' deleted.")
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_DELETE_HOLON -> {
                val correlationId = payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_HOLON: Missing required 'holonId' field. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Missing required 'holonId' field.")
                    return
                }
                if (isModificationLocked(holonId = holonId, originator = originator, kgState = kgState, store = store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "HKG is locked by another user.")
                    return
                }

                val holonToDelete = kgState.holons[holonId] ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_HOLON: Holon '$holonId' not found in state. Ignoring.")
                    publishActionResult(store, correlationId, action.name, false, error = "Holon '$holonId' not found in state.")
                    return
                }
                val parentId = holonToDelete.header.parentId
                val parentHolon = parentId?.let { kgState.holons[it] }

                val holonDir = platformDependencies.getParentDirectory(holonToDelete.header.filePath)
                if (holonDir != null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject {
                        put("path", holonDir)
                    }))
                } else {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "DELETE_HOLON: Could not resolve parent directory for holon '$holonId' at path '${holonToDelete.header.filePath}'. Directory deletion skipped.")
                }

                if (parentHolon != null) {
                    val updatedSubHolons = parentHolon.header.subHolons.filter { it.id != holonId }
                    val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
                    val updatedParentHeader = parentHolon.header.copy(subHolons = updatedSubHolons, modifiedAt = newTimestamp)
                    val intermediateParent = parentHolon.copy(header = updatedParentHeader)
                    val finalSyncedParent = synchronizeRawContent(intermediateParent)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                        put("path", finalSyncedParent.header.filePath)
                        put("content", prepareHolonForWriting(finalSyncedParent))
                    }))
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_HOLON, buildJsonObject {
                    put("holonId", holonId)
                }))

                publishActionResult(store, correlationId, action.name, true, summary = "Holon '$holonId' deleted.")
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

    // ========================================================================
    // ACTION_RESULT broadcast helper
    // ========================================================================

    /**
     * Publishes a lightweight broadcast notification after completing a
     * command-dispatchable action. CommandBot matches via `correlationId`
     * to post feedback to the originating session.
     *
     * Follows the same contract as `AgentRuntimeFeature.publishActionResult`.
     */
    private fun publishActionResult(
        store: Store,
        correlationId: String?,
        requestAction: String,
        success: Boolean,
        summary: String? = null,
        error: String? = null
    ) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.KNOWLEDGEGRAPH_ACTION_RESULT,
            payload = buildJsonObject {
                correlationId?.let { put("correlationId", it) }
                put("requestAction", requestAction)
                put("success", success)
                summary?.let { put("summary", it) }
                error?.let { put("error", it) }
            }
        ))
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? KnowledgeGraphState ?: KnowledgeGraphState()
        val payload = action.payload

        when (action.name) {
            // [FIX] Handle the new action to toggle the execution flag.
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_EXECUTION_STATUS -> {
                val isExecuting = payload?.get("isExecuting")?.jsonPrimitive?.booleanOrNull ?: false
                return currentFeatureState.copy(isExecutingImport = isExecuting)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_RESERVE_HKG -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return currentFeatureState
                val originator = action.originator ?: return currentFeatureState

                if (currentFeatureState.reservations.containsKey(personaId)) {
                    return currentFeatureState
                }

                return currentFeatureState.copy(
                    reservations = currentFeatureState.reservations + (personaId to originator)
                )
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_RELEASE_HKG -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.content ?: return currentFeatureState
                return currentFeatureState.copy(
                    reservations = currentFeatureState.reservations - personaId
                )
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PENDING_IMPORT_ID -> {
                val id = payload?.get("id")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(pendingImportCorrelationId = id)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_PERSONA_LOADED -> {
                val holons = payload?.get("holons")?.let { json.decodeFromJsonElement<Map<String, Holon>>(it) } ?: return currentFeatureState
                val newHolons = currentFeatureState.holons + holons
                val newRoots = newHolons.values
                    .filter { it.header.type == "AI_Persona_Root" }
                    .associate { it.header.name to it.header.id }
                return currentFeatureState.copy(holons = newHolons, personaRoots = newRoots, isLoading = false)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_PERSONA -> {
                return currentFeatureState.copy(isLoading = true, fatalError = null)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_FAILED -> {
                val error = payload?.get("error")?.jsonPrimitive?.content ?: "Unknown loading error."
                return currentFeatureState.copy(isLoading = false, fatalError = error)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_ACTIVE_VIEW_HOLON -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(activeHolonIdForView = holonId, holonIdToEdit = null)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_EDIT -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToEdit = holonId)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_RENAME -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToRename = holonId)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_HOLON_EXPANDED -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.content ?: return currentFeatureState
                val newSet = if (currentFeatureState.collapsedHolonIds.contains(holonId)) {
                    currentFeatureState.collapsedHolonIds - holonId
                } else {
                    currentFeatureState.collapsedHolonIds + holonId
                }
                return currentFeatureState.copy(collapsedHolonIds = newSet)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_SUMMARIES -> {
                return currentFeatureState.copy(showSummariesInTreeView = !currentFeatureState.showSummariesInTreeView)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_TYPE_FILTERS -> {
                val types = payload?.get("types")?.let { json.decodeFromJsonElement<Set<String>>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(activeTypeFilters = types)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE -> {
                val modeStr = payload?.get("mode")?.jsonPrimitive?.content ?: return currentFeatureState
                val mode = KnowledgeGraphViewMode.valueOf(modeStr)
                if (mode == KnowledgeGraphViewMode.INSPECTOR) {
                    return currentFeatureState.copy(
                        viewMode = mode,
                        importItems = emptyList(),
                        importSelectedActions = emptyMap(),
                        importUserOverrides = emptyMap(),
                        importFileContents = emptyMap(),
                        pendingImportCorrelationId = null,
                        isExecutingImport = false // Reset execution flag on view switch
                    )
                }
                return currentFeatureState.copy(viewMode = mode)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_START_IMPORT_ANALYSIS -> {
                return currentFeatureState.copy(
                    isLoading = true,
                    importItems = emptyList(),
                    importSelectedActions = emptyMap(),
                    importUserOverrides = emptyMap(),
                    importFileContents = emptyMap()
                )
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_ANALYSIS_COMPLETE -> {

                val analysis = payload?.let { json.decodeFromJsonElement<AnalysisCompletePayload>(it) } ?: return currentFeatureState
                return currentFeatureState.copy(
                    isLoading = false,
                    importItems = analysis.items,
                    importSelectedActions = analysis.selectedActions,
                    importFileContents = analysis.contents,
                    pendingImportCorrelationId = null
                )
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_UPDATE_IMPORT_ACTION -> {
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
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_IMPORT_RECURSIVE -> {
                val recursive = payload?.get("recursive")?.jsonPrimitive?.booleanOrNull ?: return currentFeatureState
                return currentFeatureState.copy(isImportRecursive = recursive, isLoading = true)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_TOGGLE_SHOW_ONLY_CHANGED -> {
                return currentFeatureState.copy(showOnlyChangedImportItems = !currentFeatureState.showOnlyChangedImportItems)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_PERSONA_TO_DELETE -> {
                val personaId = payload?.get("personaId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(personaIdToDelete = personaId)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_CREATING_PERSONA -> {
                val isCreating = payload?.get("isCreating")?.jsonPrimitive?.booleanOrNull ?: return currentFeatureState
                return currentFeatureState.copy(isCreatingPersona = isCreating)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_PERSONA -> {
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
            ActionRegistry.Names.KNOWLEDGEGRAPH_SET_HOLON_TO_DELETE -> {
                val holonId = payload?.get("holonId")?.jsonPrimitive?.contentOrNull
                return currentFeatureState.copy(holonIdToDelete = holonId)
            }
            ActionRegistry.Names.KNOWLEDGEGRAPH_CONFIRM_DELETE_HOLON -> {
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

    private fun handleFilesContentForLoad(fileData: FilesContentPayload, store: Store) {
        val holonsById = mutableMapOf<String, Holon>()
        var hasErrors = false

        for ((path, rawContent) in fileData.contents) {
            try {
                val holon = createHolonFromString(rawContent, path, platformDependencies)
                holonsById[holon.header.id] = holon
            } catch (e: HolonValidationException) {
                platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to load holon at '$path': ${e.message}")
                hasErrors = true
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, identity.handle, "An unexpected error occurred parsing '$path'", e)
                hasErrors = true
            }
        }

        if (holonsById.isEmpty() && fileData.contents.isNotEmpty()) {
            val errorMsg = "Loading failed: All holon files in the persona were malformed or had ID mismatches."
            store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_LOAD_FAILED, buildJsonObject {
                put("error", errorMsg)
            }))
            return
        }

        if(hasErrors) {
            store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Warning: Some holon files failed to load. Check logs.") }))
        }


        val enrichedHolons = mutableMapOf<String, Holon>()
        val allSubHolonIds = holonsById.values.flatMap { it.header.subHolons }.map { it.id }.toSet()
        val rootHolons = holonsById.values.filter { it.header.id !in allSubHolonIds }


        fun enrichRecursively(holon: Holon, parentId: String?, depth: Int) {
            val enrichedHeader = holon.header.copy(parentId = parentId, depth = depth)
            var enrichedHolon = holon.copy(header = enrichedHeader)
            enrichedHolon = synchronizeRawContent(enrichedHolon)

            enrichedHolons[holon.header.id] = enrichedHolon
            enrichedHolon.header.subHolons.forEach { subRef ->
                holonsById[subRef.id]?.let { childHolon ->
                    enrichRecursively(childHolon, holon.header.id, depth + 1)
                }
            }
        }

        rootHolons.forEach { enrichRecursively(it, null, 0) }

        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.KNOWLEDGEGRAPH_PERSONA_LOADED, buildJsonObject {
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
            IconButton(onClick = { store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = "Knowledge Graph Manager",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}