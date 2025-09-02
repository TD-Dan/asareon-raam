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
import app.auf.core.AppAction
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.StateManager
import app.auf.core.Store
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import app.auf.service.GatewayService
import app.auf.service.PromptCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import app.auf.feature.knowledgegraph.Holon as HolonData

// --- 1. MODEL ---

/**
 * ## Mandate
 * Defines the state for all active HKG Agent instances.
 */
@Serializable
data class HkgAgentFeatureState(
    val agents: Map<String, HkgAgentState> = emptyMap()
)

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
    val isProcessing: Boolean = false
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
    private val gatewayService: GatewayService,
    private val promptCompiler: PromptCompiler,
    // TODO: This direct dependency will be removed when the HkgAgent builds its own context.
    private val platform: app.auf.util.PlatformDependencies,
    private val jsonParser: Json,
    private val coroutineScope: CoroutineScope
) : Feature {

    override val name: String = "HkgAgentFeature"
    private var store: Store? = null

    override val composableProvider: Feature.ComposableProvider = HkgAgentComposableProvider()

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is HkgAgentAction) return state
        val currentState = state.featureStates[name] as? HkgAgentFeatureState ?: HkgAgentFeatureState()

        val newFeatureState = when (action) {
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
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store

        // Create a default agent for the default session on startup
        store.dispatch(HkgAgentAction.CreateAgent("default-session", "sage-agent-1", store.state.value.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState)?.aiPersonaId)


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
        val selectedModel = store.state.value.selectedModel

        // This is a temporary conversion until GatewayService is updated to accept a simpler structure
        val apiRequest = promptContents.map {
            val author = if (it.role == "model") app.auf.core.Author.AI else app.auf.core.Author.USER
            val title = if (author == app.auf.core.Author.USER) null else "AI"
            app.auf.core.ChatMessage.createSystem(title ?: "", it.parts.first().text)
        }

        val response = gatewayService.sendMessage(selectedModel, apiRequest)

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

    private fun _buildPromptContents(agent: HkgAgentState, transcript: List<app.auf.feature.session.LedgerEntry>): List<app.auf.service.Content> {
        val store = this.store ?: return emptyList()
        val appState = store.state.value
        val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
        val compilerSettings = appState.compilerSettings

        val promptMessages = mutableListOf<Pair<String, String>>() // Role, Content

        // Smart Mode: Build a rich context prompt
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

        // Add chat history for both Smart and Dumb modes
        transcript.forEach { entry ->
            val role = when(entry.agentId) {
                "USER" -> "user"
                else -> "model" // Treat CORE and other AI agents as the 'model' role
            }
            promptMessages.add(role to entry.content)
        }

        // Merge consecutive messages
        return gatewayService.buildApiContentsFromChatHistory(
            promptMessages.map { (role, content) ->
                val author = if (role == "user") app.auf.core.Author.USER else app.auf.core.Author.AI
                app.auf.core.ChatMessage.createSystem("prompt", content).copy(author = author)
            }
        )
    }

    inner class HkgAgentComposableProvider : Feature.ComposableProvider {
        @Composable
        override fun SessionHeader(stateManager: StateManager) {
            val appState by stateManager.state.collectAsState()
            val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
            val agentFeatureState = appState.featureStates[name] as? HkgAgentFeatureState

            // For now, assume a single agent for a single session view
            // A more complex UI would need to know which session is active
            val activeAgent = agentFeatureState?.agents?.values?.firstOrNull()
            val aiPersonas = kgState?.availableAiPersonas ?: emptyList()

            if (activeAgent != null) {
                var isAgentSelectorExpanded by remember { mutableStateOf(false) }
                val selectedAiPersonaId = activeAgent.hkgPersonaId
                val selectedAiPersonaName = aiPersonas.find { it.id == selectedAiPersonaId }?.name ?: "None (Dumb Mode)"

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent:", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Button(
                            onClick = { isAgentSelectorExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) { Text(selectedAiPersonaName) }
                        DropdownMenu(
                            expanded = isAgentSelectorExpanded,
                            onDismissRequest = { isAgentSelectorExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Dumb Mode)") },
                                onClick = {
                                    stateManager.dispatch(HkgAgentAction.SelectHkgPersona(activeAgent.id, null))
                                    isAgentSelectorExpanded = false
                                }
                            )
                            aiPersonas.forEach { persona ->
                                DropdownMenuItem(
                                    text = { Text(persona.name) },
                                    onClick = {
                                        stateManager.dispatch(HkgAgentAction.SelectHkgPersona(activeAgent.id, persona.id))
                                        isAgentSelectorExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}