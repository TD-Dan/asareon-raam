package asareon.raam.feature.agent.strategies

import asareon.raam.core.IdentityUUID
import asareon.raam.feature.agent.*
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Tests for VanillaStrategy.
 *
 * Tests cover:
 * - validateConfig: outputSessionId ∈ subscribedSessionIds invariant
 * - buildPrompt: identity, instructions, session listing, multi-agent context
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
    // buildPrompt
    // =========================================================================

    @Test
    fun `buildPrompt should include agent name and identity section`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val builder = VanillaStrategy.buildPrompt(context, JsonNull)

        val identity = builder.findSection("YOUR IDENTITY AND ROLE")
        assertNotNull(identity, "identity section should be emitted")
        assertTrue(identity.content.contains("HelpBot"),
            "identity content should echo the injected agent name")
    }

    @Test
    fun `buildPrompt should include system instructions when provided`() {
        val sentinel = "SENTINEL_SYSINSTR_${kotlin.random.Random.nextInt()}"
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = mapOf("system_instruction" to sentinel),
            gatheredContextKeys = emptySet()
        )

        val builder = VanillaStrategy.buildPrompt(context, JsonNull)

        val instructions = builder.findSection("SYSTEM INSTRUCTIONS")
        assertNotNull(instructions, "instructions section should be emitted when resource provided")
        assertTrue(instructions.content.contains(sentinel),
            "instructions content should echo the injected resource verbatim")
    }

    @Test
    fun `buildPrompt should not include instructions section when empty`() {
        val context = AgentTurnContext(
            agentName = "HelpBot",
            resolvedResources = emptyMap(),
            gatheredContextKeys = emptySet()
        )

        val builder = VanillaStrategy.buildPrompt(context, JsonNull)

        assertNull(builder.findSection("SYSTEM INSTRUCTIONS"),
            "instructions section should be omitted when no resource is provided")
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