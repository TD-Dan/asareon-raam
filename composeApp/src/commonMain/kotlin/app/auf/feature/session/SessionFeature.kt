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
    @Serializable private data class CreatePayload(val name: String? = null, val isHidden: Boolean = false, val isPrivate: Boolean = false)
    @Serializable private data class ClonePayload(val session: String)
    @Serializable private data class UpdateConfigPayload(val session: String, val name: String)
    @Serializable private data class SessionTargetPayload(val session: String)
    @Serializable private data class PostPayload(val session: String, val senderId: String, val message: String? = null, val messageId: String? = null, val metadata: JsonObject? = null, val afterMessageId: String? = null, val doNotClear: Boolean = false)
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

    // --- SLICE 4: New payload types for agent-facing message targeting ---
    @Serializable private data class LockMessagePayload(val session: String, val senderId: String, val timestamp: String)
    @Serializable private data class DeleteMessageExtPayload(val session: String, val messageId: String? = null, val senderId: String? = null, val timestamp: String? = null)

    // --- Phase 4: Payload for RESPONSE_REGISTER_IDENTITY (from CoreFeature) ---
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

    // --- Phase 4: Payload for RESPONSE_UPDATE_IDENTITY (from CoreFeature) ---
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
            ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST -> {
                val data = action.payload ?: return
                val fileList = data["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return

                if (startupLoadingActive) pendingStartupOps--

                fileList.forEach { entry ->
                    if (entry.path.endsWith(".json")) {
                        // It's a JSON file inside a UUID folder — read it.
                        // Both localHandle.json and input.json are handled this way;
                        // FILESYSTEM_RESPONSE_READ routes them based on the filename.
                        if (startupLoadingActive) pendingStartupOps++
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", entry.path) }))
                    } else if (!entry.path.contains(".")) {
                        // Looks like a UUID folder — list its contents
                        if (startupLoadingActive) pendingStartupOps++
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST, buildJsonObject { put("subpath", platformDependencies.getFileName(entry.path)) }))
                    }
                }

                if (startupLoadingActive) checkStartupLoadComplete(store, sessionState)
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_READ -> {
                val data = action.payload ?: return
                val subpath = data["subpath"]?.jsonPrimitive?.content ?: ""
                val content = data["content"]?.jsonPrimitive?.content ?: ""

                // Route input.json files to the input-state handler — they are not Session objects.
                if (subpath.endsWith("/input.json")) {
                    if (content.isNotBlank()) handleInputJsonRead(subpath, content, store)
                    if (startupLoadingActive) {
                        pendingStartupOps--
                        checkStartupLoadComplete(store, sessionState)
                    }
                    return
                }

                try {
                    if (content.isBlank()) {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "Received empty session file content for $subpath")
                        if (startupLoadingActive) {
                            pendingStartupOps--
                            checkStartupLoadComplete(store, sessionState)
                        }
                        return
                    }
                    val session = json.decodeFromString<Session>(content)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_LOADED, Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.identity.localHandle to session))) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse session file: $subpath. Error: ${e.message}")
                    if (startupLoadingActive) {
                        pendingStartupOps--
                        checkStartupLoadComplete(store, sessionState)
                    }
                }
            }
            ActionRegistry.Names.SYSTEM_STARTING -> {
                startupLoadingActive = true
                pendingStartupOps = 1 // the root listing dispatched below
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST))
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
            ActionRegistry.Names.CORE_RESPONSE_REGISTER_IDENTITY -> {
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                val newLocalHandles = sessionState.sessions.keys - prevSessions.keys
                newLocalHandles.forEach { localHandle ->
                    persistSession(localHandle, sessionState, store)
                    // Create workspace folder
                    val session = sessionState.sessions[localHandle] ?: return@forEach
                    val uuid = session.identity.uuid ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE,
                        buildJsonObject {
                            put("subpath", "$uuid/workspace/.keep")
                            put("content", "")
                        }
                    ))
                }
                if (newLocalHandles.isNotEmpty()) {
                    broadcastSessionNames(sessionState, store)
                }
                // Signal readiness when all pending identity registrations have completed.
                // This covers runtime SESSION_CREATE and SESSION_CLONE only — disk-loaded
                // sessions are tracked by the startup loading counter (pendingStartupOps)
                // and fire SESSION_FEATURE_READY from checkStartupLoadComplete instead.
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
                val localHandle = requireSessionId(identifier, sessionState, "UPDATE_CONFIG") ?: return
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
            }

            // Phase 4: Side effects for RESPONSE_UPDATE_IDENTITY — file rename + persist
            ActionRegistry.Names.CORE_RESPONSE_UPDATE_IDENTITY -> {
                val resp = action.payload?.let { json.decodeFromJsonElement<UpdateIdentityResponsePayload>(it) }
                    ?: return
                if (!resp.success) return

                val oldLocalHandle = resp.oldLocalHandle ?: return
                val newLocalHandle = resp.newLocalHandle ?: return
                val uuid = resp.uuid ?: return

                if (oldLocalHandle != newLocalHandle) {
                    // Handle changed — delete old JSON file inside the UUID folder
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE,
                        buildJsonObject { put("subpath", "$uuid/$oldLocalHandle.json") }
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

                    // Delete the session folder (uuid-named)
                    if (uuid != null) {
                        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE, buildJsonObject { put("subpath", uuid) }))
                    }
                    broadcastSessionNames(sessionState, store)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_DELETED, buildJsonObject { put("sessionId", localHandleToDelete) }))
                    // Unregister session identity (cascades any children)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject {
                            put("handle", "session.$localHandleToDelete")
                        }
                    ))
                } else {
                    val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_DELETE ignored: Session '$identifier' not found in state.")
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
                // Cancel any existing debounce job and start a fresh 5-second countdown.
                // IMPORTANT: we read the CURRENT store state when the timer fires rather than
                // capturing sessionState here — this ensures we write the most recent draft
                // even if multiple changes arrived during the debounce window.
                // We also call store.dispatch directly (not deferredDispatch) because this
                // runs from a coroutine context, not from within the synchronous action
                // processing pipeline. deferredDispatch uses the store's own internal scope,
                // which is not the TestScope and therefore isn't controlled by testScheduler.
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
                        ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE,
                        buildJsonObject {
                            put("subpath", "$uuid/input.json")
                            put("content", json.encodeToString(inputState))
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_POST -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "POST") ?: return

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
                        put("entry", json.encodeToJsonElement(postedEntry))
                    }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
                } else {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "SESSION_POST failed: Ledger entry not found after reducer update.")
                }

                // For user posts: cancel the debounce and write input.json immediately
                // so the cleared draft and updated history are persisted without waiting.
                val activeUserId = (previousState as? SessionState)?.activeUserId ?: "user"
                val postedSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                if (postedSenderId == activeUserId) {
                    draftDebounceJobs[localHandle]?.cancel()
                    draftDebounceJobs.remove(localHandle)
                    persistInputState(localHandle, sessionState, store)
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "UPDATE_MESSAGE") ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                val prevSessionState = previousState as? SessionState
                if (isMessageLockedGuard(localHandle, messageId, "UPDATE_MESSAGE", prevSessionState ?: sessionState, store)) return

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val identifier = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull

                val localHandle = requireSessionId(identifier, sessionState, action.name) ?: return

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
            }

            ActionRegistry.Names.SESSION_DELETE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "DELETE_MESSAGE") ?: return

                // --- SLICE 4 CHANGE: Support both messageId (internal) and senderId+timestamp (agent-facing) ---
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull

                val resolvedMessageId: String? = if (messageId != null) {
                    messageId
                } else if (targetSenderId != null && targetTimestamp != null) {
                    // Agent-facing path: resolve via senderId + timestamp
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

                if (resolvedMessageId == null) return

                val prevSessionStateForDelete = previousState as? SessionState
                if (isMessageLockedGuard(localHandle, resolvedMessageId, "DELETE_MESSAGE", prevSessionStateForDelete ?: sessionState, store)) return

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_MESSAGE_DELETED, buildJsonObject {
                    put("sessionId", localHandle)
                    put("messageId", resolvedMessageId)
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
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

                val session = sessionState.sessions[payload.sessionId]
                if (session == null) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "REQUEST_LEDGER_CONTENT failed: Session '${payload.sessionId}' not found.")
                    return
                }

                val messages = session.ledger.map { json.encodeToJsonElement(it) }

                val responsePayload = buildJsonObject {
                    put("correlationId", payload.correlationId)
                    putJsonArray("messages") { messages.forEach { add(it) } }
                }
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.SESSION_RESPONSE_LEDGER,
                    payload = responsePayload,
                    targetRecipient = action.originator ?: "unknown"
                ))
            }

            ActionRegistry.Names.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "TOGGLE_SESSION_HIDDEN") ?: return
                persistSession(localHandle, sessionState, store)
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val resolvedId = requireSessionId(sessionId, sessionState, "TOGGLE_MESSAGE_LOCKED") ?: return
                persistSession(resolvedId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", resolvedId) }))
            }

            ActionRegistry.Names.SESSION_CLEAR -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, "CLEAR") ?: return
                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
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
                } else {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "LIST_SESSIONS: No response session specified.")
                }
            }

            // --- SLICE 4: LOCK_MESSAGE / UNLOCK_MESSAGE handlers ---
            ActionRegistry.Names.SESSION_LOCK_MESSAGE, ActionRegistry.Names.SESSION_UNLOCK_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val localHandle = requireSessionId(identifier, sessionState, action.name) ?: return

                // Check if the reducer flagged a resolution error
                val prevState = previousState as? SessionState
                val prevSession = prevState?.sessions?.get(localHandle)
                val newSession = sessionState.sessions[localHandle]

                // If the ledger didn't change, the reducer couldn't find the target → post error
                if (prevSession?.ledger == newSession?.ledger && prevSession != null) {
                    val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull ?: ""
                    val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull ?: ""
                    val result = MessageResolution.resolve(
                        prevSession.ledger, targetSenderId, targetTimestamp, platformDependencies
                    )
                    if (result.entry == null && result.errorMessage != null) {
                        postResolutionError(localHandle, result.errorMessage, action.originator, store)
                    }
                    return
                }

                persistSession(localHandle, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_UPDATED, buildJsonObject { put("sessionId", localHandle) }))
            }
        }
    }

    // ========================================================================
    // SLICE 4: Error feedback for message resolution failures
    // ========================================================================

    /**
     * Posts a resolution error message. If the action came from an agent (via CommandBot),
     * the error goes to the originating session so the agent can see it.
     */
    private fun postResolutionError(localHandle: String, error: String, originator: String?, store: Store) {
        val formattedError = "```text\n[SESSION] Message resolution failed:\n$error\n```"
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", localHandle)
            put("senderId", "system")
            put("message", formattedError)
        }))
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
        val subpath = "$uuid/${sessionToSave.identity.localHandle}.json"
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", subpath); put("content", json.encodeToString(persistedSession))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        val subscribableNames = state.sessions
            .filterValues { !it.isPrivate }
            .mapValues { it.value.identity.name }
        store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_SESSION_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(subscribableNames))
        }))
    }

    // Phase 4: Resolves by localHandle, full handle, or display name
    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        // Direct localHandle match (map key)
        if (state.sessions.containsKey(identifier)) return identifier
        // Match by full handle (e.g., "session.cats-chat")
        state.sessions.values.singleOrNull { it.identity.handle == identifier }
            ?.let { return it.identity.localHandle }
        // Match by display name
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

    /**
     * Writes the current draft and history for [localHandle] to {uuid}/input.json.
     * Uses [sessionState] (the post-reducer new state) so the data is always fresh.
     */
    private fun persistInputState(localHandle: String, sessionState: SessionState, store: Store) {
        val session = sessionState.sessions[localHandle] ?: return
        val uuid = session.identity.uuid ?: return
        val draft = sessionState.draftInputs[localHandle] ?: ""
        val history = sessionState.inputHistories[localHandle] ?: emptyList()
        val inputState = SessionInputState(draft = draft, history = history)
        store.deferredDispatch(identity.handle, Action(
            ActionRegistry.Names.FILESYSTEM_SYSTEM_WRITE,
            buildJsonObject {
                put("subpath", "$uuid/input.json")
                put("content", json.encodeToString(inputState))
            }
        ))
    }

    /**
     * Parses a {uuid}/input.json file and dispatches INPUT_HISTORIES_LOADED.
     * Logs a warning on parse failure without crashing.
     */
    private fun handleInputJsonRead(subpath: String, content: String, store: Store) {
        if (content.isBlank()) return
        // subpath is always "{uuid}/input.json" as a relative path inside the session folder,
        // so the UUID is always the second-to-last path segment.
        // substringBefore("/") would break on absolute paths (e.g. "/app/sessions/uuid/input.json")
        // where it would return "". Using the parent segment is robust against any prefix.
        val uuid = subpath.trimEnd('/').substringBeforeLast("/").substringAfterLast("/")
        if (uuid.isBlank()) {
            platformDependencies.log(LogLevel.WARN, identity.handle,
                "Cannot extract UUID from input.json subpath: $subpath — ignoring.")
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

            // Phase 4: SESSION_CREATE stashes a PendingSessionCreation — no session in the map yet
            ActionRegistry.Names.SESSION_CREATE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) } ?: CreatePayload()
                val desiredName = decoded.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val uniqueName = findUniqueName(desiredName, currentFeatureState)
                val uuid = platformDependencies.generateUUID()

                val pending = PendingSessionCreation(
                    uuid = uuid,
                    requestedName = uniqueName,
                    isHidden = decoded.isHidden,
                    isPrivate = decoded.isPrivate,
                    createdAt = platformDependencies.currentTimeMillis()
                )
                currentFeatureState.copy(
                    pendingCreations = currentFeatureState.pendingCreations + (uuid to pending)
                )
            }

            // Phase 4: SESSION_CLONE stashes a PendingSessionCreation with clone source
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
                    isPrivate = false,
                    createdAt = platformDependencies.currentTimeMillis(),
                    cloneSourceLocalHandle = sourceLocalHandle
                )
                currentFeatureState.copy(
                    pendingCreations = currentFeatureState.pendingCreations + (uuid to pending)
                )
            }

            // Phase 4: RESPONSE_REGISTER_IDENTITY — create session from pending or remove pending on failure
            ActionRegistry.Names.CORE_RESPONSE_REGISTER_IDENTITY -> {
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

                    // Build the session
                    val newSession = if (pending.cloneSourceLocalHandle != null) {
                        // CLONE: copy ledger from source
                        val source = currentFeatureState.sessions[pending.cloneSourceLocalHandle]
                        Session(
                            identity = approvedIdentity,
                            ledger = source?.ledger ?: emptyList(),
                            createdAt = pending.createdAt,
                            messageUiState = source?.messageUiState ?: emptyMap(),
                            isHidden = pending.isHidden,
                            isPrivate = pending.isPrivate,
                            orderIndex = 0
                        )
                    } else {
                        // CREATE: fresh session
                        Session(
                            identity = approvedIdentity,
                            ledger = emptyList(),
                            createdAt = pending.createdAt,
                            isHidden = pending.isHidden,
                            isPrivate = pending.isPrivate,
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
                    // Registration failed — remove pending
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

            // Phase 4: RESPONSE_UPDATE_IDENTITY — reconcile handle change
            ActionRegistry.Names.CORE_RESPONSE_UPDATE_IDENTITY -> {
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
                    // Handle changed — re-key in the map
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
                    // Handle didn't change (rare — name changed but slug stayed the same)
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

                // Optimistically update the session's identity name
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
                val newEntry = LedgerEntry(
                    id = decoded.messageId ?: platformDependencies.generateUUID(),
                    timestamp = platformDependencies.currentTimeMillis(),
                    senderId = decoded.senderId,
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

                // ── Draft / history management for user posts ──────────────────────
                val activeUserId = currentFeatureState.activeUserId ?: "user"
                val isUserPost = decoded.senderId == activeUserId
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
                        // Don't add a duplicate of the most recent entry
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
                // --- SLICE 4 CHANGE: Support senderId+timestamp in addition to messageId ---
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
                        result.entry?.id // null if not found; handleSideEffects handles the error feedback
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

            // --- SLICE 4: LOCK_MESSAGE / UNLOCK_MESSAGE reducer ---
            ActionRegistry.Names.SESSION_LOCK_MESSAGE, ActionRegistry.Names.SESSION_UNLOCK_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<LockMessagePayload>(it) } ?: return currentFeatureState
                val localHandle = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[localHandle] ?: return currentFeatureState

                val targetLock = action.name == ActionRegistry.Names.SESSION_LOCK_MESSAGE
                val result = MessageResolution.resolve(targetSession.ledger, decoded.senderId, decoded.timestamp, platformDependencies)

                if (result.entry == null) {
                    // Can't update state; handleSideEffects will detect unchanged ledger and post error
                    return currentFeatureState
                }

                val updatedLedger = targetSession.ledger.map {
                    if (it.id == result.entry.id) it.copy(isLocked = targetLock) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (localHandle to updatedSession))
            }

            // --- LIST_SESSIONS is handled purely in handleSideEffects (side-effect only, no state change) ---
            ActionRegistry.Names.SESSION_LIST_SESSIONS -> currentFeatureState

            // ── Input draft persistence ───────────────────────────────────────────
            ActionRegistry.Names.SESSION_INPUT_DRAFT_CHANGED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<InputDraftChangedPayload>(it) }
                    ?: return currentFeatureState
                if (!currentFeatureState.sessions.containsKey(decoded.sessionId)) return currentFeatureState
                currentFeatureState.copy(
                    draftInputs = currentFeatureState.draftInputs + (decoded.sessionId to decoded.draft)
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
                            // First UP: save the current draft, jump to history[0]
                            val savedDraft = currentFeatureState.draftInputs[sessionId] ?: ""
                            currentFeatureState.copy(
                                historyNavIndex = currentFeatureState.historyNavIndex + (sessionId to 0),
                                draftInputs = currentFeatureState.draftInputs + (sessionId to history[0]),
                                preNavDrafts = currentFeatureState.preNavDrafts + (sessionId to savedDraft)
                            )
                        } else {
                            // Subsequent UP: advance toward older entries, clamped at the last index
                            val newIndex = (currentIndex + 1).coerceAtMost(history.lastIndex)
                            currentFeatureState.copy(
                                historyNavIndex = currentFeatureState.historyNavIndex + (sessionId to newIndex),
                                draftInputs = currentFeatureState.draftInputs + (sessionId to history[newIndex])
                            )
                        }
                    }
                    "DOWN" -> {
                        when {
                            currentIndex == -1 -> currentFeatureState // not navigating — no-op
                            currentIndex == 0 -> {
                                // Back to live draft — restore preNavDraft and exit navigation mode
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
                    // Session already loaded — apply directly
                    val localHandle = session.identity.localHandle
                    currentFeatureState.copy(
                        draftInputs = currentFeatureState.draftInputs + (localHandle to draft),
                        inputHistories = currentFeatureState.inputHistories + (localHandle to history)
                    )
                } else {
                    // Session not yet loaded — buffer until SESSION_LOADED drains it
                    val inputState = SessionInputState(draft = draft, history = history)
                    currentFeatureState.copy(
                        pendingInputLoads = currentFeatureState.pendingInputLoads + (uuid to inputState)
                    )
                }
            }

            ActionRegistry.Names.SESSION_SET_ACTIVE_TAB -> {
                val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val localHandle = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                currentFeatureState.copy(activeSessionLocalHandle = localHandle)
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
                val newActiveLocalHandle = currentFeatureState.activeSessionLocalHandle ?: normalized.values.maxByOrNull { it.createdAt }?.identity?.localHandle

                // Drain pending input loads for sessions that just arrived.
                // This handles the startup race where input.json is read before localHandle.json.
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
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MANAGER) })) }) {
                Icon(Icons.AutoMirrored.Filled.ViewList, "Session Manager", tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MAIN) })) }) {
                Icon(Icons.Default.ChatBubble, "Active Session", tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}