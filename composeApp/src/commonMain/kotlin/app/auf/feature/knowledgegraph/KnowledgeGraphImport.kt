package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionNames
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * The master, idempotent analysis function for the import workflow.
 * It performs a two-pass analysis to generate a complete and consistent import plan.
 */
internal fun runImportAnalysis(
    fileContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    userOverrides: Map<String, ImportAction>,
    isRecursive: Boolean,
    platformDependencies: PlatformDependencies
): JsonObject {
    // --- Pass 1: Initial Action Determination ---
    val sourceHolons = fileContents.mapNotNull { (path, content) ->
        try { path to json.decodeFromString<Holon>(content) } catch (e: Exception) { null }
    }.toMap()

    val sourceParentMap = sourceHolons.values.flatMap { holon ->
        holon.header.subHolons.map { child -> child.id to holon.header.id }
    }.toMap()

    val initialActions = fileContents.keys.associateWith { path ->
        // Respect user override if it exists
        if (userOverrides.containsKey(path)) return@associateWith userOverrides[path]!!

        val sourceHolon = sourceHolons[path]
        val holonId = platformDependencies.getFileName(path).removeSuffix(".json")

        when {
            sourceHolon == null -> Quarantine("Malformed JSON")
            kgState.holons.containsKey(holonId) -> Update(holonId)
            sourceHolon.header.type == "AI_Persona_Root" -> CreateRoot()
            sourceParentMap.containsKey(holonId) -> Integrate(sourceParentMap[holonId]!!)
            else -> Quarantine("Unknown top-level holon.")
        }
    }

    // --- Pass 2: Consistency Check (Cascading Demotion) ---
    val finalActions = initialActions.toMutableMap()
    for ((path, action) in initialActions) {
        if (action is Integrate) {
            val parentHolonId = action.parentHolonId
            val parentSourcePath = sourceHolons.entries.find { it.value.header.id == parentHolonId }?.key
            val parentAction = parentSourcePath?.let { finalActions[it] }

            if (parentAction is Ignore || parentAction is Quarantine) {
                finalActions[path] = Quarantine("Parent holon '${parentHolonId}' is not being imported.")
            }
        }
    }

    val importItems = fileContents.keys.map { path ->
        ImportItem(
            sourcePath = path,
            initialAction = finalActions[path]!!,
            targetPath = (finalActions[path] as? Update)?.let { kgState.holons[it.targetHolonId]?.header?.filePath }
        )
    }

    val filteredItems = if (isRecursive) {
        importItems
    } else {
        importItems.filter { !it.sourcePath.contains(platformDependencies.pathSeparator) }
    }

    return buildJsonObject {
        put("items", Json.encodeToJsonElement(filteredItems))
        put("selectedActions", Json.encodeToJsonElement(finalActions))
        put("contents", Json.encodeToJsonElement(fileContents))
    }
}

/**
 * [RE-ARCHITECTED] Executes the import plan as a transaction on structured Holon objects.
 * This function no longer manipulates JSON strings. It builds an in-memory representation
 * of all changes and serializes them only at the final step, ensuring data integrity.
 */
internal fun executeImportWrites(
    parentContents: Map<String, String>, // This parameter is now obsolete and will be ignored.
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    // Transactional workspace for all holons being modified or created in this import.
    val holonsInTransaction = kgState.importSelectedActions.mapNotNull { (sourcePath, action) ->
        if (action is Quarantine || action is Ignore) return@mapNotNull null
        val content = kgState.importFileContents[sourcePath] ?: return@mapNotNull null
        try {
            val holon = json.decodeFromString<Holon>(content)
            holon.header.id to holon.copy(rawContent = content)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, "ImportExecution", "Skipping malformed holon '$sourcePath' during execution.")
            null
        }
    }.toMap().toMutableMap()

    // --- PHASE 1: RECURSIVE PATH RESOLUTION ---
    val finalPaths = mutableMapOf<String, String>()

    fun determinePath(holonId: String): String? {
        // Memoization: If path is already calculated, return it.
        if (finalPaths.containsKey(holonId)) return finalPaths[holonId]

        val holon = holonsInTransaction[holonId] ?: return null
        val sourcePath = kgState.importSelectedActions.entries.find { platformDependencies.getFileName(it.key).removeSuffix(".json") == holonId }?.key
        val action = sourcePath?.let { kgState.importSelectedActions[it] }

        val path = when (action) {
            is CreateRoot -> "$holonId/$holonId.json"
            is Update -> kgState.holons[action.targetHolonId]?.header?.filePath
            is Integrate, is AssignParent -> {
                val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                if (parentId == null) return null // Should not happen with a valid plan

                // Recursively determine parent path first.
                val parentPath = kgState.holons[parentId]?.header?.filePath ?: determinePath(parentId)
                if (parentPath == null) null else {
                    val parentDir = platformDependencies.getParentDirectory(parentPath)
                    "$parentDir/$holonId/$holonId.json"
                }
            }
            else -> null
        }

        path?.let { finalPaths[holonId] = it }
        return path
    }

    // Trigger path resolution for all holons in the transaction.
    holonsInTransaction.keys.forEach { determinePath(it) }


    // --- PHASE 2: STRUCTURAL MODIFICATION ---
    holonsInTransaction.values.forEach { holon ->
        val sourcePath = kgState.importSelectedActions.entries.find { platformDependencies.getFileName(it.key).removeSuffix(".json") == holon.header.id }?.key
        val action = sourcePath?.let { kgState.importSelectedActions[it] }

        if (action is Integrate || action is AssignParent) {
            val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
            if (parentId != null) {
                // IMPORTANT: Look up the parent in the transaction map first to get the most up-to-date version.
                val parentHolon = holonsInTransaction[parentId] ?: kgState.holons[parentId]
                if (parentHolon != null) {
                    val childRef = SubHolonRef(holon.header.id, holon.header.type, holon.header.summary ?: "")
                    if (!parentHolon.header.subHolons.any { it.id == childRef.id }) {
                        val updatedSubHolons = parentHolon.header.subHolons + childRef
                        val updatedHeader = parentHolon.header.copy(subHolons = updatedSubHolons)
                        // Overwrite the parent in our transaction map with the updated version.
                        holonsInTransaction[parentId] = parentHolon.copy(header = updatedHeader)
                    }
                }
            }
        }
    }


    // --- PHASE 3: SERIALIZATION AND DISPATCH ---
    holonsInTransaction.forEach { (holonId, holon) ->
        val finalPath = finalPaths[holonId]
        if (finalPath != null) {
            val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())
            val headerWithMeta = holon.header.copy(
                filePath = finalPath,
                modifiedAt = newTimestamp,
                createdAt = holon.header.createdAt ?: newTimestamp
                // Parent ID and Depth will be enriched on the next full load.
            )
            val finalHolon = synchronizeRawContent(holon.copy(header = headerWithMeta))
            val contentToWrite = prepareHolonForWriting(finalHolon)
            store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                put("subpath", finalPath); put("content", contentToWrite)
            }))
        }
    }


    store.dispatch("ui.kgView", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Import complete. Reloading Knowledge Graph...") }))
    store.deferredDispatch("knowledgegraph", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) }))
    store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
}