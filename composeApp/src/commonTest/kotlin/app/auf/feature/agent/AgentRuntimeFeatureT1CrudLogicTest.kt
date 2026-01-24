package app.auf.feature.agent

import app.auf.core.Action
import app.auf.core.generated.ActionNames
import app.auf.fakes.FakePlatformDependencies
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * Tier 1 Unit Tests for AgentCrudLogic.
 * Verifies pure state transitions for configuration and persistence logic.
 */
class AgentRuntimeFeatureT1CrudLogicTest {

    private val platform = FakePlatformDependencies("test")

    @Test
    fun `CREATE should add new agent with valid defaults`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionNames.AGENT_CREATE, buildJsonObject {
            put("name", "My Agent")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        assertEquals(1, newState.agents.size)
        val agent = newState.agents.values.first()
        assertEquals("My Agent", agent.name)
        assertEquals("gemini", agent.modelProvider) // Default
        assertEquals(false, agent.automaticMode) // Default
        assertEquals(agent.id, newState.editingAgentId) // Auto-select for editing
    }

    @Test
    fun `UPDATE_CONFIG should filter out private sessions from subscriptions`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", subscribedSessionIds = listOf("public-1"))
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            sessionNames = mapOf(
                "public-1" to "Public Chat",
                "private-1" to "p-cognition: Secret"
            )
        )

        val action = Action(ActionNames.AGENT_UPDATE_CONFIG, buildJsonObject {
            put("agentId", "a1")
            put("subscribedSessionIds", buildJsonArray {
                add("public-1")
                add("private-1")
            })
        })

        val newState = AgentCrudLogic.reduce(state, action, platform)
        val updatedAgent = newState.agents["a1"]!!

        assertEquals(1, updatedAgent.subscribedSessionIds.size)
        assertEquals("public-1", updatedAgent.subscribedSessionIds.first())
    }

    @Test
    fun `TOGGLE_AUTOMATIC_MODE should flip the boolean flag`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m", automaticMode = false)
        val state = AgentRuntimeState(agents = mapOf("a1" to agent))

        val action = Action(ActionNames.AGENT_TOGGLE_AUTOMATIC_MODE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertTrue(newState.agents["a1"]!!.automaticMode)
    }

    @Test
    fun `DELETE (internal confirm) should remove agent and avatar card info`() {
        val agent = AgentInstance("a1", "Test", null, "p", "m")
        val state = AgentRuntimeState(
            agents = mapOf("a1" to agent),
            agentAvatarCardIds = mapOf("a1" to mapOf("s-1" to "msg-1"))
        )

        val action = Action(ActionNames.AGENT_INTERNAL_CONFIRM_DELETE, buildJsonObject { put("agentId", "a1") })
        val newState = AgentCrudLogic.reduce(state, action, platform)

        assertNull(newState.agents["a1"])
        assertNull(newState.agentAvatarCardIds["a1"])
    }

    // --- NEW: Resource CRUD Tests ---

    @Test
    fun `CREATE_RESOURCE adds a new resource`() {
        val initialState = AgentRuntimeState()
        val action = Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Test Const")
            put("type", "CONSTITUTION")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        // Assert: built-ins (2) + 1 new
        assertEquals(3, newState.resources.size)
        val created = newState.resources.last()
        assertEquals("Test Const", created.name)
        assertEquals(AgentResourceType.CONSTITUTION, created.type)
        assertEquals(newState.editingResourceId, created.id)
    }

    @Test
    fun `CREATE_RESOURCE supports cloning via initialContent`() {
        val initialState = AgentRuntimeState()
        val content = "CLONED CONTENT"
        val action = Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Cloned Const")
            put("type", "CONSTITUTION")
            put("initialContent", content)
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)
        val created = newState.resources.last()
        assertEquals(content, created.content)
    }

    @Test
    fun `SAVE_RESOURCE updates content of custom resource`() {
        // Setup: Create a resource first
        val initialState = AgentRuntimeState()
        val createAction = Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Editable")
            put("type", "BOOTLOADER")
        })
        val stateWithResource = AgentCrudLogic.reduce(initialState, createAction, platform)
        val resourceId = stateWithResource.resources.last().id

        // Execute: Save
        val saveAction = Action(ActionNames.AGENT_SAVE_RESOURCE, buildJsonObject {
            put("resourceId", resourceId)
            put("content", "Updated Content")
        })
        val finalState = AgentCrudLogic.reduce(stateWithResource, saveAction, platform)

        // Assert
        assertEquals("Updated Content", finalState.resources.last().content)
    }

    @Test
    fun `SAVE_RESOURCE is ignored for built-in resources`() {
        val initialState = AgentRuntimeState()
        val builtInId = AgentDefaults.builtInResources.first().id
        val action = Action(ActionNames.AGENT_SAVE_RESOURCE, buildJsonObject {
            put("resourceId", builtInId)
            put("content", "Hacked Content")
        })

        val newState = AgentCrudLogic.reduce(initialState, action, platform)

        // Assert: Content unchanged
        assertEquals(AgentDefaults.builtInResources.first().content, newState.resources.first().content)
    }

    @Test
    fun `RENAME_RESOURCE updates name`() {
        // Setup
        val initialState = AgentRuntimeState()
        val createAction = Action(ActionNames.AGENT_CREATE_RESOURCE, buildJsonObject {
            put("name", "Old Name")
            put("type", "BOOTLOADER")
        })
        val stateWithResource = AgentCrudLogic.reduce(initialState, createAction, platform)
        val resourceId = stateWithResource.resources.last().id

        // Execute: Rename
        val renameAction = Action(ActionNames.AGENT_RENAME_RESOURCE, buildJsonObject {
            put("resourceId", resourceId)
            put("newName", "New Name")
        })
        val finalState = AgentCrudLogic.reduce(stateWithResource, renameAction, platform)

        // Assert
        assertEquals("New Name", finalState.resources.last().name)
    }
}