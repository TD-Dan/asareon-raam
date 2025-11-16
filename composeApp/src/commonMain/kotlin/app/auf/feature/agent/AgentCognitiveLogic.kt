package app.auf.feature.agent

import app.auf.core.*
import app.auf.core.generated.ActionNames
import app.auf.util.abbreviate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ## Mandate
 * To provide pure, testable, static functions for an agent's cognitive cycle.
 * This object is the sole authority on context assembly and prompt engineering,
 * isolating this complex, text-heavy logic from the feature's orchestration.
 */
object AgentCognitiveLogic {
    fun assemblePromptAndRequestGeneration(
        agent: AgentInstance,
        ledgerContext: List<GatewayMessage>,
        hkgContext: JsonObject?,
        agentState: AgentRuntimeState,
        store: Store
    ) {
        val platformDependencies = store.platformDependencies
        val json = Json { ignoreUnknownKeys = true }

        val hkgContextContent = hkgContext?.entries?.joinToString("\n\n---\n\n") { (holonId, content) ->
            "--- START OF FILE $holonId.json ---\n${content.jsonPrimitive.content}\n--- END OF FILE $holonId.json ---"
        } ?: ""

        val sessionName = agent.subscribedSessionIds.firstOrNull()?.let { agentState.sessionNames[it] } ?: "Unknown Session"
        var systemPrompt = """
            --- SYSTEM BOOTSTRAP DIRECTIVES ---
            // You are an autonomous agent operating within the multi user and multi agent AUF App.
            // The following directives and context are provided for this turn.

            **OPERATIONAL DIRECTIVES:**
            *   **IDENTITY:** You are agent '${abbreviate(agent.name, 64)}' (ID: ${agent.id}).
            *   **FORMATTING:** Your response MUST be your direct reply only. DO NOT include prefixes (names, IDs, timestamps). The application handles all formatting.
            *   **DISCIPLINE:** You MUST NOT speak for or impersonate any other participant. Generate content only from your own perspective as "${abbreviate(agent.name, 64)}".

            **SITUATIONAL AWARENESS:**
            *   Platform: 'AUF App ${Version.APP_VERSION}'
            *   Host LLM: '${agent.modelProvider}'
            *   Host Model: '${agent.modelName}'
            *   Session: '${abbreviate(sessionName, 64)}'
            *   Request Time: ${platformDependencies.formatIsoTimestamp(platformDependencies.getSystemTimeMillis())}


        """.trimIndent()

        if (hkgContextContent.isNotEmpty()) {
            systemPrompt += """
            --- HOLON KNOWLEDGE GRAPH CONTEXT ---
            $hkgContextContent
            
            """.trimIndent()
        }

        val requestActionName = if (agent.turnMode == TurnMode.PREVIEW) ActionNames.GATEWAY_PREPARE_PREVIEW else ActionNames.GATEWAY_GENERATE_CONTENT
        val step = if (agent.turnMode == TurnMode.PREVIEW) "Preparing Preview" else "Generating Content"

        store.dispatch("agent", Action(ActionNames.AGENT_INTERNAL_SET_PROCESSING_STEP, buildJsonObject {
            put("agentId", agent.id); put("step", step)
        }))

        store.dispatch("agent", Action(requestActionName, buildJsonObject {
            put("providerId", agent.modelProvider)
            put("modelName", agent.modelName)
            put("correlationId", agent.id)
            put("contents", json.encodeToJsonElement(ledgerContext))
            put("systemPrompt", systemPrompt)
        }))
    }
}