package app.auf.feature.agent

import app.auf.core.AppState
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import app.auf.fakes.FakeStore
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for SovereignAgentLogic.
 */
class AgentRuntimeFeatureT1SovereignAgentLogicTest {

    private val platform = FakePlatformDependencies("test")
    private val fakeStore = FakeStore(AppState(), platform)

    // ... [Other tests remain] ...

    @Test
    fun `ensureSovereignSessions should dispatch CREATE if session missing and ID is null`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null)
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        val sessionAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.SESSION_CREATE }
        assertNotNull(sessionAction)
        assertEquals("p-cognition: Sovereign (a1)", sessionAction.payload?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureSovereignSessions should dispatch UPDATE if session exists but unlinked`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = null)
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = mapOf("s-new" to "p-cognition: Sovereign (a1)")
        )

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        val updateAction = fakeStore.dispatchedActions.find { it.name == ActionRegistry.Names.AGENT_UPDATE_CONFIG }
        assertNotNull(updateAction)
        assertEquals("a1", updateAction.payload?.get("agentId")?.jsonPrimitive?.content)
        assertEquals("s-new", updateAction.payload?.get("privateSessionId")?.jsonPrimitive?.content)
    }

    @Test
    fun `ensureSovereignSessions should DO NOTHING if privateSessionId is set but session is missing`() {
        // [CRITICAL TEST]: This verifies the "Broken Link" tolerance.
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "broken-id-123")
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = emptyMap(), // Session is truly missing from the list
            hkgReservedIds = setOf("kg1")  // ← add this
        )

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        // It should NOT try to create.
        // It should NOT try to update.
        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }

    @Test
    fun `ensureSovereignSessions should DO NOTHING if privateSessionId is set and valid`() {
        fakeStore.dispatchedActions.clear()
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", privateSessionId = "s1")
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = mapOf("s1" to "p-cognition: Sovereign (a1)"),
            hkgReservedIds = setOf("kg1")  // ← add this
        )

        SovereignHKGResourceLogic.ensureSovereignSessions(fakeStore, state)

        assertTrue(fakeStore.dispatchedActions.isEmpty())
    }
}