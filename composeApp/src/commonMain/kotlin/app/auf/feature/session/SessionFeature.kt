package app.auf.feature.session

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "session", localHandle = "session", name="Session Manager")

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class CreatePayload(val name: String? = null, val isHidden: Boolean = false, val isPrivateTo: String? = null)
    @Serializable private data class ClonePayload(val session: String)
    @Serializable private data class UpdateConfigPayload(val session: String, val name: String)
    @Serializable private data class SessionTargetPayload(val session: String)
    @Serializable private data class PostPayload(val session: String, val senderId: String? = null, val message: String? = null, val messageId: String? = null, val metadata: JsonObject? = null, val afterMessageId: String? = null, val doNotClear: Boolean = false)
    @Serializable private data class UpdateMessagePayload(val session: String, val messageId: String, val newContent: String? = null, val newMetadata: JsonObject? = null, val doNotClear: Boolean? = null)
    @Serializable private data class MessageTargetPayload(val session: String, val messageId: String)
    @Serializable private data class SetEditingSessionPayload(val sessionId: String?)
    @Serializable private data class SetEditingMessagePayload(val messageId: String?)
    @Serializable private data class ToggleMessageUiPayload(val sessionId: String, val messageId: String)
    @Serializable internal data class InternalSessionLoadedPayload(val sessions: Map<String, Session>)
    @Serializable private data class RequestLedgerPayload(val sessionId: String, val correlationId: String)
    @Serializable private data class GatewayMessage(val role: String, val content: String, val senderId: String, val senderName: String)
    @Serializable private data class ReorderPayload(val sessionId: String, val toIndex: Int)
    @Serializable private data class SetOrderPayload(val order: List<String>)
    @Serializable private data class ToggleMessageLockedPayload(val sessionId: String, val messageId: String)

    // --- Input draft & history payload types ---
    @Serializable private data class InputDraftChangedPayload(val sessionId: String, val draft: String)
    @Serializable private data class HistoryNavigatePayload(val sessionId: String, val direction: String)

    // --- Workspace pane payload types ---
    @Serializable private data class RefreshWorkspacePayload(val session: String)
    @Serializable private data class WorkspaceFilePayload(val session: String, val fileName: String? = null)
    @Serializable private data class WorkspaceFilesLoadedPayload(val sessionLocalHandle: String, val files: List<FileEntry>)
    @Serializable private data class WorkspaceFileContentPayload(val fileName: String, val content: String)

    // --- Session workspace file delegation payload types ---
    @Serializable private data class RequestWorkspaceFilesPayload(
        val sessionId: String,
        val correlationId: String,
        val requesterId: String,
        val expandedFilePaths: List<String> = emptyList()
    )
    @Serializable private data class ReadWorkspaceFilePayload(
        val sessionId: String,
        val path: String,
        val requesterId: String,
        val correlationId: String
    )

    // --- Payload types for agent-facing message targeting ---
    @Serializable private data class LockMessagePayload(val session: String, val senderId: String, val timestamp: String)
    @Serializable private data class DeleteMessageExtPayload(val session: String, val messageId: String? = null, val senderId: String? = null, val timestamp: String? = null)

    // --- Payload for RETURN_REGISTER_IDENTITY (from CoreFeature) ---
    @Serializable private data class RegisterIdentityResponsePayload(
        val success: Boolean,
        val requestedLocalHandle: String? = null,
        val approvedLocalHandle: String? = null,
        val handle: String? = null,
        val uuid: String? = null,
        val name: String? = null,
        val parentHandle: String? = null,
        val error: String? = null
    )

    // --- Phase 4: Payload for RETURN_UPDATE_IDENTITY (from CoreFeature) ---
    @Serializable private data class UpdateIdentityResponsePayload(
        val success: Boolean,
        val oldHandle: String,
        val newHandle: String? = null,
        val oldLocalHandle: String? = null,
        val newLocalHandle: String? = null,
        val name: String? = null,
        val uuid: String? = null,
        val error: String? = null
    )

    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Maximum number of sent-message history entries kept per session. */
    private val MAX_HISTORY_SIZE = 50

    /**
     * Debounce jobs for writing input.json, keyed by session localHandle.
     * A new draft change cancels the previous job and starts a 5-second countdown.
     * On SESSION_POST the job is cancelled and input.json is written immediately.
     */
    private val draftDebounceJobs = mutableMapOf<String, Job>()

    // --- Startup Loading Tracking ---
    // Tracks outstanding filesystem operations during startup so that
    // SESSION_FEATURE_READY fires exactly once after all disk-loaded sessions
    // are in the sessions map — not per-file.
    private var startupLoadingActive = false
    private var pendingStartupOps = 0

    // --- Session workspace file delegation tracking ---
    // Tracks in-flight cross-sandbox workspace file requests from agents.
    // Keyed by the original correlationId from the agent's request.
    private data class PendingWorkspaceDelegation(
        val correlationId: String,
        val sessionUUID: String,
        val requester: String,
        val expandedFilePaths: List<String>,
        val workspacePath: String,
        val cachedListing: JsonArray? = null
    )
    private data class PendingOnDemandFileRead(
        val correlationId: String,
        val sessionUUID: String,
        val relativePath: String,
        val requester: String
    )
    private val pendingWorkspaceDelegations = mutableMapOf<String, PendingWorkspaceDelegation>()
    private val pendingOnDemandFileReads = mutableMapOf<String, PendingOnDemandFileRead>()

    /**
     * Fires [SESSION_FEATURE_READY] once when all startup filesystem operations
     * (folder listings + file reads) have completed. Resets the loading flag so
     * subsequent runtime operations are unaffected.
     */
    private fun checkStartupLoadComplete(store: Store, sessionState: SessionState) {
        if (startupLoadingActive && pendingStartupOps <= 0) {
            startupLoadingActive = false
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.SESSION_SESSION_FEATURE_READY,
                buildJsonObject {
                    put("sessionCount", sessionState.sessions.size)
                }
            ))
        }
    }

    override fun handleSideEffects(action: Action, store: Store, previousState: FeatureState?, newState: FeatureState?) {
        val sessionState = newState as? SessionState ?: return

        // Helper to log errors for missing sessions
        fun requireSessionId(identifier: String?, state: SessionState, context: String): String? {
            if (identifier == null) {
                platformDependencies.log(LogLevel.ERROR, identity.handle, "Action $context failed: 'session' identifier missing in payload.")
                return null
            }
            val resolved = resolveSessionId(identifier, state)
            if (resolved == null) {
                platformDependencies.log(LogLevel.ERROR, identity.handle, "Action $context failed: Could not resolve session with identifier '$identifier'.")
            }
            return resolved
        }

        when (action.name) {
            // Phase 3: Targeted responses from FilesystemFeature — migrated from onPrivateData.
            // Phase 4: Updated for new folder-based file structure (uuid/localHandle.json)
            // Phase 5 (Workspace): Workspace folder listings are routed to WORKSPACE_FILES_LOADED.
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> {
                val data = action.payload ?: return
                val fileList = data["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
                val returnedPath = data["path"]?.jsonPrimitive?.contentOrNull ?: ""

                // ── Session file delegation listing response ───────────────
                val listCorrelationId = data["correlationId"]?.jsonPrimitive?.contentOrNull
                if (listCorrelationId != null && listCorrelationId.startsWith("sf-delegation:")) {
                    val originalCorrelationId = listCorrelationId.removePrefix("sf-delegation:")
                    val delegation = pendingWorkspaceDelegations[originalCorrelationId]
                    if (delegation != null) {
                        handleDelegationListingResponse(delegation, fileList, data, store)
                        return
                    } else {
                        platformDependencies.log(LogLevel.WARN, identity.handle,
                            "[SF-TRACE] FILESYSTEM_RETURN_LIST: sf-delegation correlationId '$originalCorrelationId' " +
                                    "has no matching pending delegation. May have been cleaned up or timed out.")
                        // Fall through to normal workspace handling
                    }
                }

                // ── Workspace file listing ────────────────────────────────
                // If the path contains "/workspace" or "\workspace", this is a
                // workspace pane refresh — route to the workspace files loader.
                val normalizedReturnedPath = returnedPath.replace('\\', '/')
                if (normalizedReturnedPath.contains("/workspace")) {
                    // Extract the session UUID from the path (format: "{uuid}/workspace")
                    val uuid = normalizedReturnedPath.substringBefore("/workspace")
                    val session = sessionState.sessions.values.find { it.identity.uuid == uuid }
                    if (session != null) {
                        // Filter to files only (not directories or .keep)
                        val workspaceFiles = fileList.filter { entry ->
                            val name = platformDependencies.getFileName(entry.path)
                            !entry.isDirectory && name != ".keep"
                        }
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.SESSION_WORKSPACE_FILES_LOADED,
                            buildJsonObject {
                                put("sessionLocalHandle", session.identity.localHandle)
                                put("files", Json.encodeToJsonElement(workspaceFiles))
                            }
                        ))
                    }
                    return
                }

                // ── Original session file loading ─────────────────────────
                if (startupLoadingActive) pendingStartupOps--

                fileList.forEach { entry ->
                    if (entry.path.endsWith(".json")) {
                        // It's a JSON file inside a UUID folder — read it.
                        // Both localHandle.json and input.json are handled this way;
                        // FILESYSTEM_RETURN_READ routes them based on the filename.
                        if (startupLoadingActive) pendingStartupOps++
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_READ, buildJsonObject { put("path", entry.path) }))
                    } else if (!entry.path.contains(".")) {
                        // Looks like a UUID folder — list its contents
                        if (startupLoadingActive) pendingStartupOps++
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject { put("path", platformDependencies.getFileName(entry.path)) }))
                    }
                }

                if (startupLoadingActive) checkStartupLoadComplete(store, sessionState)
            }
            // Phase 5 (Workspace): Workspace file reads are routed to WORKSPACE_FILE_CONTENT_LOADED.
            ActionRegistry.Names.FILESYSTEM_RETURN_READ -> {
                val data = action.payload ?: return
                val path = data["path"]?.jsonPrimitive?.content ?: ""
                val content = data["content"]?.jsonPrimitive?.content ?: ""

                // ── Session file on-demand read response ──────────────────
                val readCorrelationId = data["correlationId"]?.jsonPrimitive?.contentOrNull
                if (readCorrelationId != null && readCorrelationId.startsWith("sfod-delegation:")) {
                    val originalCorrelationId = readCorrelationId.removePrefix("sfod-delegation:")
                    val pending = pendingOnDemandFileReads.remove(originalCorrelationId)
                    if (pending != null) {
                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE,
                            payload = buildJsonObject {
                                put("correlationId", pending.correlationId)
                                put("sessionId", pending.sessionUUID)
                                put("path", pending.relativePath)
                                if (content.isNotBlank()) put("content", content) else put("error", "File not found or empty.")
                            },
                            targetRecipient = pending.requester
                        ))
                        return
                    } else {
                        platformDependencies.log(LogLevel.WARN, identity.handle,
                            "[SF-TRACE] FILESYSTEM_RETURN_READ: sfod-delegation correlationId '$originalCorrelationId' " +
                                    "has no matching pending on-demand read. May have been cleaned up or timed out.")
                        return // Do NOT fall through to workspace preview handler — this was a delegation read
                    }
                }

                // ── Workspace file content ────────────────────────────────
                // Route files inside a /workspace/ subfolder to the preview handler.
                val normalizedPath = path.replace('\\', '/')
                if (normalizedPath.contains("/workspace/")) {
                    val fileName = platformDependencies.getFileName(path)
                    if (content.isNotBlank()) {
                        store.deferredDispatch(identity.handle, Action(
                            ActionRegistry.Names.SESSION_WORKSPACE_FILE_CONTENT_LOADED,
                            buildJsonObject {
                                put("fileName", fileName)
                                put("content", content)
                            }
                        ))
                    }
                    return
                }

                // Route input.json files to the input-state handler — they are not Session objects.
                // Check both "/" and "\" separators for cross-platform compatibility (Windows uses "\").
                if (path.endsWith("/input.json") || path.endsWith("\\input.json")) {
                    if (content.isNotBlank()) handleInputJsonRead(path, content, store)
                    if (startupLoadingActive) {
                        pendingStartupOps--
                        checkStartupLoadComplete(store, sessionState)
                    }
                    return
                }

                try {
                    if (content.isBlank()) {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "Received empty session file content for $path")
                        if (startupLoadingActive) {
                            pendingStartupOps--
                            checkStartupLoadComplete(store, sessionState)
                        }
                        return
                    }
                    val session = json.decodeFromString<Session>(content)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_LOADED, Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.identity.localHandle to session))) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse session file: $path. Error: ${e.message}", e)
                    if (startupLoadingActive) {
                        pendingStartupOps--
                        checkStartupLoadComplete(store, sessionState)
                    }
                }
            }
            // Phase 6 (Session Files): READ_MULTIPLE responses for workspace file delegation.
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> {
                val data = action.payload ?: return
                val correlationId = data["correlationId"]?.jsonPrimitive?.contentOrNull
                if (correlationId != null && correlationId.startsWith("sf-delegation-files:")) {
                    val originalCorrelationId = correlationId.removePrefix("sf-delegation-files:")
                    val delegation = pendingWorkspaceDelegations.remove(originalCorrelationId)
                    if (delegation != null) {
                        val contentsJson = data["contents"]?.jsonObject ?: buildJsonObject {}

                        // Strip session workspace prefix from paths
                        val workspacePrefix = "${delegation.sessionUUID}/workspace/"
                        val relativeContents = buildJsonObject {
                            contentsJson.forEach { (path, content) ->
                                val normalizedPath = path.replace("\\", "/")
                                val relativePath = normalizedPath.removePrefix(workspacePrefix)
                                put(relativePath, content)
                            }
                        }

                        val listing = delegation.cachedListing ?: run {
                            platformDependencies.log(LogLevel.WARN, identity.handle,
                                "[SF-TRACE] RETURN_WORKSPACE_FILES: cachedListing is null for delegation " +
                                        "'${delegation.correlationId}'. Listing may not have been cached properly. " +
                                        "Sending empty listing.")
                            JsonArray(emptyList())
                        }

                        store.deferredDispatch(identity.handle, Action(
                            name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
                            payload = buildJsonObject {
                                put("correlationId", delegation.correlationId)
                                put("sessionId", delegation.sessionUUID)
                                put("listing", listing)
                                put("contents", relativeContents)
                            },
                            targetRecipient = delegation.requester
                        ))

                        platformDependencies.log(LogLevel.DEBUG, identity.handle,
                            "[SF-TRACE] RETURN_WORKSPACE_FILES sent for session '${delegation.sessionUUID}' " +
                                    "(${listing.size} entries, ${contentsJson.size} file contents).")
                    } else {
                        platformDependencies.log(LogLevel.WARN, identity.handle,
                            "[SF-TRACE] FILESYSTEM_RETURN_FILES_CONTENT: sf-delegation-files correlationId " +
                                    "'$originalCorrelationId' has no matching pending delegation. " +
                                    "May have been cleaned up or timed out.")
                    }
                }
            }
            ActionRegistry.Names.SYSTEM_STARTING -> {
                startupLoadingActive = true
                pendingStartupOps = 1 // the root listing dispatched below
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_LIST))
                // Register hide-hidden settings with the Settings feature for persistence.
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    put("type", "BOOLEAN")
                    put("label", "Hide hidden sessions in viewer")
                    put("description", "When enabled, sessions marked as hidden are not shown in the tab bar.")
                    put("section", "Session")
                    put("defaultValue", "true")
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SETTINGS_ADD, buildJsonObject {
                    put("key", SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                    put("type", "BOOLEAN")
                    put("label", "Hide hidden sessions in manager")
                    put("description", "When enabled, sessions marked as hidden are not shown in the session manager.")
                    put("section", "Session")
                    put("defaultValue", "true")
                }))
            }

            // ── Graceful shutdown: flush all pending draft writes immediately ──────
            ActionRegistry.Names.SYSTEM_CLOSING -> {
                // Cancel all debounce timers — we're writing everything now.
                draftDebounceJobs.values.forEach { it.cancel() }
                draftDebounceJobs.clear()
                // Persist every session's input state that has draft content or history.
                // This ensures no data is lost even if the UI debounce hadn't fired yet.
                sessionState.sessions.keys.forEach { localHandle ->
                    persistInputState(localHandle, sessionState, store)
                }
            }

            // Phase 4: SESSION_CREATE and SESSION_CLONE now dispatch REGISTER_IDENTITY for pending creations
            ActionRegistry.Names.SESSION_CREATE, ActionRegistry.Names.SESSION_CLONE -> {
                val prevPending = (previousState as? SessionState)?.pendingCreations ?: emptyMap()
                val newPendingIds = sessionState.pendingCreations.keys - prevPending.keys
                newPendingIds.forEach { uuid ->
                    val pending = sessionState.pendingCreations[uuid] ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("name", pending.requestedName)
                            put("uuid", pending.uuid)
                        }
                    ))
                }
            }

            // Phase 4: When identity is approved, persist the new session and broadcast
            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY -> {
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                val newLocalHandles = sessionState.sessions.keys - prevSessions.keys
                newLocalHandles.forEach { localHandle ->
                    persistSession(localHandle, sessionState, store)
                    // Create workspace folder
                    val session = sessionState.sessions[localHandle] ?: return@forEach
                    val uuid = session.identity.uuid ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_WRITE,
                        buildJsonObject {
                            put("path", "$uuid/workspace/.keep")
                            put("content", "")
                        }
                    ))

                    // Phase A: Broadcast SESSION_CREATED (§5.1)
                    // Fires after the session is persisted and identity approved.
                    // Agents listen for this to detect their private cognition session via isPrivateTo.
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.SESSION_SESSION_CREATED,
                        buildJsonObject {
                            put("uuid", uuid)
                            put("name", session.identity.name)
                            put("handle", session.identity.handle)
                            put("localHandle", localHandle)
                            put("isHidden", session.isHidden)
                            val privateTo = session.isPrivateTo?.handle
                            if (privateTo != null) {
                                put("isPrivateTo", privateTo)
                            } else {
                                put("isPrivateTo", JsonNull)
                            }
                        }
                    ))
                }
                if (newLocalHandles.isNotEmpty()) {
                    broadcastSessionNames(sessionState, store)
                }
                // Signal readiness when all pending identity registrations have completed.
                val prevPending = (previousState as? SessionState)?.pendingCreations ?: emptyMap()
                if (prevPending.isNotEmpty() && sessionState.pendingCreations.isEmpty()) {
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.SESSION_SESSION_FEATURE_READY,
                        buildJsonObject {
                            put("sessionCount", sessionState.sessions.size)
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_CONFIG -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "UPDATE_CONFIG") ?: run {
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, false, error = "Session '$identifier' not found.")
                    return
                }
                val prevSession = (previousState as? SessionState)?.sessions?.get(localHandle)
                val newSession = sessionState.sessions[localHandle]

                if (prevSession != null && newSession != null && prevSession.identity.name != newSession.identity.name) {
                    // Name changed — request identity update (handle may change)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UPDATE_IDENTITY,
                        buildJsonObject {
                            put("handle", prevSession.identity.handle)
                            put("newName", newSession.identity.name)
                        }
                    ))
                }

                // Persist (with current — possibly intermediate — state)
                persistSession(localHandle, sessionState, store)
                broadcastSessionNames(sessionState, store)
                publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                    action.name, true, summary = "Session config updated for '${newSession?.identity?.name ?: localHandle}'.")
            }

            // Phase 4: Side effects for RETURN_UPDATE_IDENTITY — file rename + persist
            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY -> {
                val resp = action.payload?.let { json.decodeFromJsonElement<UpdateIdentityResponsePayload>(it) }
                    ?: return
                if (!resp.success) return

                val oldLocalHandle = resp.oldLocalHandle ?: return
                val newLocalHandle = resp.newLocalHandle ?: return
                val uuid = resp.uuid ?: return

                if (oldLocalHandle != newLocalHandle) {
                    // Handle changed — delete old JSON file inside the UUID folder
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_DELETE_FILE,
                        buildJsonObject { put("path", "$uuid/$oldLocalHandle.json") }
                    ))
                }

                // Persist with new file name
                if (newLocalHandle in sessionState.sessions) {
                    persistSession(newLocalHandle, sessionState, store)
                }
                broadcastSessionNames(sessionState, store)
            }

            ActionRegistry.Names.SESSION_DELETE -> {
                val localHandleToDelete = sessionState.lastDeletedSessionLocalHandle
                if (localHandleToDelete != null) {
                    val prevSession = (previousState as? SessionState)?.sessions?.get(localHandleToDelete)
                    val uuid = prevSession?.identity?.uuid
                    val sessionName = prevSession?.identity?.name ?: localHandleToDelete

                    // Delete the session folder (uuid-named)
                    if (uuid != null) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_DELETE_DIRECTORY, buildJsonObject { put("path", uuid) }))
                    }
                    broadcastSessionNames(sessionState, store)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_DELETED, buildJsonObject {
                        put("sessionId", localHandleToDelete)
                        uuid?.let { put("sessionUUID", it) }
                    }))
                    // Unregister session identity (cascades any children)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject {
                            put("handle", "session.$localHandleToDelete")
                        }
                    ))
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, true, summary = "Session '$sessionName' deleted.")
                } else {
                    val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_DELETE ignored: Session '$identifier' not found in state.")
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, false, error = "Session '$identifier' not found.")
                }
            }

            ActionRegistry.Names.SESSION_LOADED -> {
                broadcastSessionNames(sessionState, store)
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()

                // Persist sessions whose orderIndex was normalized
                sessionState.sessions.forEach { (localHandle, session) ->
                    if (prevSessions[localHandle]?.orderIndex != session.orderIndex) {
                        persistSession(localHandle, sessionState, store)
                    }
                }
                // Register identities for newly loaded sessions
                val newLocalHandles = sessionState.sessions.keys - prevSessions.keys
                newLocalHandles.forEach { localHandle ->
                    val session = sessionState.sessions[localHandle] ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("name", session.identity.name)
                            put("uuid", session.identity.uuid)
                            // localHandle from the loaded file — pass it to ensure consistency
                            put("localHandle", session.identity.localHandle)
                        }
                    ))
                }
                // Startup tracking: decrement once per SESSION_LOADED (one per file read).
                // SESSION_FEATURE_READY fires from checkStartupLoadComplete when all
                // pending filesystem ops drain to zero — exactly once.
                if (startupLoadingActive) {
                    pendingStartupOps--
                    checkStartupLoadComplete(store, sessionState)
                }
            }

            ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull ?: return
                if (!sessionState.sessions.containsKey(sessionId)) return
                draftDebounceJobs[sessionId]?.cancel()
                draftDebounceJobs[sessionId] = coroutineScope.launch {
                    delay(5_000)
                    val latestState = store.state.value.featureStates["session"] as? SessionState ?: return@launch
                    val session = latestState.sessions[sessionId] ?: return@launch
                    val uuid = session.identity.uuid ?: return@launch
                    val inputState = SessionInputState(
                        draft = latestState.draftInputs[sessionId] ?: "",
                        history = latestState.inputHistories[sessionId] ?: emptyList()
                    )
                    store.dispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_WRITE,
                        buildJsonObject {
                            put("path", "$uuid/input.json")
                            put("content", json.encodeToString(inputState))
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_POST -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "POST") ?: run {
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, false, error = "Session '$identifier' not found.")
                    return
                }

                persistSession(localHandle, sessionState, store)
                val updatedSession = sessionState.sessions[localHandle] ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val postedEntry = if (messageId != null) {
                    updatedSession.ledger.find { it.id == messageId }
                } else {
                    updatedSession.ledger.lastOrNull()
                }

                if (postedEntry != null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_MESSAGE_POSTED, buildJsonObject {
                        put("sessionId", localHandle)
                        sessionState.sessions[localHandle]?.identity?.uuid?.let { put("sessionUUID", it) }
                        put("entry", json.encodeToJsonElement(postedEntry))
                    }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, true, summary = "Message posted to '${updatedSession.identity.name}'.")
                } else {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "SESSION_POST failed: Ledger entry not found after reducer update.")
                    publishActionResult(store, action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull,
                        action.name, false, error = "Ledger entry not found after reducer update.")
                }

                // For user posts: cancel the debounce and write input.json immediately
                val activeUserId = (previousState as? SessionState)?.activeUserId ?: "user"
                val postedSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                    ?: action.originator
                if (postedSenderId == activeUserId) {
                    draftDebounceJobs[localHandle]?.cancel()
                    draftDebounceJobs.remove(localHandle)
                    persistInputState(localHandle, sessionState, store)
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "UPDATE_MESSAGE") ?: run {
                    publishActionResult(store, correlationId, action.name, false, error = "Session '$identifier' not found.")
                    return
                }
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: run {
                    publishActionResult(store, correlationId, action.name, false, error = "Missing messageId.")
                    return
                }
                val prevSessionState = previousState as? SessionState
                if (isMessageLockedGuard(localHandle, messageId, "UPDATE_MESSAGE", prevSessionState ?: sessionState, store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "Message '$messageId' is locked.")
                    return
                }

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                publishActionResult(store, correlationId, action.name, true, summary = "Message updated.")
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val identifier = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull

                val localHandle = requireSessionId(identifier, sessionState, action.name) ?: run {
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Session '$identifier' not found")
                    return
                }

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                publishActionResult(store, correlationId, action.name, success = true,
                    summary = "Message UI state toggled")
            }

            ActionRegistry.Names.SESSION_DELETE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "DELETE_MESSAGE") ?: run {
                    publishActionResult(store, correlationId, action.name, false, error = "Session '$identifier' not found.")
                    return
                }

                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull

                val resolvedMessageId: String? = if (messageId != null) {
                    messageId
                } else if (targetSenderId != null && targetTimestamp != null) {
                    val prevState = previousState as? SessionState ?: sessionState
                    val prevSession = prevState.sessions[localHandle]
                    if (prevSession != null) {
                        val result = MessageResolution.resolve(prevSession.ledger, targetSenderId, targetTimestamp, platformDependencies)
                        result.entry?.id
                    } else null
                } else {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "DELETE_MESSAGE failed: Neither messageId nor senderId+timestamp provided.")
                    null
                }

                if (resolvedMessageId == null) {
                    publishActionResult(store, correlationId, action.name, false, error = "Message not found.")
                    return
                }

                val prevSessionStateForDelete = previousState as? SessionState
                if (isMessageLockedGuard(localHandle, resolvedMessageId, "DELETE_MESSAGE", prevSessionStateForDelete ?: sessionState, store)) {
                    publishActionResult(store, correlationId, action.name, false, error = "Message '$resolvedMessageId' is locked.")
                    return
                }

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_MESSAGE_DELETED, buildJsonObject {
                    put("sessionId", localHandle)
                    sessionState.sessions[localHandle]?.identity?.uuid?.let { put("sessionUUID", it) }
                    put("messageId", resolvedMessageId)
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                publishActionResult(store, correlationId, action.name, true, summary = "Message deleted.")
            }

            ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE -> {
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                val prevSessionStateForEdit = previousState as? SessionState ?: sessionState
                val entry = prevSessionStateForEdit.sessions.values.flatMap { it.ledger }.find { it.id == messageId }
                if (entry?.isLocked == true) {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SET_EDITING_MESSAGE blocked: Message '$messageId' is locked.")
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                        put("message", "This message is locked and cannot be modified.")
                    }))
                }
            }

            ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestLedgerPayload>(it) }
                if (payload == null) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "REQUEST_LEDGER_CONTENT failed: Payload invalid.")
                    return
                }
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull

                val localHandle = resolveSessionId(payload.sessionId, sessionState)
                val session = localHandle?.let { sessionState.sessions[it] }
                if (session == null) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "REQUEST_LEDGER_CONTENT failed: Could not resolve session '${payload.sessionId}'.")
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Session '${payload.sessionId}' not found")
                    return
                }

                val messages = session.ledger.map { json.encodeToJsonElement(it) }

                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    putJsonArray("messages") { messages.forEach { add(it) } }
                }
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.SESSION_RETURN_LEDGER,
                    payload = responsePayload,
                    targetRecipient = action.originator ?: "unknown"
                ))
                publishActionResult(store, correlationId, action.name, success = true,
                    summary = "Ledger returned (${messages.size} entries)")
            }

            // ================================================================
            // Session Workspace File Delegation (Cross-Sandbox)
            // ================================================================
            ActionRegistry.Names.SESSION_REQUEST_WORKSPACE_FILES -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<RequestWorkspaceFilesPayload>(it) }
                if (payload == null) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle,
                        "REQUEST_WORKSPACE_FILES: Invalid payload.")
                    return
                }

                val localHandle = resolveSessionId(payload.sessionId, sessionState)
                val session = localHandle?.let { sessionState.sessions[it] }
                if (session == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "REQUEST_WORKSPACE_FILES: Could not resolve session '${payload.sessionId}'.")
                    // Send error response so the agent gate doesn't hang
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
                        payload = buildJsonObject {
                            put("correlationId", payload.correlationId)
                            put("sessionId", payload.sessionId)
                            put("error", "Session '${payload.sessionId}' not found.")
                        },
                        targetRecipient = action.originator ?: "unknown"
                    ))
                    return
                }

                val uuid = session.identity.uuid ?: run {
                    platformDependencies.log(LogLevel.ERROR, identity.handle,
                        "REQUEST_WORKSPACE_FILES: Session '${payload.sessionId}' resolved but has null UUID. " +
                                "Cannot access workspace files.")
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
                        payload = buildJsonObject {
                            put("correlationId", payload.correlationId)
                            put("sessionId", payload.sessionId)
                            put("error", "Session '${payload.sessionId}' has no UUID.")
                        },
                        targetRecipient = action.originator ?: "unknown"
                    ))
                    return
                }
                val workspacePath = "$uuid/workspace"

                platformDependencies.log(LogLevel.DEBUG, identity.handle,
                    "[SF-TRACE] REQUEST_WORKSPACE_FILES received for session '$uuid' " +
                            "(requested by '${payload.requesterId}'). " +
                            "Expanded paths: ${payload.expandedFilePaths}")

                // Dispatch filesystem LIST within our own sandbox
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.FILESYSTEM_LIST,
                    buildJsonObject {
                        put("path", workspacePath)
                        put("recursive", true)
                        put("correlationId", "sf-delegation:${payload.correlationId}")
                    }
                ))

                // Stash the delegation context for when the filesystem responds
                pendingWorkspaceDelegations[payload.correlationId] = PendingWorkspaceDelegation(
                    correlationId = payload.correlationId,
                    sessionUUID = uuid,
                    requester = action.originator ?: "unknown",
                    expandedFilePaths = payload.expandedFilePaths,
                    workspacePath = workspacePath
                )
            }

            ActionRegistry.Names.SESSION_READ_WORKSPACE_FILE -> {
                val payload = action.payload?.let { json.decodeFromJsonElement<ReadWorkspaceFilePayload>(it) }
                if (payload == null) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle,
                        "READ_WORKSPACE_FILE: Invalid payload.")
                    return
                }

                val localHandle = resolveSessionId(payload.sessionId, sessionState)
                val session = localHandle?.let { sessionState.sessions[it] }
                if (session == null) {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "READ_WORKSPACE_FILE: Could not resolve session '${payload.sessionId}'.")
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE,
                        payload = buildJsonObject {
                            put("correlationId", payload.correlationId)
                            put("sessionId", payload.sessionId)
                            put("path", payload.path)
                            put("error", "Session '${payload.sessionId}' not found.")
                        },
                        targetRecipient = action.originator ?: "unknown"
                    ))
                    return
                }

                val uuid = session.identity.uuid ?: run {
                    platformDependencies.log(LogLevel.WARN, identity.handle,
                        "READ_WORKSPACE_FILE: Session '${payload.sessionId}' resolved but has null UUID.")
                    store.deferredDispatch(identity.handle, Action(
                        name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE,
                        payload = buildJsonObject {
                            put("correlationId", payload.correlationId)
                            put("sessionId", payload.sessionId)
                            put("path", payload.path)
                            put("error", "Session '${payload.sessionId}' has no UUID.")
                        },
                        targetRecipient = action.originator ?: "unknown"
                    ))
                    return
                }
                val filePath = "$uuid/workspace/${payload.path}"

                // Stash on-demand context
                pendingOnDemandFileReads[payload.correlationId] = PendingOnDemandFileRead(
                    correlationId = payload.correlationId,
                    sessionUUID = uuid,
                    relativePath = payload.path,
                    requester = action.originator ?: "unknown"
                )

                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.FILESYSTEM_READ,
                    buildJsonObject {
                        put("path", filePath)
                        put("correlationId", "sfod-delegation:${payload.correlationId}")
                    }
                ))
            }

            ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "TOGGLE_SESSION_HIDDEN") ?: run {
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Session '$identifier' not found")
                    return
                }
                persistSession(localHandle, sessionState, store)
                val isNowHidden = sessionState.sessions[localHandle]?.isHidden ?: false
                publishActionResult(store, correlationId, action.name, success = true,
                    summary = if (isNowHidden) "Session hidden" else "Session unhidden")
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val resolvedId = requireSessionId(sessionId, sessionState, "TOGGLE_MESSAGE_LOCKED") ?: run {
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Session '$sessionId' not found")
                    return
                }
                persistSession(resolvedId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", resolvedId) }))
                publishActionResult(store, correlationId, action.name, success = true,
                    summary = "Message lock toggled")
            }

            ActionRegistry.Names.SESSION_CLEAR -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "CLEAR") ?: run {
                    publishActionResult(store, correlationId, action.name, false, error = "Session '$identifier' not found.")
                    return
                }
                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                val sessionName = sessionState.sessions[localHandle]?.identity?.name ?: localHandle
                publishActionResult(store, correlationId, action.name, true, summary = "Session '$sessionName' cleared.")
            }

            ActionRegistry.Names.SESSION_SET_ORDER, ActionRegistry.Names.SESSION_REORDER -> {
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                sessionState.sessions.forEach { (localHandle, session) ->
                    if (prevSessions[localHandle]?.orderIndex != session.orderIndex) {
                        persistSession(localHandle, sessionState, store)
                    }
                }
            }

            // --- SLICE 4: LIST_SESSIONS handler ---
            ActionRegistry.Names.SESSION_LIST_SESSIONS -> {
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val sessions = sessionState.sessions.values
                    .filter { !it.isHidden }
                    .map { "• ${it.identity.name} (handle: ${it.identity.localHandle})" }
                    .joinToString("\n")

                val responseMessage = "**Available Sessions:**\n$sessions"

                val responseSessionId = action.payload?.get("responseSession")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull

                if (responseSessionId != null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                        put("session", responseSessionId)
                        put("senderId", "system")
                        put("message", responseMessage)
                    }))
                    publishActionResult(store, correlationId, action.name, success = true,
                        summary = "Listed ${sessionState.sessions.values.count { !it.isHidden }} sessions")
                } else {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "LIST_SESSIONS: No response session specified.")
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "No response session specified")
                }
            }

            // --- SLICE 4: LOCK_MESSAGE / UNLOCK_MESSAGE handlers ---
            ActionRegistry.Names.SESSION_LOCK_MESSAGE, ActionRegistry.Names.SESSION_UNLOCK_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val correlationId = action.payload?.get("correlationId")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, action.name) ?: run {
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Session '$identifier' not found")
                    return
                }

                val prevState = previousState as? SessionState
                val prevSession = prevState?.sessions?.get(localHandle)
                val newSession = sessionState.sessions[localHandle]

                if (prevSession?.ledger == newSession?.ledger && prevSession != null) {
                    val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull ?: ""
                    val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull ?: ""
                    val result = MessageResolution.resolve(
                        prevSession.ledger, targetSenderId, targetTimestamp, platformDependencies
                    )
                    if (result.entry == null && result.errorMessage != null) {
                        postResolutionError(localHandle, result.errorMessage, action.originator, store)
                    }
                    publishActionResult(store, correlationId, action.name, success = false,
                        error = "Message not found or resolution failed")
                    return
                }

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                val verb = if (action.name == ActionRegistry.Names.SESSION_LOCK_MESSAGE) "locked" else "unlocked"
                publishActionResult(store, correlationId, action.name, success = true,
                    summary = "Message $verb")
            }

            // ── Workspace pane: REFRESH ───────────────────────────────────
            ActionRegistry.Names.SESSION_REFRESH_WORKSPACE -> {
                val decoded = action.payload?.let { json.decodeFromJsonElement<RefreshWorkspacePayload>(it) } ?: return
                val localHandle = resolveSessionId(decoded.session, sessionState) ?: return
                val session = sessionState.sessions[localHandle] ?: return
                val uuid = session.identity.uuid ?: return
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.FILESYSTEM_LIST,
                    buildJsonObject { put("path", "$uuid/workspace") }
                ))
            }

            // ── Workspace pane: SELECT FILE (triggers read for preview) ──
            ActionRegistry.Names.SESSION_SELECT_WORKSPACE_FILE -> {
                val decoded = action.payload?.let { json.decodeFromJsonElement<WorkspaceFilePayload>(it) } ?: return
                val fileName = decoded.fileName ?: return // null fileName = deselect, handled by reducer only
                val localHandle = resolveSessionId(decoded.session, sessionState) ?: return
                val session = sessionState.sessions[localHandle] ?: return
                val uuid = session.identity.uuid ?: return
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.FILESYSTEM_READ,
                    buildJsonObject { put("path", "$uuid/workspace/$fileName") }
                ))
            }

            // ── Workspace pane: DELETE FILE ───────────────────────────────
            ActionRegistry.Names.SESSION_DELETE_WORKSPACE_FILE -> {
                val decoded = action.payload?.let { json.decodeFromJsonElement<WorkspaceFilePayload>(it) } ?: return
                val fileName = decoded.fileName ?: return
                val localHandle = resolveSessionId(decoded.session, sessionState) ?: return
                val session = sessionState.sessions[localHandle] ?: return
                val uuid = session.identity.uuid ?: return
                store.deferredDispatch(identity.handle, Action(
                    ActionRegistry.Names.FILESYSTEM_DELETE_FILE,
                    buildJsonObject { put("path", "$uuid/workspace/$fileName") }
                ))
            }

            // ── Workspace pane: auto-refresh when switching sessions ──────
            ActionRegistry.Names.SESSION_SET_ACTIVE_TAB -> {
                if (sessionState.isWorkspacePaneOpen) {
                    val localHandle = sessionState.activeSessionLocalHandle ?: return
                    val session = sessionState.sessions[localHandle] ?: return
                    val uuid = session.identity.uuid ?: return
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_LIST,
                        buildJsonObject { put("path", "$uuid/workspace") }
                    ))
                }
            }
        }
    }

    // ========================================================================
    // SLICE 4: Error feedback for message resolution failures
    // ========================================================================

    private fun postResolutionError(localHandle: String, error: String, originator: String?, store: Store) {
        val formattedError = "```text\n[SESSION] Message resolution failed:\n$error\n```"
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", localHandle)
            put("senderId", "system")
            put("message", formattedError)
        }))
    }

    private fun publishActionResult(
        store: Store,
        correlationId: String?,
        requestAction: String,
        success: Boolean,
        summary: String? = null,
        error: String? = null
    ) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.SESSION_ACTION_RESULT,
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
     * Handles the filesystem listing response for a session file delegation.
     * Cross-references expanded paths against the actual listing, then dispatches
     * READ_MULTIPLE for valid files (or sends the response immediately if no
     * files need reading).
     */
    private fun handleDelegationListingResponse(
        delegation: PendingWorkspaceDelegation,
        fileList: List<FileEntry>,
        rawPayload: JsonObject,
        store: Store
    ) {
        val listing = rawPayload["listing"]?.jsonArray ?: JsonArray(emptyList())

        // Filter to files only (not directories, not .keep)
        val availableFiles = fileList
            .filter { !it.isDirectory }
            .map {
                val normalized = it.path.replace("\\", "/")
                normalized.removePrefix("${delegation.sessionUUID}/workspace/")
            }
            .filter { it.isNotBlank() && !it.endsWith(".keep") }
            .toSet()

        // Cross-reference expanded paths against actual listing (§6 recommendation)
        val validExpandedPaths = delegation.expandedFilePaths
            .filter { it.isNotBlank() && it in availableFiles }

        val droppedPaths = delegation.expandedFilePaths.filter { it.isNotBlank() && it !in availableFiles }
        if (droppedPaths.isNotEmpty()) {
            platformDependencies.log(LogLevel.DEBUG, identity.handle,
                "[SF-TRACE] handleDelegationListingResponse: Dropped ${droppedPaths.size} stale/invalid expanded paths " +
                        "for session '${delegation.sessionUUID}': $droppedPaths")
        }

        if (validExpandedPaths.isEmpty()) {
            // No files to read — send response immediately with listing only
            pendingWorkspaceDelegations.remove(delegation.correlationId)

            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES,
                payload = buildJsonObject {
                    put("correlationId", delegation.correlationId)
                    put("sessionId", delegation.sessionUUID)
                    put("listing", listing)
                    put("contents", buildJsonObject {})
                },
                targetRecipient = delegation.requester
            ))

            platformDependencies.log(LogLevel.DEBUG, identity.handle,
                "[SF-TRACE] RETURN_WORKSPACE_FILES sent (listing only, no expanded files) " +
                        "for session '${delegation.sessionUUID}'.")
        } else {
            // Cache the listing on the delegation and dispatch READ_MULTIPLE
            pendingWorkspaceDelegations[delegation.correlationId] = delegation.copy(
                cachedListing = listing
            )

            val sandboxPaths = validExpandedPaths.map { "${delegation.sessionUUID}/workspace/$it" }

            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE,
                buildJsonObject {
                    put("paths", buildJsonArray { sandboxPaths.forEach { add(it) } })
                    put("correlationId", "sf-delegation-files:${delegation.correlationId}")
                }
            ))

            platformDependencies.log(LogLevel.DEBUG, identity.handle,
                "[SF-TRACE] Dispatched READ_MULTIPLE for ${validExpandedPaths.size} expanded files " +
                        "in session '${delegation.sessionUUID}'.")
        }
    }

    private fun resolveSessionIdFromGenericPayload(payload: JsonObject?, state: SessionState): String? {
        val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return null
        return resolveSessionId(identifier, state)
    }

    // Phase 4: Updated to use uuid/localHandle.json folder structure
    private fun persistSession(localHandle: String, sessionState: SessionState, store: Store) {
        val sessionToSave = sessionState.sessions[localHandle] ?: return
        val persistedSession = sessionToSave.copy(
            ledger = sessionToSave.ledger.filterNot {
                it.metadata?.get("is_transient")?.jsonPrimitive?.booleanOrNull ?: false
            }
        )
        val uuid = sessionToSave.identity.uuid ?: return
        val path = "$uuid/${sessionToSave.identity.localHandle}.json"
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_WRITE, buildJsonObject {
            put("path", path); put("content", json.encodeToString(persistedSession))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        val subscribable = state.sessions
            .filterValues { it.isPrivateTo == null && !it.isPrivate }
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED, buildJsonObject {
            put("sessions", buildJsonArray {
                subscribable.forEach { (localHandle, session) ->
                    add(buildJsonObject {
                        session.identity.uuid?.let { put("uuid", it) }
                        put("handle", session.identity.handle)
                        put("localHandle", localHandle)
                        put("name", session.identity.name)
                    })
                }
            })
        }))
    }

    // Resolves a session identifier to its localHandle (map key).
    // Accepts: localHandle, full handle, display name, or UUID.
    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        if (state.sessions.containsKey(identifier)) return identifier
        state.sessions.values.singleOrNull { it.identity.handle == identifier }
            ?.let { return it.identity.localHandle }
        state.sessions.values.singleOrNull { it.identity.uuid == identifier }
            ?.let { return it.identity.localHandle }
        state.sessions.values.singleOrNull { it.identity.name == identifier }
            ?.let { return it.identity.localHandle }
        return null
    }

    private fun findUniqueName(desiredName: String, state: SessionState): String {
        val existingNames = state.sessions.values.map { it.identity.name }.toSet()
        if (desiredName !in existingNames) return desiredName
        var n = 2; var newName: String; do { newName = "$desiredName-$n"; n++ } while (newName in existingNames)
        return newName
    }

    private fun persistInputState(localHandle: String, sessionState: SessionState, store: Store) {
        val session = sessionState.sessions[localHandle] ?: return
        val uuid = session.identity.uuid ?: return
        val draft = sessionState.draftInputs[localHandle] ?: ""
        val history = sessionState.inputHistories[localHandle] ?: emptyList()
        val inputState = SessionInputState(draft = draft, history = history)
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.FILESYSTEM_WRITE,
            buildJsonObject {
                put("path", "$uuid/input.json")
                put("content", json.encodeToString(inputState))
            }
        ))
    }

    private fun handleInputJsonRead(path: String, content: String, store: Store) {
        if (content.isBlank()) return
        val normalizedPath = path.replace('\\', '/')
        val uuid = normalizedPath.trimEnd('/').substringBeforeLast("/").substringAfterLast("/")
        if (uuid.isBlank()) {
            platformDependencies.log(LogLevel.WARN, identity.handle,
                "Cannot extract UUID from input.json path: $path — ignoring.")
            return
        }
        try {
            val inputState = json.decodeFromString<SessionInputState>(content)
            store.deferredDispatch(identity.handle, Action(
                ActionRegistry.Names.SESSION_INPUT_HISTORIES_LOADED,
                buildJsonObject {
                    put("uuid", uuid)
                    put("draft", inputState.draft)
                    put("history", Json.encodeToJsonElement(inputState.history))
                }
            ))
        } catch (e: Exception) {
            platformDependencies.log(
                LogLevel.WARN, identity.handle,
                "Failed to parse input.json for UUID $uuid — ignoring. Error: ${e.message}"
            )
        }
    }

    private fun isMessageLockedGuard(
        localHandle: String,
        messageId: String,
        actionContext: String,
        state: SessionState,
        store: Store
    ): Boolean {
        val session = state.sessions[localHandle] ?: return false
        val entry = session.ledger.find { it.id == messageId } ?: return false
        if (entry.isLocked) {
            platformDependencies.log(LogLevel.WARN, identity.handle, "$actionContext blocked: Message '$messageId' in session '$localHandle' is locked.")
            store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                put("message", "This message is locked and cannot be modified.")
            }))
            return true
        }
        return false
    }

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val currentFeatureState = state as? SessionState ?: SessionState()
        val payload = action.payload

        var newState: SessionState = when (action.name) {
            ActionRegistry.Names.CORE_IDENTITIES_UPDATED -> {
                val activeId = payload?.get("activeId")?.jsonPrimitive?.contentOrNull
                currentFeatureState.copy(
                    activeUserId = activeId ?: currentFeatureState.activeUserId
                )
            }

            ActionRegistry.Names.SESSION_CREATE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) } ?: CreatePayload()
                val desiredName = decoded.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val uniqueName = findUniqueName(desiredName, currentFeatureState)
                val uuid = platformDependencies.generateUUID()

                val pending = PendingSessionCreation(
                    uuid = uuid,
                    requestedName = uniqueName,
                    isHidden = decoded.isHidden,
                    isPrivateTo = decoded.isPrivateTo?.let { IdentityHandle(it) },
                    createdAt = platformDependencies.currentTimeMillis()
                )
                currentFeatureState.copy(
                    pendingCreations = currentFeatureState.pendingCreations + (uuid to pending)
                )
            }

            ActionRegistry.Names.SESSION_CLONE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ClonePayload>(it) } ?: return currentFeatureState
                val sourceLocalHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val sessionToClone = currentFeatureState.sessions[sourceLocalHandle] ?: return currentFeatureState

                val newName = findUniqueName("${sessionToClone.identity.name} (Copy)", currentFeatureState)
                val uuid = platformDependencies.generateUUID()

                val pending = PendingSessionCreation(
                    uuid = uuid,
                    requestedName = newName,
                    isHidden = false,
                    isPrivateTo = null,
                    createdAt = platformDependencies.currentTimeMillis(),
                    cloneSourceLocalHandle = sourceLocalHandle
                )
                currentFeatureState.copy(
                    pendingCreations = currentFeatureState.pendingCreations + (uuid to pending)
                )
            }

            ActionRegistry.Names.CORE_RETURN_REGISTER_IDENTITY -> {
                val resp = payload?.let { json.decodeFromJsonElement<RegisterIdentityResponsePayload>(it) }
                    ?: return currentFeatureState
                val uuid = resp.uuid

                if (resp.success && uuid != null) {
                    val pending = currentFeatureState.pendingCreations[uuid] ?: return currentFeatureState
                    val approvedIdentity = Identity(
                        uuid = uuid,
                        localHandle = resp.approvedLocalHandle ?: return currentFeatureState,
                        handle = resp.handle ?: return currentFeatureState,
                        name = resp.name ?: pending.requestedName,
                        parentHandle = resp.parentHandle,
                        registeredAt = platformDependencies.currentTimeMillis()
                    )

                    val newSession = if (pending.cloneSourceLocalHandle != null) {
                        val source = currentFeatureState.sessions[pending.cloneSourceLocalHandle]
                        Session(
                            identity = approvedIdentity,
                            ledger = source?.ledger ?: emptyList(),
                            createdAt = pending.createdAt,
                            messageUiState = source?.messageUiState ?: emptyMap(),
                            isHidden = pending.isHidden,
                            isPrivateTo = pending.isPrivateTo,
                            orderIndex = 0
                        )
                    } else {
                        Session(
                            identity = approvedIdentity,
                            ledger = emptyList(),
                            createdAt = pending.createdAt,
                            isHidden = pending.isHidden,
                            isPrivateTo = pending.isPrivateTo,
                            orderIndex = 0
                        )
                    }

                    val localHandle = approvedIdentity.localHandle
                    val updatedSessions = currentFeatureState.sessions + (localHandle to newSession)
                    currentFeatureState.copy(
                        sessions = updatedSessions,
                        activeSessionLocalHandle = localHandle,
                        sessionOrder = SessionState.deriveSessionOrder(updatedSessions),
                        pendingCreations = currentFeatureState.pendingCreations - uuid
                    )
                } else {
                    val failedUuid = resp.uuid
                    if (failedUuid != null && failedUuid in currentFeatureState.pendingCreations) {
                        currentFeatureState.copy(
                            pendingCreations = currentFeatureState.pendingCreations - failedUuid,
                            error = resp.error
                        )
                    } else {
                        currentFeatureState
                    }
                }
            }

            ActionRegistry.Names.CORE_RETURN_UPDATE_IDENTITY -> {
                val resp = payload?.let { json.decodeFromJsonElement<UpdateIdentityResponsePayload>(it) }
                    ?: return currentFeatureState
                if (!resp.success) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle,
                        "UPDATE_IDENTITY failed for ${resp.oldHandle}: ${resp.error}")
                    return currentFeatureState
                }

                val oldLocalHandle = resp.oldLocalHandle ?: return currentFeatureState
                val newLocalHandle = resp.newLocalHandle ?: return currentFeatureState

                val session = currentFeatureState.sessions[oldLocalHandle] ?: return currentFeatureState
                val updatedIdentity = session.identity.copy(
                    localHandle = newLocalHandle,
                    handle = resp.newHandle ?: session.identity.handle,
                    name = resp.name ?: session.identity.name
                )
                val updatedSession = session.copy(identity = updatedIdentity)

                if (oldLocalHandle != newLocalHandle) {
                    val updatedSessions = (currentFeatureState.sessions - oldLocalHandle) + (newLocalHandle to updatedSession)
                    val newActive = if (currentFeatureState.activeSessionLocalHandle == oldLocalHandle) newLocalHandle
                    else currentFeatureState.activeSessionLocalHandle
                    val newEditing = if (currentFeatureState.editingSessionLocalHandle == oldLocalHandle) newLocalHandle
                    else currentFeatureState.editingSessionLocalHandle
                    currentFeatureState.copy(
                        sessions = updatedSessions,
                        activeSessionLocalHandle = newActive,
                        editingSessionLocalHandle = newEditing,
                        sessionOrder = SessionState.deriveSessionOrder(updatedSessions)
                    )
                } else {
                    currentFeatureState.copy(
                        sessions = currentFeatureState.sessions + (oldLocalHandle to updatedSession)
                    )
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_CONFIG -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[localHandle] ?: return currentFeatureState
                val newName = findUniqueName(decoded.name, currentFeatureState)

                val updatedIdentity = session.identity.copy(name = newName)
                val updatedSession = session.copy(identity = updatedIdentity)
                currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (localHandle to updatedSession),
                    editingSessionLocalHandle = null
                )
            }
            ActionRegistry.Names.SESSION_POST -> {
                val decoded = payload?.let { json.decodeFromJsonElement<PostPayload>(it) } ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState

                val resolvedSenderId = decoded.senderId ?: action.originator ?: "unknown"

                val newEntry = LedgerEntry(
                    id = decoded.messageId ?: platformDependencies.generateUUID(),
                    timestamp = platformDependencies.currentTimeMillis(),
                    senderId = resolvedSenderId,
                    rawContent = decoded.message,
                    content = decoded.message?.let { blockParser.parse(it) } ?: emptyList(),
                    metadata = decoded.metadata,
                    doNotClear = decoded.doNotClear
                )
                val updatedLedger = if (decoded.afterMessageId != null) {
                    val insertionIndex = targetSession.ledger.indexOfFirst { it.id == decoded.afterMessageId }
                    if (insertionIndex != -1) {
                        targetSession.ledger.toMutableList().apply { add(insertionIndex + 1, newEntry) }
                    } else {
                        targetSession.ledger + newEntry
                    }
                } else {
                    targetSession.ledger + newEntry
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)

                val activeUserId = currentFeatureState.activeUserId ?: "user"
                val isUserPost = resolvedSenderId == activeUserId
                val messageText = decoded.message

                var newDraftInputs = currentFeatureState.draftInputs
                var newInputHistories = currentFeatureState.inputHistories
                var newHistoryNavIndex = currentFeatureState.historyNavIndex
                var newPreNavDrafts = currentFeatureState.preNavDrafts

                if (isUserPost) {
                    newDraftInputs = newDraftInputs - localHandle
                    newHistoryNavIndex = newHistoryNavIndex - localHandle
                    newPreNavDrafts = newPreNavDrafts - localHandle
                    if (!messageText.isNullOrBlank()) {
                        val existing = newInputHistories[localHandle] ?: emptyList()
                        if (existing.firstOrNull() != messageText) {
                            val capped = (listOf(messageText) + existing).take(MAX_HISTORY_SIZE)
                            newInputHistories = newInputHistories + (localHandle to capped)
                        }
                    }
                }

                currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (localHandle to updatedSession),
                    draftInputs = newDraftInputs,
                    inputHistories = newInputHistories,
                    historyNavIndex = newHistoryNavIndex,
                    preNavDrafts = newPreNavDrafts
                )
            }
            ActionRegistry.Names.SESSION_DELETE -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: ""
                val localHandle = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val newSessions = currentFeatureState.sessions - localHandle
                val newActive = if (currentFeatureState.activeSessionLocalHandle != localHandle) currentFeatureState.activeSessionLocalHandle
                else newSessions.values.maxByOrNull { it.createdAt }?.identity?.localHandle
                currentFeatureState.copy(
                    sessions = newSessions,
                    activeSessionLocalHandle = newActive,
                    lastDeletedSessionLocalHandle = localHandle,
                    sessionOrder = SessionState.deriveSessionOrder(newSessions),
                    draftInputs = currentFeatureState.draftInputs - localHandle,
                    inputHistories = currentFeatureState.inputHistories - localHandle,
                    historyNavIndex = currentFeatureState.historyNavIndex - localHandle,
                    preNavDrafts = currentFeatureState.preNavDrafts - localHandle
                )
            }
            ActionRegistry.Names.SESSION_UPDATE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState
                val targetEntry = targetSession.ledger.find { it.id == decoded.messageId }
                if (targetEntry?.isLocked == true) return currentFeatureState
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) {
                        val updatedRawContent = decoded.newContent ?: it.rawContent
                        val updatedMetadata = decoded.newMetadata ?: it.metadata
                        val updatedDoNotClear = decoded.doNotClear ?: it.doNotClear
                        it.copy(
                            rawContent = updatedRawContent,
                            content = updatedRawContent?.let { c -> blockParser.parse(c) } ?: emptyList(),
                            metadata = updatedMetadata,
                            doNotClear = updatedDoNotClear
                        )
                    } else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(
                    sessions = currentFeatureState.sessions + (localHandle to updatedSession),
                    editingMessageId = null,
                    editingMessageContent = null
                )
            }
            ActionRegistry.Names.SESSION_DELETE_MESSAGE -> {
                val sessionIdentifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val localHandle = resolveSessionId(sessionIdentifier, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState

                val messageId = payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val targetSenderId = payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                val targetTimestamp = payload?.get("timestamp")?.jsonPrimitive?.contentOrNull

                val resolvedMessageId: String? = when {
                    messageId != null -> messageId
                    targetSenderId != null && targetTimestamp != null -> {
                        val result = MessageResolution.resolve(targetSession.ledger, targetSenderId, targetTimestamp, platformDependencies)
                        result.entry?.id
                    }
                    else -> null
                }

                if (resolvedMessageId == null) return currentFeatureState

                val targetEntry = targetSession.ledger.find { it.id == resolvedMessageId }
                if (targetEntry?.isLocked == true) return currentFeatureState

                val updatedLedger = targetSession.ledger.filter { it.id != resolvedMessageId }
                val updatedSession = targetSession.copy(
                    ledger = updatedLedger,
                    messageUiState = targetSession.messageUiState - resolvedMessageId
                )
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (localHandle to updatedSession))
            }

            ActionRegistry.Names.SESSION_LOCK_MESSAGE, ActionRegistry.Names.SESSION_UNLOCK_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<LockMessagePayload>(it) } ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState

                val targetLock = action.name == ActionRegistry.Names.SESSION_LOCK_MESSAGE
                val result = MessageResolution.resolve(targetSession.ledger, decoded.senderId, decoded.timestamp, platformDependencies)

                if (result.entry == null) {
                    return currentFeatureState
                }

                val updatedLedger = targetSession.ledger.map {
                    if (it.id == result.entry.id) it.copy(isLocked = targetLock) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (localHandle to updatedSession))
            }

            ActionRegistry.Names.SESSION_LIST_SESSIONS -> currentFeatureState

            ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<InputDraftChangedPayload>(it) }
                    ?: return currentFeatureState
                if (!currentFeatureState.sessions.containsKey(decoded.sessionId)) return currentFeatureState

                val navActive = (currentFeatureState.historyNavIndex[decoded.sessionId] ?: -1) >= 0

                currentFeatureState.copy(
                    draftInputs = currentFeatureState.draftInputs + (decoded.sessionId to decoded.draft),
                    historyNavIndex = if (navActive) currentFeatureState.historyNavIndex - decoded.sessionId else currentFeatureState.historyNavIndex,
                    preNavDrafts = if (navActive) currentFeatureState.preNavDrafts - decoded.sessionId else currentFeatureState.preNavDrafts
                )
            }

            ActionRegistry.Names.SESSION_HISTORY_NAVIGATE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<HistoryNavigatePayload>(it) }
                    ?: return currentFeatureState
                val sessionId = decoded.sessionId
                if (!currentFeatureState.sessions.containsKey(sessionId)) return currentFeatureState

                val history = currentFeatureState.inputHistories[sessionId] ?: emptyList()
                if (history.isEmpty()) return currentFeatureState

                val currentIndex = currentFeatureState.historyNavIndex[sessionId] ?: -1

                when (decoded.direction) {
                    "UP" -> {
                        if (currentIndex == -1) {
                            val savedDraft = currentFeatureState.draftInputs[sessionId] ?: ""
                            currentFeatureState.copy(
                                historyNavIndex = currentFeatureState.historyNavIndex + (sessionId to 0),
                                draftInputs = currentFeatureState.draftInputs + (sessionId to history[0]),
                                preNavDrafts = currentFeatureState.preNavDrafts + (sessionId to savedDraft)
                            )
                        } else {
                            val newIndex = (currentIndex + 1).coerceAtMost(history.lastIndex)
                            currentFeatureState.copy(
                                historyNavIndex = currentFeatureState.historyNavIndex + (sessionId to newIndex),
                                draftInputs = currentFeatureState.draftInputs + (sessionId to history[newIndex])
                            )
                        }
                    }
                    "DOWN" -> {
                        when {
                            currentIndex == -1 -> currentFeatureState
                            currentIndex == 0 -> {
                                val restored = currentFeatureState.preNavDrafts[sessionId] ?: ""
                                currentFeatureState.copy(
                                    historyNavIndex = currentFeatureState.historyNavIndex - sessionId,
                                    draftInputs = if (restored.isEmpty())
                                        currentFeatureState.draftInputs - sessionId
                                    else
                                        currentFeatureState.draftInputs + (sessionId to restored),
                                    preNavDrafts = currentFeatureState.preNavDrafts - sessionId
                                )
                            }
                            else -> {
                                val newIndex = currentIndex - 1
                                currentFeatureState.copy(
                                    historyNavIndex = currentFeatureState.historyNavIndex + (sessionId to newIndex),
                                    draftInputs = currentFeatureState.draftInputs + (sessionId to history[newIndex])
                                )
                            }
                        }
                    }
                    else -> currentFeatureState
                }
            }

            ActionRegistry.Names.SESSION_INPUT_HISTORIES_LOADED -> {
                val uuid = payload?.get("uuid")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val draft = payload["draft"]?.jsonPrimitive?.contentOrNull ?: ""
                val history = payload["history"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()

                val session = currentFeatureState.sessions.values.find { it.identity.uuid == uuid }
                if (session != null) {
                    val localHandle = session.identity.localHandle
                    currentFeatureState.copy(
                        draftInputs = currentFeatureState.draftInputs + (localHandle to draft),
                        inputHistories = currentFeatureState.inputHistories + (localHandle to history)
                    )
                } else {
                    val inputState = SessionInputState(draft = draft, history = history)
                    currentFeatureState.copy(
                        pendingInputLoads = currentFeatureState.pendingInputLoads + (uuid to inputState)
                    )
                }
            }

            ActionRegistry.Names.SESSION_SET_ACTIVE_TAB -> {
                val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val localHandle = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                currentFeatureState.copy(
                    activeSessionLocalHandle = localHandle,
                    // Clear workspace preview when switching sessions
                    selectedWorkspaceFile = null,
                    workspaceFilePreview = null
                )
            }
            ActionRegistry.Names.SESSION_SET_EDITING_SESSION_NAME -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetEditingSessionPayload>(it) } ?: return currentFeatureState
                currentFeatureState.copy(editingSessionLocalHandle = decoded.sessionId)
            }
            ActionRegistry.Names.SESSION_SET_EDITING_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetEditingMessagePayload>(it) } ?: return currentFeatureState
                val messageId = decoded.messageId
                if (messageId != null) {
                    val entry = currentFeatureState.sessions.values.flatMap { it.ledger }.find { it.id == messageId }
                    if (entry?.isLocked == true) return currentFeatureState
                    currentFeatureState.copy(editingMessageId = messageId, editingMessageContent = entry?.rawContent)
                } else {
                    currentFeatureState.copy(editingMessageId = null, editingMessageContent = null)
                }
            }
            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return currentFeatureState
                val session = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val current = session.messageUiState[decoded.messageId] ?: MessageUiState()
                val updated = session.copy(messageUiState = session.messageUiState + (decoded.messageId to current.copy(isCollapsed = !current.isCollapsed)))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updated))
            }
            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return currentFeatureState
                val session = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val current = session.messageUiState[decoded.messageId] ?: MessageUiState()
                val updated = session.copy(messageUiState = session.messageUiState + (decoded.messageId to current.copy(isRawView = !current.isRawView)))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updated))
            }
            ActionRegistry.Names.SESSION_LOADED -> {
                val loaded = payload?.let { json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) } ?: return currentFeatureState
                val merged = currentFeatureState.sessions + loaded.sessions
                val normalized = SessionState.normalizeOrderIndices(merged)
                val newActiveLocalHandle = currentFeatureState.activeSessionLocalHandle
                    ?: normalized.values
                        .filter { !it.isHidden }
                        .maxByOrNull { it.createdAt }
                        ?.identity?.localHandle

                var newDraftInputs = currentFeatureState.draftInputs
                var newInputHistories = currentFeatureState.inputHistories
                var newPendingInputLoads = currentFeatureState.pendingInputLoads

                val newLocalHandles = normalized.keys - currentFeatureState.sessions.keys
                newLocalHandles.forEach { localHandle ->
                    val session = normalized[localHandle] ?: return@forEach
                    val uuid = session.identity.uuid ?: return@forEach
                    val pending = newPendingInputLoads[uuid] ?: return@forEach
                    if (pending.draft.isNotEmpty()) newDraftInputs = newDraftInputs + (localHandle to pending.draft)
                    if (pending.history.isNotEmpty()) newInputHistories = newInputHistories + (localHandle to pending.history)
                    newPendingInputLoads = newPendingInputLoads - uuid
                }

                currentFeatureState.copy(
                    sessions = normalized,
                    activeSessionLocalHandle = newActiveLocalHandle,
                    sessionOrder = SessionState.deriveSessionOrder(normalized),
                    draftInputs = newDraftInputs,
                    inputHistories = newInputHistories,
                    pendingInputLoads = newPendingInputLoads
                )
            }
            ActionRegistry.Names.SESSION_REORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ReorderPayload>(it) } ?: return currentFeatureState
                val currentOrder = currentFeatureState.sessionOrder.toMutableList()
                val currentIndex = currentOrder.indexOf(decoded.sessionId)
                if (currentIndex == -1) return currentFeatureState
                currentOrder.removeAt(currentIndex)
                val clampedIndex = decoded.toIndex.coerceIn(0, currentOrder.size)
                currentOrder.add(clampedIndex, decoded.sessionId)
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                currentOrder.forEachIndexed { index, localHandle ->
                    updatedSessions[localHandle]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[localHandle] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = currentOrder)
            }

            ActionRegistry.Names.SESSION_SET_ORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetOrderPayload>(it) } ?: return currentFeatureState
                val suppliedSet = decoded.order.toSet()
                val remainder = currentFeatureState.sessionOrder.filter { it !in suppliedSet }
                val fullOrder = decoded.order + remainder
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                fullOrder.forEachIndexed { index, localHandle ->
                    updatedSessions[localHandle]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[localHandle] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = fullOrder)
            }

            // --- Agent Discovery (from agent.AGENT_NAMES_UPDATED broadcast) ---
            ActionRegistry.Names.AGENT_AGENT_NAMES_UPDATED -> {
                val agentsArray = payload?.get("agents")?.jsonArray ?: return currentFeatureState
                val names = mutableMapOf<String, String>()
                val subscriptions = mutableMapOf<String, Set<String>>()
                agentsArray.forEach { element ->
                    val obj = element.jsonObject
                    val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val sessionIds = obj["subscribedSessionIds"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.toSet() ?: emptySet()
                    names[uuid] = name
                    subscriptions[uuid] = sessionIds
                }
                currentFeatureState.copy(
                    knownAgentNames = names,
                    knownAgentSubscriptions = subscriptions
                )
            }

            // --- Settings Hydration ---

            ActionRegistry.Names.SETTINGS_LOADED -> {
                val viewerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val managerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                currentFeatureState.copy(
                    hideHiddenInViewer = viewerSetting ?: currentFeatureState.hideHiddenInViewer,
                    hideHiddenInManager = managerSetting ?: currentFeatureState.hideHiddenInManager
                )
            }

            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val key = payload?.get("key")?.jsonPrimitive?.content ?: return currentFeatureState
                val value = payload["value"]?.jsonPrimitive?.content ?: return currentFeatureState
                when (key) {
                    SessionState.SETTING_HIDE_HIDDEN_VIEWER ->
                        currentFeatureState.copy(hideHiddenInViewer = value.toBooleanStrictOrNull() ?: currentFeatureState.hideHiddenInViewer)
                    SessionState.SETTING_HIDE_HIDDEN_MANAGER ->
                        currentFeatureState.copy(hideHiddenInManager = value.toBooleanStrictOrNull() ?: currentFeatureState.hideHiddenInManager)
                    else -> currentFeatureState
                }
            }

            ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val localHandle = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[localHandle] ?: return currentFeatureState
                val updatedSession = session.copy(isHidden = !session.isHidden)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (localHandle to updatedSession))
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageLockedPayload>(it) } ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) it.copy(isLocked = !it.isLocked) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }

            ActionRegistry.Names.SESSION_CLEAR -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val localHandle = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState
                val survivingLedger = targetSession.ledger.filter { it.isLocked || it.doNotClear }
                val survivingIds = survivingLedger.map { it.id }.toSet()
                val updatedSession = targetSession.copy(
                    ledger = survivingLedger,
                    messageUiState = targetSession.messageUiState.filterKeys { it in survivingIds }
                )
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (localHandle to updatedSession))
            }

            // ── Workspace pane reducer cases ──────────────────────────────

            ActionRegistry.Names.SESSION_TOGGLE_WORKSPACE_PANE -> {
                val isOpen = !currentFeatureState.isWorkspacePaneOpen
                if (isOpen) {
                    currentFeatureState.copy(isWorkspacePaneOpen = true)
                } else {
                    currentFeatureState.copy(
                        isWorkspacePaneOpen = false,
                        selectedWorkspaceFile = null,
                        workspaceFilePreview = null
                    )
                }
            }

            ActionRegistry.Names.SESSION_REFRESH_WORKSPACE -> {
                // Side-effect-only — listing arrives via WORKSPACE_FILES_LOADED
                currentFeatureState
            }

            ActionRegistry.Names.SESSION_WORKSPACE_FILES_LOADED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<WorkspaceFilesLoadedPayload>(it) }
                    ?: return currentFeatureState
                currentFeatureState.copy(
                    workspaceFiles = currentFeatureState.workspaceFiles +
                            (decoded.sessionLocalHandle to decoded.files)
                )
            }

            ActionRegistry.Names.SESSION_SELECT_WORKSPACE_FILE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<WorkspaceFilePayload>(it) }
                    ?: return currentFeatureState
                val fileName = decoded.fileName
                if (fileName != null) {
                    currentFeatureState.copy(
                        selectedWorkspaceFile = fileName,
                        workspaceFilePreview = null
                    )
                } else {
                    currentFeatureState.copy(
                        selectedWorkspaceFile = null,
                        workspaceFilePreview = null
                    )
                }
            }

            ActionRegistry.Names.SESSION_WORKSPACE_FILE_CONTENT_LOADED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<WorkspaceFileContentPayload>(it) }
                    ?: return currentFeatureState
                if (decoded.fileName == currentFeatureState.selectedWorkspaceFile) {
                    currentFeatureState.copy(workspaceFilePreview = decoded.content)
                } else {
                    currentFeatureState
                }
            }

            ActionRegistry.Names.SESSION_DELETE_WORKSPACE_FILE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<WorkspaceFilePayload>(it) }
                    ?: return currentFeatureState
                val fileName = decoded.fileName ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState)
                    ?: return currentFeatureState

                val currentFiles = currentFeatureState.workspaceFiles[localHandle] ?: emptyList()
                val updatedFiles = currentFiles.filter {
                    platformDependencies.getFileName(it.path) != fileName
                }

                val clearPreview = currentFeatureState.selectedWorkspaceFile == fileName

                currentFeatureState.copy(
                    workspaceFiles = currentFeatureState.workspaceFiles + (localHandle to updatedFiles),
                    selectedWorkspaceFile = if (clearPreview) null else currentFeatureState.selectedWorkspaceFile,
                    workspaceFilePreview = if (clearPreview) null else currentFeatureState.workspaceFilePreview
                )
            }

            else -> currentFeatureState
        }

        if (action.name != ActionRegistry.Names.SESSION_DELETE && newState.lastDeletedSessionLocalHandle != null) {
            newState = newState.copy(lastDeletedSessionLocalHandle = null)
        }

        return newState
    }


    override val composableProvider = object : Feature.ComposableProvider {
        private val VIEW_KEY_MAIN = "feature.session.main"
        private val VIEW_KEY_MANAGER = "feature.session.manager"
        override val stageViews: Map<String, @Composable (Store, List<Feature>) -> Unit> = mapOf(
            VIEW_KEY_MAIN to { store, features -> SessionView(store, features, platformDependencies) },
            VIEW_KEY_MANAGER to { store, _ -> SessionsManagerView(store, platformDependencies) }
        )
        @Composable override fun RibbonContent(store: Store, activeViewKey: String?) {
            IconButton(onClick = { store.dispatch("session", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MANAGER) })) }) {
                Icon(Icons.AutoMirrored.Filled.ViewList, "Session Manager", tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { store.dispatch("session", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MAIN) })) }) {
                Icon(Icons.Default.ChatBubble, "Active Session", tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}