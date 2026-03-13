package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.IdentityUUID
import app.auf.core.generated.ActionRegistry
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentCrudLogic.
 * Verifies pure state transitions for configuration and persistence logic.
 */
class AgentRuntimeFeatureT1CrudLogicTest {

    private val platform = FakePlatformDependencies("test")

    @BeforeTest
    fun setUp() {
        CognitiveStrategyRegistry.clearForTesting()
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.MinimalStrategy)
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.VanillaStrategy,
            legacyId = "vanilla_v1"
        )
        CognitiveStrategyRegistry.register(
            app.auf.feature.agent.strategies.SovereignStrategy,
            legacyId = "sovereign_v1"
        )
    }

    @AfterTest
    fun tearDown() {
        CognitiveStrategyRegistry.clearForTesting()
    }

    // =========================================================================
    // AGENT_CREATE
    // =========================================================================

    @Test
    fun `CREATE should add new agent with valid defaults`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionRegistry.Names.AGENT_CREATE, buildJsonObject {
            put("name", "My Agent")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        assertEquals(1, newState.agents.size)
        val agent = newState.agents.values.first()
        assertEquals("My Agent", agent.identity.name)
        assertEquals("gemini", agent.modelProvider)
        assertEquals(false, agent.automaticMode)
        assertEquals(agent.identityUUID, newState.editingAgentId)
    }

    @Test
    fun `CREATE with budget fields produces agent with correct budget values`() {
        val state = AgentRuntimeState()

        val result = AgentCrudLogic.reduce(state, Action(
            ActionRegistry.Names.AGENT_CREATE,
            buildJsonObject {
                put("name", "BudgetAgent")
                put("contextBudgetChars", 80_000)
                put("contextMaxBudgetChars", 200_000)
                put("contextMaxPartialChars", 30_000)
            }
        ), platform)

        val agent = result.agents.values.first()
        assertEquals(80_000, agent.contextBudgetChars)
        assertEquals(200_000, agent.contextMaxBudgetChars)
        assertEquals(30_000, agent.contextMaxPartialChars)
    }

    @Test
    fun `CREATE without budget fields uses design doc defaults`() {
        val state = AgentRuntimeState()

        val result = AgentCrudLogic.reduce(state, Action(
            ActionRegistry.Names.AGENT_CREATE,
            buildJsonObject { put("name", "DefaultAgent") }
        ), platform)

        val agent = result.agents.values.first()
        assertEquals(50_000, agent.contextBudgetChars)
        assertEquals(150_000, agent.contextMaxBudgetChars)
        assertEquals(20_000, agent.contextMaxPartialChars)
    }

    // =========================================================================
    // AGENT_UPDATE_CONFIG
    // =========================================================================

    @Test
    fun `UPDATE_CONFIG should filter out private sessions from subscriptions`() {
        val agent = testAgent("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("public-1"))
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            subscribableSessionNames = mapOf(uid("public-1") to "Public Chat")
        )

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("subscribedSessionIds", buildJsonArray { add("public-1"); add("private-1") })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!

        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertEquals(IdentityUUID("public-1"), updatedAgent.subscribedSessionIds.first())
    }

    @Test
    fun `UPDATE_CONFIG should update resources map`() {
        val agent = testAgent("a1", "Test", null, "p", "m", resources = emptyMap())
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("resources", buildJsonObject { put("CONSTITUTION", "res-123"); put("BOOTLOADER", "res-456") })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!

        assertEquals(2, updatedAgent.resources.size)
        assertEquals(IdentityUUID("res-123"), updatedAgent.resources["CONSTITUTION"])
        assertEquals(IdentityUUID("res-456"), updatedAgent.resources["BOOTLOADER"])
    }

    @Test
    fun `UPDATE_CONFIG without resources field preserves existing resources`() {
        val agent = testAgent("a1", "Test", null, "p", "m", resources = mapOf("CONSTITUTION" to "res-existing"))
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1"); put("name", "Renamed")
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!

        assertEquals("Renamed", updatedAgent.identity.name)
        assertEquals(IdentityUUID("res-existing"), updatedAgent.resources["CONSTITUTION"])
    }

    @Test
    fun `UPDATE_CONFIG should update knowledgeGraphId in strategyConfig`() {
        val agent = testAgent("a1", "Sovereign", "kg-old", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("strategyConfig", buildJsonObject { put("knowledgeGraphId", "kg-new") })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!

        assertEquals("kg-new", updatedAgent.strategyConfig["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull)
        // cognitiveState (NVRAM) should be untouched by config changes
        assertEquals("BOOTING", (updatedAgent.cognitiveState as JsonObject)["phase"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `UPDATE_CONFIG should set knowledgeGraphId to JsonNull on revocation`() {
        val agent = testAgent("a1", "Sovereign", "kg-old", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("strategyConfig", buildJsonObject { put("knowledgeGraphId", JsonNull) })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!

        assertTrue(updatedAgent.strategyConfig["knowledgeGraphId"] is JsonNull)
    }

    @Test
    fun `UPDATE_CONFIG without strategyConfig preserves existing knowledgeGraphId`() {
        val agent = testAgent("a1", "Sovereign", "kg-keep", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1"); put("name", "Renamed")
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents[uid("a1")]!!
        assertEquals("kg-keep", updatedAgent.strategyConfig["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `UPDATE_CONFIG with budget fields updates agent`() {
        val agentId = uid("a1")
        val state = AgentRuntimeState(agents = mapOf(agentId to testAgent("a1", "Test")))

        val result = AgentCrudLogic.reduce(state, Action(
            ActionRegistry.Names.AGENT_UPDATE_CONFIG,
            buildJsonObject {
                put("agentId", "a1")
                put("contextBudgetChars", 100_000)
                put("contextMaxBudgetChars", 300_000)
                put("contextMaxPartialChars", 40_000)
            }
        ), platform)

        val updated = result.agents[agentId]!!
        assertEquals(100_000, updated.contextBudgetChars)
        assertEquals(300_000, updated.contextMaxBudgetChars)
        assertEquals(40_000, updated.contextMaxPartialChars)
    }

    @Test
    fun `UPDATE_CONFIG without budget fields preserves existing values`() {
        val agentId = uid("a1")
        val agent = testAgent("a1", "Test")
        val state = AgentRuntimeState(agents = mapOf(agentId to agent))

        val result = AgentCrudLogic.reduce(state, Action(
            ActionRegistry.Names.AGENT_UPDATE_CONFIG,
            buildJsonObject { put("agentId", "a1"); put("name", "Renamed") }
        ), platform)

        val updated = result.agents[agentId]!!
        assertEquals(agent.contextBudgetChars, updated.contextBudgetChars)
        assertEquals(agent.contextMaxBudgetChars, updated.contextMaxBudgetChars)
        assertEquals(agent.contextMaxPartialChars, updated.contextMaxPartialChars)
    }

    // =========================================================================
    // AGENT_TOGGLE_AUTOMATIC_MODE / AGENT_TOGGLE_ACTIVE
    // =========================================================================

    @Test
    fun `TOGGLE_AUTOMATIC_MODE should flip the boolean flag`() {
        val agent = testAgent("a1", "Test", null, "p", "m", automaticMode = false)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertTrue(newState.agents[uid("a1")]!!.automaticMode)
    }

    @Test
    fun `TOGGLE_ACTIVE should flip the isAgentActive flag`() {
        val agent = testAgent("a1", "Test", null, "p", "m", isAgentActive = true)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_TOGGLE_ACTIVE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertFalse(newState.agents[uid("a1")]!!.isAgentActive)
    }

    @Test
    fun `TOGGLE_ACTIVE twice should return to original state`() {
        val agent = testAgent("a1", "Test", null, "p", "m", isAgentActive = true)
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_TOGGLE_ACTIVE, buildJsonObject { put("agentId", "a1") })
        val state2 = AgentCrudLogic.reduce(state, action, platform)
        val state3 = AgentCrudLogic.reduce(state2, action, platform)

        assertTrue(state3.agents[uid("a1")]!!.isAgentActive)
    }

    // =========================================================================
    // AGENT_CONFIRM_DELETE
    // =========================================================================

    @Test
    fun `DELETE (internal confirm) should remove agent and avatar card info`() {
        val agent = testAgent("a1", "Test", null, "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf(uid("a1") to agent),
            agentAvatarCardIds = mapOf(uid("a1") to mapOf(uid("s-1") to "msg-1"))
        )

        val action = Action(ActionRegistry.Names.AGENT_CONFIRM_DELETE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertNull(newState.agents[uid("a1")])
        assertNull(newState.agentAvatarCardIds[uid("a1")])
    }

    // =========================================================================
    // AGENT_AGENT_LOADED (Deduplication Guard)
    // =========================================================================

    @Test
    fun `AGENT_LOADED should add agent if not already present`() {
        val agent = testAgent("a1", "Loaded Agent", null, "p", "m")
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.AGENT_AGENT_LOADED, Json.encodeToJsonElement(AgentInstance.serializer(), agent).jsonObject)
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals(1, newState.agents.size)
        assertEquals("Loaded Agent", newState.agents[uid("a1")]!!.identity.name)
    }

    @Test
    fun `AGENT_LOADED should NOT overwrite existing agent (dedup guard)`() {
        val existing = testAgent("a1", "Original", null, "p", "m")
        val duplicate = testAgent("a1", "Duplicate", null, "p", "m")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to existing))

        val action = Action(ActionRegistry.Names.AGENT_AGENT_LOADED, Json.encodeToJsonElement(AgentInstance.serializer(), duplicate).jsonObject)
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals("Original", newState.agents[uid("a1")]!!.identity.name)
    }

    // =========================================================================
    // RESOURCE_LOADED (Merge Logic)
    // =========================================================================

    @Test
    fun `RESOURCE_LOADED should add new resource`() {
        val resource = AgentResource("r1", AgentResourceType.CONSTITUTION, "My Const", "content", isBuiltIn = false)
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.AGENT_RESOURCE_LOADED, Json.encodeToJsonElement(resource).jsonObject)
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals(1, newState.resources.size)
        assertEquals("My Const", newState.resources.first().name)
    }

    @Test
    fun `RESOURCE_LOADED should replace existing resource with same ID`() {
        val old = AgentResource("r1", AgentResourceType.CONSTITUTION, "Old", "old content", isBuiltIn = false)
        val updated = AgentResource("r1", AgentResourceType.CONSTITUTION, "Updated", "new content", isBuiltIn = false)
        val state = AgentRuntimeState(resources = listOf(old))

        val action = Action(ActionRegistry.Names.AGENT_RESOURCE_LOADED, Json.encodeToJsonElement(updated).jsonObject)
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals(1, newState.resources.size)
        assertEquals("Updated", newState.resources.first().name)
        assertEquals("new content", newState.resources.first().content)
    }

    // =========================================================================
    // Resource CRUD
    // =========================================================================

    @Test
    fun `CREATE_RESOURCE adds a new resource`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Test Const"); put("type", "CONSTITUTION")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        assertEquals(1, newState.resources.size)
        val created = newState.resources.last()
        assertEquals("Test Const", created.name)
        assertEquals(AgentResourceType.CONSTITUTION, created.type)
        assertEquals(newState.editingResourceId, created.id)
    }

    @Test
    fun `CREATE_RESOURCE supports cloning via initialContent`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Cloned Const"); put("type", "CONSTITUTION"); put("initialContent", "CLONED CONTENT")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)
        assertEquals("CLONED CONTENT", newState.resources.last().content)
    }

    @Test
    fun `SAVE_RESOURCE updates content of custom resource`() {
        val initialState = AgentRuntimeState()
        val createAction = Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Editable"); put("type", "BOOTLOADER")
        })
        val stateWithResource = AgentCrudLogic.reduce(initialState, createAction, platform)
        val resourceId = stateWithResource.resources.last().id

        val saveAction = Action(ActionRegistry.Names.AGENT_SAVE_RESOURCE, buildJsonObject {
            put("resourceId", resourceId); put("content", "Updated Content")
        })
        val finalState = AgentCrudLogic.reduce(stateWithResource, saveAction, platform)

        assertEquals("Updated Content", finalState.resources.last().content)
    }

    @Test
    fun `SAVE_RESOURCE should reject save on built-in resource`() {
        val builtIn = AgentResource("bi-1", AgentResourceType.CONSTITUTION, "BuiltIn", "Original", isBuiltIn = true)
        val state = AgentRuntimeState(resources = listOf(builtIn))

        val action = Action(ActionRegistry.Names.AGENT_SAVE_RESOURCE, buildJsonObject {
            put("resourceId", "bi-1"); put("content", "Hacked Content")
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        assertEquals("Original", newState.resources.first().content)
    }

    @Test
    fun `RENAME_RESOURCE updates name`() {
        val initialState = AgentRuntimeState()
        val createAction = Action(ActionRegistry.Names.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Old Name"); put("type", "BOOTLOADER")
        })
        val stateWithResource = AgentCrudLogic.reduce(initialState, createAction, platform)
        val resourceId = stateWithResource.resources.last().id

        val renameAction = Action(ActionRegistry.Names.AGENT_RENAME_RESOURCE, buildJsonObject {
            put("resourceId", resourceId); put("newName", "New Name")
        })
        val finalState = AgentCrudLogic.reduce(stateWithResource, renameAction, platform)

        assertEquals("New Name", finalState.resources.last().name)
    }

    @Test
    fun `RENAME_RESOURCE should not rename built-in resource`() {
        val builtIn = AgentResource("bi-1", AgentResourceType.CONSTITUTION, "BuiltIn", "content", isBuiltIn = true)
        val state = AgentRuntimeState(resources = listOf(builtIn))

        val action = Action(ActionRegistry.Names.AGENT_RENAME_RESOURCE, buildJsonObject {
            put("resourceId", "bi-1"); put("newName", "Renamed")
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        assertEquals("BuiltIn", newState.resources.first().name)
    }

    @Test
    fun `DELETE_RESOURCE should remove custom resource`() {
        val resource = AgentResource("r1", AgentResourceType.CONSTITUTION, "Custom", "content", isBuiltIn = false)
        val state = AgentRuntimeState(resources = listOf(resource), editingResourceId = "r1")

        val action = Action(ActionRegistry.Names.AGENT_DELETE_RESOURCE, buildJsonObject { put("resourceId", "r1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertTrue(newState.resources.isEmpty())
        assertNull(newState.editingResourceId)
    }

    @Test
    fun `DELETE_RESOURCE should NOT delete built-in resource`() {
        val builtIn = AgentResource("bi-1", AgentResourceType.CONSTITUTION, "BuiltIn", "content", isBuiltIn = true)
        val state = AgentRuntimeState(resources = listOf(builtIn))

        val action = Action(ActionRegistry.Names.AGENT_DELETE_RESOURCE, buildJsonObject { put("resourceId", "bi-1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals(1, newState.resources.size)
    }

    @Test
    fun `DELETE_RESOURCE should preserve editingResourceId if different resource deleted`() {
        val r1 = AgentResource("r1", AgentResourceType.CONSTITUTION, "A", "a", isBuiltIn = false)
        val r2 = AgentResource("r2", AgentResourceType.BOOTLOADER, "B", "b", isBuiltIn = false)
        val state = AgentRuntimeState(resources = listOf(r1, r2), editingResourceId = "r1")

        val action = Action(ActionRegistry.Names.AGENT_DELETE_RESOURCE, buildJsonObject { put("resourceId", "r2") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertEquals("r1", newState.editingResourceId)
        assertEquals(1, newState.resources.size)
    }

    // =========================================================================
    // UPDATE_NVRAM / NVRAM_LOADED
    // =========================================================================

    @Test
    fun `UPDATE_NVRAM should merge updates into existing cognitiveState`() {
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_NVRAM, buildJsonObject {
            put("agentId", "a1")
            put("updates", buildJsonObject {
                put("phase", "AWAKE")
                put("operationalPosture", "ELEVATED")
            })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val cogState = newState.agents[uid("a1")]!!.cognitiveState as JsonObject

        assertEquals("AWAKE", cogState["phase"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ELEVATED", cogState["operationalPosture"]?.jsonPrimitive?.contentOrNull)
        // knowledgeGraphId lives in cognitiveState via testAgent (legacy) — preserved by merge
        assertEquals("kg1", cogState["knowledgeGraphId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `UPDATE_NVRAM on vanilla agent should create cognitiveState from scratch`() {
        val agent = testAgent("a1", "Vanilla", null, "p", "m")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_NVRAM, buildJsonObject {
            put("agentId", "a1")
            put("updates", buildJsonObject { put("custom_key", "value") })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val cogState = newState.agents[uid("a1")]!!.cognitiveState as JsonObject
        assertEquals("value", cogState["custom_key"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `NVRAM_LOADED should replace entire cognitiveState`() {
        val agent = testAgent("a1", "Sovereign", "kg1", "p", "m", cognitiveStrategyId = "sovereign_v1")
        val state = AgentRuntimeState(agents = mapOf(uid("a1") to agent))

        val newCogState = buildJsonObject {
            put("phase", "AWAKE")
            put("knowledgeGraphId", "kg1")
            put("boot_count", 3)
        }

        val action = Action(ActionRegistry.Names.AGENT_NVRAM_LOADED, buildJsonObject {
            put("agentId", "a1")
            put("state", newCogState)
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val cogState = newState.agents[uid("a1")]!!.cognitiveState as JsonObject

        assertEquals("AWAKE", cogState["phase"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, cogState["boot_count"]?.jsonPrimitive?.intOrNull)
    }

    // =========================================================================
    // Boundary Tests
    // =========================================================================

    @Test
    fun `UPDATE_CONFIG for non-existent agent should be a no-op`() {
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "non-existent"); put("name", "Ghost")
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        assertEquals(state, newState)
    }

    @Test
    fun `TOGGLE_AUTOMATIC_MODE for non-existent agent should be a no-op`() {
        val state = AgentRuntimeState()

        val action = Action(ActionRegistry.Names.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", "nope") })
        val newState = AgentCrudLogic.reduce(state, action, platform)
        assertEquals(state, newState)
    }

    @Test
    fun `unrecognized action should be a no-op`() {
        val state = AgentRuntimeState()
        val newState = AgentCrudLogic.reduce(state, Action("totally.unknown", null), platform)
        assertEquals(state, newState)
    }
}