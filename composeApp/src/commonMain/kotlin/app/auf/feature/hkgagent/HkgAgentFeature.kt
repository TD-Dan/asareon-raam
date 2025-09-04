package app.auf.feature.hkgagent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.LedgerEntry
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.auf.feature.session.Session
import kotlinx.coroutines.flow.distinctUntilChangedBy

// --- 1. MODEL ---

/**
 * An enumeration of the possible lifecycle states for an HKG Agent.
 */
@Serializable
enum class AgentStatus {
    /** The agent is idle and observing the session. */
    WAITING,
    /** The agent has seen a new message and is waiting for a pause in conversation before replying. */
    PRIMED,
    /** The agent has committed to replying and is currently processing a request to the gateway. */
    PROCESSING
}

@Serializable
data class CompilerSettings(
    val removeWhitespace: Boolean = true,
    val cleanHeaders: Boolean = true,
    val minifyJson: Boolean = false
)

@Serializable
data class HkgAgentFeatureState(
    val agents: Map<String, HkgAgentState> = emptyMap()
) : FeatureState

@Serializable
data class HkgAgentState(
    val id: String,
    val sessionId: String,
    val hkgPersonaId: String? = null,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "gemini-1.5-flash-latest",
    val compilerSettings: CompilerSettings = CompilerSettings(),
    // --- State Machine ---
    val status: AgentStatus = AgentStatus.WAITING,
    val primedAt: Long? = null,
    val lastEntryAt: Long? = null,
    // --- Configuration ---
    val initialWaitMillis: Long = 1500L,
    val maxWaitMillis: Long = 10000L
) {
    // Convenience property derived from state
    val isProcessing: Boolean
        get() = status == AgentStatus.PROCESSING
}

// --- 2. ACTIONS ---

sealed interface HkgAgentAction : AppAction {
    // Public / UI-driven actions
    data class CreateAgent(val sessionId: String, val agentId: String, val hkgPersonaId: String? = null) : HkgAgentAction
    data class SelectHkgPersona(val agentId: String, val hkgPersonaId: String?) : HkgAgentAction
    data class SelectModel(val modelName: String) : HkgAgentAction
    data class UpdateCompilerSetting(val setting: SettingValue) : HkgAgentAction
    data class UpdateTimingSetting(val setting: SettingValue) : HkgAgentAction
    data class SetAvailableModels(val models: List<String>) : HkgAgentAction

    // Internal, lifecycle-driven actions
    data class _UpdateAgentStatus(
        val agentId: String,
        val status: AgentStatus,
        val primedAt: Long? = null,
        val lastEntryAt: Long? = null
    ) : HkgAgentAction

    data class _DebounceTimerExpired(val agentId: String) : HkgAgentAction
}


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

    private var debounceJob: Job? = null

    override fun createActionForSetting(setting: SettingValue): AppAction? {
        return when {
            setting.key.startsWith("compiler.") -> HkgAgentAction.UpdateCompilerSetting(setting)
            setting.key.startsWith("agent.") -> HkgAgentAction.UpdateTimingSetting(setting)
            else -> null
        }
    }

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is HkgAgentAction) return state
        val currentState = state.featureStates[name] as? HkgAgentFeatureState ?: HkgAgentFeatureState()
        val newFeatureState = when (action) {
            is HkgAgentAction.CreateAgent -> {
                val newAgent = HkgAgentState(id = action.agentId, sessionId = action.sessionId, hkgPersonaId = action.hkgPersonaId)
                currentState.copy(agents = currentState.agents + (action.agentId to newAgent))
            }
            is HkgAgentAction._UpdateAgentStatus -> {
                val agent = currentState.agents[action.agentId] ?: return state
                val updatedAgent = agent.copy(
                    status = action.status,
                    primedAt = action.primedAt,
                    lastEntryAt = action.lastEntryAt
                )
                currentState.copy(agents = currentState.agents + (action.agentId to updatedAgent))
            }
            is HkgAgentAction._DebounceTimerExpired -> {
                val agent = currentState.agents[action.agentId] ?: return state
                if (agent.status == AgentStatus.PRIMED) {
                    val updatedAgent = agent.copy(status = AgentStatus.PROCESSING, primedAt = null, lastEntryAt = null)
                    currentState.copy(agents = currentState.agents + (action.agentId to updatedAgent))
                } else {
                    currentState // Do nothing if state changed in the meantime
                }
            }
            is HkgAgentAction.SetAvailableModels -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    agent.copy(
                        availableModels = action.models,
                        selectedModel = if (action.models.contains(agent.selectedModel)) agent.selectedModel else action.models.firstOrNull() ?: ""
                    )
                }
                currentState.copy(agents = updatedAgents)
            }
            is HkgAgentAction.SelectModel -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    agent.copy(selectedModel = action.modelName)
                }
                currentState.copy(agents = updatedAgents)
            }
            is HkgAgentAction.SelectHkgPersona -> {
                val targetAgent = currentState.agents[action.agentId] ?: return state
                val updatedAgent = targetAgent.copy(hkgPersonaId = action.hkgPersonaId)
                currentState.copy(agents = currentState.agents + (action.agentId to updatedAgent))
            }
            is HkgAgentAction.UpdateCompilerSetting -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    val currentSettings = agent.compilerSettings
                    val newSettings = when(action.setting.key) {
                        "compiler.removeWhitespace" -> currentSettings.copy(removeWhitespace = action.setting.value as? Boolean ?: currentSettings.removeWhitespace)
                        "compiler.cleanHeaders" -> currentSettings.copy(cleanHeaders = action.setting.value as? Boolean ?: currentSettings.cleanHeaders)
                        "compiler.minifyJson" -> currentSettings.copy(minifyJson = action.setting.value as? Boolean ?: currentSettings.minifyJson)
                        else -> currentSettings
                    }
                    agent.copy(compilerSettings = newSettings)
                }
                currentState.copy(agents = updatedAgents)
            }
            is HkgAgentAction.UpdateTimingSetting -> {
                val updatedAgents = currentState.agents.mapValues { (_, agent) ->
                    when(action.setting.key) {
                        "agent.initialWaitMillis" -> agent.copy(initialWaitMillis = action.setting.value as? Long ?: agent.initialWaitMillis)
                        "agent.maxWaitMillis" -> agent.copy(maxWaitMillis = action.setting.value as? Long ?: agent.maxWaitMillis)
                        else -> agent
                    }
                }
                currentState.copy(agents = updatedAgents)
            }
        }
        return state.copy(featureStates = state.featureStates + (name to newFeatureState))
    }

    override fun start(store: Store) {
        this.store = store
        coroutineScope.launch {
            val models = agentGateway.listAvailableModels()
            withContext(Dispatchers.Main) {
                store.dispatch(HkgAgentAction.SetAvailableModels(models))
            }
        }

        // --- Collector 1: The Watcher (Manages Debouncing) ---
        coroutineScope.launch {
            var lastSeenEntryId = -1L
            val initialTranscriptSize = (store.state.value.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions?.values?.firstOrNull()?.transcript?.size ?: 0
            if (initialTranscriptSize > 0) {
                lastSeenEntryId = (store.state.value.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions?.values?.firstOrNull()?.transcript?.last()?.id ?: -1L
            }

            store.state.map {
                (it.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions?.values?.firstOrNull()?.transcript?.lastOrNull() to
                        (it.featureStates[name] as? HkgAgentFeatureState)?.agents?.values?.firstOrNull()
            }.distinctUntilChanged().collect { (lastEntry, agent) ->
                if (lastEntry == null || agent == null || lastEntry.id <= lastSeenEntryId) return@collect

                lastSeenEntryId = lastEntry.id
                if (lastEntry.agentId != agent.id && (agent.status == AgentStatus.WAITING || agent.status == AgentStatus.PRIMED)) {
                    debounceJob?.cancel()

                    val currentTime = platform.getSystemTimeMillis()
                    val newPrimedAt = if (agent.status == AgentStatus.WAITING) currentTime else (agent.primedAt ?: currentTime)
                    store.dispatch(HkgAgentAction._UpdateAgentStatus(agent.id, AgentStatus.PRIMED, newPrimedAt, currentTime))

                    val agentAfterPriming = (store.state.value.featureStates[name] as HkgAgentFeatureState).agents[agent.id]!!
                    val timeSincePrimed = currentTime - (agentAfterPriming.primedAt ?: currentTime)

                    if (timeSincePrimed >= agentAfterPriming.maxWaitMillis) {
                        store.dispatch(HkgAgentAction._DebounceTimerExpired(agent.id))
                    } else {
                        debounceJob = coroutineScope.launch {
                            delay(agentAfterPriming.initialWaitMillis)
                            store.dispatch(HkgAgentAction._DebounceTimerExpired(agent.id))
                        }
                    }
                }
            }
        }

        // --- Collector 2: The Executor (Handles Side Effects) ---
        coroutineScope.launch {
            store.state.map {
                (it.featureStates[name] as? HkgAgentFeatureState)?.agents?.values?.firstOrNull()
            }.distinctUntilChangedBy { it?.status }.collect { agent ->
                if (agent?.status == AgentStatus.PROCESSING) {
                    val appState = store.state.value
                    val session = (appState.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions?.get(agent.sessionId)
                    val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
                    if (session != null && kgState != null) {
                        triggerAgentResponse(agent, session.transcript, kgState)
                    }
                }
            }
        }

        // --- Collector 3: Agent Creator ---
        coroutineScope.launch {
            store.state
                .map { (it.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions }
                .distinctUntilChanged()
                .collect { sessions ->
                    sessions?.values?.forEach { handleSessionUpdate(it) }
                }
        }
    }

    private fun handleSessionUpdate(session: Session) {
        val store = this.store ?: return
        val agentState = store.state.value.featureStates[name] as? HkgAgentFeatureState
        val agentForSessionExists = agentState?.agents?.values?.any { it.sessionId == session.id } ?: false
        if (!agentForSessionExists) {
            val kgState = store.state.value.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
            store.dispatch(HkgAgentAction.CreateAgent(
                sessionId = session.id,
                agentId = "agent-for-${session.id}",
                hkgPersonaId = kgState?.aiPersonaId
            ))
        }
    }

    private suspend fun triggerAgentResponse(
        agentSnapshot: HkgAgentState,
        transcriptSnapshot: List<LedgerEntry>,
        kgStateSnapshot: KnowledgeGraphState
    ) {
        val store = this.store ?: return
        try {
            val promptContents = _buildPromptContents(agentSnapshot, transcriptSnapshot, kgStateSnapshot)
            if (promptContents.isEmpty()) {
                store.dispatch(HkgAgentAction._UpdateAgentStatus(agentSnapshot.id, AgentStatus.WAITING, null, null))
                return
            }
            val request = AgentRequest(agentSnapshot.selectedModel, promptContents)
            val response = agentGateway.generateContent(request)

            if (response.rawContent != null) {
                store.dispatch(SessionAction.PostEntry(agentSnapshot.sessionId, agentSnapshot.id, response.rawContent))
            } else {
                val errorMessage = "[CORE ERROR] Agent failed to generate response: ${response.errorMessage ?: "Unknown error"}"
                store.dispatch(SessionAction.PostEntry(agentSnapshot.sessionId, "CORE", errorMessage))
            }
        } catch (e: Exception) {
            val errorMessage = "[CORE CRITICAL] An exception occurred during agent processing: ${e.message}"
            store.dispatch(SessionAction.PostEntry(agentSnapshot.sessionId, "CORE", errorMessage))
        } finally {
            val latestTranscript = (store.state.value.featureStates["SessionFeature"] as? SessionFeatureState)?.sessions?.get(agentSnapshot.sessionId)?.transcript ?: transcriptSnapshot
            val hasNewMessages = latestTranscript.any { it.id > (transcriptSnapshot.lastOrNull()?.id ?: -1) && it.agentId != agentSnapshot.id }

            if (hasNewMessages) {
                val latestKgState = store.state.value.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: kgStateSnapshot
                val latestAgentState = (store.state.value.featureStates[name] as? HkgAgentFeatureState)?.agents?.get(agentSnapshot.id) ?: agentSnapshot
                store.dispatch(HkgAgentAction._UpdateAgentStatus(agentSnapshot.id, AgentStatus.PROCESSING, null, null))
                // The new PROCESSING state will be picked up by the executor collector, creating a clean loop.
            } else {
                store.dispatch(HkgAgentAction._UpdateAgentStatus(agentSnapshot.id, AgentStatus.WAITING, null, null))
            }
        }
    }

    private fun _buildPromptContents(
        agent: HkgAgentState,
        transcript: List<LedgerEntry>,
        kgState: KnowledgeGraphState
    ): List<Content> {
        val personaId = agent.hkgPersonaId

        val history = transcript.map {
            val role = if (it.agentId == "USER" || it.agentId == "CORE") "user" else "model"
            Content(role, listOf(Part(it.content)))
        }

        if (history.isEmpty()) return emptyList()

        return if (personaId != null) {
            val systemPrompt = "You are an AI assistant." // Placeholder for full compilation
            listOf(
                Content("user", listOf(Part(systemPrompt))),
                Content("model", listOf(Part("Understood.")))
            ) + history
        } else {
            val systemPrompt = "You are a helpful assistant."
            listOf(
                Content("user", listOf(Part(systemPrompt))),
                Content("model", listOf(Part("Understood.")))
            ) + history
        }
    }

    inner class HkgAgentComposableProvider : Feature.ComposableProvider {
        override val settingDefinitions: List<SettingDefinition> = listOf(
            SettingDefinition("compiler.removeWhitespace", "Prompt Compiler", "Remove extraneous whitespace", "Reduces token count by trimming leading/trailing whitespace from each line and removing empty lines.", SettingType.BOOLEAN),
            SettingDefinition("compiler.cleanHeaders", "Prompt Compiler", "Clean non-essential Holon headers", "Removes fields like version, timestamps, and relationships from holon headers before sending.", SettingType.BOOLEAN),
            SettingDefinition("compiler.minifyJson", "Prompt Compiler", "Minify Holon JSON", "Compresses Holon JSON into a single line. Highest token savings, but may impact complex reasoning.", SettingType.BOOLEAN),
            SettingDefinition("agent.initialWaitMillis", "Agent Timings", "Initial Wait (ms)", "How long the agent waits after the last message before starting a reply.", SettingType.NUMERIC_LONG),
            SettingDefinition("agent.maxWaitMillis", "Agent Timings", "Max Wait (ms)", "The maximum time the agent will wait after the first message in a series before replying, regardless of new messages.", SettingType.NUMERIC_LONG)
        )

        override fun getSettingValue(state: AppState, key: String): Any? {
            val featureState = state.featureStates[name] as? HkgAgentFeatureState ?: return null
            val agent = featureState.agents.values.firstOrNull() ?: return null
            return when (key) {
                "compiler.removeWhitespace" -> agent.compilerSettings.removeWhitespace
                "compiler.cleanHeaders" -> agent.compilerSettings.cleanHeaders
                "compiler.minifyJson" -> agent.compilerSettings.minifyJson
                "agent.initialWaitMillis" -> agent.initialWaitMillis
                "agent.maxWaitMillis" -> agent.maxWaitMillis
                else -> null
            }
        }

        @Composable
        override fun SessionHeader(stateManager: StateManager) {
            val appState by stateManager.state.collectAsState()
            val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
            val agentFeatureState = appState.featureStates[name] as? HkgAgentFeatureState
            val activeAgent = agentFeatureState?.agents?.values?.firstOrNull()
            val aiPersonas = kgState?.availableAiPersonas ?: emptyList()

            var isModelSelectorExpanded by remember { mutableStateOf(false) }
            val availableModels = activeAgent?.availableModels ?: emptyList()
            val selectedModel = if (availableModels.isEmpty()) "loading..." else activeAgent?.selectedModel ?: ""


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
                Spacer(Modifier.width(16.dp))
                Text("Model:", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    Button(
                        onClick = { isModelSelectorExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        enabled = availableModels.isNotEmpty()
                    ) { Text(selectedModel, maxLines = 1) }
                    DropdownMenu(expanded = isModelSelectorExpanded, onDismissRequest = { isModelSelectorExpanded = false }) {
                        availableModels.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName) },
                                onClick = {
                                    stateManager.dispatch(HkgAgentAction.SelectModel(modelName))
                                    isModelSelectorExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}