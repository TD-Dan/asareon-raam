package app.auf.feature.systemclock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.session.SessionAction
import app.auf.model.SettingDefinition
import app.auf.model.SettingType
import app.auf.model.SettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// --- 1. MODEL ---
@Serializable
data class SystemClockState(
    val isEnabled: Boolean = false,
    val intervalMillis: Long = 300_000L // 5 minutes
) : FeatureState

// --- 2. ACTIONS ---
sealed interface ClockAction : AppAction {
    data class UpdateSetting(val setting: SettingValue) : ClockAction
}


// --- 3. FEATURE IMPLEMENTATION ---
class SystemClockFeature(
    private val coroutineScope: CoroutineScope
) : Feature {
    override val name: String = "SystemClockFeature"
    override val composableProvider: Feature.ComposableProvider = SystemClockComposableProvider()

    override fun createActionForSetting(setting: SettingValue): AppAction? {
        return if (setting.key.startsWith("clock.")) {
            ClockAction.UpdateSetting(setting)
        } else {
            null
        }
    }

    override fun reducer(state: AppState, action: AppAction): AppState {
        if (action !is ClockAction) return state
        val currentState = state.featureStates[name] as? SystemClockState ?: SystemClockState()

        val newFeatureState = when (action) {
            is ClockAction.UpdateSetting -> {
                when (action.setting.key) {
                    "clock.isEnabled" -> currentState.copy(isEnabled = action.setting.value as? Boolean ?: currentState.isEnabled)
                    "clock.intervalMillis" -> currentState.copy(intervalMillis = (action.setting.value as? Long) ?: currentState.intervalMillis)
                    else -> currentState
                }
            }
        }
        return state.copy(
            featureStates = state.featureStates + (name to newFeatureState)
        )
    }

    override fun start(store: Store) {
        coroutineScope.launch {
            while (true) {
                val latestState = store.state.value
                val clockState = latestState.featureStates[name] as? SystemClockState ?: SystemClockState()

                if (clockState.isEnabled) {
                    store.dispatch(
                        SessionAction.PostEntry(
                            sessionId = "default-session", // Assume default for now
                            agentId = "CORE",
                            content = "[SYSTEM: TICK]"
                        )
                    )
                    delay(clockState.intervalMillis)
                } else {
                    // Check less frequently when disabled
                    delay(1000L)
                }
            }
        }
    }

    inner class SystemClockComposableProvider : Feature.ComposableProvider {
        override val settingDefinitions: List<SettingDefinition> = listOf(
            SettingDefinition("clock.isEnabled", "System Clock", "Enable Autonomous TICK", "When enabled, the application will periodically post a TICK message to the session, allowing agents to perform background introspection.", SettingType.BOOLEAN),
            SettingDefinition("clock.intervalMillis", "System Clock", "TICK Interval (milliseconds)", "The time in milliseconds between each autonomous TICK message.", SettingType.NUMERIC_LONG)
        )

        @Composable
        override fun SettingsContent(stateManager: StateManager) {
            val appState by stateManager.state.collectAsState()
            val clockState = appState.featureStates[name] as? SystemClockState ?: SystemClockState()

            settingDefinitions.forEach { definition ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(definition.label, fontWeight = FontWeight.SemiBold)
                        Text(definition.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                    }

                    when (definition.type) {
                        SettingType.BOOLEAN -> {
                            Switch(
                                checked = clockState.isEnabled,
                                onCheckedChange = { newValue ->
                                    stateManager.updateSetting(SettingValue(key = definition.key, value = newValue))
                                }
                            )
                        }
                        SettingType.NUMERIC_LONG -> {
                            var textValue by remember(clockState.intervalMillis) { mutableStateOf(clockState.intervalMillis.toString()) }
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = {
                                    val filtered = it.filter { char -> char.isDigit() }
                                    if (filtered.length <= 18) {
                                        textValue = filtered
                                        filtered.toLongOrNull()?.let { longValue ->
                                            stateManager.updateSetting(SettingValue(key = definition.key, value = longValue))
                                        }
                                    }
                                },
                                modifier = Modifier.width(150.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }
    }
}