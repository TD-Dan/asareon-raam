package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.BasePath
import app.auf.util.FileEntry
import app.auf.util.LogLevel
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class FileSystemFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "filesystem", localHandle = "filesystem", name="File System")
    override val composableProvider: Feature.ComposableProvider = FileSystemComposableProvider()

    private val json = Json { ignoreUnknownKeys = true }
    private val cryptoManager = CryptoManager(platformDependencies)

    @Serializable private data class PathPayload(val path: String)
    @Serializable private data class ToggleItemPayload(val path: String, val recursive: Boolean = false)
    @Serializable private data class DirectoryLoadedPayload(val parentPath: String, val children: List<FileEntry>)
    @Serializable private data class SystemListPayload(val path: String = "", val recursive: Boolean = false, val correlationId: String? = null)
    @Serializable private data class ReadFilesContentPayload(val paths: List<String>)
    @Serializable private data class SystemReadPayload(val path: String, val correlationId: String? = null)
    @Serializable private data class SystemWritePayload(val path: String, val content: String, val encrypt: Boolean = false, val correlationId: String? = null)
    @Serializable private data class SystemDeletePayload(val path: String, val correlationId: String? = null)
    @Serializable private data class SystemDeleteDirectoryPayload(val path: String, val correlationId: String? = null)
    @Serializable private data class OpenAppSubfolderPayload(val path: String)
    @Serializable data class RequestScopedReadUiPayload(val correlationId: String? = null, val recursive: Boolean = true, val fileExtensions: List<String>? = null)
    @Serializable private data class StageScopedReadPayload(val requestId: String, val originator: String, val requestPayload: JsonObject)
    @Serializable private data class ExecuteScopedReadPayload(val clientOriginator: String, val requestPayload: JsonObject)
    @Serializable private data class ConfirmationResponsePayload(val requestId: String, val confirmed: Boolean)


    private val settingKeyWhitelist = "filesystem.whitelistedPaths"
    private val settingKeyFavorites = "filesystem.favoritePaths"

    private fun getSandboxPathFor(originator: String): String {
        val appZoneRoot = platformDependencies.getBasePathFor(BasePath.APP_ZONE)
        val safeOriginator = originator.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$appZoneRoot${platformDependencies.pathSeparator}$safeOriginator"
    }

    private fun filenameGuard(path: String, originator: String, operation: String): Boolean {
        if (path.isBlank()) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "Refused to $operation a blank filename for originator '$originator'.")
            return false
        }
        if (path.contains("..")) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "SECURITY: Refused path with directory traversal characters ('..') for originator '$originator' in operation '$operation': '$path'")
            return false
        }
        val fileName = path.substringAfterLast(platformDependencies.pathSeparator)
        if (!fileName.contains('.')) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "Refused filename without a file extension for originator '$originator' in operation '$operation': '$path'")
            return false
        }
        return true
    }

    private fun filepathGuard(path: String, originator: String, operation: String): Boolean {
        if (path.contains("..")) {
            platformDependencies.log(LogLevel.ERROR, identity.handle, "SECURITY: Refused path with directory traversal characters ('..') for originator '$originator' in operation '$operation': '$path'")
            return false
        }
        return true
    }

    /**
     * Publishes a lightweight, privacy-safe broadcast notification after completing
     * a command-dispatchable action. Summaries MUST NOT include sandbox-internal paths.
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
            name = ActionRegistry.Names.FILESYSTEM_ACTION_RESULT,
            payload = buildJsonObject {
                correlationId?.let { put("correlationId", it) }
                put("requestAction", requestAction)
                put("success", success)
                summary?.let { put("summary", it) }
                error?.let { put("error", it) }
            }
        ))
    }


    /**
     * Strips absolute sandbox paths from exception messages before they are
     * included in broadcast ACTION_RESULT payloads. Exception messages from
     * file I/O routinely embed the full absolute path (e.g.
     * "C:\Users\...\agent\uuid\workspace\file.txt (not found)").
     * Broadcasting that leaks the sandbox structure to every feature on the bus.
     *
     * This replaces any occurrence of the APP_ZONE root with a generic placeholder
     * so the user still gets a useful error without exposing internal paths.
     */
    private fun sanitizeErrorForBroadcast(message: String?): String {
        if (message == null) return "Unknown error"
        val appZoneRoot = platformDependencies.getBasePathFor(BasePath.APP_ZONE)
        // Replace both forward-slash and backslash variants of the root path
        return message
            .replace(appZoneRoot, "<sandbox>")
            .replace(appZoneRoot.replace("\\", "/"), "<sandbox>")
            .replace(appZoneRoot.replace("/", "\\"), "<sandbox>")
    }

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val originator = action.originator ?: return
        val fileSystemState = newState as? FileSystemState ?: return
        when (action.name) {
            // Phase 3: Targeted response from CoreFeature — confirmation dialog result.
            // Migrated from onPrivateData.
            ActionRegistry.Names.CORE_RETURN_CONFIRMATION -> {
                val confirmPayload = json.decodeFromJsonElement<ConfirmationResponsePayload>(action.payload ?: return)
                val pendingRequest = fileSystemState.pendingScopedRead
                if (pendingRequest?.requestId == confirmPayload.requestId) {
                    if (confirmPayload.confirmed) {
                        store.deferredDispatch(
                            originator = identity.handle,
                            action = Action(
                                name = ActionRegistry.Names.FILESYSTEM_EXECUTE_SCOPED_READ,
                                payload = buildJsonObject {
                                    put("clientOriginator", pendingRequest.originator)
                                    put("requestPayload", Json.encodeToJsonElement(pendingRequest.payload))
                                }
                            )
                        )
                    }
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_FINALIZE_SCOPED_READ))
                }
            }
            ActionRegistry.Names.SYSTEM_INITIALIZING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyWhitelist); put("type", "STRING_SET"); put("label", "Whitelisted Paths")
                    put("description", "Whitelisted directory paths that the app is allowed to edit.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", settingKeyFavorites); put("type", "STRING_SET"); put("label", "Favorite Paths")
                    put("description", "A list of favorite directory paths.")
                    put("section", "FileSystem"); put("defaultValue", "")
                }))
            }
            ActionRegistry.Names.SYSTEM_STARTING -> {
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", platformDependencies.getUserHomePath()) }))
            }
            ActionRegistry.Names.FILESYSTEM_NAVIGATE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) } ?: return
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", payload.path) }))
            }
            ActionRegistry.Names.FILESYSTEM_SELECT_DIRECTORY_UI -> {
                platformDependencies.selectDirectoryPath()?.let {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_NAVIGATE, buildJsonObject { put("path", it) }))
                }
            }
            ActionRegistry.Names.FILESYSTEM_REQUEST_SCOPED_READ_UI -> {
                val requestId = platformDependencies.generateUUID()
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.FILESYSTEM_STAGE_SCOPED_READ,
                    payload = buildJsonObject {
                        put("requestId", requestId)
                        put("originator", originator)
                        put("requestPayload", action.payload ?: buildJsonObject {})
                    }
                ))
            }
            ActionRegistry.Names.FILESYSTEM_STAGE_SCOPED_READ -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<StageScopedReadPayload>(it) } ?: return
                val dialogRequest = buildJsonObject {
                    put("title", "Danger Zone: Grant File Access?")
                    put("text", "You are about to grant the '${payload.originator}' feature one-time read access to a folder and its entire file content.\n\nDo not expose folders with sensitive content.")
                    put("confirmButtonText", "Proceed with Care")
                    put("requestId", payload.requestId)
                }
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG, dialogRequest))
            }
            ActionRegistry.Names.FILESYSTEM_EXECUTE_SCOPED_READ -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ExecuteScopedReadPayload>(it) } ?: return
                val clientOriginator = payload.clientOriginator
                val requestPayload = json.decodeFromJsonElement<RequestScopedReadUiPayload>(payload.requestPayload)

                platformDependencies.selectDirectoryPath()?.let { selectedPath ->
                    platformDependencies.log(LogLevel.INFO, identity.handle, "User granted one-time access to '$selectedPath' for '$clientOriginator'.")
                    try {
                        // 1. List files recursively
                        val allFiles = if (requestPayload.recursive) {
                            platformDependencies.listDirectoryRecursive(selectedPath)
                        } else {
                            platformDependencies.listDirectory(selectedPath).filterNot { it.isDirectory }
                        }

                        // 2. Filter by extension
                        val filteredFiles = requestPayload.fileExtensions?.let { extensions ->
                            allFiles.filter { fileEntry -> extensions.any { ext -> fileEntry.path.endsWith(".$ext", ignoreCase = true) } }
                        } ?: allFiles

                        // 3. Enforce security limit
                        if (filteredFiles.size > 1000) {
                            val errorMsg = "Import failed: Directory contains more than 1000 files (${filteredFiles.size}). Please select a smaller directory."
                            platformDependencies.log(LogLevel.ERROR, identity.handle, errorMsg)
                            store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
                            return // Abort the operation
                        }

                        // 4. Read content and build the relative-path map
                        val contentMap = mutableMapOf<String, String>()
                        val rootPathWithSeparator = if (selectedPath.endsWith(platformDependencies.pathSeparator)) selectedPath else "$selectedPath${platformDependencies.pathSeparator}"
                        filteredFiles.forEach { fileEntry ->
                            try {
                                val fileContent = platformDependencies.readFileContent(fileEntry.path)
                                val relativePath = fileEntry.path.removePrefix(rootPathWithSeparator)
                                contentMap[relativePath] = fileContent
                            } catch (e: Exception) {
                                platformDependencies.log(LogLevel.WARN, identity.handle, "Scoped read failed for one file '${fileEntry.path}': ${e.message}", e)
                            }
                        }

                        // 5. Deliver the complete payload
                        val responsePayload = buildJsonObject {
                            put("correlationId", requestPayload.correlationId)
                            put("contents", Json.encodeToJsonElement(contentMap))
                        }
                        store.deferredDispatch(identity.handle, Action(

                            name = ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT,

                            payload = responsePayload,

                            targetRecipient = clientOriginator

                        ))

                    } catch (e: Exception) {
                        val errorMsg = "Scoped read failed for '$selectedPath': ${e.message}"
                        platformDependencies.log(LogLevel.ERROR, identity.handle, errorMsg, e)
                        store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", errorMsg) }))
                    }
                } ?: platformDependencies.log(LogLevel.INFO, identity.handle, "User cancelled one-time access grant for '$clientOriginator'.")
            }
            ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_EXPANDED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) } ?: return
                val item = findItemByPath(fileSystemState.rootItems, payload.path)
                if (item?.isDirectory == true && item.children == null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN, action.payload))
                }
            }
            ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return
                if (payload.recursive) {
                    findItemByPath(fileSystemState.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
                }
            }
            ActionRegistry.Names.FILESYSTEM_EXPAND_ALL -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) } ?: return
                findItemByPath(fileSystemState.rootItems, payload.path)?.let { dispatchLoadChildrenRecursive(it, 3, store) }
            }
            ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) } ?: return
                try {
                    val children = platformDependencies.listDirectory(payload.path)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED, buildJsonObject {
                        put("parentPath", payload.path); put("children", Json.encodeToJsonElement(children))
                    }))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to read directory ${payload.path}", e)
                    store.dispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Failed to read directory: ${e.message}") }))
                }
            }
            ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return
                if (findItemByPath(fileSystemState.rootItems, payload.parentPath)?.isSelected == true) {
                    payload.children.forEach { child ->
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED, buildJsonObject {
                            put("path", child.path); put("recursive", true)
                        }))
                    }
                }
            }
            ActionRegistry.Names.FILESYSTEM_COPY_SELECTION_TO_CLIPBOARD -> {
                val selectedFiles = findSelectedFiles(fileSystemState.rootItems)
                if (selectedFiles.isEmpty()) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "No files selected.") })); return
                }
                val stringBuilder = StringBuilder()
                val rootPath = fileSystemState.currentPath ?: ""
                var errorCount = 0
                selectedFiles.forEach { item ->
                    try {
                        val content = platformDependencies.readFileContent(item.path)
                        val relativePath = item.path.removePrefix(rootPath).removePrefix(platformDependencies.pathSeparator.toString())
                        stringBuilder.append("```${relativePath.substringAfterLast('.', "")} \"$relativePath\"\n$content\n```\n\n")
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "Could not read file for clipboard copy: ${item.path}: ${e.message}", e)
                        errorCount++
                    }
                }
                if (stringBuilder.isNotEmpty()) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_COPY_TO_CLIPBOARD, buildJsonObject { put("text", stringBuilder.toString().trim()) }))
                }
                if (errorCount > 0) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Failed to read $errorCount files.") }))
                }
            }
            ActionRegistry.Names.FILESYSTEM_ADD_WHITELIST_PATH, ActionRegistry.Names.FILESYSTEM_REMOVE_WHITELIST_PATH -> {
                val path = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) fileSystemState.whitelistedPaths + path else fileSystemState.whitelistedPaths - path
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject { put("key", settingKeyWhitelist); put("value", serializeSet(newSet)) }))
            }
            ActionRegistry.Names.FILESYSTEM_ADD_FAVORITE_PATH, ActionRegistry.Names.FILESYSTEM_REMOVE_FAVORITE_PATH -> {
                val path = action.payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return
                val newSet = if (action.name.startsWith("filesystem.ADD")) fileSystemState.favoritePaths + path else fileSystemState.favoritePaths - path
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_UPDATE, buildJsonObject { put("key", settingKeyFavorites); put("value", serializeSet(newSet)) }))
            }
            ActionRegistry.Names.FILESYSTEM_LIST -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SystemListPayload>(it) } ?: SystemListPayload()
                if (!filepathGuard(payload.path, originator, "list directory")) return

                val sandboxPath = getSandboxPathFor(originator)
                val fullPath = if (payload.path.isNotBlank()) "$sandboxPath${platformDependencies.pathSeparator}${payload.path}" else sandboxPath
                try {
                    if (!platformDependencies.fileExists(fullPath)) platformDependencies.createDirectories(fullPath)
                    val absoluteListing = if (payload.recursive) platformDependencies.listDirectoryRecursive(fullPath) else platformDependencies.listDirectory(fullPath)
                    val relativeListing = absoluteListing
                        .filter { it.path != fullPath }
                        .map { it.copy(path = it.path.removePrefix(sandboxPath).removePrefix(platformDependencies.pathSeparator.toString())) }
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(relativeListing))
                        put("path", payload.path)
                        payload.correlationId?.let { put("correlationId", it) }
                    }
                    store.deferredDispatch(identity.handle, Action(

                        name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,

                        payload = responsePayload,

                        targetRecipient = originator

                    ))
                    publishActionResult(store, payload.correlationId, action.name, success = true,
                        summary = "Listed ${relativeListing.size} items")
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, "filesystem","Filesystem listing failed: ${e.message}", e)
                    val responsePayload = buildJsonObject {
                        put("listing", Json.encodeToJsonElement(emptyList<FileEntry>()))
                        put("path", payload.path)
                        payload.correlationId?.let { put("correlationId", it) }
                    }
                    store.deferredDispatch(identity.handle, Action(

                        name = ActionRegistry.Names.FILESYSTEM_RETURN_LIST,

                        payload = responsePayload,

                        targetRecipient = originator

                    ))
                    publishActionResult(store, payload.correlationId, action.name, success = false,
                        error = "Listing failed: ${sanitizeErrorForBroadcast(e.message)}")
                }
            }
            ActionRegistry.Names.FILESYSTEM_READ_FILES_CONTENT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ReadFilesContentPayload>(it) } ?: return
                val sandboxPath = getSandboxPathFor(originator)
                val contentMap = mutableMapOf<String, String>()
                payload.paths.forEach { path ->
                    if (!filenameGuard(path, originator, "bulk read")) return@forEach
                    val fullPath = "$sandboxPath${platformDependencies.pathSeparator}$path"
                    try {
                        contentMap[path] = platformDependencies.readFileContent(fullPath)
                    } catch (e: Exception) {
                        platformDependencies.log(LogLevel.WARN, "filesystem", "Bulk read failed for one file '$path': ${e.message}", e)
                    }
                }
                val responsePayload = buildJsonObject {
                    put("correlationId", JsonNull)
                    put("contents", Json.encodeToJsonElement(contentMap))
                }
                store.deferredDispatch(identity.handle, Action(

                    name = ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT,

                    payload = responsePayload,

                    targetRecipient = originator

                ))
            }
            ActionRegistry.Names.FILESYSTEM_SYSTEM_READ -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SystemReadPayload>(it) } ?: return
                if (!filenameGuard(payload.path, originator, "read")) return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.path}"
                try {
                    val content = cryptoManager.decrypt(platformDependencies.readFileContent(fullPath),true)
                    val responsePayload = buildJsonObject {
                        put("path", payload.path)
                        put("content", content)
                        payload.correlationId?.let { put("correlationId", it) }
                    }
                    store.deferredDispatch(identity.handle, Action(

                        name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,

                        payload = responsePayload,

                        targetRecipient = originator

                    ))
                    publishActionResult(store, payload.correlationId, action.name, success = true,
                        summary = "Read 1 file (${content.length} bytes)")
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "System read failed for '${payload.path}'", e)
                    val responsePayload = buildJsonObject {
                        put("path", payload.path)
                        put("content", JsonNull)
                        payload.correlationId?.let { put("correlationId", it) }
                    }
                    store.deferredDispatch(identity.handle, Action(

                        name = ActionRegistry.Names.FILESYSTEM_RETURN_READ,

                        payload = responsePayload,

                        targetRecipient = originator

                    ))
                    publishActionResult(store, payload.correlationId, action.name, success = false,
                        error = "Read failed: ${sanitizeErrorForBroadcast(e.message)}")
                }
            }
            ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SystemWritePayload>(it) } ?: return
                if (!filenameGuard(payload.path, originator, "write")) return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.path}"
                try {
                    platformDependencies.writeFileContent(fullPath, if (payload.encrypt) cryptoManager.encrypt(payload.content) else payload.content)
                    publishActionResult(store, payload.correlationId, action.name, success = true,
                        summary = "Wrote 1 file (${payload.content.length} bytes)")
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Error writing system file", e)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject { put("message", "Error writing system file: ${e.message}") }))
                    publishActionResult(store, payload.correlationId, action.name, success = false,
                        error = "Write failed: ${sanitizeErrorForBroadcast(e.message)}")
                }
            }
            ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SystemDeletePayload>(it) } ?: return
                if (!filenameGuard(payload.path, originator, "delete")) return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.path}"
                try {
                    if (platformDependencies.fileExists(fullPath)) {
                        platformDependencies.deleteFile(fullPath)
                        publishActionResult(store, payload.correlationId, action.name, success = true,
                            summary = "Deleted 1 file")
                    } else {
                        publishActionResult(store, payload.correlationId, action.name, success = false,
                            error = "File not found")
                    }
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Error deleting file '${payload.path}'", e)
                    publishActionResult(store, payload.correlationId, action.name, success = false,
                        error = "Delete failed: ${sanitizeErrorForBroadcast(e.message)}")
                }
            }
            ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE_DIRECTORY -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<SystemDeleteDirectoryPayload>(it) } ?: return
                if (!filepathGuard(payload.path, originator, "delete directory")) return
                val fullPath = "${getSandboxPathFor(originator)}${platformDependencies.pathSeparator}${payload.path}"
                try {
                    if (platformDependencies.fileExists(fullPath)) {
                        platformDependencies.deleteDirectory(fullPath)
                        publishActionResult(store, payload.correlationId, action.name, success = true,
                            summary = "Deleted directory")
                    } else {
                        publishActionResult(store, payload.correlationId, action.name, success = false,
                            error = "Directory not found")
                    }
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Error deleting directory '${payload.path}'", e)
                    publishActionResult(store, payload.correlationId, action.name, success = false,
                        error = "Delete directory failed: ${sanitizeErrorForBroadcast(e.message)}")
                }
            }
            ActionRegistry.Names.FILESYSTEM_OPEN_WORKSPACE_FOLDER -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<OpenAppSubfolderPayload>(it) } ?: return
                platformDependencies.openFolderInExplorer("${platformDependencies.getBasePathFor(BasePath.APP_ZONE)}${platformDependencies.pathSeparator}${payload.path}")
            }
        }
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? FileSystemState ?: FileSystemState()
        val payload = action.payload

        when (action.name) {
            ActionRegistry.Names.FILESYSTEM_STAGE_SCOPED_READ -> {
                val decoded = payload?.let { json.decodeFromJsonElement<StageScopedReadPayload>(it) } ?: return currentFeatureState
                val requestPayload = json.decodeFromJsonElement<RequestScopedReadUiPayload>(decoded.requestPayload)
                val pendingRequest = PendingScopedRead(
                    requestId = decoded.requestId,
                    originator = decoded.originator,
                    payload = requestPayload,
                    correlationId = requestPayload.correlationId
                )
                return currentFeatureState.copy(pendingScopedRead = pendingRequest)
            }
            ActionRegistry.Names.FILESYSTEM_FINALIZE_SCOPED_READ -> {
                return currentFeatureState.copy(pendingScopedRead = null)
            }
            ActionRegistry.Names.FILESYSTEM_DIRECTORY_LOADED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<DirectoryLoadedPayload>(it) } ?: return currentFeatureState
                val newChildren = decoded.children.map { FileSystemItem(it.path, platformDependencies.getFileName(it.path), it.isDirectory) }
                return if (currentFeatureState.currentPath == decoded.parentPath) currentFeatureState.copy(rootItems = newChildren, error = null)
                else currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.parentPath) { it.copy(children = newChildren) })
            }
            ActionRegistry.Names.FILESYSTEM_NAVIGATE -> return currentFeatureState.copy(currentPath = payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path)
            ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_EXPANDED -> {
                val path = payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: return currentFeatureState
                return currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, path) { it.copy(isExpanded = !it.isExpanded) })
            }
            ActionRegistry.Names.FILESYSTEM_TOGGLE_ITEM_SELECTED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleItemPayload>(it) } ?: return currentFeatureState
                val targetItem = findItemByPath(currentFeatureState.rootItems, decoded.path)
                val targetState = if (targetItem?.isDirectory == true) { val stats = getSelectionStats(targetItem); stats.selectedCount < stats.totalCount }
                else !(targetItem?.isSelected ?: false)
                return currentFeatureState.copy(rootItems = updateItemByPath(currentFeatureState.rootItems, decoded.path, decoded.recursive) { it.copy(isSelected = targetState) })
            }
            ActionRegistry.Names.FILESYSTEM_EXPAND_ALL -> return currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", true))
            ActionRegistry.Names.FILESYSTEM_COLLAPSE_ALL -> return currentFeatureState.copy(rootItems = updateExpansionStateRecursive(currentFeatureState.rootItems, payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: "", false))
            ActionRegistry.Names.FILESYSTEM_ADD_WHITELIST_PATH -> return currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths + (payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionRegistry.Names.FILESYSTEM_REMOVE_WHITELIST_PATH -> return currentFeatureState.copy(whitelistedPaths = currentFeatureState.whitelistedPaths - (payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionRegistry.Names.FILESYSTEM_ADD_FAVORITE_PATH -> return currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths + (payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionRegistry.Names.FILESYSTEM_REMOVE_FAVORITE_PATH -> return currentFeatureState.copy(favoritePaths = currentFeatureState.favoritePaths - (payload?.let { json.decodeFromJsonElement<PathPayload>(it) }?.path ?: ""))
            ActionRegistry.Names.SETTINGS_LOADED -> return currentFeatureState.copy(
                whitelistedPaths = deserializeSet(payload?.get(settingKeyWhitelist)?.jsonPrimitive?.content),
                favoritePaths = deserializeSet(payload?.get(settingKeyFavorites)?.jsonPrimitive?.content)
            )
            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                when (payload?.get("key")?.jsonPrimitive?.content) {
                    settingKeyWhitelist -> return currentFeatureState.copy(whitelistedPaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                    settingKeyFavorites -> return currentFeatureState.copy(favoritePaths = deserializeSet(payload["value"]?.jsonPrimitive?.content))
                }
            }
        }
        return currentFeatureState
    }

    private fun dispatchLoadChildrenRecursive(item: FileSystemItem, maxDepth: Int, store: Store) {
        if (maxDepth <= 0 || !item.isDirectory) return
        if (item.children == null) store.dispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LOAD_CHILDREN, buildJsonObject { put("path", item.path) }))
        else item.children.forEach { dispatchLoadChildrenRecursive(it, maxDepth - 1, store) }
    }

    private fun serializeSet(set: Set<String>): String = set.joinToString(",")
    private fun deserializeSet(str: String?): Set<String> = str?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    private fun updateExpansionStateRecursive(items: List<FileSystemItem>, path: String, expand: Boolean): List<FileSystemItem> = items.map { item ->
        when {
            item.path == path -> expandOrCollapse(item, expand, 3)
            path.startsWith(item.path) && item.children != null -> item.copy(children = updateExpansionStateRecursive(item.children, path, expand))
            else -> item
        }
    }
    private fun expandOrCollapse(item: FileSystemItem, expand: Boolean, maxDepth: Int): FileSystemItem {
        if (!item.isDirectory || maxDepth <= 0) return item.copy(isExpanded = expand)
        return item.copy(isExpanded = expand, children = item.children?.map { expandOrCollapse(it, expand, maxDepth - 1) })
    }
    private fun updateItemByPath(items: List<FileSystemItem>, path: String, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> = items.map { item ->
        when {
            item.path == path -> update(item)
            path.startsWith(item.path) && item.children != null -> item.copy(children = updateItemByPath(item.children, path, update))
            else -> item
        }
    }
    private fun updateItemByPath(items: List<FileSystemItem>, path: String, recursive: Boolean, update: (FileSystemItem) -> FileSystemItem): List<FileSystemItem> = items.map { item ->
        var updatedItem = item
        if (item.path == path) {
            updatedItem = update(item)
            if (recursive && updatedItem.children != null) updatedItem = updatedItem.copy(children = updatedItem.children.map { updateItemRecursive(it, update) })
        } else if (path.startsWith(item.path) && item.children != null) {
            updatedItem = item.copy(children = updateItemByPath(item.children, path, recursive, update))
        }
        updatedItem
    }
    private fun updateItemRecursive(item: FileSystemItem, update: (FileSystemItem) -> FileSystemItem): FileSystemItem {
        val updated = update(item)
        return if (updated.children != null) updated.copy(children = updated.children.map { updateItemRecursive(it, update) }) else updated
    }
    private fun findItemByPath(items: List<FileSystemItem>, path: String): FileSystemItem? {
        items.forEach { item ->
            if (item.path == path) return item
            if (path.startsWith(item.path)) item.children?.let { findItemByPath(it, path)?.let { return it } }
        }
        return null
    }
    private fun findSelectedFiles(items: List<FileSystemItem>): List<FileSystemItem> = items.flatMap { item ->
        when {
            item.isSelected && !item.isDirectory -> listOf(item)
            item.isDirectory && item.children != null -> findSelectedFiles(item.children)
            else -> emptyList()
        }
    }
    private data class SelectionStats(val selectedCount: Int, val totalCount: Int)
    private fun getSelectionStats(item: FileSystemItem): SelectionStats {
        if (!item.isDirectory) return SelectionStats(if (item.isSelected) 1 else 0, 1)
        if (item.children.isNullOrEmpty()) return SelectionStats(0, 0)
        return item.children.map { getSelectionStats(it) }.let { stats -> SelectionStats(stats.sumOf { it.selectedCount }, stats.sumOf { it.totalCount }) }
    }

    inner class FileSystemComposableProvider : Feature.ComposableProvider {
        private val viewKey = "feature.filesystem.main"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            viewKey to { store, _ -> FileSystemView(store, platformDependencies) }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            val isActive = activeViewKey == viewKey
            IconButton(onClick = { store.dispatch("filesystem.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", viewKey) })) }) {
                Icon(Icons.Default.Folder, "File System Browser", tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}