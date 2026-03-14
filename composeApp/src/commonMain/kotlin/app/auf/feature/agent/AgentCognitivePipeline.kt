package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.util.LogLevel
import app.auf.core.Version
import app.auf.util.PlatformDependencies
import app.auf.util.abbreviate
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
object AgentCognitivePipeline {

    private val json = Json { ignoreUnknownKeys = true }
    private const val LOG_TAG = "AgentCognitivePipeline"

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
        // For agents with subscribedSessionIds, request from all of them.
        // For agents with no subscriptions, fall back to outputSessionId.
        val sessionsToRequest = agent.subscribedSessionIds.ifEmpty {
            listOfNotNull(agent.outputSessionId)
        }

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

        // Polymorphic: ask the strategy if it expects additional context.
        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val expectsAdditionalContext = strategy.needsAdditionalContext(agent)
        val additionalContextReady = !expectsAdditionalContext || statusInfo.transientHkgContext != null

        if (workspaceReady && additionalContextReady) {
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

    private fun executeTurn(
        agent: AgentInstance,
        sessionLedgers: Map<IdentityUUID, List<GatewayMessage>>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val agentUuid = agent.identityUUID
        val statusInfo = agentState.agentStatuses[agentUuid] ?: AgentStatusInfo()
        val platformDependencies = store.platformDependencies

        val strategy = CognitiveStrategyRegistry.get(agent.cognitiveStrategyId)
        val cognitiveState = if (agent.cognitiveState !is JsonNull) agent.cognitiveState else strategy.getInitialState()

        platformDependencies.log(LogLevel.DEBUG, LOG_TAG,
            "Assembling prompt for '${agentUuid}' using strategy '${strategy.identityHandle}' (State: ${abbreviate(cognitiveState.toString(),30)}).")

        // === RESOURCE RESOLUTION ===
        val resolvedResources = resolveAgentResources(agent, agentState.resources, strategy, platformDependencies, store, agentState)
        if (resolvedResources == null) {
            return
        }

        val contextMap = mutableMapOf<String, String>()

        // Phase C: Two-partition HKG view (INDEX + FILES).
        // INDEX is always present — the agent's navigational awareness of its knowledge graph.
        // FILES carries token weight and is subject to collapse overrides (default: all closed).
        if (hkgContext != null && hkgContext.isNotEmpty()) {
            val hkgHeaders = HkgContextFormatter.parseHolonHeaders(hkgContext, platformDependencies)
            val agentOverrides = statusInfo.contextCollapseOverrides

            // Build effective overrides: agent sticky overrides + root auto-expand.
            // Root holons (parentId == null) default to EXPANDED so the agent always
            // sees its persona root and immediate children in the INDEX, and has the
            // root file open in FILES. If the agent has explicitly collapsed the root,
            // that sticky override is respected.
            val effectiveOverrides = buildMap {
                // 1. Seed with root defaults (EXPANDED unless agent overrode)
                hkgHeaders.values
                    .filter { it.parentId == null }
                    .forEach { root ->
                        val key = "hkg:${root.id}"
                        if (key !in agentOverrides) {
                            put(key, CollapseState.EXPANDED)
                        }
                    }
                // 2. Layer agent sticky overrides on top (always win)
                putAll(agentOverrides)
            }

            // Resolve persona name from root holon header
            val personaName = hkgHeaders.values.find { it.parentId == null }?.name

            if (personaName == null && hkgHeaders.isNotEmpty()) {
                platformDependencies.log(
                    LogLevel.WARN, LOG_TAG,
                    "HKG for agent '${agentUuid}': No root holon (parentId == null) found among " +
                            "${hkgHeaders.size} holons. INDEX will render without a root — possible broken tree structure."
                )
            }

            contextMap["HOLON_KNOWLEDGE_GRAPH_INDEX"] = HkgContextFormatter.buildIndexTree(
                hkgHeaders, effectiveOverrides, personaName
            )
            contextMap["HOLON_KNOWLEDGE_GRAPH_FILES"] = HkgContextFormatter.buildFilesSection(
                hkgContext, effectiveOverrides, platformDependencies
            )
        }

        // === SESSION METADATA (with token usage context) ===
        val identityRegistry = store.state.value.identityRegistry
        val subscribedSessionNames = agent.subscribedSessionIds.mapNotNull { uuid ->
            identityRegistry.findByUUID(uuid)?.name
        }
        val sessionListDisplay = if (subscribedSessionNames.isNotEmpty())
            subscribedSessionNames.joinToString(", ") else "none"
        val lastInput = statusInfo.lastInputTokens
        val lastOutput = statusInfo.lastOutputTokens
        val tokenUsageContext = if (lastInput != null || lastOutput != null) {
            val total = (lastInput ?: 0) + (lastOutput ?: 0)
            val inputStr = lastInput?.let { "$it" } ?: "N/A"
            val outputStr = lastOutput?.let { "$it" } ?: "N/A"
            "\nLast request token usage (your approximate context size): $total tokens ($inputStr input, $outputStr output). Your model token maximum and saturation point varies by model and context coherence/complexity — consult your provider documentation."
        } else {
            "\nLast request token usage: Not yet available (first turn or provider did not report usage)."
        }

        contextMap["SESSION_METADATA"] = """
            This data is provided for you to reason about your running environment and is updated on the moment of latest request to you.
            
            You are running on platform: 'AUF App ${Version.APP_VERSION} (Windows), a multi-agent, multi-session agent/chat platform.'
            Your Host LLM (API connection): '${agent.modelProvider}' / '${agent.modelName}'
            Subscribed sessions: $sessionListDisplay
            Your agent handle is: '${agent.identityHandle}'
            Your agent id (internal): '${agentUuid}'
            Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.currentTimeMillis())}
        """.trimIndent()
        contextMap["SESSION_METADATA"]+= tokenUsageContext

        // ============================================================
        // Inject available system actions for agent tooling
        // ============================================================
        val agentIdentity = store.state.value.identityRegistry[agent.identityHandle.handle]
        if (agentIdentity != null) {
            contextMap["AVAILABLE_ACTIONS"] = ExposedActionsContextProvider.generateContext(store, agentIdentity)
        }

        // ============================================================
        // Inject workspace context (Two-Partition Model: INDEX + FILES)
        //
        // INDEX: navigational tree with [EXPANDED]/[COLLAPSED] badges.
        // FILES: full content of expanded files only.
        // Mirrors the HKG two-partition approach.
        // ============================================================
        val workspaceListing = statusInfo.transientWorkspaceListing
        if (workspaceListing != null && workspaceListing.isNotEmpty()) {
            val safeAgentIdForWs = agent.identityUUID.uuid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val workspacePrefix = "$safeAgentIdForWs/workspace"

            val wsEntries = WorkspaceContextFormatter.parseListingEntries(
                workspaceListing, workspacePrefix, platformDependencies
            )

            if (wsEntries.isNotEmpty()) {
                val agentOverrides = statusInfo.contextCollapseOverrides

                // Build effective overrides: root directories auto-expanded
                // (agent sees top-level contents by default).
                val effectiveWsOverrides = buildMap {
                    wsEntries
                        .filter { it.parentPath == null && it.isDirectory }
                        .forEach { rootDir ->
                            val key = "ws:${rootDir.relativePath}"
                            if (key !in agentOverrides) {
                                put(key, CollapseState.EXPANDED)
                            }
                        }
                    putAll(agentOverrides)
                }

                contextMap["WORKSPACE_INDEX"] = WorkspaceContextFormatter.buildIndexTree(
                    wsEntries, effectiveWsOverrides
                )

                // FILES section: only if there are expanded files with content
                val fileContents = statusInfo.transientWorkspaceFileContents
                if (fileContents.isNotEmpty()) {
                    contextMap["WORKSPACE_FILES"] = WorkspaceContextFormatter.buildFilesSection(
                        fileContents, effectiveWsOverrides, platformDependencies
                    )
                }
            }
        }

        // ============================================================
        // Inject workspace navigation instructions
        //
        // Parallel to the HKG NAVIGATION section — tells the agent the
        // correct key conventions for workspace collapse/uncollapse.
        // Only injected when the workspace has content.
        // ============================================================
        if (contextMap.containsKey("WORKSPACE_INDEX")) {
            contextMap["WORKSPACE_NAVIGATION"] = """
                |--- WORKSPACE NAVIGATION ---
                |
                |Your workspace is presented as a WORKSPACE_INDEX (tree overview) and WORKSPACE_FILES (open file contents).
                |By default, all files are closed. Use these commands to navigate:
                |
                |Open a single workspace file:
                |```auf_agent.CONTEXT_UNCOLLAPSE
                |{ "partitionKey": "ws:<relativePath>", "scope": "single" }
                |```
                |
                |Expand a directory (reveal its contents in the tree, without opening files):
                |```auf_agent.CONTEXT_UNCOLLAPSE
                |{ "partitionKey": "ws:<dirPath>/", "scope": "single" }
                |```
                |
                |Expand a directory and all sub-directories (tree navigation only, no files opened):
                |```auf_agent.CONTEXT_UNCOLLAPSE
                |{ "partitionKey": "ws:<dirPath>/", "scope": "subtree" }
                |```
                |
                |Close a workspace file or collapse a directory:
                |```auf_agent.CONTEXT_COLLAPSE
                |{ "partitionKey": "ws:<relativePath>" }
                |```
                |
                |IMPORTANT: The prefix is "ws:", not "workspace:". Directory paths end with "/".
                |Example: "ws:sovereign-design.md", "ws:src/", "ws:src/main.kt"
                |You must expand a workspace file before writing to it.
                |The system will block writes to collapsed files to prevent data loss.
                |
                |--- END OF WORKSPACE NAVIGATION ---
            """.trimMargin()
        }

        // ============================================================
        // Build CONVERSATION_LOG context partition
        //
        // The conversation lives in the system prompt as a structured
        // context partition. Providers receive empty contents and inject
        // a minimal trigger message to satisfy API requirements.
        //
        // sessionLedgers maps session UUID → enriched messages for that
        // session. Each entry becomes a SessionLedgerSnapshot.
        // ============================================================
        val outputSessionUUID = agent.outputSessionId
        val outputSessionHandle = outputSessionUUID?.let { identityRegistry.findByUUID(it)?.handle }

        val sessionSnapshots = sessionLedgers.map { (sessionUUID, messages) ->
            val sessIdentity = identityRegistry.findByUUID(sessionUUID)
            ConversationLogFormatter.SessionLedgerSnapshot(
                sessionName = sessIdentity?.name
                    ?: agentState.subscribableSessionNames[sessionUUID]
                    ?: sessionUUID.uuid,
                sessionUUID = sessionUUID.uuid,
                sessionHandle = sessIdentity?.handle ?: sessionUUID.uuid,
                messages = messages,
                isOutputSession = sessionUUID == outputSessionUUID
            )
        }

        contextMap["CONVERSATION_LOG"] = ConversationLogFormatter.format(sessionSnapshots, platformDependencies)

        // ============================================================
        // Build multi-agent context for system prompt
        // ============================================================
        val participants = ConversationLogFormatter.extractParticipants(sessionSnapshots)

        if (participants.size > 2) {
            val multiAgentContext = buildString {
                appendLine("\n--- MULTI-AGENT ENVIRONMENT ---")
                appendLine("This is a multi-agent conversation with the following participants:")
                participants.forEach { (id, name) ->
                    val isSelf = (id == agentUuid.uuid || id == agent.identityHandle.handle)
                    val type = when {
                        isSelf -> "YOU (this agent)"
                        agentState.agents.values.any { it.identityUUID.uuid == id || it.identityHandle.handle == id } -> "AI Agent"
                        agentState.userIdentities.any { it.handle == id } -> "Human User"
                        else -> "User/System"
                    }
                    appendLine("- $name ($id): $type")
                }
                appendLine()
                appendLine("IMPORTANT: Each message in the conversation log is wrapped with sender headers.")
                appendLine("When YOU respond, do NOT include these headers. Just write your response naturally.")
                appendLine("The system will automatically add your name and timestamp to your messages.")
            }
            contextMap["MULTI_AGENT_CONTEXT"] = multiAgentContext
        }

        // ============================================================
        // Build structured session subscription context with participants
        // ============================================================
        val subscribedSessionInfos = agent.subscribedSessionIds.mapNotNull { sessUUID ->
            val sessIdentity = identityRegistry.findByUUID(sessUUID)
            val sessName = sessIdentity?.name
                ?: agentState.subscribableSessionNames[sessUUID]
                ?: sessUUID.uuid
            val sessHandle = sessIdentity?.handle ?: sessUUID.uuid

            // Derive participants from the session's ledger messages
            val sessMessages = sessionLedgers[sessUUID] ?: emptyList()
            val participants = sessMessages
                .groupBy { it.senderId }
                .map { (senderId, messages) ->
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
                handle = sessHandle,
                name = sessName,
                isOutput = sessUUID == outputSessionUUID,
                participants = participants,
                messageCount = sessMessages.size
            )
        }

        val context = AgentTurnContext(
            agentName = agent.identity.name,
            resolvedResources = resolvedResources,
            gatheredContexts = contextMap,
            subscribedSessions = subscribedSessionInfos,
            outputSessionUUID = outputSessionUUID?.uuid,
            outputSessionHandle = outputSessionHandle
        )

        val systemPrompt = strategy.prepareSystemPrompt(context, cognitiveState)

        val requestActionName = if (statusInfo.turnMode == TurnMode.PREVIEW) ActionRegistry.Names.GATEWAY_PREPARE_PREVIEW else ActionRegistry.Names.GATEWAY_GENERATE_CONTENT
        val step = if (statusInfo.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agentUuid.uuid); put("step", step)
        }))

        // System-prompt-only mode: conversation is in CONVERSATION_LOG context partition.
        // Providers inject a minimal trigger message to satisfy API requirements.
        store.deferredDispatch("agent", Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agentUuid.uuid)
            put("contents", buildJsonArray {}) // Empty — conversation is in systemPrompt
            put("systemPrompt", systemPrompt)
        }))
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

        store.deferredDispatch("agent", Action(ActionRegistry.Names.AGENT_SET_PREVIEW_DATA, buildJsonObject {
            put("agentId", agent.identityUUID.uuid)
            put("agnosticRequest", json.encodeToJsonElement(decoded.agnosticRequest))
            put("rawRequestJson", decoded.rawRequestJson)
            decoded.estimatedInputTokens?.let { put("estimatedInputTokens", it) }
        }))
        store.dispatch("agent", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, buildJsonObject { put("key", "feature.agent.context_viewer") }))
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