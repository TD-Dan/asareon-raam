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
 * Creates a "canonical" copy of a Holon for comparison purposes.
 * This removes fields that should not be considered in a semantic equality check,
 * such as file paths, timestamps, and transient runtime data.
 */
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

/**
 * The master, idempotent analysis function for the import workflow.
 * [RE-ARCHITECTED] to perform a multi-pass analysis that validates, checks for integrity,
 * determines actions, and ensures final consistency.
 */
internal fun runImportAnalysis(
    fileContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    userOverrides: Map<String, ImportAction>,
    isRecursive: Boolean, // Note: This is now handled by the caller/UI, analyzer processes all given files.
    platformDependencies: PlatformDependencies
): JsonObject {
    val finalActions = mutableMapOf<String, ImportAction>()
    val sourceHolons = mutableMapOf<String, Holon>()

    // --- Pass 1: Validation & Deserialization using the Hardened Gateway ---
    for ((path, content) in fileContents) {
        try {
            // Use the hardened gateway for initial creation and validation.
            val holon = createHolonFromString(content, path, platformDependencies)
            sourceHolons[path] = holon
        } catch (e: HolonValidationException) {
            finalActions[path] = Quarantine("Validation Error: ${e.message}")
        }
    }

    // --- Pass 2: Data Integrity Checks (Duplicates & Cycles) ---
    // Check for duplicate IDs within the import batch.
    val idsToPaths = sourceHolons.entries.groupBy { it.value.header.id }
    for ((id, entries) in idsToPaths) {
        if (entries.size > 1) {
            val filePaths = entries.map { it.key }.joinToString()
            for (entry in entries) {
                finalActions[entry.key] = Quarantine("Duplicate ID '$id' found in other files: $filePaths")
            }
        }
    }

    // Check for circular dependencies.
    val parentMap = sourceHolons.values.flatMap { h -> h.header.subHolons.map { it.id to h.header.id } }.toMap()
    val visited = mutableSetOf<String>()
    val recursionStack = mutableSetOf<String>()
    val cycles = mutableSetOf<String>()

    fun detectCycle(holonId: String) {
        visited.add(holonId)
        recursionStack.add(holonId)
        parentMap[holonId]?.let { parentId ->
            if (parentId in recursionStack) {
                cycles.add(holonId)
                cycles.add(parentId)
            }
            if (parentId !in visited) {
                detectCycle(parentId)
            }
        }
        recursionStack.remove(holonId)
    }
    sourceHolons.values.forEach { detectCycle(it.header.id) }

    if (cycles.isNotEmpty()) {
        sourceHolons.forEach { (path, holon) ->
            if (holon.header.id in cycles) {
                finalActions[path] = Quarantine("Circular dependency detected involving holon '${holon.header.id}'.")
            }
        }
    }


    // --- Pass 3: Initial Action Determination (for valid holons) ---
    for ((path, sourceHolon) in sourceHolons) {
        // Skip if already quarantined by a previous pass.
        if (finalActions.containsKey(path)) continue
        // Respect user override if it exists.
        if (userOverrides.containsKey(path)) {
            finalActions[path] = userOverrides[path]!!
            continue
        }

        val holonId = sourceHolon.header.id
        when {
            kgState.holons.containsKey(holonId) -> {
                val existingHolon = kgState.holons[holonId]!!
                // [THE FIX] Use semantic comparison, not raw string comparison.
                if (existingHolon.toComparable() == sourceHolon.toComparable()) {
                    finalActions[path] = Ignore()
                } else {
                    finalActions[path] = Update(holonId)
                }
            }
            sourceHolon.header.type == "AI_Persona_Root" -> finalActions[path] = CreateRoot()
            parentMap.containsKey(holonId) -> finalActions[path] = Integrate(parentMap[holonId]!!)
            else -> finalActions[path] = Quarantine("Orphaned holon - parent not found in import set.")
        }
    }

    // --- Pass 4: Consistency Check (Cascading Quarantine) ---
    val finalConsistentActions = finalActions.toMutableMap()
    var changed: Boolean
    do {
        changed = false
        for ((path, action) in finalConsistentActions) {
            if (action is Integrate) {
                val parentHolonId = action.parentHolonId
                val parentSourcePath = sourceHolons.entries.find { it.value.header.id == parentHolonId }?.key
                val parentAction = parentSourcePath?.let { finalConsistentActions[it] }

                if (parentAction is Ignore || parentAction is Quarantine) {
                    finalConsistentActions[path] = Quarantine("Parent holon '${parentHolonId}' is not being imported.")
                    changed = true
                }
            }
        }
    } while (changed)


    val importItems = fileContents.keys.map { path ->
        val action = finalConsistentActions[path] ?: Quarantine("Analysis failed unexpectedly.")
        val availableActions = when(action) {
            is Update -> listOf(ImportActionType.UPDATE, ImportActionType.IGNORE)
            is Integrate -> listOf(ImportActionType.INTEGRATE, ImportActionType.ASSIGN_PARENT, ImportActionType.QUARANTINE, ImportActionType.IGNORE)
            is Quarantine -> listOf(ImportActionType.QUARANTINE, ImportActionType.ASSIGN_PARENT, ImportActionType.IGNORE)
            is CreateRoot -> listOf(ImportActionType.CREATE_ROOT, ImportActionType.IGNORE)
            is Ignore -> listOf(ImportActionType.IGNORE, ImportActionType.UPDATE) // Allow overriding an ignore
            else -> emptyList()
        }

        ImportItem(
            sourcePath = path,
            initialAction = action,
            targetPath = (action as? Update)?.let { kgState.holons[it.targetHolonId]?.header?.filePath },
            availableActions = availableActions
        )
    }

    return buildJsonObject {
        put("items", Json.encodeToJsonElement(importItems))
        put("selectedActions", Json.encodeToJsonElement(finalConsistentActions))
        put("contents", Json.encodeToJsonElement(fileContents))
    }
}


/**
 * [RE-ARCHITECTED] Executes the import plan as a transaction on structured Holon objects.
 * This function no longer manipulates JSON strings. It builds an in-memory representation
 * of all changes and serializes them only at the final step, ensuring data integrity.
 */
internal fun executeImportWrites(
    parentContents: Map<String, String>, // This parameter is obsolete.
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    // Transactional workspace for all holons being modified or created in this import.
    val holonsInTransaction = kgState.importSelectedActions.mapNotNull { (sourcePath, action) ->
        if (action is Quarantine || action is Ignore) return@mapNotNull null
        val content = kgState.importFileContents[sourcePath] ?: return@mapNotNull null
        try {
            // Use the hardened gateway here as well for a final safety check.
            val holon = createHolonFromString(content, sourcePath, platformDependencies)
            holon.header.id to holon
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
                    // This check prevents the "null/" string concatenation.
                    if (parentDir == null) null else "$parentDir/$holonId/$holonId.json"
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