package asareon.raam.feature.knowledgegraph

import asareon.raam.core.Action
import asareon.raam.core.Store
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

/**
 * Reduce a Holon to the fields that define its *semantic content*, so two holons
 * representing the same knowledge compare equal regardless of on-disk key order,
 * sub-holon list order, relationship list order, filesystem location, or timestamps.
 *
 * Without this, re-importing the same files reports "Content differs" forever,
 * because kotlinx.serialization preserves JSON object key order from the original
 * parse and data-class equality on Lists is order-sensitive.
 */
private fun Holon.toComparable(): Holon {
    return this.copy(
        rawContent = "",
        header = canonicalizeHeader(
            this.header.copy(
                filePath = "",
                parentId = null,
                depth = 0,
                createdAt = null,
                modifiedAt = null
            )
        ),
        payload = canonicalize(this.payload),
        execute = this.execute?.let { canonicalize(it) }
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
    // Build parent lookup from both the kgState and the incoming import set.
    // kgState is enumerated first so that when an id exists in both, the import-set
    // mapping wins (toMap keeps the last value for a given key) — the import-set is
    // the more recent truth about the parent-child relationship.
    // Without including kgState parents, re-imports of a subset (e.g. just the
    // dream-record children without re-selecting their dream-records folder) would
    // wrongly flag every child as "Orphaned: Parent not found in import set."
    val parentMap = (kgState.holons.values + sourceHolons.values)
        .flatMap { h -> h.header.subHolons.map { it.id to h.header.id } }
        .toMap()


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
            else -> proposedActions[path] = Quarantine("Orphaned: Parent not found in import set or existing graph.")
        }
    }

    var finalActions = proposedActions.toMutableMap()
    userOverrides.forEach { (path, action) ->
        finalActions[path] = action
    }

    // Cascade-demote children whose parent won't land — but only if the parent is
    // also absent from kgState. A parent that already exists in the graph is a valid
    // integration target even if its corresponding file in the import set is Ignored
    // (identical content) or Quarantined (malformed): the child will still link to
    // the kgState parent, and executeImportWrites rewrites the parent with the new
    // sub-holon ref. Previously this loop cascade-quarantined such children,
    // producing the "Parent holon 'X' is not being imported" flood in re-import
    // reports even when X was present in the graph.
    var changed: Boolean
    do {
        changed = false
        val actionsForConsistencyCheck = finalActions.toMutableMap()
        for ((path, action) in actionsForConsistencyCheck) {
            if (action is Integrate) {
                val parentHolonId = action.parentHolonId
                val parentSourcePath = sourceHolons.entries.find { it.value.header.id == parentHolonId }?.key
                val parentAction = parentSourcePath?.let { finalActions[it] }
                val parentExistsInKgState = kgState.holons.containsKey(parentHolonId)

                if (!parentExistsInKgState && (parentAction is Ignore || parentAction is Quarantine)) {
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

    // Build holonsInTransaction *and* a normalized-id → sourcePath index in one pass.
    // The index is authoritative because an id-repaired holon's `header.id` no longer
    // equals its on-disk filename — so matching by filename loses the action lookup
    // for every file whose ID had to be repaired (e.g. HHMM-without-seconds legacy
    // names), silently dropping those writes in Phase 1/2/3.
    val holonIdToSourcePath = mutableMapOf<String, String>()
    val holonsInTransaction = kgState.importSelectedActions.mapNotNull { (sourcePath, action) ->
        if (action is Quarantine || action is Ignore) return@mapNotNull null
        val content = kgState.importFileContents[sourcePath] ?: return@mapNotNull null
        try {
            val holon = createHolonFromString(content, sourcePath, platformDependencies)
            holonIdToSourcePath[holon.header.id] = sourcePath
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
        // Resolve this holon's import action via the normalized-id index. Filename
        // matching doesn't work here because id-repair may have rewritten the id
        // to no longer equal the on-disk filename.
        val sourcePath = holonIdToSourcePath[holonId]
        val action = sourcePath?.let { kgState.importSelectedActions[it] }

        // No import action, or the holon is in the import set as Ignore (meaning
        // "already in kgState with identical content" — no write needed, but the
        // path must still be resolvable because children may integrate under it).
        // Without the Ignore branch, any subtree whose ancestor resolves to Ignore
        // produces a null path, silently dropping every planned write beneath it.
        if (action == null || action is Ignore) {
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
    // Iterate over imported holons to link them to parents.
    // Snapshot the values before iterating — Phase 2 may add new parent entries
    // (existing kgState parents getting a new sub-holon ref) and iterating a live
    // MutableMap's values while mutating it produces undefined behavior.
    holonsInTransaction.values.toList().forEach { holon ->
        val sourcePath = holonIdToSourcePath[holon.header.id]
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
            store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
                put("path", finalPath); put("content", contentToWrite)
            }))
        }
    }


    store.dispatch("knowledgegraph", Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Import complete. Reloading Knowledge Graph...") }))
    store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) }))
    store.deferredDispatch("knowledgegraph", Action(ActionRegistry.Names.FILESYSTEM_LIST))
}