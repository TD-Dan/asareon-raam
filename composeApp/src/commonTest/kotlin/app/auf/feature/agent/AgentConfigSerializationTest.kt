package app.auf.feature.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentConfigSerializationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `should serialize and deserialize AgentInstance with cognitive state correctly`() {
        // Arrange
        val cognitiveState = buildJsonObject {
            put("phase", "AWAKE")
            put("rigor", "MAXIMUM")
            put("boot_count", 1)
        }

        val original = AgentInstance(
            id = "agent-123",
            name = "Test Agent",
            modelProvider = "openai",
            modelName = "gpt-4",
            cognitiveStrategyId = "sovereign_v1",
            cognitiveState = cognitiveState,
            resources = mapOf("constitution" to "const_v1")
        )

        // Act
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<AgentInstance>(serialized)

        // Assert
        assertEquals(original, deserialized)
        assertEquals("sovereign_v1", deserialized.cognitiveStrategyId)

        // Verify we can read the structure of the opaque state
        val deserializedState = deserialized.cognitiveState as kotlinx.serialization.json.JsonObject
        assertEquals("AWAKE", deserializedState["phase"].toString().replace("\"", ""))
        assertEquals("MAXIMUM", deserializedState["rigor"].toString().replace("\"", ""))
    }

    @Test
    fun `should handle null cognitive state (Vanilla default)`() {
        // Arrange
        val original = AgentInstance(
            id = "agent-vanilla",
            name = "Vanilla Agent",
            modelProvider = "openai",
            modelName = "gpt-4"
            // cognitiveState defaults to JsonNull
        )

        // Act
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<AgentInstance>(serialized)

        // Assert
        assertEquals(original, deserialized)
        assertEquals("vanilla_v1", deserialized.cognitiveStrategyId)
    }
}