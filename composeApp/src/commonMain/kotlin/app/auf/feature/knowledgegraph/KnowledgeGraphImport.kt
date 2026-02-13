package app.auf.feature.knowledgegraph

import app.auf.core.Action
import app.auf.core.Store
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

private fun Holon.toComparable(): Holon {
    return this.copy(
        rawContent = "",
        header = this.header.copy(
            filePath = "",
            parentId = null,
            depth = 0,
            createdAt = null,
            modifiedAt = null
        )
    )
}

internal fun runImportAnalysis(
    fileContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    userOverrides: Map<String, ImportAction>,
    isRecursive: Boolean,
    platformDependencies: PlatformDependencies
): JsonObject {
    val proposedActions = mutableMapOf<String, ImportAction>()
    val sourceHolons = mutableMapOf<String, Holon>()

    for ((path, content) in fileContents) {
        try {
            val holon = createHolonFromString(content, path, platformDependencies)
            sourceHolons[path] = holon
        } catch (e: HolonValidationException) {
            proposedActions[path] = Quarantine("Validation Error: ${e.message}")
        }
    }

    val idsToPaths = sourceHolons.entries.groupBy { it.value.header.id }
    for ((id, entries) in idsToPaths) {
        if (entries.size > 1) {
            val filePaths = entries.map { platformDependencies.getFileName(it.key) }.joinToString()
            for (entry in entries) {
                proposedActions[entry.key] = Quarantine("Duplicate ID '$id' found in other files: $filePaths")
            }
        }
    }
    val parentMap = sourceHolons.values.flatMap { h -> h.header.subHolons.map { it.id to h.header.id } }.toMap()


    for ((path, sourceHolon) in sourceHolons) {
        if (proposedActions.containsKey(path)) continue

        val holonId = sourceHolon.header.id
        when {
            kgState.holons.containsKey(holonId) -> {
                val existingHolon = kgState.holons[holonId]!!
                if (existingHolon.toComparable() == sourceHolon.toComparable()) {
                    proposedActions[path] = Ignore("Content is identical")
                } else {
                    proposedActions[path] = Update(holonId)
                }
            }
            sourceHolon.header.type == "AI_Persona_Root" -> proposedActions[path] = CreateRoot()
            parentMap.containsKey(holonId) -> proposedActions[path] = Integrate(parentMap[holonId]!!)
            else -> proposedActions[path] = Quarantine("Orphaned: Parent not found in import set.")
        }
    }

    var finalActions = proposedActions.toMutableMap()
    userOverrides.forEach { (path, action) ->
        finalActions[path] = action
    }

    var changed: Boolean
    do {
        changed = false
        val actionsForConsistencyCheck = finalActions.toMutableMap()
        for ((path, action) in actionsForConsistencyCheck) {
            if (action is Integrate) {
                val parentHolonId = action.parentHolonId
                val parentSourcePath = sourceHolons.entries.find { it.value.header.id == parentHolonId }?.key
                val parentAction = parentSourcePath?.let { finalActions[it] }

                if (parentAction is Ignore || parentAction is Quarantine) {
                    if (finalActions[path] !is Quarantine) {
                        finalActions[path] = Quarantine("Parent holon '$parentHolonId' is not being imported.")
                        changed = true
                    }
                }
            }
        }
    } while (changed)


    val importItems = fileContents.keys.sorted().map { path ->
        val proposedAction = proposedActions[path] ?: Quarantine("Analysis failed.")
        val selectedAction = finalActions[path] ?: proposedAction

        val statusReason = when {
            userOverrides.containsKey(path) -> "USER: ${selectedAction.summary}"
            selectedAction is Quarantine -> selectedAction.reason
            selectedAction is Ignore -> selectedAction.reason
            selectedAction is Update -> "Content differs from existing"
            selectedAction is Integrate -> "New holon with known parent"
            else -> null
        }

        val availableActions = when(proposedAction) {
            is Update -> listOf(ImportActionType.UPDATE, ImportActionType.IGNORE)
            is Integrate -> listOf(ImportActionType.INTEGRATE, ImportActionType.ASSIGN_PARENT, ImportActionType.QUARANTINE, ImportActionType.IGNORE)
            is Quarantine -> listOf(ImportActionType.QUARANTINE, ImportActionType.ASSIGN_PARENT, ImportActionType.IGNORE)
            is CreateRoot -> listOf(ImportActionType.CREATE_ROOT, ImportActionType.IGNORE)
            is Ignore -> listOf(ImportActionType.IGNORE, ImportActionType.UPDATE)
            else -> emptyList()
        }

        ImportItem(
            sourcePath = path,
            proposedAction = proposedAction,
            targetPath = (selectedAction as? Update)?.let { kgState.holons[it.targetHolonId]?.header?.filePath },
            statusReason = statusReason,
            availableActions = availableActions
        )
    }

    return buildJsonObject {
        put("items", Json.encodeToJsonElement(importItems))
        put("selectedActions", Json.encodeToJsonElement(finalActions))
        put("contents", Json.encodeToJsonElement(fileContents))
    }
}


internal fun executeImportWrites(
    parentContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    // [FIX] Pre-process parent contents into Holon objects for the transaction.
    // This allows us to work with the fresh state of parents read from disk.
    val loadedParents = parentContents.mapNotNull { (path, content) ->
        try {
            val holon = createHolonFromString(content, path, platformDependencies)
            holon.header.id to holon
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, "ImportExecution", "Failed to parse parent content for '$path': ${e.message}")
            null
        }
    }.toMap()

    val holonsInTransaction = kgState.importSelectedActions.mapNotNull { (sourcePath, action) ->
        if (action is Quarantine || action is Ignore) return@mapNotNull null
        val content = kgState.importFileContents[sourcePath] ?: return@mapNotNull null
        try {
            val holon = createHolonFromString(content, sourcePath, platformDependencies)
            holon.header.id to holon
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, "ImportExecution", "Skipping malformed holon '$sourcePath' during execution.")
            null
        }
    }.toMap().toMutableMap()

    // [FIX] Merge loaded parents into the transaction context.
    // We prioritize the `holonsInTransaction` (imports) but fallback to `loadedParents`.
    // We do NOT modify `kgState.holons` directly here; we work on a transient set.
    val transactionContext = (loadedParents + holonsInTransaction).toMutableMap()


    // --- PHASE 1: RECURSIVE PATH RESOLUTION ---
    val finalPaths = mutableMapOf<String, String>()

    fun determinePath(holonId: String): String? {
        if (finalPaths.containsKey(holonId)) return finalPaths[holonId]

        val holon = transactionContext[holonId] ?: kgState.holons[holonId] ?: return null
        // Check if this holon is being imported to find its action
        val sourcePath = kgState.importSelectedActions.entries.find { platformDependencies.getFileName(it.key).removeSuffix(".json") == holonId }?.key
        val action = sourcePath?.let { kgState.importSelectedActions[it] }

        // If no import action, it's an existing holon. Use its existing path.
        if (action == null) {
            val path = kgState.holons[holonId]?.header?.filePath ?: loadedParents[holonId]?.header?.filePath
            path?.let { finalPaths[holonId] = it }
            return path
        }

        val path = when (action) {
            is CreateRoot -> "$holonId/$holonId.json"
            is Update -> kgState.holons[action.targetHolonId]?.header?.filePath
            is Integrate, is AssignParent -> {
                val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                if (parentId == null) return null

                val parentPath = determinePath(parentId)
                if (parentPath == null) null else {
                    val parentDir = platformDependencies.getParentDirectory(parentPath)
                    if (parentDir == null) null else "$parentDir/$holonId/$holonId.json"
                }
            }
            else -> null
        }

        path?.let { finalPaths[holonId] = it }
        return path
    }

    holonsInTransaction.keys.forEach { determinePath(it) }


    // --- PHASE 2: STRUCTURAL MODIFICATION ---
    // Iterate over imported holons to link them to parents
    holonsInTransaction.values.forEach { holon ->
        val sourcePath = kgState.importSelectedActions.entries.find { platformDependencies.getFileName(it.key).removeSuffix(".json") == holon.header.id }?.key
        val action = sourcePath?.let { kgState.importSelectedActions[it] }

        if (action is Integrate || action is AssignParent) {
            val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
            if (parentId != null) {
                // [FIX] Use transactionContext to find and modify the parent.
                val parentHolon = transactionContext[parentId] ?: kgState.holons[parentId]
                if (parentHolon != null) {
                    val childRef = SubHolonRef(holon.header.id, holon.header.type, holon.header.summary ?: "")
                    if (!parentHolon.header.subHolons.any { it.id == childRef.id }) {
                        val updatedSubHolons = parentHolon.header.subHolons + childRef
                        val updatedHeader = parentHolon.header.copy(subHolons = updatedSubHolons)

                        // Update the context. If the parent was an existing holon not in transaction, it is now effectively in transaction.
                        // We also add it to `holonsInTransaction` so it gets written out in Phase 3.
                        val updatedParent = parentHolon.copy(header = updatedHeader)
                        transactionContext[parentId] = updatedParent
                        holonsInTransaction[parentId] = updatedParent
                    }
                }
            }
        }
    }


    // --- PHASE 3: SERIALIZATION AND DISPATCH ---
    holonsInTransaction.forEach { (holonId, holon) ->
        // Recalculate path one last time to be sure, or use cache
        val finalPath = finalPaths[holonId] ?: determinePath(holonId)

        if (finalPath != null) {
            val newTimestamp = platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())
            val headerWithMeta = holon.header.copy(
                filePath = finalPath,
                modifiedAt = newTimestamp,
                createdAt = holon.header.createdAt ?: newTimestamp
            )
            val finalHolon = synchronizeRawContent(holon.copy(header = headerWithMeta))
            val contentToWrite = prepareHolonForWriting(finalHolon)
            store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                put("subpath", finalPath); put("content", contentToWrite)
            }))
        }
    }


    store.dispatch("ui.kgView", Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Import complete. Reloading Knowledge Graph...") }))
    store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) }))
    store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST))
}