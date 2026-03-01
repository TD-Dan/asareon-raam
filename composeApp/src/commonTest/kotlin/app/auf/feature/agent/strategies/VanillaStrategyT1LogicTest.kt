package app.auf.feature.agent.strategies

import app.auf.core.IdentityUUID
import app.auf.feature.agent.*
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for VanillaStrategy.
 *
 * Tests cover:
 * - validateConfig: outputSessionId ∈ subscribedSessionIds invariant
 * - prepareSystemPrompt: identity, instructions, session listing, multi-agent context
 * - postProcessResponse: always PROCEED
 * - getBuiltInResources: default system instruction
 */
class VanillaStrategyT1LogicTest {

    // =========================================================================
    // validateConfig
    // =========================================================================

    @Test
    fun `validateConfig should correct outputSessionId when not in subscribedSessionIds`() {
        val agent = testAgent("a1", "Bot", null, "p", "m",
            subscribedSessionIds = listOf("s1", "s2"),
            privateSessionId = "s-nonexistent" // Not in subscriptions
        )

        val validated = VanillaStrategy.validateConfig(agent)

        // Should fall back to first subscribed session
        assertEquals(IdentityUUID("s1"), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should auto-assign outputSessionId when null but subscriptions exist`() {
        val agent = testAgent("a1", "Bot", null, "p", "m",
            subscribedSessionIds = listOf("s1", "s2"),
            privateSessionId = null
        )

        val validated = VanillaStrategy.validateConfig(agent)

        assertEquals(IdentityUUID("s1"), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should leave outputSessionId null when no subscriptions`() {
        val agent = testAgent("a1", "Bot", null, "p", "m",
            subscribedSessionIds = emptyList(),
            privateSessionId = null
        )

        val validated = VanillaStrategy.validateConfig(agent)

        assertNull(validated.outputSessionId)
    }

    @Test
    fun `validateConfig should keep valid outputSessionId that is in subscribedSessionIds`() {
        val agent = testAgent("a1", "Bot", null, "p", "m",
            subscribedSessionIds = listOf("s1", "s2"),
            privateSessionId = "s2"
        )

        val validated = VanillaStrategy.validateConfig(agent)

        assertEquals(IdentityUUID("s2"), validated.outputSessionId)
    }

    @Test
    fun `validateConfig should fall back to null when outputSessionId invalid and no subscriptions`() {
        val agent = testAgent("a1", "Bot", null, "p", "m",
            subscribedSessionIds = emptyList(),
            privateSessionId = "s-orphan"
        )

        val validated = VanillaStrategy.validateConfig(agent)

        assertNull(validated.outputSessionId)
    }

    // =========================================================================
    // prepareSystemPrompt
    // =========================================================================

    @Test
    fun `prepareSystemPrompt should include agent name and identity section`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = emptyMap(),
            gatheredContexts = emptyMap()
        )

        val prompt = VanillaStrategy.prepareSystemPrompt(context, JsonNull)

        assertTrue(prompt.contains("You are HelpBot."))
        assertTrue(prompt.contains("YOUR IDENTITY AND ROLE"))
    }

    @Test
    fun `prepareSystemPrompt should include system instructions when provided`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = mapOf("system_instruction" to "Always be polite."),
            gatheredContexts = emptyMap()
        )

        val prompt = VanillaStrategy.prepareSystemPrompt(context, JsonNull)

        assertTrue(prompt.contains("SYSTEM INSTRUCTIONS"))
        assertTrue(prompt.contains("Always be polite."))
    }

    @Test
    fun `prepareSystemPrompt should not include instructions section when empty`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = emptyMap(),
            gatheredContexts = emptyMap()
        )

        val prompt = VanillaStrategy.prepareSystemPrompt(context, JsonNull)

        assertTrue(!prompt.contains("SYSTEM INSTRUCTIONS"))
    }

    @Test
    fun `prepareSystemPrompt should include multi-agent context before other contexts`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = emptyMap(),
            gatheredContexts = mapOf(
                "MULTI_AGENT_CONTEXT" to "There are 3 participants.",
                "workspace" to "Project files here."
            )
        )

        val prompt = VanillaStrategy.prepareSystemPrompt(context, JsonNull)

        assertTrue(prompt.contains("There are 3 participants."))
        assertTrue(prompt.contains("workspace"))
        assertTrue(prompt.contains("Project files here."))

        // MULTI_AGENT_CONTEXT should appear before the workspace context section
        val multiAgentPos = prompt.indexOf("There are 3 participants.")
        val contextPos = prompt.indexOf("--- CONTEXT ---")
        assertTrue(multiAgentPos < contextPos, "MULTI_AGENT_CONTEXT should appear before other contexts")
    }

    // =========================================================================
    // postProcessResponse
    // =========================================================================

    @Test
    fun `postProcessResponse should always return PROCEED`() {
        val result1 = VanillaStrategy.postProcessResponse("Normal response.", JsonNull)
        assertEquals(SentinelAction.PROCEED, result1.action)

        val result2 = VanillaStrategy.postProcessResponse("[FAILURE_CODE: something]", JsonNull)
        assertEquals(SentinelAction.PROCEED, result2.action)
    }

    // =========================================================================
    // getBuiltInResources
    // =========================================================================

    @Test
    fun `getBuiltInResources should return default system instruction`() {
        val resources = VanillaStrategy.getBuiltInResources()

        assertEquals(1, resources.size)
        val resource = resources.first()
        assertEquals(AgentResourceType.SYSTEM_INSTRUCTION, resource.type)
        assertTrue(resource.isBuiltIn)
        assertTrue(resource.content.isNotBlank())
    }

    // =========================================================================
    // getInitialState
    // =========================================================================

    @Test
    fun `getInitialState should return JsonNull`() {
        assertEquals(JsonNull, VanillaStrategy.getInitialState())
    }

    // =========================================================================
    // getResourceSlots
    // =========================================================================

    @Test
    fun `getResourceSlots should declare system instruction slot`() {
        val slots = VanillaStrategy.getResourceSlots()

        assertEquals(1, slots.size)
        assertEquals("system_instruction", slots.first().slotId)
        assertEquals(AgentResourceType.SYSTEM_INSTRUCTION, slots.first().type)
        assertTrue(slots.first().isRequired)
    }
}