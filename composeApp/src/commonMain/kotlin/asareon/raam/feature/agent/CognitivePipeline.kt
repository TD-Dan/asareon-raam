package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.core.Version
import app.auf.feature.agent.contextformatters.ActionsContextFormatter
import app.auf.feature.agent.contextformatters.HkgContextFormatter
import app.auf.feature.agent.contextformatters.SessionContextFormatter
import app.auf.feature.agent.contextformatters.SessionFilesContextFormatter
import app.auf.feature.agent.contextformatters.WorkspaceContextFormatter
import app.auf.feature.agent.ui.AgentAvatarLogic
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * ## Mandate
 * Orchestrates the "Think" phase of the Agent's lifecycle:
 * 1. Gathering Context (Ledger + HKG + Workspace)
 * 2. Formulating the Prompt (via Strategy)
 * 3. Processing the Response (via Strategy)
 *
 * Uses typed identity accessors throughout. All session references are [IdentityUUID];
 * handles are resolved from the identity registry at dispatch time for cross-feature actions.
 *
 * Strategy-specific context requests are dispatched polymorphically via
 * [CognitiveStrategy.requestAdditionalContext] and gated via
 * [CognitiveStrategy.needsAdditionalContext].
 */
object CognitivePipeline {

    private val json = Json { ignoreUnknownKeys = true }
    private const val LOG_TAG = "AgentCognitivePipeline"

    // =========================================================================
    // Transient stash for complex non-serializable objects (Phase 4)
    //
    // The Redux-like architecture requires state changes to flow through the
    // reducer, but ContextAssemblyResult / PartitionAssemblyResult are too
    // complex for JSON action payloads. Side-effects store results here before
    // dispatching the lightweight action; the reducer retrieves and clears them.
    // Safe because the Store dispatches on a single thread.
    // =========================================================================
    internal var pendingManagedContext: ContextAssemblyResult? = null
    internal var pendingManagedPartitions: PartitionAssemblyResult? = null

    private val redundantHeaderRegex = Regex("""^.+? \([^)]+\) @ \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z:\s*""")

    fun startCognitiveCycle(agentId: IdentityUUID, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "startCognitiveCycle: Agent state missing.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "startCognitiveCycle: Agent '$agentId' not found.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: AgentStatusInfo()

        if (statusInfo.status == AgentStatus.PROCESSING && agent.isAgentActive) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "startCognitiveCycle: Agent '$agentId' is already processing. Ignoring.")
            return
        }

        // Collect all sessions to request ledger from.
        // Always include subscribedSessionIds + outputSessionId (if not already subscribed).
        // This ensures auto-managed private/cognition sessions are visible in context.
        val sessionsToRequest = buildSet {
            addAll(agent.subscribedSessionIds)
            agent.outputSessionId?.let { add(it) }
        }.toList()

        if (sessionsToRequest.isEmpty()) {
            val msg = "Cannot start turn: Agent has no session for context."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.ERROR, msg)
            return
        }

        // Validate all session UUIDs are in the registry
        val validSessions = sessionsToRequest.filter { sessionUUID ->
            store.state.value.identityRegistry.findByUUID(sessionUUID) != null
        }
        if (validSessions.isEmpty()) {
            val msg = "Cannot start turn: None of agent's sessions are in the registry."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.ERROR, msg)
            return
        }

        if (statusInfo.turnMode == TurnMode.DIRECT) {
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.PROCESSING)
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId.uuid); put("step", "Requesting Ledger")
        }))

        // Initialize multi-session ledger accumulation
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PENDING_LEDGER_SESSIONS, buildJsonObject {
            put("agentId", agentId.uuid)
            put("sessionIds", buildJsonArray {
                validSessions.forEach { add(it.uuid) }
            })
        }))

        // Request ledger from each subscribed session.
        // Compound correlationId "agentUUID::sessionUUID" lets handleLedgerResponse
        // identify which session the response belongs to.
        validSessions.forEach { sessionUUID ->
            store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_REQUEST_LEDGER_CONTENT, buildJsonObject {
                put("sessionId", sessionUUID.uuid)
                put("correlationId", "${agentId.uuid}::${sessionUUID.uuid}")
            }))
        }
    }

    /**
     * Handles targeted actions delivered to the agent feature by the Store.
     * Replaces the former handlePrivateData — action.name replaces envelope.type,
     * action.payload replaces envelope.payload.
     */
    fun handleTargetedAction(action: Action, store: Store) {
        val payload = action.payload ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleTargetedAction: Action '${action.name}' has no payload. Ignoring.")
            return
        }
        when (action.name) {
            ActionRegistry.Names.SESSION_RETURN_LEDGER -> handleLedgerResponse(payload, store)
            ActionRegistry.Names.KNOWLEDGEGRAPH_RETURN_CONTEXT -> handleHkgContextResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_LIST -> handleWorkspaceListingResponse(payload, store)
            ActionRegistry.Names.FILESYSTEM_RETURN_FILES_CONTENT -> handleWorkspaceFileContentsResponse(payload, store)
            ActionRegistry.Names.GATEWAY_RETURN_RESPONSE -> handleGatewayResponse(payload, store)
            ActionRegistry.Names.GATEWAY_RETURN_PREVIEW -> handleGatewayPreviewResponse(payload, store)
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILES -> handleSessionWorkspaceFilesResponse(payload, store)
            ActionRegistry.Names.SESSION_RETURN_WORKSPACE_FILE -> handleOnDemandSessionFileResponse(payload, store)
            else -> {
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "handleTargetedAction: Received unrecognised action '${action.name}'. Ignoring."
                )
            }
        }
    }

    private fun handleLedgerResponse(payload: JsonObject, store: Store) {
        val rawCorrelationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleLedgerResponse: Missing correlationId.")
            return
        }

        // Compound correlationId: "agentUUID::sessionUUID"
        val parts = rawCorrelationId.split("::", limit = 2)
        val agentIdStr = parts[0]
        val sessionIdStr = if (parts.size == 2) parts[1] else null
        val agentId = IdentityUUID(agentIdStr)

        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleLedgerResponse: Agent feature state missing. Dropping ledger response for correlationId='$rawCorrelationId'.")
            return
        }

        val decoded = try {
            json.decodeFromJsonElement<LedgerResponsePayload>(payload)
        } catch (e: Exception) {
            val msg = "Failed to parse ledger response: ${e.message}"
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.ERROR, msg)
            return
        }

        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleLedgerResponse: Agent '$agentId' not found in state. Dropping ledger response.")
            return
        }

        val enrichedMessages = decoded.messages.mapNotNull { element ->
            try {
                val entryJson = element.jsonObject
                val senderId = entryJson["senderId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawContent = entryJson["rawContent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timestamp = entryJson["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null

                val user = state.userIdentities.find { it.handle == senderId }
                // Dual check: senderId may be UUID (old ledger entries) or handle (new entries)
                val isSelf = (senderId == agent.identityUUID.uuid || senderId == agent.identityHandle.handle)
                val (senderName, role) = when {
                    isSelf -> agent.identity.name to "model"
                    state.agents.values.any { it.identityUUID.uuid == senderId || it.identityHandle.handle == senderId } -> {
                        val otherAgent = state.agents.values.first { it.identityUUID.uuid == senderId || it.identityHandle.handle == senderId }
                        otherAgent.identity.name to "user"
                    }
                    user != null -> user.name to "user"
                    else -> "Unknown" to "user"
                }
                GatewayMessage(role, rawContent, senderId, senderName, timestamp)
            } catch (e: Exception) {
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "handleLedgerResponse: Failed to parse a ledger message for agent '$agentId': ${e.message}. Skipping."
                )
                null
            }
        }

        if (sessionIdStr != null) {
            // Multi-session path: accumulate per-session ledger
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_ACCUMULATE_SESSION_LEDGER, buildJsonObject {
                put("agentId", agentId.uuid)
                put("sessionId", sessionIdStr)
                put("messages", json.encodeToJsonElement(enrichedMessages))
            }))
        } else {
            // Legacy path: single correlationId without "::" separator.
            // Fall back to the old STAGE_TURN_CONTEXT for backward compatibility
            // with any callers that haven't been migrated yet.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_STAGE_TURN_CONTEXT, buildJsonObject {
                put("agentId", agent.identityUUID.uuid)
                put("messages", json.encodeToJsonElement(enrichedMessages))
            }))
        }
    }

    /**
     * Handles the workspace listing response from the filesystem feature.
     * Extracts the correlationId (agentId), stores the raw listing, and dispatches
     * file reads for any EXPANDED workspace files.
     *
     * Phase 1: Store the raw listing (AGENT_SET_WORKSPACE_LISTING) — this unblocks
     * the INDEX tree building even if no files are expanded.
     * Phase 2: If any workspace files are EXPANDED in collapse overrides, dispatch
     * a single READ_MULTIPLE request and set pendingWorkspaceFileReads = true.
     * If no files are expanded, workspace context is immediately ready.
     */
    private fun handleWorkspaceListingResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            // No correlationId means this listing wasn't requested by the pipeline — ignore.
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleWorkspaceListingResponse: No correlationId — not a pipeline-initiated listing. Ignoring.")
            return
        }

        val listing = payload["listing"]?.jsonArray ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleWorkspaceListingResponse: Missing 'listing' in payload for agent '$agentIdStr'. Storing empty listing.")
            JsonArray(emptyList())
        }

        val agentId = IdentityUUID(agentIdStr)

        // 2. Determine which workspace files need content fetched (BEFORE storing the listing,
        //    because storing the listing triggers evaluateFullContext via side-effect — the
        //    pending flag must already be in state when that gate check runs).
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: return
        val statusInfo = state.agentStatuses[agentId] ?: AgentStatusInfo()
        val agent = state.agents[agentId] ?: return

        val safeAgentId = agentIdStr.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val workspacePrefix = "$safeAgentId/workspace"

        val entries = WorkspaceContextFormatter.parseListingEntries(
            listing, workspacePrefix, store.platformDependencies
        )

        // Build effective overrides: root directory auto-expanded (same pattern as HKG root).
        // Root-level entries (parentPath == null, isDirectory) get EXPANDED unless agent overrode.
        val agentOverrides = statusInfo.contextCollapseOverrides
        val effectiveOverrides = buildMap {
            // Seed with root directory default — ensure top-level tree is always visible
            entries
                .filter { it.parentPath == null && it.isDirectory }
                .forEach { rootDir ->
                    val key = "ws:${rootDir.relativePath}"
                    if (key !in agentOverrides) {
                        put(key, CollapseState.EXPANDED)
                    }
                }
            putAll(agentOverrides)
        }

        val expandedFilePaths = WorkspaceContextFormatter.getExpandedFilePaths(entries, effectiveOverrides)

        if (expandedFilePaths.isNotEmpty()) {
            // 3a. Set pending flag FIRST — before the listing is stored.
            // CRITICAL ORDERING: SET_WORKSPACE_LISTING's side-effect calls evaluateFullContext.
            // If pendingWorkspaceFileReads is still false when that gate runs, the turn fires
            // prematurely without waiting for file contents. Setting the flag first ensures
            // the gate sees pending=true and waits.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PENDING_WORKSPACE_FILES, buildJsonObject {
                put("agentId", agentIdStr)
                put("pending", true)
            }))
        }

        // 1. Store the raw listing for INDEX tree building.
        // Side-effect: triggers evaluateFullContext. If expanded files exist, the pending
        // flag (set above) keeps the gate closed until file contents arrive.
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_LISTING, buildJsonObject {
            put("agentId", agentIdStr)
            put("listing", listing)
        }))

        if (expandedFilePaths.isNotEmpty()) {
            // 3b. Dispatch READ_MULTIPLE for the expanded files
            val sandboxPaths = expandedFilePaths.map { "$workspacePrefix/$it" }

            store.deferredDispatch("agent", Action(ActionRegistry.Names.FILESYSTEM_READ_MULTIPLE, buildJsonObject {
                put("paths", buildJsonArray { sandboxPaths.forEach { add(it) } })
                put("correlationId", "ws:$agentIdStr")
            }))

            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleWorkspaceListingResponse: Dispatched READ_MULTIPLE for ${expandedFilePaths.size} expanded workspace files for agent '$agentIdStr'.")
        } else {
            // 3c. No expanded files — workspace context is fully ready with just the listing
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleWorkspaceListingResponse: No expanded workspace files for agent '$agentIdStr'. Listing-only context ready.")
        }
    }

    /**
     * Handles the READ_MULTIPLE response containing expanded workspace file contents.
     * Strips the workspace prefix from paths to produce workspace-relative keys,
     * then stores the contents via AGENT_SET_WORKSPACE_FILE_CONTENTS.
     */
    private fun handleWorkspaceFileContentsResponse(payload: JsonObject, store: Store) {
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: return
        if (!correlationId.startsWith("ws:")) return // Not a workspace file read

        val agentIdStr = correlationId.removePrefix("ws:")
        val contentsJson = payload["contents"]?.jsonObject

        if (contentsJson == null) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleWorkspaceFileContentsResponse: Missing 'contents' for agent '$agentIdStr'. Storing empty map.")
        }

        // Strip the sandbox prefix from paths to produce workspace-relative keys.
        // The READ_MULTIPLE response uses sandbox-relative paths like "{agentId}/workspace/src/main.kt".
        // We need just "src/main.kt" to match the WorkspaceContextFormatter key convention.
        val safeAgentId = agentIdStr.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val workspacePrefix = "$safeAgentId/workspace/"

        val relativeContents = buildJsonObject {
            contentsJson?.forEach { (path, content) ->
                val normalizedPath = path.replace("\\", "/")
                val relativePath = normalizedPath.removePrefix(workspacePrefix)
                put(relativePath, content)
            }
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_WORKSPACE_FILE_CONTENTS, buildJsonObject {
            put("agentId", agentIdStr)
            put("contents", relativeContents)
        }))
    }

    private fun handleHkgContextResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleHkgContextResponse: Missing correlationId in payload.")
            return
        }
        val hkgContext = payload["context"]?.jsonObject

        if (hkgContext == null) {
            val personaId = payload["personaId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            store.platformDependencies.log(
                LogLevel.WARN, LOG_TAG,
                "handleHkgContextResponse: Null or missing 'context' for agent '$agentIdStr' " +
                        "(personaId='$personaId'). Agent will see empty HKG. " +
                        "Check that the persona is loaded in KnowledgeGraphFeature."
            )
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_HKG_CONTEXT, buildJsonObject {
            put("agentId", agentIdStr)
            put("context", hkgContext ?: buildJsonObject {})
        }))
    }

    /**
     * Handles the targeted session.RETURN_WORKSPACE_FILES response from the session feature.
     * Extracts listing + contents and stores them via AGENT_STORE_SESSION_FILES.
     */
    private fun handleSessionWorkspaceFilesResponse(payload: JsonObject, store: Store) {
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleSessionWorkspaceFilesResponse: Missing correlationId. Ignoring.")
            return
        }
        if (!correlationId.startsWith("sf:")) {
            // Not a session file response — may be a different targeted action sharing this handler.
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleSessionWorkspaceFilesResponse: CorrelationId '$correlationId' does not start with 'sf:'. Ignoring.")
            return
        }

        // Correlation format: "sf:{agentUUID}:{sessionUUID}"
        val parts = correlationId.removePrefix("sf:").split(":", limit = 2)
        if (parts.size != 2) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleSessionWorkspaceFilesResponse: Malformed correlationId '$correlationId'.")
            return
        }
        val agentIdStr = parts[0]
        val sessionIdStr = parts[1]

        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleSessionWorkspaceFilesResponse: Session error for agent '$agentIdStr', " +
                        "session '$sessionIdStr': $error. Storing empty data.")
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_STORE_SESSION_FILES, buildJsonObject {
                put("agentId", agentIdStr)
                put("sessionId", sessionIdStr)
                put("listing", JsonArray(emptyList()))
                put("contents", buildJsonObject {})
            }))
            return
        }

        val listing = payload["listing"]?.jsonArray ?: JsonArray(emptyList())
        val contentsJson = payload["contents"]?.jsonObject ?: buildJsonObject {}

        val normalizedContents = buildJsonObject {
            contentsJson.forEach { (path, content) ->
                put(path.replace("\\", "/"), content)
            }
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_STORE_SESSION_FILES, buildJsonObject {
            put("agentId", agentIdStr)
            put("sessionId", sessionIdStr)
            put("listing", listing)
            put("contents", normalizedContents)
        }))

        store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
            "handleSessionWorkspaceFilesResponse: Stored session files for agent '$agentIdStr', " +
                    "session '$sessionIdStr' (${listing.size} entries, ${contentsJson.size} file contents).")
    }

    /**
     * Handles the targeted session.RETURN_WORKSPACE_FILE response for on-demand
     * single-file reads triggered by CONTEXT_UNCOLLAPSE on sf: keys.
     */
    private fun handleOnDemandSessionFileResponse(payload: JsonObject, store: Store) {
        val correlationId = payload["correlationId"]?.jsonPrimitive?.contentOrNull ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleOnDemandSessionFileResponse: Missing correlationId. Ignoring.")
            return
        }
        if (!correlationId.startsWith("sfod:")) {
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
                "handleOnDemandSessionFileResponse: CorrelationId '$correlationId' does not start with 'sfod:'. Ignoring.")
            return
        }

        val parts = correlationId.removePrefix("sfod:").split(":", limit = 2)
        if (parts.size != 2) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleOnDemandSessionFileResponse: Malformed correlationId '$correlationId'. Expected 'sfod:{agentUUID}:{sessionUUID}'.")
            return
        }
        val agentIdStr = parts[0]
        val sessionIdStr = parts[1]

        val path = payload["path"]?.jsonPrimitive?.contentOrNull
        val content = payload["content"]?.jsonPrimitive?.contentOrNull
        val error = payload["error"]?.jsonPrimitive?.contentOrNull

        if (error != null) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleOnDemandSessionFileResponse: Error reading '$path' from session '$sessionIdStr': $error")
            return
        }

        if (path == null || content == null) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "handleOnDemandSessionFileResponse: Missing path or content in response for agent '$agentIdStr', " +
                        "session '$sessionIdStr' (path=${path != null}, content=${content != null}). Dropping.")
            return
        }

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_MERGE_SESSION_FILE_CONTENT, buildJsonObject {
            put("agentId", agentIdStr)
            put("sessionId", sessionIdStr)
            put("contents", buildJsonObject { put(path, content) })
        }))
    }

    /**
     * Called after the ledger is staged. Dispatches ALL context requests in parallel
     * and sets up a timeout. Does NOT call executeTurn directly — the gate handles it.
     */
    fun evaluateTurnContext(agentId: IdentityUUID, store: Store) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateTurnContext: Agent feature state missing. Cannot evaluate context for '$agentId'.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateTurnContext: Agent '$agentId' not found.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateTurnContext: No status entry for agent '$agentId'. Turn may have been cancelled.")
            return
        }

        if (statusInfo.stagedTurnContext == null && statusInfo.accumulatedSessionLedgers.isEmpty()) {
            val msg = "Turn Context Missing for '$agentId'. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.ERROR, msg)
            return
        }

        // 1. Record the context gathering start time (for timeout validation)
        val startedAt = store.platformDependencies.currentTimeMillis()
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", startedAt)
        }))

        // 2. Dispatch workspace listing request (parallel)
        val safeAgentId = agentId.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        store.deferredDispatch("agent", Action(ActionRegistry.Names.FILESYSTEM_LIST, buildJsonObject {
            put("path", "$safeAgentId/workspace")
            put("recursive", true)
            put("correlationId", agentId.uuid)
        }))

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentId.uuid); put("step", "Gathering Context")
        }))

        // 3. Dispatch session workspace file requests (parallel, cross-sandbox delegation)
        // Only dispatched if the agent has session:read-files permission.
        val filePerms = resolveFilePermissions(agent, store)
        val sessionsForFiles = if (filePerms.sessionFilesCanRead) {
            buildSet {
                addAll(agent.subscribedSessionIds)
                agent.outputSessionId?.let { add(it) }
            }.filter { sessionUUID ->
                store.state.value.identityRegistry.findByUUID(sessionUUID) != null
            }
        } else emptyList()

        if (sessionsForFiles.isNotEmpty()) {
            // Initialize pending set BEFORE dispatching requests
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PENDING_SESSION_FILE_LISTINGS, buildJsonObject {
                put("agentId", agentId.uuid)
                put("sessionIds", buildJsonArray {
                    sessionsForFiles.forEach { add(it.uuid) }
                })
            }))

            sessionsForFiles.forEach { sessionUUID ->
                val sessionIdentity = store.state.value.identityRegistry.findByUUID(sessionUUID)
                val sessionHandle = sessionIdentity?.handle ?: sessionUUID.uuid

                // Determine which session files the agent has expanded
                val sfPrefix = "sf:$sessionHandle:"
                val expandedPaths = statusInfo.contextCollapseOverrides
                    .filter { (key, collapseState) ->
                        collapseState == CollapseState.EXPANDED &&
                                key.startsWith(sfPrefix) &&
                                !key.removePrefix(sfPrefix).endsWith("/")
                    }
                    .keys
                    .map { it.removePrefix(sfPrefix) }
                    .filter { it.isNotBlank() }

                store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_REQUEST_WORKSPACE_FILES, buildJsonObject {
                    put("sessionId", sessionUUID.uuid)
                    put("correlationId", "sf:${agentId.uuid}:${sessionUUID.uuid}")
                    put("requesterId", agent.identityHandle.handle)
                    put("expandedFilePaths", buildJsonArray { expandedPaths.forEach { add(it) } })
                }))
            }
        }

        // Polymorphic: let the strategy request any additional context it needs.
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        strategy.requestAdditionalContext(agent, store)

        // 4. Schedule timeout after 10 seconds
        store.scheduleDelayed(10_000L, "agent", Action(ActionRegistry.Names.AGENT_CONTEXT_GATHERING_TIMEOUT, buildJsonObject {
            put("agentId", agentId.uuid)
            put("startedAt", startedAt)
        }))

        // Do NOT call executeTurn here — the gate (evaluateFullContext) will handle it
    }

    /**
     * The unified gate function. Called whenever a context response arrives or the timeout fires.
     * Proceeds to executeTurn only when all expected contexts are ready (or timeout forces it).
     */
    fun evaluateFullContext(agentId: IdentityUUID, store: Store, isTimeout: Boolean = false) {
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "evaluateFullContext: Agent feature state missing for '$agentId'. Context gate cannot proceed.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateFullContext: Agent '$agentId' not found. May have been deleted mid-turn.")
            return
        }
        val statusInfo = state.agentStatuses[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "evaluateFullContext: No status entry for agent '$agentId'. Cannot evaluate context gate.")
            return
        }

        // Bail if not in an active turn.
        if (statusInfo.contextGatheringStartedAt == null) {
            store.platformDependencies.log(LogLevel.DEBUG, LOG_TAG, "evaluateFullContext: contextGatheringStartedAt is null for '$agentId'. No active context-gathering phase — ignoring.")
            return
        }

        // Resolve ledger data from either:
        //   (a) Multi-session: accumulatedSessionLedgers (pendingLedgerSessionIds must be empty)
        //   (b) Legacy: stagedTurnContext (single-session flat list)
        val sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>
        if (statusInfo.accumulatedSessionLedgers.isNotEmpty() || statusInfo.pendingLedgerSessionIds.isNotEmpty()) {
            // Multi-session path — only ready when all pending sessions have arrived
            if (statusInfo.pendingLedgerSessionIds.isNotEmpty() && !isTimeout) {
                // Still waiting for sessions — not ready yet, let the next arrival re-evaluate
                return
            }
            sessionLedgers = statusInfo.accumulatedSessionLedgers
        } else if (statusInfo.stagedTurnContext != null) {
            // Legacy single-session path — wrap in a map with a synthetic key
            val contextSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull()
            sessionLedgers = if (contextSessionUUID != null) {
                mapOf(contextSessionUUID to statusInfo.stagedTurnContext)
            } else {
                mapOf(IdentityUUID("unknown") to statusInfo.stagedTurnContext)
            }
        } else {
            val msg = "Context arrived for '$agentId' without any ledger context. Aborting."
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, msg)
            AgentAvatarLogic.updateAgentAvatars(agentId, store, state, AgentStatus.ERROR, "Context assembly failed.")
            return
        }

        val workspaceReady = statusInfo.transientWorkspaceListing != null && !statusInfo.pendingWorkspaceFileReads
        val sessionFilesReady = statusInfo.pendingSessionFileListingIds.isEmpty()

        // Polymorphic: ask the strategy if it expects additional context.
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val expectsAdditionalContext = strategy.needsAdditionalContext(agent)
        val additionalContextReady = !expectsAdditionalContext || statusInfo.transientHkgContext != null

        if (workspaceReady && additionalContextReady && sessionFilesReady) {
            // Close the gate immediately before dispatching executeTurn so that a concurrent
            // timeout callback cannot re-enter and produce a duplicate GATEWAY_GENERATE_CONTENT.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
                put("agentId", agentId.uuid)
                put("startedAt", JsonNull)
            }))
            executeTurn(agent, sessionLedgers, statusInfo.transientHkgContext, state, store)
        } else if (isTimeout) {
            val missing = mutableListOf<String>()
            if (!workspaceReady) missing.add("workspace")
            if (!additionalContextReady) missing.add("strategy-context")
            if (!sessionFilesReady) missing.add("session-files")
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG,
                "Context gathering timeout for agent '$agentId'. Missing: ${missing.joinToString(", ")}. Proceeding without.")
            // Same gate-closing dispatch on the timeout path.
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_CONTEXT_GATHERING_STARTED, buildJsonObject {
                put("agentId", agentId.uuid)
                put("startedAt", JsonNull)
            }))
            executeTurn(agent, sessionLedgers, statusInfo.transientHkgContext, state, store)
        }
    }

    /**
     * Kept as a thin wrapper for backward compatibility.
     */
    fun evaluateHkgContext(agentId: IdentityUUID, store: Store) {
        evaluateFullContext(agentId, store)
    }


    // =========================================================================
    // Context Assembly Pipeline (§5.3)
    //
    // assembleContext()        — full path: partitions + assembled prompt + gateway request
    // assemblePartitions()     — fast path: partitions only (no string assembly)
    // executeTurn()            — thin wrapper: assemble + dispatch
    //
    // Internal helpers:
    //   mergeIntoPartitions()  — resolve GatheredRef / RemainingGathered
    //   flattenWithCascade()   — cascade collapse semantics (Red Team Fix F1)
    //   toPartition()          — Section → ContextPartition
    //   assemblePromptString() — h1/h2 wrap + concatenate
    //   buildContextMap()      — gather all raw partition content
    //   buildSessionInfos()    — derive participant rosters from ledgers
    // =========================================================================

    /**
     * Full assembly path. Implements §5.3 steps 1–9:
     *
     * 1. Build contextMap (raw gathered partitions)
     * 2. Build AgentTurnContext with gatheredContextKeys
     * 3. strategy.buildPrompt(context, frozenState) → PromptBuilder.sections
     * 4. mergeIntoPartitions(sections, contextMap)
     * 5. flattenWithCascade(merged, overrides)
     * 6. ContextCollapseLogic.collapse(flat, budget)
     * 7. h1/h2 wrap + concatenate
     * 8. ContextDelimiters.wrapSystemPrompt()
     * 9. Return ContextAssemblyResult
     *
     * Returns null on failure (missing resources). Errors reported via avatar status.
     */
    fun assembleContext(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ): ContextAssemblyResult? {
        val agentUuid = agent.identityUUID
        val statusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
        val platformDependencies = store.platformDependencies

        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
            "Assembling prompt for '${agentUuid}' using strategy '${strategy.identityHandle}' (State: ${abbreviate(cognitiveState.toString(),30)}).")

        // === Step 0: Resource resolution ===
        val resolvedResources = resolveAgentResources(agent, agentState.resources, strategy, platformDependencies, store, agentState)
            ?: return null

        // === Step 1: Build session infos (needed by both contextMap and AgentTurnContext) ===
        val identityRegistry = store.state.value.identityRegistry
        val outputSessionUUID = agent.outputSessionId
        val outputSessionHandle = outputSessionUUID?.let { identityRegistry.findByUUID(it)?.handle }
        val subscribedSessionInfos = buildSessionInfos(agent, sessionLedgers, agentState, outputSessionUUID, identityRegistry)

        // === Step 2: Build contextMap (structured gathered partitions, no h1 wrapping) ===
        val isPrivateFormat = strategy.hasAutoManagedOutputSession
        val (contextMap, effectiveOverrides) = buildContextMap(
            agent, sessionLedgers, hkgContext, agentState, statusInfo, store,
            subscribedSessionInfos, isPrivateFormat
        )

        // === Step 3: Build AgentTurnContext with keys only ===
        val context = AgentTurnContext(
            agentName = agent.identity.name,
            resolvedResources = resolvedResources,
            gatheredContextKeys = contextMap.keys,
            subscribedSessions = subscribedSessionInfos,
            outputSessionUUID = outputSessionUUID?.uuid,
            outputSessionHandle = outputSessionHandle
        )

        // === Step 3: strategy.buildPrompt() with frozen state (Red Team Fix F3) ===
        val frozenState = Json.parseToJsonElement(Json.encodeToString(cognitiveState))
        val builder = strategy.buildPrompt(context, frozenState)

        // === Step 4: Resolve GatheredRef / RemainingGathered against contextMap ===
        val mergedSections = mergeIntoPartitions(builder.sections, contextMap)

        // === Step 5: Flatten with cascade collapse semantics ===
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentUuid.uuid); put("step", "Applying Context Budget")
        }))
        val flatPartitions = flattenWithCascade(mergedSections, effectiveOverrides)

        // === Step 6: Run budget collapse on the flat list ===
        val collapseResult = ContextCollapseLogic.collapse(
            partitions = flatPartitions,
            maxBudgetChars = agent.contextMaxBudgetChars,
            maxPartialChars = agent.contextMaxPartialChars,
            platformDependencies = platformDependencies,
            agentId = agentUuid.uuid
        )

        // === Steps 7–8: Assemble prompt string ===
        val budgetReport = ContextCollapseLogic.buildBudgetReport(
            result = collapseResult,
            softBudgetChars = agent.contextBudgetChars,
            maxBudgetChars = agent.contextMaxBudgetChars
        )

        // Add CONTEXT_BUDGET as a real partition so it's visible in the Context Manager UI.
        // Generated after the collapse pass (it reports on the result of collapse).
        val budgetPartition = ContextCollapseLogic.ContextPartition(
            key = "CONTEXT_BUDGET",
            fullContent = budgetReport,
            collapsedContent = budgetReport,
            state = CollapseState.EXPANDED,
            isAutoCollapsible = false,
            parentKey = null
        )
        val allPartitions = collapseResult.partitions + budgetPartition
        val totalCharsWithBudget = collapseResult.totalChars + budgetPartition.effectiveCharCount

        val systemPrompt = assemblePromptString(allPartitions, collapseResult.truncatedKeys)

        // === Step 9: Return result ===
        return ContextAssemblyResult(
            partitions = allPartitions,
            collapseResult = collapseResult.copy(
                partitions = allPartitions,
                totalChars = totalCharsWithBudget
            ),
            budgetReport = budgetReport,
            systemPrompt = systemPrompt,
            gatewayRequest = GatewayRequest(
                modelName = agent.modelName,
                contents = emptyList(),
                correlationId = agentUuid.uuid,
                systemPrompt = systemPrompt
            ),
            softBudgetChars = agent.contextBudgetChars,
            maxBudgetChars = agent.contextMaxBudgetChars,
            transientDataSnapshot = TransientDataSnapshot(
                sessionLedgers = sessionLedgers,
                hkgContext = hkgContext,
                workspaceListing = statusInfo.transientWorkspaceListing,
                workspaceFileContents = statusInfo.transientWorkspaceFileContents,
                sessionFileListings = statusInfo.transientSessionFileListings,
                sessionFileContents = statusInfo.transientSessionFileContents
            )
        )
    }

    /**
     * Fast assembly path: partition metadata only (no string assembly).
     * Used by the Context Manager UI (Tab 0) for instant reassembly on toggle.
     */
    fun assemblePartitions(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ): PartitionAssemblyResult? {
        val agentUuid = agent.identityUUID
        val statusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
        val platformDependencies = store.platformDependencies
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        val resolvedResources = resolveAgentResources(agent, agentState.resources, strategy, platformDependencies, store, agentState)
            ?: return null

        val identityRegistry = store.state.value.identityRegistry
        val outputSessionUUID = agent.outputSessionId
        val outputSessionHandle = outputSessionUUID?.let { identityRegistry.findByUUID(it)?.handle }
        val subscribedSessionInfos = buildSessionInfos(agent, sessionLedgers, agentState, outputSessionUUID, identityRegistry)

        val isPrivateFormat = strategy.hasAutoManagedOutputSession
        val (contextMap, effectiveOverrides) = buildContextMap(
            agent, sessionLedgers, hkgContext, agentState, statusInfo, store,
            subscribedSessionInfos, isPrivateFormat
        )

        val context = AgentTurnContext(
            agentName = agent.identity.name,
            resolvedResources = resolvedResources,
            gatheredContextKeys = contextMap.keys,
            subscribedSessions = subscribedSessionInfos,
            outputSessionUUID = outputSessionUUID?.uuid,
            outputSessionHandle = outputSessionHandle
        )

        val frozenState = Json.parseToJsonElement(Json.encodeToString(cognitiveState))
        val builder = strategy.buildPrompt(context, frozenState)
        val mergedSections = mergeIntoPartitions(builder.sections, contextMap)
        val flatPartitions = flattenWithCascade(mergedSections, effectiveOverrides)

        val collapseResult = ContextCollapseLogic.collapse(
            partitions = flatPartitions,
            maxBudgetChars = agent.contextMaxBudgetChars,
            maxPartialChars = agent.contextMaxPartialChars,
            platformDependencies = platformDependencies,
            agentId = agentUuid.uuid
        )

        // Add CONTEXT_BUDGET partition for UI visibility (same as full path).
        val budgetReport = ContextCollapseLogic.buildBudgetReport(
            result = collapseResult,
            softBudgetChars = agent.contextBudgetChars,
            maxBudgetChars = agent.contextMaxBudgetChars
        )
        val budgetPartition = ContextCollapseLogic.ContextPartition(
            key = "CONTEXT_BUDGET",
            fullContent = budgetReport,
            collapsedContent = budgetReport,
            state = CollapseState.EXPANDED,
            isAutoCollapsible = false,
            parentKey = null
        )
        val allPartitions = collapseResult.partitions + budgetPartition
        val totalCharsWithBudget = collapseResult.totalChars + budgetPartition.effectiveCharCount

        return PartitionAssemblyResult(
            partitions = allPartitions,
            collapseResult = collapseResult.copy(
                partitions = allPartitions,
                totalChars = totalCharsWithBudget
            ),
            totalChars = totalCharsWithBudget,
            softBudgetChars = agent.contextBudgetChars,
            maxBudgetChars = agent.contextMaxBudgetChars
        )
    }

    /** Thin wrapper: assembles context and dispatches the gateway request. */
    private fun executeTurn(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val agentUuid = agent.identityUUID
        val statusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
        val result = assembleContext(agent, sessionLedgers, hkgContext, agentState, store) ?: return

        if (statusInfo.turnMode == TurnMode.PREVIEW) {
            // PREVIEW mode: store the assembly result as managed context.
            // The Manage Context UI reads it directly — no gateway roundtrip needed.
            // The debounced preview within the UI handles token estimation.
            pendingManagedContext = result
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_MANAGED_CONTEXT, buildJsonObject {
                put("agentId", agentUuid.uuid)
            }))
            store.dispatch("agent", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject {
                put("key", "feature.agent.context_viewer")
            }))
        } else {
            // DIRECT mode: dispatch to gateway immediately
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
                put("agentId", agentUuid.uuid); put("step", "Generating Content")
            }))
            store.deferredDispatch("agent", Action(ActionRegistry.Names.GATEWAY_GENERATE_CONTENT, buildJsonObject {
                put("providerId", agent.modelProvider)
                put("modelName", agent.modelName)
                put("correlationId", agentUuid.uuid)
                put("contents", buildJsonArray {})
                put("systemPrompt", result.systemPrompt)
            }))
        }
    }

    // =========================================================================
    // Pipeline Internals
    // =========================================================================

    /**
     * Result of [buildContextMap] — the structured partition map plus the effective
     * collapse overrides that include auto-expanded defaults (HKG roots, workspace roots).
     */
    private data class ContextMapResult(
        val contextMap: Map<String, PromptSection>,
        /** Agent overrides + auto-expanded defaults. Pass to [flattenWithCascade]. */
        val effectiveOverrides: Map<String, CollapseState>
    )

    /**
     * Resolved file access permissions for the agent. Used to gate context sections
     * and generate dynamic explanatory text in workspace/session file headers.
     */
    private data class FilePermissions(
        val workspaceCanRead: Boolean,
        val workspaceCanWrite: Boolean,
        val sessionFilesCanRead: Boolean,
        val sessionFilesCanWrite: Boolean
    )

    /**
     * Resolves the agent's effective file permissions by walking the identity
     * registry's permission chain.
     *
     * - Workspace: sandbox-owned, defaults to full read+write access unless
     *   `filesystem:workspace` is explicitly set to NO.
     * - Session files: permission-gated via `session:read-files` (visibility gate)
     *   and `session:write-files` (write access text).
     */
    private fun resolveFilePermissions(agent: AgentInstance, store: Store): FilePermissions {
        val agentIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
        if (agentIdentity == null) {
            // Agent not yet in registry — default to workspace-only (sandbox-owned)
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG,
                "Agent not found in registry. Denying all file permissions.")
            return FilePermissions(
                workspaceCanRead = false, workspaceCanWrite = false,
                sessionFilesCanRead = false, sessionFilesCanWrite = false
            )
        }

        val effective = store.resolveEffectivePermissions(agentIdentity)

        // Workspace: sandbox-owned. Full access unless explicitly denied.
        val wsGrant = effective["filesystem:workspace"]?.level
        val workspaceCanRead = wsGrant != PermissionLevel.NO
        val workspaceCanWrite = wsGrant != PermissionLevel.NO

        // Session files: permission-gated. NO by default unless granted.
        val sfReadLevel = effective["session:read-files"]?.level
        val sfWriteLevel = effective["session:write-files"]?.level
        val sessionFilesCanRead = sfReadLevel != null && sfReadLevel >= PermissionLevel.YES
        val sessionFilesCanWrite = sfWriteLevel != null && sfWriteLevel >= PermissionLevel.YES

        return FilePermissions(workspaceCanRead, workspaceCanWrite, sessionFilesCanRead, sessionFilesCanWrite)
    }

    /**
     * Builds the structured context map — all gathered partitions as [PromptSection]
     * entries WITHOUT h1 wrapping.
     *
     * Flat partitions (METADATA, AVAILABLE_ACTIONS, etc.) become [PromptSection.Section].
     * Structured partitions (SESSIONS, HOLON_KNOWLEDGE_GRAPH, WORKSPACE_FILES) become
     * [PromptSection.Group] with per-item children, enabling per-child collapse and
     * budget management in the unified partition model.
     *
     * Returns both the context map and the effective collapse overrides (agent overrides
     * merged with auto-expanded defaults for HKG roots and workspace roots). The effective
     * overrides are passed to [flattenWithCascade] so that child Sections with
     * [PromptSection.Section.defaultCollapsed] resolve correctly.
     */
    private fun buildContextMap(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        statusInfo: AgentStatusInfo,
        store: Store,
        subscribedSessionInfos: List<SessionInfo>,
        isPrivateFormat: Boolean
    ): ContextMapResult {
        val agentUuid = agent.identityUUID
        val platformDependencies = store.platformDependencies
        val identityRegistry = store.state.value.identityRegistry
        val contextMap = mutableMapOf<String, PromptSection>()

        // Accumulate effective overrides: start with agent's sticky overrides,
        // then add auto-expanded defaults for roots that the agent hasn't explicitly set.
        val mergedOverrides = mutableMapOf<String, CollapseState>()
        mergedOverrides.putAll(statusInfo.contextCollapseOverrides)

        // HKG — unified partition (INDEX + holon tree + NAVIGATION)
        if (hkgContext != null && hkgContext.isNotEmpty()) {
            val hkgHeaders = HkgContextFormatter.parseHolonHeaders(hkgContext, platformDependencies)
            // Auto-expand HKG root holons
            hkgHeaders.values.filter { it.parentId == null }.forEach { root ->
                val key = "hkg:${root.id}"
                if (key !in mergedOverrides) mergedOverrides[key] = CollapseState.EXPANDED
            }
            val personaName = hkgHeaders.values.find { it.parentId == null }?.name
            if (personaName == null && hkgHeaders.isNotEmpty()) {
                platformDependencies.log(LogLevel.WARN, LOG_TAG,
                    "HKG for agent '${agentUuid}': No root holon found among ${hkgHeaders.size} holons.")
            }
            contextMap["HOLON_KNOWLEDGE_GRAPH"] = HkgContextFormatter.buildUnifiedSection(
                hkgContext, hkgHeaders, mergedOverrides, personaName,
                platformDependencies = platformDependencies
            )
        }

        // METADATA — runtime environment info (no session data — that's in SESSIONS)
        val lastInput = statusInfo.lastInputTokens
        val lastOutput = statusInfo.lastOutputTokens
        val tokenUsageContext = if (lastInput != null || lastOutput != null) {
            val total = (lastInput ?: 0) + (lastOutput ?: 0)
            "\nLast request token usage (your approximate context size): $total tokens (${lastInput ?: "N/A"} input, ${lastOutput ?: "N/A"} output). Your model token maximum and saturation point varies by model and context coherence/complexity — consult your provider documentation."
        } else {
            "\nLast request token usage: Not yet available (first turn or provider did not report usage)."
        }
        contextMap["METADATA"] = stringToSection("METADATA", """
            This data is provided for you to reason about your running environment and is updated on the moment of latest request to you.
            
            You are running on platform: 'AUF App ${Version.APP_VERSION} (Windows), a multi-agent, multi-session agent/chat platform.'
            Your Host LLM (API connection): '${agent.modelProvider}' / '${agent.modelName}'
            Your agent handle is: '${agent.identityHandle}'
            Your agent id (internal): '${agentUuid}'
            Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())}
        """.trimIndent() + tokenUsageContext)

        // AVAILABLE_ACTIONS — structured Group with per-feature children
        val agentIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
        if (agentIdentity != null) {
            contextMap["AVAILABLE_ACTIONS"] = ActionsContextFormatter.buildSections(store, agentIdentity)
        }

        // ── File permissions ──────────────────────────────────────────────
        val filePerms = resolveFilePermissions(agent, store)

        // WORKSPACE_FILES — consolidated partition (index tree in header + file contents as children)
        // Gated by filesystem:workspace permission (defaults to YES — sandbox-owned).
        val workspaceListing = statusInfo.transientWorkspaceListing
        if (filePerms.workspaceCanRead && workspaceListing != null && workspaceListing.isNotEmpty()) {
            val safeAgentIdForWs = agentUuid.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val workspacePrefix = "$safeAgentIdForWs/workspace"
            val wsEntries = WorkspaceContextFormatter.parseListingEntries(workspaceListing, workspacePrefix, platformDependencies)
            if (wsEntries.isNotEmpty()) {
                // Auto-expand workspace root directories
                wsEntries.filter { it.parentPath == null && it.isDirectory }.forEach { rootDir ->
                    val key = "ws:${rootDir.relativePath}"
                    if (key !in mergedOverrides) mergedOverrides[key] = CollapseState.EXPANDED
                }
                val fileContents = statusInfo.transientWorkspaceFileContents
                contextMap["WORKSPACE_FILES"] = WorkspaceContextFormatter.buildFilesSections(
                    wsEntries, fileContents, mergedOverrides, filePerms.workspaceCanWrite, platformDependencies
                )
            }
        }

        // SESSIONS — unified: subscription metadata + multi-agent context + per-session messages
        val outputSessionUUID = agent.outputSessionId
        val sessionSnapshots = sessionLedgers.map { (sessionUUID, messages) ->
            val sessIdentity = identityRegistry.findByUUID(sessionUUID)
            SessionContextFormatter.SessionLedgerSnapshot(
                sessionName = sessIdentity?.name ?: agentState.subscribableSessionNames[sessionUUID] ?: sessionUUID.uuid,
                sessionUUID = sessionUUID.uuid,
                sessionHandle = sessIdentity?.handle ?: sessionUUID.uuid,
                messages = messages,
                isOutputSession = sessionUUID == outputSessionUUID
            )
        }
        contextMap["SESSIONS"] = SessionContextFormatter.buildSessionsGroup(
            sessionSnapshots, subscribedSessionInfos, isPrivateFormat, platformDependencies
        )

        // SESSION_FILES — per-session workspace file context (cross-sandbox delegation)
        // Each session's files become a single GROUP partition whose header contains
        // the navigational index tree and whose children are the file content sections.
        // Gated by session:read-files permission.
        val sessionFileListings = statusInfo.transientSessionFileListings
        val sessionFileContents = statusInfo.transientSessionFileContents
        if (filePerms.sessionFilesCanRead && sessionFileListings.isNotEmpty()) {
            sessionFileListings.forEach { (sessionUUID, listing) ->
                if (listing.isEmpty()) return@forEach

                val sessIdentity = identityRegistry.findByUUID(sessionUUID)
                val sessionHandle = sessIdentity?.handle ?: sessionUUID.uuid
                val sessionName = sessIdentity?.name ?: agentState.subscribableSessionNames[sessionUUID] ?: sessionUUID.uuid

                val entries = SessionFilesContextFormatter.parseListingEntries(
                    listing, sessionUUID.uuid, platformDependencies
                )
                if (entries.isEmpty()) return@forEach

                // Auto-expand root directories for session files
                entries.filter { it.parentPath == null && it.isDirectory }.forEach { rootDir ->
                    val key = "sf:$sessionHandle:${rootDir.relativePath}"
                    if (key !in mergedOverrides) mergedOverrides[key] = CollapseState.EXPANDED
                }

                val fileContentsForSession = sessionFileContents[sessionUUID] ?: emptyMap()

                val groupKey = "SESSION_FILES:$sessionHandle"
                contextMap[groupKey] = SessionFilesContextFormatter.buildSessionFilesGroup(
                    sessionHandle, sessionName, entries, fileContentsForSession, mergedOverrides,
                    filePerms.sessionFilesCanWrite, platformDependencies
                )
            }
        }

        return ContextMapResult(contextMap, mergedOverrides)
    }

    /** Builds [SessionInfo] list with participant rosters derived from ledger messages. */
    private fun buildSessionInfos(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        agentState: AgentRuntimeState,
        outputSessionUUID: IdentityUUID?,
        identityRegistry: Map<String, Identity>
    ): List<SessionInfo> {
        val agentUuid = agent.identityUUID
        // Include all subscribed sessions + outputSessionId (if not already subscribed).
        // This ensures auto-managed private/cognition sessions appear in context.
        val allSessionIds = buildSet {
            addAll(agent.subscribedSessionIds)
            outputSessionUUID?.let { add(it) }
        }
        return allSessionIds.mapNotNull { sessUUID ->
            val sessIdentity = identityRegistry.findByUUID(sessUUID)
            val sessMessages = sessionLedgers[sessUUID] ?: emptyList()
            val participants = sessMessages.groupBy { it.senderId }.map { (senderId, messages) ->
                val senderName = messages.first().senderName
                val isSelf = (senderId == agentUuid.uuid || senderId == agent.identityHandle.handle)
                val type = when {
                    isSelf -> "YOU (this agent)"
                    agentState.agents.values.any { it.identityUUID.uuid == senderId || it.identityHandle.handle == senderId } -> "AI Agent"
                    agentState.userIdentities.any { it.handle == senderId } -> "Human User"
                    else -> "User/System"
                }
                SessionParticipant(senderId, senderName, type, messages.size)
            }
            SessionInfo(
                uuid = sessUUID.uuid,
                handle = sessIdentity?.handle ?: sessUUID.uuid,
                name = sessIdentity?.name ?: agentState.subscribableSessionNames[sessUUID] ?: sessUUID.uuid,
                isOutput = sessUUID == outputSessionUUID,
                participants = participants,
                messageCount = sessMessages.size
            )
        }
    }

    /**
     * Resolves [PromptSection.GatheredRef] and [PromptSection.RemainingGathered]
     * against the structured contextMap. Returns a fully resolved section list.
     *
     * The contextMap now contains [PromptSection] entries (Section for flat partitions,
     * Group for structured partitions like SESSIONS, HOLON_KNOWLEDGE_GRAPH, WORKSPACE_FILES).
     * GatheredRef resolution inserts the structured form directly — no wrapping needed.
     */
    private fun mergeIntoPartitions(
        sections: List<PromptSection>,
        contextMap: Map<String, PromptSection>
    ): List<PromptSection> {
        val placedKeys = mutableSetOf<String>()
        sections.filterIsInstance<PromptSection.GatheredRef>().forEach { placedKeys.add(it.key) }

        val result = mutableListOf<PromptSection>()
        for (section in sections) {
            when (section) {
                is PromptSection.Section -> result.add(section)
                is PromptSection.Group -> result.add(section)
                is PromptSection.GatheredRef -> {
                    val gathered = contextMap[section.key]
                    if (gathered != null && !isEmptySection(gathered)) {
                        result.add(gathered)
                    }
                }
                is PromptSection.RemainingGathered -> {
                    val remaining = contextMap.keys - placedKeys
                    val ordered = remaining.sorted()
                    for (key in ordered) {
                        val gathered = contextMap[key] ?: continue
                        if (isEmptySection(gathered)) continue
                        result.add(gathered)
                    }
                }
            }
        }
        return result
    }

    /** Returns true if a [PromptSection] is effectively empty (blank content or no children). */
    private fun isEmptySection(section: PromptSection): Boolean = when (section) {
        is PromptSection.Section -> section.content.isBlank()
        is PromptSection.Group -> section.children.isEmpty()
        is PromptSection.GatheredRef -> false // should not appear after merge
        is PromptSection.RemainingGathered -> false // should not appear after merge
    }

    /**
     * Wraps a raw content string as a [PromptSection.Section] with partition defaults
     * derived from [ContextCollapseLogic.resolvePartitionDefaults].
     *
     * Used by [buildContextMap] for flat partitions (METADATA, AVAILABLE_ACTIONS,
     * METADATA, etc.) that don't have internal sub-partition structure.
     */
    private fun stringToSection(key: String, content: String): PromptSection.Section {
        val defaults = ContextCollapseLogic.resolvePartitionDefaults(key, content)
        return PromptSection.Section(
            key = key,
            content = content,
            isProtected = !defaults.isAutoCollapsible,
            isCollapsible = defaults.isAutoCollapsible,
            priority = defaults.priority,
            collapsedSummary = defaults.collapsedContent,
            truncateFromStart = defaults.truncateFromStart
        )
    }

    /**
     * Flattens the section tree into a [ContextPartition] list with cascade semantics.
     * Red Team Fix F1: collapsed Group → children excluded from flat list.
     *
     * Group rendering:
     * - **COLLAPSED**: one summary partition (key = group key), children excluded.
     * - **EXPANDED**: one container partition (key = group key, content = header or empty)
     *   + N child partitions (parentKey = group key). The container serves as the parent
     *   card in the UI and carries the toggle for collapsing the entire group.
     */
    private fun flattenWithCascade(
        sections: List<PromptSection>,
        overrides: Map<String, CollapseState>,
        parentKey: String? = null
    ): List<ContextCollapseLogic.ContextPartition> {
        val result = mutableListOf<ContextCollapseLogic.ContextPartition>()
        for (section in sections) {
            when (section) {
                is PromptSection.Section -> result.add(sectionToPartition(section, parentKey, overrides))
                is PromptSection.Group -> {
                    val defaultState = if (section.defaultCollapsed) CollapseState.COLLAPSED else CollapseState.EXPANDED
                    val groupState = overrides[section.key] ?: defaultState
                    val collapsedSummary = section.collapsedSummary
                        ?: "[${section.key} collapsed — use CONTEXT_UNCOLLAPSE to expand]"

                    if (groupState == CollapseState.COLLAPSED) {
                        // CASCADE: emit summary only, children excluded from flat list.
                        result.add(ContextCollapseLogic.ContextPartition(
                            key = section.key,
                            fullContent = collapsedSummary,
                            collapsedContent = collapsedSummary,
                            state = CollapseState.COLLAPSED,
                            priority = section.priority,
                            isAutoCollapsible = section.isCollapsible && !section.isProtected,
                            parentKey = parentKey
                        ))
                    } else {
                        // EXPANDED: emit a container partition for the group, then children.
                        // The container partition:
                        // - key = group key (e.g., "SESSIONS") → children match via parentKey
                        // - fullContent = header text (may be empty) → actual content lives in children
                        // - collapsedContent = summary → shown if budget auto-collapses this group
                        // - isAutoCollapsible = true → budget can collapse the group, which hides children
                        val headerContent = section.header.ifBlank { "" }
                        result.add(ContextCollapseLogic.ContextPartition(
                            key = section.key,
                            fullContent = headerContent,
                            collapsedContent = collapsedSummary,
                            state = CollapseState.EXPANDED,
                            priority = section.priority,
                            isAutoCollapsible = section.isCollapsible && !section.isProtected,
                            parentKey = parentKey
                        ))
                        // Recurse — children carry this group as their parent
                        result.addAll(flattenWithCascade(
                            section.children, overrides, parentKey = section.key
                        ))
                    }
                }
                is PromptSection.GatheredRef -> { /* resolved by merge step */ }
                is PromptSection.RemainingGathered -> { /* resolved by merge step */ }
            }
        }
        return result
    }

    /** Converts a [PromptSection.Section] to a [ContextPartition]. */
    private fun sectionToPartition(
        section: PromptSection.Section,
        parentKey: String?,
        overrides: Map<String, CollapseState>
    ): ContextCollapseLogic.ContextPartition {
        val isOverridden = section.key in overrides
        val defaultState = if (section.defaultCollapsed) CollapseState.COLLAPSED else CollapseState.EXPANDED
        val state = if (isOverridden) overrides[section.key] ?: defaultState else defaultState
        val collapsedContent = section.collapsedSummary ?: "[${section.key} collapsed — use CONTEXT_UNCOLLAPSE to expand]"
        return ContextCollapseLogic.ContextPartition(
            key = section.key,
            fullContent = section.content,
            collapsedContent = if (section.isCollapsible) collapsedContent else section.content,
            state = state,
            priority = section.priority,
            isAutoCollapsible = section.isCollapsible && !section.isProtected,
            isAgentOverridden = isOverridden,
            truncateFromStart = section.truncateFromStart,
            parentKey = parentKey
        )
    }

    /**
     * Assembles the final system prompt string from collapsed partitions.
     *
     * Rendering is recursive and depth-aware to support nested holon trees:
     * - Depth 0 → h1 (top-level partitions)
     * - Depth 1 → h2 (sessions, holons, files within a group)
     * - Depth 2 → h3 (sub-holons within a holon)
     * - Depth 3+ → h4 (capped — deepest level supported by ContextDelimiters)
     *
     * Children are rendered INSIDE their parent's delimiters. Collapsed parents
     * suppress all descendant rendering (cascade semantics).
     *
     * ```
     * - [ HOLON_KNOWLEDGE_GRAPH ] -
     *   --- HOLON_KNOWLEDGE_GRAPH:INDEX [PROTECTED] ---
     *   ...
     *   ---
     *   --- hkg:persona-root [EXPANDED] ---
     *     --- hkg:memory-bank [COLLAPSED] ---
     *     ---
     *     --- hkg:skills [EXPANDED] ---
     *       --- hkg:skill-writing [EXPANDED] ---
     *       ...
     *       ---
     *     --- END OF hkg:skills ---
     *   --- END OF hkg:persona-root ---
     *   --- HOLON_KNOWLEDGE_GRAPH:NAVIGATION [PROTECTED] ---
     *   ...
     *   ---
     * - [ END OF HOLON_KNOWLEDGE_GRAPH ] -
     * ```
     *
     * @param partitions The full flat partition list including CONTEXT_BUDGET.
     * @param truncatedKeys Keys of partitions truncated by the sentinel.
     */
    private fun assemblePromptString(
        partitions: List<ContextCollapseLogic.ContextPartition>,
        truncatedKeys: List<String> = emptyList()
    ): String {
        // Build a children map for nested rendering
        val childrenByParent = partitions
            .filter { it.parentKey != null }
            .groupBy { it.parentKey!! }
        val topLevel = partitions.filter { it.parentKey == null }

        fun stateBadge(partition: ContextCollapseLogic.ContextPartition): String = when {
            !partition.isAutoCollapsible -> ContextDelimiters.PROTECTED
            partition.key in truncatedKeys -> ContextDelimiters.TRUNCATED
            partition.state == CollapseState.EXPANDED -> ContextDelimiters.EXPANDED
            else -> ContextDelimiters.COLLAPSED
        }

        /** Recursively compute total chars for a partition and all its visible descendants. */
        fun totalCharsRecursive(partition: ContextCollapseLogic.ContextPartition): Int {
            val ownContent = if (partition.state == CollapseState.EXPANDED) partition.fullContent else partition.collapsedContent
            val ownChars = ownContent.length
            if (partition.state == CollapseState.COLLAPSED) return ownChars
            val childChars = (childrenByParent[partition.key] ?: emptyList()).sumOf { totalCharsRecursive(it) }
            return ownChars + childChars
        }

        /** Depth-aware open delimiter. */
        fun openDelimiter(key: String, chars: Int, badge: String, depth: Int): String = when (depth) {
            0 -> ContextDelimiters.h1(key, chars, badge)
            1 -> ContextDelimiters.h2(key, chars, badge)
            2 -> ContextDelimiters.h3(key, chars, badge)
            else -> ContextDelimiters.h4(key, chars, badge)
        }

        /** Depth-aware close delimiter. */
        fun closeDelimiter(key: String, depth: Int): String = when (depth) {
            0 -> ContextDelimiters.h1End(key)
            1 -> ContextDelimiters.h2End(key)
            2 -> ContextDelimiters.h3End()
            else -> ContextDelimiters.h4End()
        }

        /** Recursively render a partition and its children into the StringBuilder. */
        fun StringBuilder.renderPartition(
            partition: ContextCollapseLogic.ContextPartition,
            depth: Int
        ) {
            val isCollapsed = partition.state == CollapseState.COLLAPSED
            val rawContent = if (!isCollapsed) partition.fullContent else partition.collapsedContent
            val children = if (!isCollapsed) childrenByParent[partition.key] ?: emptyList() else emptyList()

            // Skip entirely empty partitions
            if (rawContent.isBlank() && children.isEmpty()) return

            val totalChars = totalCharsRecursive(partition)

            // Open
            append(openDelimiter(partition.key, totalChars, stateBadge(partition), depth))

            // Own content
            if (rawContent.isNotBlank()) {
                append(rawContent)
            }

            // Children (recursive — depth increases)
            for (child in children) {
                renderPartition(child, depth + 1)
            }

            // Close
            append(closeDelimiter(partition.key, depth))
        }

        val body = buildString {
            for (partition in topLevel) {
                renderPartition(partition, depth = 0)
            }
        }
        return ContextDelimiters.wrapSystemPrompt(body)
    }

    /**
     * Resolves agent.resources map (slotId → resourceId) to actual content.
     * Validates that all required resources are present.
     * Returns null on validation failure (error already reported).
     */
    private fun resolveAgentResources(
        agent: AgentInstance,
        loadedResources: List<AgentResource>,
        strategy: CognitiveStrategy,
        platformDeps: PlatformDependencies,
        store: Store,
        agentState: AgentRuntimeState
    ): Map<String, String>? {
        val resolved = mutableMapOf<String, String>()
        val missingRequired = mutableListOf<String>()

        for (slot in strategy.getResourceSlots()) {
            val resourceId = agent.resources[slot.slotId]

            if (resourceId == null) {
                if (slot.isRequired) {
                    missingRequired.add(slot.displayName)
                }
                continue
            }

            val resource = loadedResources.find { it.id == resourceId.uuid }
            if (resource == null) {
                platformDeps.log(LogLevel.ERROR, LOG_TAG,
                    "Agent '${agent.identityUUID}' references unknown resource '$resourceId' for slot '${slot.slotId}'.")
                missingRequired.add("${slot.displayName} (broken reference: $resourceId)")
                continue
            }

            resolved[slot.slotId] = resource.content
        }

        if (missingRequired.isNotEmpty()) {
            val errorMsg = "Missing required resources: ${missingRequired.joinToString(", ")}"
            platformDeps.log(LogLevel.ERROR, LOG_TAG, "Agent '${agent.identityUUID}': $errorMsg")
            AgentAvatarLogic.updateAgentAvatars(agent.identityUUID, store, agentState, AgentStatus.ERROR, errorMsg)
            store.dispatch("agent", Action(ActionRegistry.Names.CORE_SHOW_TOAST, buildJsonObject {
                put("message", "Agent '${agent.identity.name}': $errorMsg")
            }))
            return null
        }

        return resolved
    }

    private fun handleGatewayPreviewResponse(payload: JsonObject, store: Store) {
        val decoded = try {
            json.decodeFromJsonElement<GatewayPreviewResponsePayload>(payload)
        } catch (e: Exception) {
            store.platformDependencies.log(
                LogLevel.ERROR, LOG_TAG,
                "handleGatewayPreviewResponse: Failed to parse preview response: ${e.message}"
            )
            return
        }
        val agentId = IdentityUUID(decoded.correlationId)
        val state = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayPreviewResponse: Agent state missing.")
            return
        }
        val agent = state.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleGatewayPreviewResponse: Agent '$agentId' not found. May have been deleted during preview.")
            return
        }

        // Debounced gateway preview response — update token estimate + raw JSON
        // for the Manage Context UI (Tabs 1+2). View is already open.
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_UPDATE_MANAGED_PREVIEW, buildJsonObject {
            put("agentId", agent.identityUUID.uuid)
            put("rawRequestJson", decoded.rawRequestJson)
            decoded.estimatedInputTokens?.let { put("estimatedInputTokens", it) }
        }))
    }

    private fun handleGatewayResponse(payload: JsonObject, store: Store) {
        val agentIdStr = payload["correlationId"]?.jsonPrimitive?.contentOrNull
        if (agentIdStr == null) {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Missing correlationId in gateway response payload.")
            return
        }
        val agentId = IdentityUUID(agentIdStr)
        val agentState = store.state.value.featureStates["agent"] as? AgentRuntimeState ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Agent state missing.")
            return
        }
        val decoded = try {
            json.decodeFromJsonElement<GatewayResponsePayload>(payload)
        } catch (e: Exception) {
            store.platformDependencies.log(
                LogLevel.ERROR, LOG_TAG,
                "handleGatewayResponse: Failed to parse gateway response for agent '$agentId': ${e.message}"
            )
            AgentAvatarLogic.updateAgentAvatars(agentId, store, agentState, AgentStatus.ERROR, "Failed to parse gateway response.")
            return
        }
        val agent = agentState.agents[agentId] ?: run {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "handleGatewayResponse: Agent '$agentId' not found. May have been deleted during generation.")
            return
        }
        val agentUuid = agent.identityUUID
        val targetSessionUUID = agent.outputSessionId ?: agent.subscribedSessionIds.firstOrNull() ?: run {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Agent '$agentUuid' has no target session to post response to.")
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.ERROR, "No target session for response.")
            return
        }
        if (store.state.value.identityRegistry.findByUUID(targetSessionUUID) == null) {
            store.platformDependencies.log(LogLevel.ERROR, LOG_TAG, "handleGatewayResponse: Session UUID '$targetSessionUUID' not in registry for agent '$agentUuid'.")
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.ERROR, "Target session not in registry.")
            return
        }

        // ================================================================
        // Error Handling (with rate limit detection)
        // ================================================================
        if (decoded.errorMessage != null) {
            // Check if this is a rate limit error — the gateway sets retryAfterMs on HTTP 429.
            val retryAfterMs = decoded.rateLimitInfo?.get("retryAfterMs")?.jsonPrimitive?.longOrNull

            if (retryAfterMs != null) {
                // RATE LIMIT PATH: Set agent to RATE_LIMITED with retry timestamp.
                // The auto-trigger heartbeat will re-initiate the turn once the window expires.
                store.platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "Agent '$agentUuid' rate limited by provider '${agent.modelProvider}'. " +
                            "Retry after: ${store.platformDependencies.formatIsoTimestamp(retryAfterMs)}. " +
                            "Error: ${decoded.errorMessage}"
                )
                store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
                    put("agentId", agentUuid.uuid)
                    put("status", AgentStatus.RATE_LIMITED.name)
                    put("error", decoded.errorMessage)
                    put("rateLimitedUntilMs", retryAfterMs)
                }))
                AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState)
                return
            }

            // NORMAL ERROR PATH: No rate limit — show error on avatar.
            store.platformDependencies.log(
                LogLevel.ERROR, LOG_TAG,
                "Agent '$agentUuid' generation failed (provider '${agent.modelProvider}', model '${agent.modelName}'): ${decoded.errorMessage}"
            )
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.ERROR, "[AGENT ERROR] Generation failed: ${decoded.errorMessage}")
            return
        }

        // ================================================================
        // Success Path
        // ================================================================
        val rawContent = decoded.rawContent ?: ""

        // Log rate limit info from successful response at DEBUG level
        decoded.rateLimitInfo?.let { rl ->
            store.platformDependencies.log(
                LogLevel.DEBUG, LOG_TAG,
                "Rate limit snapshot for agent '$agentUuid' (provider '${agent.modelProvider}'): " +
                        "requests=${rl["requestsRemaining"]?.jsonPrimitive?.intOrNull}/${rl["requestLimit"]?.jsonPrimitive?.intOrNull}, " +
                        "tokens=${rl["tokensRemaining"]?.jsonPrimitive?.intOrNull}/${rl["tokenLimit"]?.jsonPrimitive?.intOrNull}"
            )
        }

        // Cognitive Strategy Post-Processing
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        val result = strategy.postProcessResponse(rawContent, cognitiveState)

        // 1. Handle State Updates
        if (result.newState != cognitiveState) {
            store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
                put("agentId", agentIdStr)
                put("state", result.newState)
            }))
        }

        // 2. Handle Sentinel Actions
        if (result.action == SentinelAction.HALT_AND_SILENCE) {
            store.platformDependencies.log(LogLevel.WARN, LOG_TAG, "Agent '$agentUuid' halted by Cognitive Strategy (Sentinel Action).")
            AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.IDLE, "Halted by Internal Sentinel.")
            return
        }

        // 3. Handle PROCEED_WITH_UPDATE — a state transition occurred (e.g., BOOTING → AWAKE).
        //    NVRAM is persisted (done above). The response IS posted — for Sovereign boot,
        //    this is the persona's first conscious act (running the boot sequence).
        //    The distinction from PROCEED is for logging and future UI indicators.
        if (result.action == SentinelAction.PROCEED_WITH_UPDATE) {
            store.platformDependencies.log(LogLevel.INFO, LOG_TAG,
                "Agent '$agentUuid' completed state transition via PROCEED_WITH_UPDATE. " +
                        "New state: ${result.newState}. Response will be posted normally.")
            // Fall through to the normal posting path below.
        }

        // 4. Run system sentinels
        var contentToPost = rawContent
        val match = redundantHeaderRegex.find(contentToPost)
        if (match != null) {
            contentToPost = contentToPost.substring(match.range.last + 1).trimStart()
            store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
                put("session", targetSessionUUID.uuid)
                put("senderId", "system")
                put("message", """SYSTEM SENTINEL (llm-output-sanitizer): Warning for [${agent.identity.name}]: Please do not include the standard system "name (id) @timestamp:" part in your output. The host system adds this automatically.""")
            }))
        }

        // 4. Proceed to Post — senderId is now the agent's handle for bus addressing
        store.deferredDispatch("agent", Action(ActionRegistry.Names.SESSION_POST, buildJsonObject {
            put("session", targetSessionUUID.uuid); put("senderId", agent.identityHandle.handle); put("message", contentToPost)
        }))

        // Determine post-turn status: always IDLE (runtime-owned).
        // Strategy display hints are stored separately for the UI.
        AgentAvatarLogic.updateAgentAvatars(agentUuid, store, agentState, AgentStatus.IDLE)

        // Forward token usage and strategy status label to agent state
        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_STATUS, buildJsonObject {
            put("agentId", agentUuid.uuid)
            put("status", AgentStatus.IDLE.name)
            decoded.inputTokens?.let { put("lastInputTokens", it) }
            decoded.outputTokens?.let { put("lastOutputTokens", it) }
            result.displayHint?.let { put("strategyDisplayHint", it) }
        }))

        if (decoded.inputTokens == null && decoded.outputTokens == null) {
            store.platformDependencies.log(
                LogLevel.WARN, LOG_TAG,
                "Gateway response for agent '$agentUuid' contained no token usage data. " +
                        "Provider '${agent.modelProvider}' may not support usage reporting or there is a deserialization issue."
            )
        }
    }
}