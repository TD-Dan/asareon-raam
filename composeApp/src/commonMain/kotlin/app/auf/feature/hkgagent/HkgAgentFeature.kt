package app.auf.feature.hkgagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import app.auf.model.CompilerSettings
import app.auf.service.PromptCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.auf.feature.knowledgegraph.Holon as HolonData

// --- 1. MODEL ---

/**
 * ## Mandate
 * Defines the state for all active HKG Agent instances.
 */
@Serializable
data class HkgAgentFeatureState(
    val agents: Map<String, HkgAgentState> = emptyMap()
) : FeatureState

/**
 * ## Mandate
 * Represents the state of a single, session-scoped AI agent.
 * The nullability of `hkgPersonaId` is the key to its flexibility, allowing it
 * to function with or without a full knowledge graph.
 */
@Serializable
data class HkgAgentState(
    val id: String,
    val sessionId: String,
    val hkgPersonaId: String? = null,
    val isProcessing: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val compilerSettings: CompilerSettings = CompilerSettings()
)


// --- 2. ACTIONS ---

/**
 * ## Mandate
 * Defines actions to manage the lifecycle and state of HkgAgent instances.
 */
sealed interface HkgAgentAction : AppAction {
    data class CreateAgent(val sessionId: String, val agentId: String, val hkgPersonaId: String? = null) : HkgAgentAction
    data class SetProcessingStatus(val agentId: String, val isProcessing: Boolean) : HkgAgentAction
    data class SelectHkgPersona(val agentId: String, val hkgPersonaId: String?) : HkgAgentAction
}


// --- 3. FEATURE IMPLEMENTATION ---

/**
 * ## Mandate
 * Implements the "Localized Cognition" pattern. This feature is responsible for creating
 * and managing autonomous AI agents. Each agent listens to a specific session on the
 * "Public Ledger" (`SessionFeature`) and uses its local knowledge (the HKG) to formulate
 * and post responses.
 */
class HkgAgentFeature(
    private val agentGateway: AgentGateway,
    private val promptCompiler: PromptCompiler,
    private val platform: app.auf.util.PlatformDependencies,
    private val jsonParser: Json,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "HkgAgentFeature"
    private var store: Store? = null

    override val composableProvider: Feature.ComposableProvider = HkgAgentComposableProvider()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is HkgAgentAction && action !is AppAction.SelectModel && action !is AppAction.SetAvailableModels && action !is AppAction.UpdateSetting) return state
        val currentState = state.featureStates[name] as? HkgAgentFeatureState ?: HkgAgentFeatureState()

        val newFeatureState: HkgAgentFeatureState = when (action) {
            is HkgAgentAction.CreateAgent -> {
                val newAgent = HkgAgentState(
                    id = action.agentId,
                    sessionId = action.sessionId,
                    hkgPersonaId = action.hkgPersonaId
                )
                currentState.copy(agents = currentState.agents + (action.agentId to newAgent))
            }
            is HkgAgentAction.SetProcessingStatus -> {
                val agentToUpdate = currentState.agents[action.agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(isProcessing = action.isProcessing)
                currentState.copy(agents = currentState.agents + (action.agentId to updatedAgent))
            }
            is HkgAgentAction.SelectHkgPersona -> {
                val agentToUpdate = currentState.agents[action.agentId] ?: return state
                val updatedAgent = agentToUpdate.copy(hkgPersonaId = action.hkgPersonaId)
                currentState.copy(agents = currentState.agents + (action.agentId to updatedAgent))
            }
            is AppAction.SetAvailableModels -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    val defaultModel = "gemini-1.5-flash-latest"
                    val newSelectedModel = if (agent.selectedModel in action.models) {
                        agent.selectedModel
                    } else if (defaultModel in action.models) {
                        defaultModel
                    } else {
                        action.models.firstOrNull() ?: agent.selectedModel
                    }
                    agent.copy(availableModels = action.models, selectedModel = newSelectedModel)
                }
                currentState.copy(agents = updatedAgents)
            }
            is AppAction.SelectModel -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    agent.copy(selectedModel = action.modelName)
                }
                currentState.copy(agents = updatedAgents)
            }
            is AppAction.UpdateSetting -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    val newCompilerSettings = when (action.setting.key) {
                        "compiler.removeWhitespace" -> agent.compilerSettings.copy(removeWhitespace = action.setting.value as? Boolean ?: agent.compilerSettings.removeWhitespace)
                        "compiler.cleanHeaders" -> agent.compilerSettings.copy(cleanHeaders = action.setting.value as? Boolean ?: agent.compilerSettings.cleanHeaders)
                        "compiler.minifyJson" -> agent.compilerSettings.copy(minifyJson = action.setting.value as? Boolean ?: agent.compilerSettings.minifyJson)
                        else -> agent.compilerSettings
                    }
                    if (newCompilerSettings != agent.compilerSettings) {
                        agent.copy(compilerSettings = newCompilerSettings)
                    } else {
                        agent
                    }
                }
                currentState.copy(agents = updatedAgents)
            }
            else -> currentState
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store

        coroutineScope.launch {
            val models = agentGateway.listAvailableModels()
            withContext(Dispatchers.Main) {
                store.dispatch(AppAction.SetAvailableModels(models))
            }
        }

        // Create a default agent for the default session on startup
        val kgState = store.state.value.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
        store.dispatch(HkgAgentAction.CreateAgent("default-session", "sage-agent-1", kgState?.aiPersonaId))


        coroutineScope.launch(Dispatchers.Default) {
            store.state
                .map { (it.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions }
                .distinctUntilChanged()
                .collect { sessionState ->
                    sessionState?.values?.forEach { session ->
                        handleSessionUpdate(session)
                    }
                }
        }
    }

    private fun handleSessionUpdate(session: app.auf.feature.session.Session) {
        val store = this.store ?: return
        val agent = (store.state.value.featureStates[name] as? HkgAgentFeatureState)
            ?.agents?.values?.find { it.sessionId == session.id } ?: return

        val lastEntry = session.transcript.lastOrNull()
        if (lastEntry?.agentId == "USER" && !agent.isProcessing) {
            coroutineScope.launch {
                triggerAgentResponse(agent, session.transcript)
            }
        }
    }

    private suspend fun triggerAgentResponse(agent: HkgAgentState, transcript: List<app.auf.feature.session.LedgerEntry>) {
        val store = this.store ?: return
        store.dispatch(HkgAgentAction.SetProcessingStatus(agent.id, true))

        val promptContents = _buildPromptContents(agent, transcript)
        val request = AgentRequest(agent.selectedModel, promptContents)

        val response = agentGateway.generateContent(request)

        withContext(Dispatchers.Main) {
            if (response.errorMessage != null) {
                store.dispatch(SessionAction.PostEntry(
                    sessionId = agent.sessionId,
                    agentId = "CORE",
                    content = "Gateway Error: ${response.errorMessage}"
                ))
            } else {
                store.dispatch(SessionAction.PostEntry(
                    sessionId = agent.sessionId,
                    agentId = agent.id,
                    content = response.rawContent ?: "Empty response."
                ))
            }
            store.dispatch(HkgAgentAction.SetProcessingStatus(agent.id, false))
        }
    }

    private fun _buildPromptContents(agent: HkgAgentState, transcript: List<app.auf.feature.session.LedgerEntry>): List<Content> {
        val store = this.store ?: return emptyList()
        val appState = store.state.value
        val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
        val compilerSettings = agent.compilerSettings

        val promptMessages = mutableListOf<Pair<String, String>>() // Role, Content

        if (agent.hkgPersonaId != null && kgState != null) {
            val protocolPath = platform.getBasePathFor(app.auf.util.BasePath.FRAMEWORK) + platform.pathSeparator + "framework_protocol.md"
            promptMessages.add("user" to "--- START OF FILE framework_protocol.md ---\n${platform.readFileContent(protocolPath)}")

            val activeIds = kgState.contextualHolonIds + agent.hkgPersonaId
            kgState.holonGraph.filter { it.header.id in activeIds }.forEach { holon ->
                val content = jsonParser.encodeToString(HolonData.serializer(), holon)
                val compiled = promptCompiler.compile(content, compilerSettings).compiledText
                promptMessages.add("user" to "--- START OF FILE ${holon.header.id}.json ---\n$compiled")
            }
        }

        transcript.forEach { entry ->
            val role = when(entry.agentId) {
                "USER" -> "user"
                else -> "model"
            }
            promptMessages.add(role to entry.content)
        }

        // Merge consecutive messages
        if (promptMessages.isEmpty()) return emptyList()

        val mergedContents = mutableListOf<Content>()
        var currentRole = promptMessages.first().first
        var currentText = StringBuilder()

        for ((role, text) in promptMessages) {
            if (role == currentRole) {
                currentText.append(text).append("\n\n")
            } else {
                mergedContents.add(Content(currentRole, listOf(Part(currentText.toString().trim()))))
                currentRole = role
                currentText = StringBuilder(text).append("\n\n")
            }
        }
        mergedContents.add(Content(currentRole, listOf(Part(currentText.toString().trim()))))

        return mergedContents
    }

    inner class HkgAgentComposableProvider : Feature.ComposableProvider {
        @Composable
        override fun SessionHeader(stateManager: StateManager) {
            val appState by stateManager.state.collectAsState()
            val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
            val agentFeatureState = appState.featureStates[name] as? HkgAgentFeatureState
            val activeAgent = agentFeatureState?.agents?.values?.firstOrNull()
            val aiPersonas = kgState?.availableAiPersonas ?: emptyList()

            // Model selection UI
            var isModelSelectorExpanded by remember { mutableStateOf(false) }
            val availableModels = activeAgent?.availableModels ?: emptyList()
            val selectedModel = activeAgent?.selectedModel ?: "loading..."

            // Agent persona selection UI
            var isAgentSelectorExpanded by remember { mutableStateOf(false) }
            val selectedAiPersonaId = activeAgent?.hkgPersonaId
            val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None (Dumb Mode)"

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeAgent != null) {
                    Text("Agent:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(
                            onClick = { isAgentSelectorExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) { Text(selectedAiPersonaName, maxLines = 1) }
                        DropdownMenu(
                            expanded = isAgentSelectorExpanded,
                            onDismissRequest = { isAgentSelectorExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Dumb Mode)") },
                                onClick = {
                                    stateManager.selectHkgPersona(activeAgent.id, null)
                                    isAgentSelectorExpanded = false
                                }
                            )
                            aiPersonas.forEach { persona ->
                                DropdownMenuItem(
                                    text = { Text(persona.name) },
                                    onClick = {
                                        stateManager.selectHkgPersona(activeAgent.id, persona.id)
                                        isAgentSelectorExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text("Model:", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    Button(
                        onClick = { isModelSelectorExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) { Text(selectedModel, maxLines = 1) }
                    DropdownMenu(expanded = isModelSelectorExpanded, onDismissRequest = { isModelSelectorExpanded = false }) {
                        availableModels.forEach { modelName ->
                            DropdownMenuItem(text = { Text(modelName) }, onClick = { stateManager.selectModel(modelName); isModelSelectorExpanded = false })
                        }
                    }
                }
            }
        }
    }
}