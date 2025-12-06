package app.auf.feature.agent.strategies

import app.auf.feature.agent.AgentTurnContext
import app.auf.feature.agent.SentinelAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SovereignStrategyT1LogicTest {

    private val testContext = AgentTurnContext(
        agentName = "TestAgent",
        systemInstructions = "Be helpful.",
        gatheredContexts = mapOf("session" to "User: Hello")
    )

    @Test
    fun `prepareSystemPrompt includes Sentinel XML when phase is BOOTING`() {
        val state = buildJsonObject { put("phase", "BOOTING") }

        val prompt = SovereignStrategy.prepareSystemPrompt(testContext, state)

        assertTrue(prompt.contains("<boot_sentinel_protocol>"), "Prompt must contain Sentinel in BOOTING phase")
        assertTrue(prompt.contains("TestAgent"), "Prompt must contain Agent Name")
    }

    @Test
    fun `prepareSystemPrompt excludes Sentinel XML when phase is AWAKE`() {
        val state = buildJsonObject { put("phase", "AWAKE") }

        val prompt = SovereignStrategy.prepareSystemPrompt(testContext, state)

        assertFalse(prompt.contains("<boot_sentinel_protocol>"), "Prompt must NOT contain Sentinel in AWAKE phase")
        assertTrue(prompt.contains("TestAgent"), "Prompt must still contain Agent Name")
    }

    @Test
    fun `postProcessResponse transitions to AWAKE on success`() {
        val bootState = buildJsonObject { put("phase", "BOOTING") }
        val response = "Executing boot sequence..." // Normal agent response

        val result = SovereignStrategy.postProcessResponse(response, bootState)

        assertEquals(SentinelAction.PROCEED_WITH_UPDATE, result.action)

        val newPhase = (result.newState as kotlinx.serialization.json.JsonObject)["phase"].toString().replace("\"", "")
        assertEquals("AWAKE", newPhase)
    }

    @Test
    fun `postProcessResponse halts on FAILURE_CODE`() {
        val bootState = buildJsonObject { put("phase", "BOOTING") }
        val response = "[FAILURE_CODE: NO_AGENT_PRESENT]"

        val result = SovereignStrategy.postProcessResponse(response, bootState)

        assertEquals(SentinelAction.HALT_AND_SILENCE, result.action)
        assertEquals(bootState, result.newState, "State should not change on failure")
    }

    @Test
    fun `postProcessResponse does nothing when already AWAKE`() {
        val awakeState = buildJsonObject { put("phase", "AWAKE") }
        val response = "Just doing my job."

        val result = SovereignStrategy.postProcessResponse(response, awakeState)

        assertEquals(SentinelAction.PROCEED, result.action)
        assertEquals(awakeState, result.newState)
    }
}