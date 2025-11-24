package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.feature.knowledgegraph.KnowledgeGraphFeature
import app.auf.fakes.FakePlatformDependencies
import app.auf.test.TestEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tier 3 Peer Test for AgentRuntimeFeature <-> KnowledgeGraphFeature interaction.
 *
 * Mandate (P-TEST-001, T3): To test the runtime contract and emergent behavior
 * between these two collaborating features, specifically verifying that the
 * AgentRuntimeFeature correctly subscribes to persona updates.
 */
class AgentRuntimeFeatureT3KnowledgeGraphPeerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val platform = FakePlatformDependencies("test")
    private val agentFeature = AgentRuntimeFeature(platform, scope)
    // The KG feature is included to make the test environment realistic, though we will dispatch its action manually.
    private val kgFeature = KnowledgeGraphFeature(platform, scope)

    @Test
    fun `agent runtime state should update when available personas are published`() = runTest {
        // --- ARRANGE ---
        val harness = TestEnvironment.create()
            .withFeature(agentFeature)
            .withFeature(kgFeature)
            .withInitialState("agent", AgentRuntimeState())
            .build(platform = platform)

        val personaMap = mapOf(
            "persona-id-1" to "Keel",
            "persona-id-2" to "Sage"
        )
        val payload = buildJsonObject {
            put("names", Json.encodeToJsonElement(personaMap))
        }
        val broadcastAction = Action(ActionNames.KNOWLEDGEGRAPH_PUBLISH_AVAILABLE_PERSONAS_UPDATED, payload)

        // --- ACT ---
        harness.store.dispatch("knowledgegraph", broadcastAction)

        // --- ASSERT ---
        val finalState = harness.store.state.value.featureStates["agent"] as? AgentRuntimeState
        assertNotNull(finalState, "AgentRuntimeState should not be null.")
        assertEquals(2, finalState.knowledgeGraphNames.size, "knowledgeGraphNames map should contain two entries.")
        assertEquals("Keel", finalState.knowledgeGraphNames["persona-id-1"])
        assertEquals("Sage", finalState.knowledgeGraphNames["persona-id-2"])
    }
}