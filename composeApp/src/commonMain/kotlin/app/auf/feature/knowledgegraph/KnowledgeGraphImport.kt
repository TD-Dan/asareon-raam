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
 * Analyzes a map of file contents to determine the appropriate import action for each.
 *
 * This function is now hardened with explicit error logging for malformed JSON files.
 */
internal fun runImportAnalysis(
    fileContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    isRecursive: Boolean,
    platformDependencies: PlatformDependencies
): JsonObject {
    val sourceHolons = fileContents.mapNotNull { (path, content) ->
        try {
            path to json.decodeFromString<Holon>(content)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, "ImportAnalysis", "Malformed JSON in source file '$path', quarantining.", e)
            null
        }
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

/**
 * Executes the multi-pass, dependency-aware import plan.
 *
 * [REFACTOR] This function is now hardened with:
 * 1. Correct multi-pass logic that re-queues unprocessed holons.
 * 2. Explicit error logging for any file that fails to parse during execution.
 * 3. A robust mechanism for finding parent content that correctly handles parents
 *    that are new within the same import batch.
 * 4. Logic is simplified by delegating holon modification to `addSubHolonRefToContent`.
 */
internal fun executeImportWrites(
    parentContents: Map<String, String>,
    kgState: KnowledgeGraphState,
    store: Store,
    platformDependencies: PlatformDependencies
) {
    val updatedParentContents = parentContents.toMutableMap()
    val processedHolonPaths = mutableMapOf<String, String>() // Maps holonId -> destination subpath
    val importHolonIdToSourcePath = kgState.importFileContents.mapNotNull { (path, content) ->
        try { json.decodeFromString<Holon>(content).header.id to path } catch (e: Exception) { null }
    }.toMap()


    val remainingActions = kgState.importSelectedActions.toMutableMap()
    var processedInPass: Int
    do {
        processedInPass = 0
        val actionsThisPass = remainingActions.toMap()
        remainingActions.clear()

        for ((sourcePath, action) in actionsThisPass) {
            var wasProcessed = true
            val sourceContent = kgState.importFileContents[sourcePath] ?: continue
            val sourceHolon = try {
                json.decodeFromString<Holon>(sourceContent)
            } catch (e: Exception) {
                platformDependencies.log(LogLevel.ERROR, "ImportExecution", "Failed to parse source holon '$sourcePath' during execution, skipping.", e)
                continue
            }
            val holonId = sourceHolon.header.id

            when (action) {
                is CreateRoot -> {
                    val destSubpath = "$holonId/$holonId.json"
                    store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                        put("subpath", destSubpath); put("content", sourceContent)
                    }))
                    processedHolonPaths[holonId] = destSubpath
                }
                is Update -> {
                    val destSubpath = kgState.holons[action.targetHolonId]?.header?.filePath ?: continue
                    store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                        put("subpath", destSubpath); put("content", sourceContent)
                    }))
                }
                is Integrate, is AssignParent -> {
                    val parentId = if (action is Integrate) action.parentHolonId else (action as AssignParent).assignedParentId
                    if (parentId == null) {
                        wasProcessed = false; remainingActions[sourcePath] = action; continue
                    }

                    val parentSubpath = kgState.holons[parentId]?.header?.filePath ?: processedHolonPaths[parentId]
                    if (parentSubpath == null) {
                        wasProcessed = false; remainingActions[sourcePath] = action; continue
                    }

                    val parentDir = platformDependencies.getParentDirectory(parentSubpath)
                    if (parentDir == null) {
                        wasProcessed = false; remainingActions[sourcePath] = action; continue
                    }

                    val destSubpath = "$parentDir/$holonId/$holonId.json"
                    store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                        put("subpath", destSubpath); put("content", sourceContent)
                    }))
                    processedHolonPaths[holonId] = destSubpath

                    // [THE FIX] Robustly find parent content from any valid source.
                    val parentContentStr = updatedParentContents[parentSubpath]
                        ?: parentContents[parentSubpath]
                        ?: kgState.importFileContents[importHolonIdToSourcePath[parentId]]

                    if (parentContentStr != null) {
                        val newSubRef = SubHolonRef(holonId, sourceHolon.header.type, sourceHolon.header.summary ?: "")
                        val updatedContent = addSubHolonRefToContent(parentContentStr, newSubRef)
                        if (updatedContent != parentContentStr) {
                            updatedParentContents[parentSubpath] = updatedContent
                        }
                    }
                }
                is Quarantine -> { /* No-op */ }
                is Ignore -> { /* No-op */ }
            }
            if (wasProcessed) processedInPass++ else remainingActions[sourcePath] = action
        }
    } while (processedInPass > 0 && remainingActions.isNotEmpty())

    updatedParentContents.forEach { (subpath, content) ->
        if (parentContents[subpath] != content) {
            store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
                put("subpath", subpath); put("content", content)
            }))
        }
    }
    store.dispatch("ui.kgView", Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject { put("message", "Import complete. Reloading Knowledge Graph...") }))
    store.deferredDispatch("knowledgegraph", Action(ActionNames.KNOWLEDGEGRAPH_SET_VIEW_MODE, buildJsonObject { put("mode", KnowledgeGraphViewMode.INSPECTOR.name) }))
    store.deferredDispatch("knowledgegraph", Action(ActionNames.FILESYSTEM_SYSTEM_LIST))
}