package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import kotlinx.coroutines.launch

@Composable
fun SessionView(
    stateManager: StateManager,
    // --- MODIFICATION START: Inject the list of features to find the UI provider ---
    features: List<Feature>,
    // --- MODIFICATION END ---
    modifier: Modifier = Modifier
) {
    val appState by stateManager.state.collectAsState()
    // --- MODIFICATION START: Read state from the new modular features ---
    val sessionFeatureState = appState.featureStates["SessionFeature"] as? SessionFeatureState
    val hkgAgentFeatureState = appState.featureStates["HkgAgentFeature"] as? HkgAgentFeatureState

    // For now, we assume a single session and a single agent.
    // A multi-tabbed UI would require a concept of the "active" session ID.
    val activeSession = sessionFeatureState?.sessions?.values?.firstOrNull()
    val activeAgent = hkgAgentFeatureState?.agents?.values?.firstOrNull()
    val isProcessing = activeAgent?.isProcessing ?: false
    val isChatActive = activeAgent?.hkgPersonaId != null || activeAgent?.hkgPersonaId == null // Chat is always active now
    val displayedTranscript = activeSession?.transcript ?: emptyList()
    // --- MODIFICATION END ---

    var userMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }

    val availableModels = appState.availableModels
    val selectedModel = appState.selectedModel

    LaunchedEffect(displayedTranscript.size) {
        coroutineScope.launch {
            if (displayedTranscript.isNotEmpty()) {
                lazyListState.animateScrollToItem(index = displayedTranscript.size - 1)
            }
        }
    }

    val sendMessageAction = {
        if (userMessage.isNotBlank() && !isProcessing && activeSession != null) {
            // --- MODIFICATION: Dispatch the new SessionAction ---
            stateManager.dispatch(SessionAction.PostEntry(
                sessionId = activeSession.id,
                agentId = "USER",
                content = userMessage
            ))
            userMessage = ""
        }
    }

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp)
        .background(MaterialTheme.colorScheme.surface)
    )
    {
        if (activeSession != null) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = displayedTranscript,
                    key = { entry -> entry.id }
                ) { entry ->
                    // This will be the next file we refactor. It will error for now.
                    MessageCard(
                        entry = entry,
                        stateManager = stateManager
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "No Active Session",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // --- DEPRECATED: Complex footer is being replaced by the modular header ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // --- MODIFICATION START: Render feature-provided UI slots ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Find any feature that provides a SessionHeader and render it.
                features.forEach { feature ->
                    feature.composableProvider?.SessionHeader(stateManager)
                }
            }
            // --- MODIFICATION END ---

            var isModelSelectorExpanded by remember { mutableStateOf(false) }

            Row(verticalAlignment = Alignment.CenterVertically) {
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


        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            if (event.isShiftPressed) {
                                // Allow newline with Shift+Enter
                            } else {
                                sendMessageAction()
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                placeholder = { Text("Enter message... (Enter to send, Shift+Enter for newline)") },
                enabled = !isProcessing && isChatActive
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (isProcessing) stateManager.cancelMessage() else sendMessageAction() },
                modifier = Modifier.height(56.dp),
                enabled = isChatActive,
                colors = if (isProcessing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop Request")
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                } else {
                    Text("Send")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(isProcessing) {
        if (!isProcessing) {
            focusRequester.requestFocus()
        }
    }
}