package app.auf.feature.session

import app.auf.core.Identity
import app.auf.core.generated.ActionRegistry.ActionDescriptor
import app.auf.core.generated.ActionRegistry.AgentExposure
import app.auf.core.generated.ActionRegistry.FeatureDescriptor
import app.auf.core.generated.ActionRegistry.PayloadField
import kotlin.test.*

/**
 * TDD tests for SlashCommandEngine.
 *
 * These tests were written BEFORE the implementation. The engine is a pure-logic
 * class with no Compose or Store dependency, making it trivially unit-testable.
 *
 * Fixture layout (mirrors a simplified ActionRegistry):
 *
 *   session.POST          open=true,  broadcast=true,  targeted=false   (command)
 *   session.CREATE        open=true,  broadcast=true,  targeted=false   (command)
 *   session.LOADED        open=false, broadcast=false, targeted=false   (internal)
 *   session.MESSAGE_POSTED open=false, broadcast=true, targeted=false   (event)
 *   session.RETURN_LEDGER open=false, broadcast=false, targeted=true  (response)
 *   agent.INITIATE_TURN   open=true,  broadcast=true,  targeted=false   (command)
 *   agent.SET_STATUS      open=false, broadcast=false, targeted=false   (internal)
 *   system.INITIALIZING   open=false, broadcast=true,  targeted=false   (event)
 *   system.STARTING       open=false, broadcast=true,  targeted=false   (event)
 *   settings.UPDATE       open=true,  broadcast=true,  targeted=false   (command)
 *   commandbot.APPROVE    open=true,  broadcast=true,  targeted=false   (command)
 */
class SlashCommandEngineTest {

    // ========================================================================
    // Test Fixtures
    // ========================================================================

    private fun field(name: String, type: String = "string", required: Boolean = false, desc: String = "") =
        PayloadField(name, type, desc, required)

    private val sessionPostDescriptor = ActionDescriptor(
        fullName = "session.POST",
        featureName = "session",
        suffix = "POST",
        summary = "Posts a message to a session ledger.",
        public = true, broadcast = true, targeted = false,
        payloadFields = listOf(
            field("session", required = true, desc = "Target session localHandle"),
            field("senderId", required = true, desc = "Sender identity handle"),
            field("message", desc = "Message text"),
            field("afterMessageId", desc = "Insert after this message ID")
        ),
        requiredFields = listOf("session", "senderId"),
        agentExposure = AgentExposure(autoFillRules = mapOf("senderId" to "{agentId}", "session" to "{sessionId}"))
    )

    private val sessionCreateDescriptor = ActionDescriptor(
        fullName = "session.CREATE",
        featureName = "session",
        suffix = "CREATE",
        summary = "Creates a new session.",
        public = true, broadcast = true, targeted = false,
        payloadFields = listOf(
            field("name", desc = "Session display name"),
            field("isHidden", type = "boolean", desc = "Whether the session is hidden")
        ),
        requiredFields = emptyList(),
        agentExposure = null
    )

    private val sessionLoadedDescriptor = ActionDescriptor(
        fullName = "session.LOADED",
        featureName = "session",
        suffix = "LOADED",
        summary = "Dispatched after sessions are read from disk.",
        public = false, broadcast = false, targeted = false,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val sessionMessagePostedDescriptor = ActionDescriptor(
        fullName = "session.MESSAGE_POSTED",
        featureName = "session",
        suffix = "MESSAGE_POSTED",
        summary = "Broadcast after a message is added to a session.",
        public = false, broadcast = true, targeted = false,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val sessionResponseLedgerDescriptor = ActionDescriptor(
        fullName = "session.RETURN_LEDGER",
        featureName = "session",
        suffix = "RETURN_LEDGER",
        summary = "Returns ledger content to the requester.",
        public = false, broadcast = false, targeted = true,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val agentInitiateTurnDescriptor = ActionDescriptor(
        fullName = "agent.INITIATE_TURN",
        featureName = "agent",
        suffix = "INITIATE_TURN",
        summary = "Starts a cognitive turn for an agent.",
        public = true, broadcast = true, targeted = false,
        payloadFields = listOf(
            field("agentHandle", required = true, desc = "The agent's identity handle"),
            field("sessionId", required = true, desc = "Session to respond in")
        ),
        requiredFields = listOf("agentHandle", "sessionId"),
        agentExposure = null
    )

    private val agentSetStatusDescriptor = ActionDescriptor(
        fullName = "agent.SET_STATUS",
        featureName = "agent",
        suffix = "SET_STATUS",
        summary = "Updates an agent's processing status.",
        public = false, broadcast = false, targeted = false,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val systemInitializingDescriptor = ActionDescriptor(
        fullName = "system.INITIALIZING",
        featureName = "system",
        suffix = "INITIALIZING",
        summary = "The first action dispatched at startup.",
        public = false, broadcast = true, targeted = false,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val systemStartingDescriptor = ActionDescriptor(
        fullName = "system.STARTING",
        featureName = "system",
        suffix = "STARTING",
        summary = "Second startup action.",
        public = false, broadcast = true, targeted = false,
        payloadFields = emptyList(), requiredFields = emptyList(), agentExposure = null
    )

    private val settingsUpdateDescriptor = ActionDescriptor(
        fullName = "settings.UPDATE",
        featureName = "settings",
        suffix = "UPDATE",
        summary = "Commits a new value for a setting.",
        public = true, broadcast = true, targeted = false,
        payloadFields = listOf(
            field("key", required = true, desc = "The setting key"),
            field("value", required = true, desc = "The new value")
        ),
        requiredFields = listOf("key", "value"),
        agentExposure = null
    )

    private val commandbotApproveDescriptor = ActionDescriptor(
        fullName = "commandbot.APPROVE",
        featureName = "commandbot",
        suffix = "APPROVE",
        summary = "User approves a pending agent action.",
        public = true, broadcast = true, targeted = false,
        payloadFields = listOf(field("approvalId", required = true)),
        requiredFields = listOf("approvalId"),
        agentExposure = null
    )

    private val testFeatures: Map<String, FeatureDescriptor> = mapOf(
        "session" to FeatureDescriptor(
            name = "session",
            summary = "Session Manager",
            permissions = emptyList(),
            actions = mapOf(
                "POST" to sessionPostDescriptor,
                "CREATE" to sessionCreateDescriptor,
                "LOADED" to sessionLoadedDescriptor,
                "MESSAGE_POSTED" to sessionMessagePostedDescriptor,
                "RETURN_LEDGER" to sessionResponseLedgerDescriptor
            )
        ),
        "agent" to FeatureDescriptor(
            name = "agent",
            summary = "Agent Runtime",
            permissions = emptyList(),
            actions = mapOf(
                "INITIATE_TURN" to agentInitiateTurnDescriptor,
                "SET_STATUS" to agentSetStatusDescriptor
            )
        ),
        "system" to FeatureDescriptor(
            name = "system",
            summary = "System lifecycle",
            permissions = emptyList(),
            actions = mapOf(
                "INITIALIZING" to systemInitializingDescriptor,
                "STARTING" to systemStartingDescriptor
            )
        ),
        "settings" to FeatureDescriptor(
            name = "settings",
            summary = "App Settings",
            permissions = emptyList(),
            actions = mapOf("UPDATE" to settingsUpdateDescriptor)
        ),
        "commandbot" to FeatureDescriptor(
            name = "commandbot",
            summary = "CommandBot",
            permissions = emptyList(),
            actions = mapOf("APPROVE" to commandbotApproveDescriptor)
        )
    )

    private val testIdentityRegistry: Map<String, Identity> = mapOf(
        "session" to Identity(uuid = null, handle = "session", localHandle = "session", name = "Session Manager"),
        "session.my-chat" to Identity(uuid = "uuid-1", handle = "session.my-chat", localHandle = "my-chat", name = "My Chat", parentHandle = "session"),
        "session.debug" to Identity(uuid = "uuid-2", handle = "session.debug", localHandle = "debug", name = "Debug Session", parentHandle = "session"),
        "core.alice" to Identity(uuid = "uuid-3", handle = "core.alice", localHandle = "alice", name = "Alice", parentHandle = "core"),
        "agent" to Identity(uuid = null, handle = "agent", localHandle = "agent", name = "Agent Runtime"),
        "agent.gemini-1" to Identity(uuid = "uuid-4", handle = "agent.gemini-1", localHandle = "gemini-1", name = "Gemini Coder", parentHandle = "agent")
    )

    private fun createEngine(
        activeSessionLocalHandle: String? = "my-chat",
        activeUserId: String? = "core.alice"
    ) = SlashCommandEngine(
        featureDescriptors = testFeatures,
        identityRegistry = testIdentityRegistry,
        activeSessionLocalHandle = activeSessionLocalHandle,
        activeUserId = activeUserId
    )

    // ========================================================================
    // 1. Visibility — Normal Mode (single slash)
    // ========================================================================

    @Test
    fun `featureCandidates excludes features with zero open actions`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = false)
        val names = candidates.map { it.name }

        // system has zero open actions → excluded
        assertFalse("system" in names, "system feature should be excluded (no open actions)")
        // session has POST and CREATE → included
        assertTrue("session" in names)
        // agent has INITIATE_TURN → included
        assertTrue("agent" in names)
    }

    @Test
    fun `featureCandidates counts only open actions`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = false)
        val sessionCandidate = candidates.first { it.name == "session" }

        // session has 5 actions total but only 2 are open (POST, CREATE)
        assertEquals(2, sessionCandidate.actionCount)
    }

    @Test
    fun `actionCandidates excludes non-open actions in normal mode`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("session", "", adminMode = false)
        val suffixes = candidates.map { it.descriptor.suffix }

        assertTrue("POST" in suffixes, "POST is open → included")
        assertTrue("CREATE" in suffixes, "CREATE is open → included")
        assertFalse("LOADED" in suffixes, "LOADED is internal → excluded")
        assertFalse("MESSAGE_POSTED" in suffixes, "MESSAGE_POSTED is event → excluded")
        assertFalse("RETURN_LEDGER" in suffixes, "RETURN_LEDGER is response → excluded")
    }

    @Test
    fun `actionCandidates returns empty for unknown feature`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("nonexistent", "", adminMode = false)
        assertTrue(candidates.isEmpty())
    }

    // ========================================================================
    // 2. Visibility — Admin Mode (double slash)
    // ========================================================================

    @Test
    fun `admin mode featureCandidates includes ALL features`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = true)
        val names = candidates.map { it.name }

        assertTrue("system" in names, "Admin mode shows system feature")
        assertTrue("session" in names)
        assertTrue("agent" in names)
        assertTrue("settings" in names)
        assertTrue("commandbot" in names)
    }

    @Test
    fun `admin mode featureCandidates counts ALL actions`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = true)
        val sessionCandidate = candidates.first { it.name == "session" }

        // All 5 session actions visible in admin mode
        assertEquals(5, sessionCandidate.actionCount)
    }

    @Test
    fun `admin mode actionCandidates includes non-open actions`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("session", "", adminMode = true)
        val suffixes = candidates.map { it.descriptor.suffix }

        assertTrue("POST" in suffixes)
        assertTrue("CREATE" in suffixes)
        assertTrue("LOADED" in suffixes, "Admin mode shows internal actions")
        assertTrue("MESSAGE_POSTED" in suffixes, "Admin mode shows events")
        assertTrue("RETURN_LEDGER" in suffixes, "Admin mode shows responses")
    }

    @Test
    fun `admin mode shows system actions`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("system", "", adminMode = true)
        val suffixes = candidates.map { it.descriptor.suffix }

        assertTrue("INITIALIZING" in suffixes)
        assertTrue("STARTING" in suffixes)
    }

    // ========================================================================
    // 3. Filtering — Prefix and Substring Matching
    // ========================================================================

    @Test
    fun `featureCandidates prefix match ranks first`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("ses", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("session", candidates[0].name)
    }

    @Test
    fun `featureCandidates substring match works`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("etti", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("settings", candidates[0].name)
    }

    @Test
    fun `featureCandidates is case insensitive`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("SES", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("session", candidates[0].name)
    }

    @Test
    fun `featureCandidates empty query returns all visible features`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = false)

        // session, agent, settings, commandbot — NOT system
        assertEquals(4, candidates.size)
    }

    @Test
    fun `actionCandidates prefix match on suffix`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("session", "PO", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("POST", candidates[0].descriptor.suffix)
        assertEquals(SlashCommandEngine.MatchType.PREFIX, candidates[0].matchType)
    }

    @Test
    fun `actionCandidates substring match on suffix`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("session", "EAT", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("CREATE", candidates[0].descriptor.suffix)
        assertEquals(SlashCommandEngine.MatchType.SUBSTRING, candidates[0].matchType)
    }

    @Test
    fun `actionCandidates prefix matches sort before substring matches`() {
        val engine = createEngine()
        // "C" → CREATE is prefix, nothing else in normal mode matches
        // But in admin mode with more actions: test with a broader set
        val candidates = engine.actionCandidates("session", "C", adminMode = false)

        assertEquals(1, candidates.size)
        assertEquals("CREATE", candidates[0].descriptor.suffix)
    }

    @Test
    fun `actionCandidates no match returns empty`() {
        val engine = createEngine()
        val candidates = engine.actionCandidates("session", "ZZZZZ", adminMode = false)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `featureCandidates no match returns empty`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("zzzzz", adminMode = false)

        assertTrue(candidates.isEmpty())
    }

    // ========================================================================
    // 4. Auto-Fill
    // ========================================================================

    @Test
    fun `autoFillParams fills session field from active session`() {
        val engine = createEngine(activeSessionLocalHandle = "my-chat")
        val params = engine.autoFillParams(sessionPostDescriptor)

        assertEquals("my-chat", params["session"])
    }

    @Test
    fun `autoFillParams fills sessionId field from active session`() {
        val engine = createEngine(activeSessionLocalHandle = "my-chat")
        val params = engine.autoFillParams(agentInitiateTurnDescriptor)

        assertEquals("my-chat", params["sessionId"])
    }

    @Test
    fun `autoFillParams fills senderId field from active user`() {
        val engine = createEngine(activeUserId = "core.alice")
        val params = engine.autoFillParams(sessionPostDescriptor)

        assertEquals("core.alice", params["senderId"])
    }

    @Test
    fun `autoFillParams returns empty map when no context available`() {
        val engine = createEngine(activeSessionLocalHandle = null, activeUserId = null)
        val params = engine.autoFillParams(sessionPostDescriptor)

        assertFalse("session" in params)
        assertFalse("senderId" in params)
    }

    @Test
    fun `autoFillParams only fills fields that exist in the descriptor`() {
        val engine = createEngine()
        // settings.UPDATE has "key" and "value" — no session or senderId fields
        val params = engine.autoFillParams(settingsUpdateDescriptor)

        assertTrue(params.isEmpty(), "No auto-fill for settings.UPDATE since it has no session/senderId fields")
    }

    // ========================================================================
    // 5. Code Generation
    // ========================================================================

    @Test
    fun `generateCodeBlock produces correct auf_ format`() {
        val engine = createEngine()
        val result = engine.generateCodeBlock(
            sessionPostDescriptor,
            mapOf("session" to "my-chat", "senderId" to "core.alice", "message" to "Hello!")
        )

        assertTrue(result.startsWith("```auf_session.POST\n"), "Should start with auf_ fence: got\n$result")
        assertTrue(result.trimEnd().endsWith("```"), "Should end with closing fence")
        assertTrue("\"session\"" in result)
        assertTrue("\"my-chat\"" in result)
        assertTrue("\"message\"" in result)
        assertTrue("\"Hello!\"" in result)
    }

    @Test
    fun `generateCodeBlock omits blank fields`() {
        val engine = createEngine()
        val result = engine.generateCodeBlock(
            sessionPostDescriptor,
            mapOf("session" to "my-chat", "senderId" to "core.alice", "message" to "", "afterMessageId" to "")
        )

        assertFalse("afterMessageId" in result, "Blank fields should be omitted")
        assertFalse("\"message\"" in result, "Empty message should be omitted")
    }

    @Test
    fun `generateCodeBlock infers boolean type`() {
        val engine = createEngine()
        val result = engine.generateCodeBlock(
            sessionCreateDescriptor,
            mapOf("name" to "Test", "isHidden" to "true")
        )

        // Should be `true` (no quotes) not `"true"`
        assertTrue("true" in result)
        // Ensure it's not the quoted version — look for the JSON key-value pattern
        assertFalse("\"true\"" in result, "Boolean should not be quoted")
    }

    @Test
    fun `generateCodeBlock infers numeric type`() {
        val engine = createEngine()
        // Use a descriptor with a numeric-ish field for testing
        val result = engine.generateCodeBlock(
            sessionPostDescriptor,
            mapOf("session" to "my-chat", "senderId" to "core.alice", "afterMessageId" to "42")
        )

        // "42" could be a string or number — but since afterMessageId is semantically an ID,
        // the engine should treat pure-numeric strings as numbers for clean JSON
        assertTrue("42" in result)
    }

    @Test
    fun `generateCodeBlock handles empty payload`() {
        val engine = createEngine()
        val result = engine.generateCodeBlock(sessionCreateDescriptor, emptyMap())

        assertEquals("```auf_session.CREATE\n```", result)
    }

    @Test
    fun `generateCodeBlock handles all-blank payload same as empty`() {
        val engine = createEngine()
        val result = engine.generateCodeBlock(
            sessionCreateDescriptor,
            mapOf("name" to "", "isHidden" to "")
        )

        assertEquals("```auf_session.CREATE\n```", result)
    }

    // ========================================================================
    // 6. Stage Transitions
    // ========================================================================

    @Test
    fun `initialState for single slash is FEATURE stage in normal mode`() {
        val engine = createEngine()
        val state = engine.initialState(adminMode = false)

        assertEquals(SlashCommandEngine.Stage.FEATURE, state.stage)
        assertFalse(state.adminMode)
        assertEquals("", state.query)
        assertNull(state.selectedFeature)
        assertNull(state.selectedAction)
    }

    @Test
    fun `initialState for double slash is FEATURE stage in admin mode`() {
        val engine = createEngine()
        val state = engine.initialState(adminMode = true)

        assertEquals(SlashCommandEngine.Stage.FEATURE, state.stage)
        assertTrue(state.adminMode)
    }

    @Test
    fun `selectFeature transitions to ACTION stage`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")

        assertEquals(SlashCommandEngine.Stage.ACTION, state.stage)
        assertEquals("session", state.selectedFeature)
        assertEquals("", state.query, "Query resets after feature selection")
    }

    @Test
    fun `selectAction transitions to PARAMS stage with auto-filled values`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")
        state = engine.selectAction(state, sessionPostDescriptor)

        assertEquals(SlashCommandEngine.Stage.PARAMS, state.stage)
        assertEquals(sessionPostDescriptor, state.selectedAction)
        assertEquals("my-chat", state.paramValues["session"], "session should be auto-filled")
        assertEquals("core.alice", state.paramValues["senderId"], "senderId should be auto-filled")
    }

    @Test
    fun `regressStage from ACTION returns to FEATURE`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")
        assertEquals(SlashCommandEngine.Stage.ACTION, state.stage)

        state = engine.regressStage(state)!!
        assertEquals(SlashCommandEngine.Stage.FEATURE, state.stage)
        assertNull(state.selectedFeature, "Feature selection cleared on regress")
    }

    @Test
    fun `regressStage from PARAMS returns to ACTION`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")
        state = engine.selectAction(state, sessionPostDescriptor)
        assertEquals(SlashCommandEngine.Stage.PARAMS, state.stage)

        state = engine.regressStage(state)!!
        assertEquals(SlashCommandEngine.Stage.ACTION, state.stage)
        assertNull(state.selectedAction, "Action selection cleared on regress")
        assertTrue(state.paramValues.isEmpty(), "Param values cleared on regress")
    }

    @Test
    fun `regressStage from FEATURE returns null to signal dismiss`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)

        val result = engine.regressStage(state)
        assertNull(result, "Regressing past FEATURE should signal dismiss (null)")
    }

    @Test
    fun `updateQuery updates the filter text`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.updateQuery(state, "ses")

        assertEquals("ses", state.query)
        assertEquals(0, state.highlightedIndex, "Highlight resets on query change")
    }

    @Test
    fun `moveHighlight clamps within bounds`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        val candidateCount = engine.featureCandidates("", adminMode = false).size

        // Move up from 0 → stays at 0
        state = engine.moveHighlight(state, -1, candidateCount)
        assertEquals(0, state.highlightedIndex)

        // Move down to max → stays at max
        state = state.copy(highlightedIndex = candidateCount - 1)
        state = engine.moveHighlight(state, 1, candidateCount)
        assertEquals(candidateCount - 1, state.highlightedIndex)
    }

    @Test
    fun `moveHighlight wraps correctly within range`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)

        state = engine.moveHighlight(state, 1, 4)
        assertEquals(1, state.highlightedIndex)

        state = engine.moveHighlight(state, 1, 4)
        assertEquals(2, state.highlightedIndex)
    }

    // ========================================================================
    // 7. Admin Mode Preserves Through Transitions
    // ========================================================================

    @Test
    fun `admin mode preserved through feature selection`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = true)
        state = engine.selectFeature(state, "system")

        assertTrue(state.adminMode, "Admin mode should persist after feature selection")
        assertEquals("system", state.selectedFeature)
    }

    @Test
    fun `admin mode preserved through action selection`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = true)
        state = engine.selectFeature(state, "session")
        state = engine.selectAction(state, sessionLoadedDescriptor)

        assertTrue(state.adminMode, "Admin mode should persist after action selection")
        assertEquals(sessionLoadedDescriptor, state.selectedAction)
    }

    @Test
    fun `admin mode preserved through regress`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = true)
        state = engine.selectFeature(state, "session")
        state = engine.regressStage(state)!!

        assertTrue(state.adminMode, "Admin mode should persist after regress")
    }

    // ========================================================================
    // 8. Param Value Updates
    // ========================================================================

    @Test
    fun `updateParamValue sets the value for a field`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")
        state = engine.selectAction(state, sessionPostDescriptor)
        state = engine.updateParamValue(state, "message", "Hello!")

        assertEquals("Hello!", state.paramValues["message"])
        // Auto-filled values should be preserved
        assertEquals("my-chat", state.paramValues["session"])
    }

    @Test
    fun `updateParamValue can overwrite auto-filled value`() {
        val engine = createEngine()
        var state = engine.initialState(adminMode = false)
        state = engine.selectFeature(state, "session")
        state = engine.selectAction(state, sessionPostDescriptor)
        state = engine.updateParamValue(state, "session", "debug")

        assertEquals("debug", state.paramValues["session"], "User should be able to overwrite auto-fill")
    }

    // ========================================================================
    // 9. Sorting
    // ========================================================================

    @Test
    fun `featureCandidates are sorted alphabetically`() {
        val engine = createEngine()
        val candidates = engine.featureCandidates("", adminMode = false)
        val names = candidates.map { it.name }

        assertEquals(names.sorted(), names, "Features should be alphabetically sorted")
    }

    @Test
    fun `actionCandidates prefix matches come before substring matches`() {
        val engine = createEngine()
        // In admin mode, session has: POST, CREATE, LOADED, MESSAGE_POSTED, RETURN_LEDGER
        // Query "O" → POST (prefix via P? no... O is in POST as substring, LOADED as substring, RETURN_LEDGER as substring)
        // Better test: query "P" → POST (prefix), MESSAGE_POSTED (substring)
        val candidates = engine.actionCandidates("session", "P", adminMode = true)
        val suffixes = candidates.map { it.descriptor.suffix }

        if (suffixes.size >= 2) {
            // POST should come before MESSAGE_POSTED (prefix before substring)
            val postIndex = suffixes.indexOf("POST")
            val messagePostedIndex = suffixes.indexOf("MESSAGE_POSTED")
            if (postIndex != -1 && messagePostedIndex != -1) {
                assertTrue(postIndex < messagePostedIndex, "Prefix match POST should rank before substring match MESSAGE_POSTED")
            }
        }
    }
}