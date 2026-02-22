package app.auf.feature.agent

import app.auf.core.IdentityHandle
import app.auf.core.IdentityUUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRuntimeFeatureT1ConfigSerializationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `should serialize and deserialize AgentInstance with cognitive state correctly`() {
        // Arrange
        val cognitiveState = buildJsonObject {
            put("phase", "AWAKE")
            put("rigor", "MAXIMUM")
            put("boot_count", 1)
        }

        val original = testAgent(
            id = "agent-123",
            name = "Test Agent",
            modelProvider = "openai",
            modelName = "gpt-4",
            cognitiveStrategyId = "sovereign_v1"
        ).copy(
            cognitiveState = cognitiveState,
            resources = mapOf("constitution" to IdentityUUID("const_v1"))
        )

        // Act
        val serialized = json.encodeToString(AgentInstance.serializer(), original)
        val deserialized = json.decodeFromString(AgentInstance.serializer(), serialized)

        // Assert
        assertEquals(original, deserialized)
        assertEquals(IdentityHandle("agent.strategy.sovereign"), deserialized.cognitiveStrategyId)

        // Verify we can read the structure of the opaque state
        val deserializedState = deserialized.cognitiveState as kotlinx.serialization.json.JsonObject
        assertEquals("AWAKE", deserializedState["phase"].toString().replace("\"", ""))
        assertEquals("MAXIMUM", deserializedState["rigor"].toString().replace("\"", ""))
    }

    @Test
    fun `should handle null cognitive state (Vanilla default)`() {
        // Arrange
        val original = testAgent(
            id = "agent-vanilla",
            name = "Vanilla Agent",
            modelProvider = "openai",
            modelName = "gpt-4"
            // cognitiveState defaults to JsonNull
        )

        // Act
        val serialized = json.encodeToString(AgentInstance.serializer(), original)
        val deserialized = json.decodeFromString(AgentInstance.serializer(), serialized)

        // Assert
        assertEquals(original, deserialized)
        assertEquals(IdentityHandle("agent.strategy.vanilla"), deserialized.cognitiveStrategyId)
    }
}