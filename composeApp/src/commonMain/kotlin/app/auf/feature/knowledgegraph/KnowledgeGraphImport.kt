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
    val holonsInTransaction = mutableMapOf<String, Holon>()
    val remainingActions = kgState.importSelectedActions.toMutableMap()
    var processedInPass: Int

    // Initial Pass: Parse all source files into canonical Holon objects.
    remainingActions.keys.forEach { sourcePath ->
        val sourceContent = kgState.importFileContents[sourcePath]
        if (sourceContent != null) {
            try {
                val sourceHolon = json.decodeFromString<Holon>(sourceContent)
                holonsInTransaction[sourceHolon.header.id] = sourceHolon.copy(rawContent = sourceContent)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.WARN, "ImportExecution", "Skipping malformed holon '$sourcePath' during execution.")
            }
        }
    }


    // Multi-pass processing to resolve parent-child dependencies.
    do {
        processedInPass = 0
        val actionsThisPass = remainingActions.toMap()
        remainingActions.clear()

        for ((sourcePath, action) in actionsThisPass) {
            val sourceHolon = holonsInTransaction[platformDependencies.getFileName(sourcePath).removeSuffix(".json")]
            if (sourceHolon == null) {
                // Was malformed and skipped in the initial pass.
                if (action !is Quarantine) remainingActions[sourcePath] = action
                continue
            }

            var wasProcessed = true
            when (action) {
                is Integrate, is AssignParent -> {
                    val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                    if (parentId == null) {
                        wasProcessed = false; continue
                    }

                    // Find the parent either in the existing state or within this transaction.
                    val parentHolon = kgState.holons[parentId] ?: holonsInTransaction[parentId]

                    if (parentHolon == null) {
                        // Parent not yet processed in this transaction, defer to next pass.
                        wasProcessed = false
                    } else {
                        // Parent found, perform the structural modification.
                        val childRef = SubHolonRef(sourceHolon.header.id, sourceHolon.header.type, sourceHolon.header.summary ?: "")
                        if (!parentHolon.header.subHolons.any { it.id == childRef.id }) {
                            val updatedSubHolons = parentHolon.header.subHolons + childRef
                            val updatedHeader = parentHolon.header.copy(subHolons = updatedSubHolons)
                            val updatedParent = parentHolon.copy(header = updatedHeader)
                            // Overwrite the parent in our transaction map with the updated version.
                            holonsInTransaction[parentId] = updatedParent
                        }
                    }
                }
                // Other actions don't have dependencies, they are already processed by being in the map.
                else -> { /* No-op */ }
            }
            if (wasProcessed) processedInPass++ else remainingActions[sourcePath] = action
        }
    } while (processedInPass > 0 && remainingActions.isNotEmpty())

    // Final Pass: Determine paths, synchronize, serialize, and dispatch write actions.
    holonsInTransaction.forEach { (holonId, holon) ->
        val action = kgState.importSelectedActions.entries.find { platformDependencies.getFileName(it.key).removeSuffix(".json") == holonId }?.value

        val finalPath = when (action) {
            is CreateRoot -> "$holonId/$holonId.json"
            is Update -> kgState.holons[action.targetHolonId]?.header?.filePath
            is Integrate, is AssignParent -> {
                val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                val parent = kgState.holons[parentId] ?: holonsInTransaction[parentId]
                parent?.let { platformDependencies.getParentDirectory(it.header.filePath) + "/$holonId/$holonId.json" }
            }
            else -> null
        }

        if (finalPath != null) {
            val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())
            val headerWithMeta = holon.header.copy(
                filePath = finalPath,
                modifiedAt = newTimestamp,
                createdAt = holon.header.createdAt ?: newTimestamp
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