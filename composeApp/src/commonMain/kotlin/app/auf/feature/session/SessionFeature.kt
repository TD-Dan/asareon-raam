package app.auf.feature.session

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.core.generated.ActionRegistry
import app.auf.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class SessionFeature(
    private val platformDependencies: PlatformDependencies,
    private val coroutineScope: CoroutineScope
) : Feature {
    override val identity: Identity = Identity(uuid = null, handle = "session", localHandle = "session", name="Session Manager")

    // --- Private, serializable data classes for decoding action payloads safely. ---
    @Serializable private data class CreatePayload(val name: String? = null, val isHidden: Boolean = false, val isAgentPrivate: Boolean = false)
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
    @Serializable private data class IdentityNamesUpdatedPayload(val names: Map<String, String>)
    @Serializable private data class AgentDeletedPayload(val agentId: String)
    @Serializable private data class RequestLedgerPayload(val sessionId: String, val correlationId: String)
    @Serializable private data class GatewayMessage(val role: String, val content: String, val senderId: String, val senderName: String)
    @Serializable private data class ReorderPayload(val sessionId: String, val toIndex: Int)
    @Serializable private data class SetOrderPayload(val order: List<String>)
    @Serializable private data class ToggleMessageLockedPayload(val sessionId: String, val messageId: String)

    // --- SLICE 4: New payload types for agent-facing message targeting ---
    @Serializable private data class LockMessagePayload(val session: String, val senderId: String, val timestamp: String)
    @Serializable private data class DeleteMessageExtPayload(val session: String, val messageId: String? = null, val senderId: String? = null, val timestamp: String? = null)

    private val blockParser = BlockSeparatingParser()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

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
            ActionRegistry.Names.FILESYSTEM_RESPONSE_LIST -> {
                val data = action.payload ?: return
                val fileList = data["listing"]?.jsonArray?.map { json.decodeFromJsonElement<FileEntry>(it) } ?: return
                fileList.filter { it.path.endsWith(".json") }.forEach {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_READ, buildJsonObject { put("subpath", platformDependencies.getFileName(it.path)) }))
                }
            }
            ActionRegistry.Names.FILESYSTEM_RESPONSE_READ -> {
                val data = action.payload ?: return
                try {
                    val content = data["content"]?.jsonPrimitive?.content ?: ""
                    if (content.isBlank()) {
                        platformDependencies.log(LogLevel.WARN, identity.handle, "Received empty session file content for ${data["subpath"]}")
                        return
                    }
                    val session = json.decodeFromString<Session>(content)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_INTERNAL_LOADED, Json.encodeToJsonElement(InternalSessionLoadedPayload(mapOf(session.id to session))) as JsonObject))
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "Failed to parse session file: ${data["subpath"]}. Error: ${e.message}")
                }
            }
            ActionRegistry.Names.SYSTEM_PUBLISH_STARTING -> {
                store.dispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_LIST))
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

            ActionRegistry.Names.SESSION_CREATE, ActionRegistry.Names.SESSION_CLONE -> {
                sessionState.activeSessionId?.let { persistSession(it, sessionState, store) }
                broadcastSessionNames(sessionState, store)
                // Register identity for the newly created session
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                val newSessionIds = sessionState.sessions.keys - prevSessions.keys
                newSessionIds.forEach { id ->
                    val session = sessionState.sessions[id] ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("localHandle", session.id)
                            put("name", session.name)
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_CONFIG -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "UPDATE_CONFIG") ?: return

                persistSession(sessionId, sessionState, store)
                broadcastSessionNames(sessionState, store)
                // If name changed, re-register identity with updated name.
                // REGISTER_IDENTITY deduplicates by handle, so re-registering with the same
                // localHandle updates the name. (This relies on the localHandle being stable.)
                val prevSession = (previousState as? SessionState)?.sessions?.get(sessionId)
                val newSession = sessionState.sessions[sessionId]
                if (prevSession != null && newSession != null && prevSession.name != newSession.name) {
                    // Unregister old, re-register with new name
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject { put("handle", "session.$sessionId") }
                    ))
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("localHandle", sessionId)
                            put("name", newSession.name)
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_DELETE -> {
                val sessionIdToDelete = sessionState.lastDeletedSessionId
                if (sessionIdToDelete != null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.FILESYSTEM_SYSTEM_DELETE, buildJsonObject { put("subpath", "$sessionIdToDelete.json") }))
                    broadcastSessionNames(sessionState, store)
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_DELETED, buildJsonObject { put("sessionId", sessionIdToDelete) }))
                    // Unregister session identity (cascades any children)
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_UNREGISTER_IDENTITY,
                        buildJsonObject {
                            put("handle", "session.$sessionIdToDelete")
                        }
                    ))
                } else {
                    val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                    platformDependencies.log(LogLevel.WARN, identity.handle, "SESSION_DELETE ignored: Session '$identifier' not found in state.")
                }
            }

            ActionRegistry.Names.SESSION_INTERNAL_LOADED -> {
                broadcastSessionNames(sessionState, store)
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                sessionState.sessions.forEach { (id, session) ->
                    if (prevSessions[id]?.orderIndex != session.orderIndex) {
                        persistSession(id, sessionState, store)
                    }
                }
                // Register identities for newly loaded sessions
                val newSessionIds = sessionState.sessions.keys - prevSessions.keys
                newSessionIds.forEach { id ->
                    val session = sessionState.sessions[id] ?: return@forEach
                    store.deferredDispatch(identity.handle, Action(
                        ActionRegistry.Names.CORE_REGISTER_IDENTITY,
                        buildJsonObject {
                            put("localHandle", session.id)
                            put("name", session.name)
                        }
                    ))
                }
            }

            ActionRegistry.Names.SESSION_POST -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "POST") ?: return

                persistSession(sessionId, sessionState, store)
                val updatedSession = sessionState.sessions[sessionId] ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val postedEntry = if (messageId != null) {
                    updatedSession.ledger.find { it.id == messageId }
                } else {
                    updatedSession.ledger.lastOrNull()
                }

                if (postedEntry != null) {
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_POSTED, buildJsonObject {
                        put("sessionId", sessionId)
                        put("entry", json.encodeToJsonElement(postedEntry))
                    }))
                    store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
                } else {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "SESSION_POST failed: Ledger entry not found after reducer update.")
                }
            }

            ActionRegistry.Names.SESSION_UPDATE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "UPDATE_MESSAGE") ?: return
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull ?: return
                val prevSessionState = previousState as? SessionState
                if (isMessageLockedGuard(sessionId, messageId, "UPDATE_MESSAGE", prevSessionState ?: sessionState, store)) return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_COLLAPSED, ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val identifier = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull

                val sessionId = requireSessionId(identifier, sessionState, action.name) ?: return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionRegistry.Names.SESSION_DELETE_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "DELETE_MESSAGE") ?: return

                // --- SLICE 4 CHANGE: Support both messageId (internal) and senderId+timestamp (agent-facing) ---
                val messageId = action.payload?.get("messageId")?.jsonPrimitive?.contentOrNull
                val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull
                val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull

                val resolvedMessageId: String? = if (messageId != null) {
                    messageId
                } else if (targetSenderId != null && targetTimestamp != null) {
                    // Agent-facing path: resolve via senderId + timestamp
                    val prevState = previousState as? SessionState ?: sessionState
                    val session = prevState.sessions[sessionId]
                    if (session != null) {
                        val result = MessageResolution.resolve(session.ledger, targetSenderId, targetTimestamp, platformDependencies)
                        if (result.entry != null) {
                            result.entry.id
                        } else {
                            // Post error feedback — the action originator is likely an agent
                            postResolutionError(sessionId, result.errorMessage ?: "Message not found.", action.originator, store)
                            null
                        }
                    } else null
                } else {
                    platformDependencies.log(LogLevel.ERROR, identity.handle, "DELETE_MESSAGE failed: Neither messageId nor senderId+timestamp provided.")
                    null
                }

                if (resolvedMessageId == null) return

                val prevSessionStateForDelete = previousState as? SessionState
                if (isMessageLockedGuard(sessionId, resolvedMessageId, "DELETE_MESSAGE", prevSessionStateForDelete ?: sessionState, store)) return

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_MESSAGE_DELETED, buildJsonObject {
                    put("sessionId", sessionId)
                    put("messageId", resolvedMessageId)
                }))
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
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
                val sessionId = requireSessionId(identifier, sessionState, "TOGGLE_SESSION_HIDDEN") ?: return
                persistSession(sessionId, sessionState, store)
            }

            ActionRegistry.Names.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val sessionId = action.payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val resolvedId = requireSessionId(sessionId, sessionState, "TOGGLE_MESSAGE_LOCKED") ?: return
                persistSession(resolvedId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionRegistry.Names.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", resolvedId) }))
            }

            ActionNames.SESSION_CLEAR -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, "CLEAR") ?: return
                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
            }

            ActionNames.SESSION_SET_ORDER, ActionNames.SESSION_REORDER -> {
                val prevSessions = (previousState as? SessionState)?.sessions ?: emptyMap()
                sessionState.sessions.forEach { (id, session) ->
                    if (prevSessions[id]?.orderIndex != session.orderIndex) {
                        persistSession(id, sessionState, store)
                    }
                }
            }

            // --- SLICE 4: LIST_SESSIONS handler ---
            ActionNames.SESSION_LIST_SESSIONS -> {
                val sessions = sessionState.sessions.values
                    .filter { !it.isHidden }
                    .map { "• ${it.name} (id: ${it.id})" }
                    .joinToString("\n")

                val responseMessage = "**Available Sessions:**\n$sessions"

                // The response is posted to the originating session.
                // CommandBot injects the session ID in the payload before dispatching,
                // or the agent can consume the response from the same session.
                val responseSessionId = action.payload?.get("responseSession")?.jsonPrimitive?.contentOrNull
                    ?: action.payload?.get("session")?.jsonPrimitive?.contentOrNull

                if (responseSessionId != null) {
                    store.deferredDispatch(identity.handle, Action(ActionNames.SESSION_POST, buildJsonObject {
                        put("session", responseSessionId)
                        put("senderId", "system")
                        put("message", responseMessage)
                    }))
                } else {
                    platformDependencies.log(LogLevel.WARN, identity.handle, "LIST_SESSIONS: No response session specified.")
                }
            }

            // --- SLICE 4: LOCK_MESSAGE / UNLOCK_MESSAGE handlers ---
            ActionNames.SESSION_LOCK_MESSAGE, ActionNames.SESSION_UNLOCK_MESSAGE -> {
                val identifier = action.payload?.get("session")?.jsonPrimitive?.contentOrNull
                val sessionId = requireSessionId(identifier, sessionState, action.name) ?: return

                // Check if the reducer flagged a resolution error
                val prevState = previousState as? SessionState
                val prevSession = prevState?.sessions?.get(sessionId)
                val newSession = sessionState.sessions[sessionId]

                // If the ledger didn't change, the reducer couldn't find the target → post error
                if (prevSession?.ledger == newSession?.ledger && prevSession != null) {
                    val targetSenderId = action.payload?.get("senderId")?.jsonPrimitive?.contentOrNull ?: ""
                    val targetTimestamp = action.payload?.get("timestamp")?.jsonPrimitive?.contentOrNull ?: ""
                    val result = MessageResolution.resolve(
                        prevSession.ledger, targetSenderId, targetTimestamp, platformDependencies
                    )
                    if (result.entry == null && result.errorMessage != null) {
                        postResolutionError(sessionId, result.errorMessage, action.originator, store)
                    }
                    return
                }

                persistSession(sessionId, sessionState, store)
                store.deferredDispatch(identity.handle, Action(ActionNames.SESSION_PUBLISH_SESSION_UPDATED, buildJsonObject { put("sessionId", sessionId) }))
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
    private fun postResolutionError(sessionId: String, error: String, originator: String?, store: Store) {
        val formattedError = "```text\n[SESSION] Message resolution failed:\n$error\n```"
        store.deferredDispatch(identity.handle, Action(ActionNames.SESSION_POST, buildJsonObject {
            put("session", sessionId)
            put("senderId", "system")
            put("message", formattedError)
        }))
    }

    private fun resolveSessionIdFromGenericPayload(payload: JsonObject?, state: SessionState): String? {
        val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return null
        return resolveSessionId(identifier, state)
    }

    private fun persistSession(sessionId: String, sessionState: SessionState, store: Store) {
        val sessionToSave = sessionState.sessions[sessionId] ?: return
        val persistedSession = sessionToSave.copy(
            ledger = sessionToSave.ledger.filterNot {
                it.metadata?.get("is_transient")?.jsonPrimitive?.booleanOrNull ?: false
            }
        )
        store.deferredDispatch(identity.handle, Action(ActionNames.FILESYSTEM_SYSTEM_WRITE, buildJsonObject {
            put("subpath", "${persistedSession.id}.json"); put("content", json.encodeToString(persistedSession))
        }))
    }

    private fun broadcastSessionNames(state: SessionState, store: Store) {
        store.deferredDispatch(identity.handle, Action(ActionNames.SESSION_PUBLISH_SESSION_NAMES_UPDATED, buildJsonObject {
            put("names", Json.encodeToJsonElement(state.sessions.mapValues { it.value.name }))
        }))
    }

    private fun resolveSessionId(identifier: String, state: SessionState): String? {
        if (state.sessions.containsKey(identifier)) return identifier
        return state.sessions.values.singleOrNull { it.name == identifier }?.id
    }

    private fun findUniqueName(desiredName: String, state: SessionState): String {
        val existingNames = state.sessions.values.map { it.name }.toSet()
        if (desiredName !in existingNames) return desiredName
        var n = 2; var newName: String; do { newName = "$desiredName-$n"; n++ } while (newName in existingNames)
        return newName
    }

    private fun isMessageLockedGuard(
        sessionId: String,
        messageId: String,
        actionContext: String,
        state: SessionState,
        store: Store
    ): Boolean {
        val session = state.sessions[sessionId] ?: return false
        val entry = session.ledger.find { it.id == messageId } ?: return false
        if (entry.isLocked) {
            platformDependencies.log(LogLevel.WARN, identity.handle, "$actionContext blocked: Message '$messageId' in session '$sessionId' is locked.")
            store.deferredDispatch(identity.handle, Action(ActionNames.CORE_SHOW_TOAST, buildJsonObject {
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
            ActionNames.CORE_PUBLISH_IDENTITIES_UPDATED -> {
                val identities = payload?.get("identities")?.jsonArray?.map { json.decodeFromJsonElement<Identity>(it) } ?: return currentFeatureState
                val userNames = identities.associate { it.handle to it.name }
                val agentNames = currentFeatureState.identityNames.filterKeys { it !in userNames.keys }
                currentFeatureState.copy(identityNames = agentNames + userNames)
            }
            ActionNames.AGENT_PUBLISH_AGENT_NAMES_UPDATED -> {
                val agentNames = payload?.let { json.decodeFromJsonElement<IdentityNamesUpdatedPayload>(it) }?.names ?: emptyMap()
                val userNames = currentFeatureState.identityNames.filterKeys { it !in agentNames.keys }
                currentFeatureState.copy(identityNames = userNames + agentNames)
            }
            ActionNames.AGENT_PUBLISH_AGENT_DELETED -> {
                val agentId = payload?.let { json.decodeFromJsonElement<AgentDeletedPayload>(it) }?.agentId ?: return currentFeatureState
                currentFeatureState.copy(identityNames = currentFeatureState.identityNames - agentId)
            }
            ActionNames.SESSION_CREATE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<CreatePayload>(it) } ?: CreatePayload()
                val desiredName = decoded.name?.takeIf { it.isNotBlank() } ?: "New Session"
                val newSession = Session(
                    id = platformDependencies.generateUUID(),
                    name = findUniqueName(desiredName, currentFeatureState),
                    ledger = emptyList(),
                    createdAt = platformDependencies.currentTimeMillis(),
                    isHidden = decoded.isHidden,
                    isAgentPrivate = decoded.isAgentPrivate,
                    orderIndex = 0
                )
                val updatedSessions = currentFeatureState.sessions + (newSession.id to newSession)
                currentFeatureState.copy(
                    sessions = updatedSessions,
                    activeSessionId = newSession.id,
                    sessionOrder = SessionState.deriveSessionOrder(updatedSessions)
                )
            }
            ActionNames.SESSION_CLONE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ClonePayload>(it) } ?: return currentFeatureState
                val sessionToClone = currentFeatureState.sessions[decoded.session] ?: return currentFeatureState
                val newName = findUniqueName("${sessionToClone.name} (Copy)", currentFeatureState)
                val newSession = sessionToClone.copy(
                    id = platformDependencies.generateUUID(),
                    name = newName,
                    createdAt = platformDependencies.currentTimeMillis(),
                    isHidden = false,
                    isAgentPrivate = false,
                    orderIndex = 0
                )
                val updatedSessions = currentFeatureState.sessions + (newSession.id to newSession)
                currentFeatureState.copy(
                    sessions = updatedSessions,
                    activeSessionId = newSession.id,
                    sessionOrder = SessionState.deriveSessionOrder(updatedSessions)
                )
            }
            ActionNames.SESSION_UPDATE_CONFIG -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateConfigPayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val updatedSession = session.copy(name = findUniqueName(decoded.name, currentFeatureState))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession), editingSessionId = null)
            }
            ActionNames.SESSION_POST -> {
                val decoded = payload?.let { json.decodeFromJsonElement<PostPayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
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
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }
            ActionNames.SESSION_DELETE -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: ""
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val newSessions = currentFeatureState.sessions - sessionId
                val newActiveId = if (currentFeatureState.activeSessionId != sessionId) currentFeatureState.activeSessionId else newSessions.values.maxByOrNull { it.createdAt }?.id
                currentFeatureState.copy(
                    sessions = newSessions,
                    activeSessionId = newActiveId,
                    lastDeletedSessionId = sessionId,
                    sessionOrder = SessionState.deriveSessionOrder(newSessions)
                )
            }
            ActionNames.SESSION_UPDATE_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<UpdateMessagePayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
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
                    sessions = currentFeatureState.sessions + (sessionId to updatedSession),
                    editingMessageId = null,
                    editingMessageContent = null
                )
            }
            ActionNames.SESSION_DELETE_MESSAGE -> {
                // --- SLICE 4 CHANGE: Support senderId+timestamp in addition to messageId ---
                val sessionIdentifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val sessionId = resolveSessionId(sessionIdentifier, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState

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
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            // --- SLICE 4: LOCK_MESSAGE / UNLOCK_MESSAGE reducer ---
            ActionNames.SESSION_LOCK_MESSAGE, ActionNames.SESSION_UNLOCK_MESSAGE -> {
                val decoded = payload?.let { json.decodeFromJsonElement<LockMessagePayload>(it) } ?: return currentFeatureState
                val sessionId = resolveSessionId(decoded.session, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState

                val targetLock = action.name == ActionNames.SESSION_LOCK_MESSAGE
                val result = MessageResolution.resolve(targetSession.ledger, decoded.senderId, decoded.timestamp, platformDependencies)

                if (result.entry == null) {
                    // Can't update state; handleSideEffects will detect unchanged ledger and post error
                    return currentFeatureState
                }

                val updatedLedger = targetSession.ledger.map {
                    if (it.id == result.entry.id) it.copy(isLocked = targetLock) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            // --- LIST_SESSIONS is handled purely in handleSideEffects (side-effect only, no state change) ---
            ActionNames.SESSION_LIST_SESSIONS -> currentFeatureState

            ActionNames.SESSION_SET_ACTIVE_TAB -> {
                val identifier = payload?.get("session")?.jsonPrimitive?.contentOrNull ?: return currentFeatureState
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                currentFeatureState.copy(activeSessionId = sessionId)
            }
            ActionNames.SESSION_SET_EDITING_SESSION_NAME -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetEditingSessionPayload>(it) } ?: return currentFeatureState
                currentFeatureState.copy(editingSessionId = decoded.sessionId)
            }
            ActionNames.SESSION_SET_EDITING_MESSAGE -> {
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
            ActionNames.SESSION_TOGGLE_MESSAGE_COLLAPSED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return currentFeatureState
                val session = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val current = session.messageUiState[decoded.messageId] ?: MessageUiState()
                val updated = session.copy(messageUiState = session.messageUiState + (decoded.messageId to current.copy(isCollapsed = !current.isCollapsed)))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updated))
            }
            ActionNames.SESSION_TOGGLE_MESSAGE_RAW_VIEW -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageUiPayload>(it) } ?: return currentFeatureState
                val session = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val current = session.messageUiState[decoded.messageId] ?: MessageUiState()
                val updated = session.copy(messageUiState = session.messageUiState + (decoded.messageId to current.copy(isRawView = !current.isRawView)))
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updated))
            }
            ActionNames.SESSION_INTERNAL_LOADED -> {
                val loaded = payload?.let { json.decodeFromJsonElement<InternalSessionLoadedPayload>(it) } ?: return currentFeatureState
                val merged = currentFeatureState.sessions + loaded.sessions
                val normalized = SessionState.normalizeOrderIndices(merged)
                val newActiveId = currentFeatureState.activeSessionId ?: normalized.values.maxByOrNull { it.createdAt }?.id
                currentFeatureState.copy(
                    sessions = normalized,
                    activeSessionId = newActiveId,
                    sessionOrder = SessionState.deriveSessionOrder(normalized)
                )
            }
            ActionNames.SESSION_REORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ReorderPayload>(it) } ?: return currentFeatureState
                val currentOrder = currentFeatureState.sessionOrder.toMutableList()
                val currentIndex = currentOrder.indexOf(decoded.sessionId)
                if (currentIndex == -1) return currentFeatureState
                currentOrder.removeAt(currentIndex)
                val clampedIndex = decoded.toIndex.coerceIn(0, currentOrder.size)
                currentOrder.add(clampedIndex, decoded.sessionId)
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                currentOrder.forEachIndexed { index, id ->
                    updatedSessions[id]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[id] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = currentOrder)
            }

            ActionNames.SESSION_SET_ORDER -> {
                val decoded = payload?.let { json.decodeFromJsonElement<SetOrderPayload>(it) } ?: return currentFeatureState
                val suppliedSet = decoded.order.toSet()
                val remainder = currentFeatureState.sessionOrder.filter { it !in suppliedSet }
                val fullOrder = decoded.order + remainder
                val updatedSessions = currentFeatureState.sessions.toMutableMap()
                fullOrder.forEachIndexed { index, id ->
                    updatedSessions[id]?.let { session ->
                        if (session.orderIndex != index) {
                            updatedSessions[id] = session.copy(orderIndex = index)
                        }
                    }
                }
                currentFeatureState.copy(sessions = updatedSessions.toMap(), sessionOrder = fullOrder)
            }

            // --- Settings Hydration ---

            ActionNames.SETTINGS_PUBLISH_LOADED -> {
                val viewerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_VIEWER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val managerSetting = payload?.get(SessionState.SETTING_HIDE_HIDDEN_MANAGER)
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                currentFeatureState.copy(
                    hideHiddenInViewer = viewerSetting ?: currentFeatureState.hideHiddenInViewer,
                    hideHiddenInManager = managerSetting ?: currentFeatureState.hideHiddenInManager
                )
            }

            ActionNames.SETTINGS_PUBLISH_VALUE_CHANGED -> {
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

            ActionNames.SESSION_TOGGLE_SESSION_HIDDEN -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val session = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val updatedSession = session.copy(isHidden = !session.isHidden)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            ActionNames.SESSION_TOGGLE_MESSAGE_LOCKED -> {
                val decoded = payload?.let { json.decodeFromJsonElement<ToggleMessageLockedPayload>(it) } ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[decoded.sessionId] ?: return currentFeatureState
                val updatedLedger = targetSession.ledger.map {
                    if (it.id == decoded.messageId) it.copy(isLocked = !it.isLocked) else it
                }
                val updatedSession = targetSession.copy(ledger = updatedLedger)
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (decoded.sessionId to updatedSession))
            }

            ActionNames.SESSION_CLEAR -> {
                val identifier = payload?.let { json.decodeFromJsonElement<SessionTargetPayload>(it) }?.session ?: return currentFeatureState
                val sessionId = resolveSessionId(identifier, currentFeatureState) ?: return currentFeatureState
                val targetSession = currentFeatureState.sessions[sessionId] ?: return currentFeatureState
                val survivingLedger = targetSession.ledger.filter { it.isLocked || it.doNotClear }
                val survivingIds = survivingLedger.map { it.id }.toSet()
                val updatedSession = targetSession.copy(
                    ledger = survivingLedger,
                    messageUiState = targetSession.messageUiState.filterKeys { it in survivingIds }
                )
                currentFeatureState.copy(sessions = currentFeatureState.sessions + (sessionId to updatedSession))
            }

            else -> currentFeatureState
        }

        if (action.name != ActionNames.SESSION_DELETE && newState.lastDeletedSessionId != null) {
            newState = newState.copy(lastDeletedSessionId = null)
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
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MANAGER) })) }) {
                Icon(Icons.AutoMirrored.Filled.ViewList, "Session Manager", tint = if (activeViewKey == VIEW_KEY_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { store.dispatch("session.ui", Action(ActionNames.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", VIEW_KEY_MAIN) })) }) {
                Icon(Icons.Default.ChatBubble, "Active Session", tint = if (activeViewKey == VIEW_KEY_MAIN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}