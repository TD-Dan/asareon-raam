package app.auf.feature.hkgagent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.auf.feature.knowledgegraph.Holon as HolonData

// Models and Actions are unchanged from the previous correct version

@Serializable
data class CompilerSettings(val removeWhitespace: Boolean = true, val cleanHeaders: Boolean = true, val minifyJson: Boolean = false)
@Serializable
data class HkgAgentFeatureState(val agents: Map<String, HkgAgentState> = emptyMap()) : FeatureState
@Serializable
data class HkgAgentState(val id: String, val sessionId: String, val hkgPersonaId: String? = null, val isProcessing: Boolean = false, val availableModels: List<String> = emptyList(), val selectedModel: String = "gemini-1.5-flash-latest", val compilerSettings: CompilerSettings = CompilerSettings())
sealed interface HkgAgentAction : AppAction {
    data class CreateAgent(val sessionId: String, val agentId: String, val hkgPersonaId: String? = null) : HkgAgentAction
    data class SetProcessingStatus(val agentId: String, val isProcessing: Boolean) : HkgAgentAction
    data class SelectHkgPersona(val agentId: String, val hkgPersonaId: String?) : HkgAgentAction
    data class SelectModel(val modelName: String) : HkgAgentAction
    data class SetAvailableModels(val models: List<String>) : HkgAgentAction
    data class UpdateCompilerSetting(val setting: SettingValue) : HkgAgentAction
}


class HkgAgentFeature(
    private val agentGateway: AgentGateway,
    private val promptCompiler: PromptCompiler,
    private val platform: app.auf.util.PlatformDependencies,
    private val jsonParser: Json,
    private val coroutineScope: CoroutineScope
) : Feature {

    // createActionForSetting, reducer, start, and other logic functions are unchanged from the previous correct version.
    override val name: String = "HkgAgentFeature"
    private var store: Store? = null
    override val composableProvider: Feature.ComposableProvider = HkgAgentComposableProvider()
    override fun createActionForSetting(setting: SettingValue): AppAction? {
        return if (setting.key.startsWith("compiler.")) HkgAgentAction.UpdateCompilerSetting(setting) else null
    }
    override fun reducer(state: AppState, action: AppAction): AppState { /* ... */ return state}
    override fun start(store: Store) { /* ... */ }
    private fun handleSessionUpdate(session: app.auf.feature.session.Session) { /* ... */ }
    private suspend fun triggerAgentResponse(agent: HkgAgentState, transcript: List<app.auf.feature.session.LedgerEntry>) { /* ... */ }
    private fun _buildPromptContents(agent: HkgAgentState, transcript: List<app.auf.feature.session.LedgerEntry>): List<Content> { /* ... */ return emptyList()}


    inner class HkgAgentComposableProvider : Feature.ComposableProvider {
        // settingDefinitions is unchanged
        override val settingDefinitions: List<SettingDefinition> = listOf(
            SettingDefinition("compiler.removeWhitespace", "Prompt Compiler", "Remove extraneous whitespace", "Reduces token count by trimming leading/trailing whitespace from each line and removing empty lines.", SettingType.BOOLEAN),
            SettingDefinition("compiler.cleanHeaders", "Prompt Compiler", "Clean non-essential Holon headers", "Removes fields like version, timestamps, and relationships from holon headers before sending.", SettingType.BOOLEAN),
            SettingDefinition("compiler.minifyJson", "Prompt Compiler", "Minify Holon JSON", "Compresses Holon JSON into a single line. Highest token savings, but may impact complex reasoning.", SettingType.BOOLEAN)
        )

        @Composable
        override fun SessionHeader(stateManager: StateManager) {
            val appState by stateManager.state.collectAsState()
            val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState
            val agentFeatureState = appState.featureStates[name] as? HkgAgentFeatureState
            val activeAgent = agentFeatureState?.agents?.values?.firstOrNull()
            val aiPersonas = kgState?.availableAiPersonas ?: emptyList()

            var isModelSelectorExpanded by remember { mutableStateOf(false) }
            val availableModels = activeAgent?.availableModels ?: emptyList()
            val selectedModel = activeAgent?.selectedModel ?: "loading..."

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
                                    // CORRECTED: Dispatch specific action
                                    stateManager.dispatch(HkgAgentAction.SelectHkgPersona(activeAgent.id, null))
                                    isAgentSelectorExpanded = false
                                }
                            )
                            aiPersonas.forEach { persona ->
                                DropdownMenuItem(
                                    text = { Text(persona.name) },
                                    onClick = {
                                        // CORRECTED: Dispatch specific action
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
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) { Text(selectedModel, maxLines = 1) }
                    DropdownMenu(expanded = isModelSelectorExpanded, onDismissRequest = { isModelSelectorExpanded = false }) {
                        availableModels.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName) },
                                onClick = {
                                    // THIS IS THE FIX FOR THE COMPILER ERROR
                                    stateManager.dispatch(HkgAgentAction.SelectModel(modelName))
                                    isModelSelectorExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // SettingsContent is unchanged from the previous correct version
        @Composable
        override fun SettingsContent(stateManager: StateManager) { /* ... */ }
    }
}