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
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import app.auf.feature.session.SessionAction
import app.auf.feature.session.SessionFeatureState
import kotlinx.coroutines.launch

@Composable
fun SessionView(
    stateManager: StateManager,
    features: List<Feature>,
    modifier: Modifier = Modifier
) {
    val appState by stateManager.state.collectAsState()
    val sessionFeatureState = appState.featureStates["SessionFeature"] as? SessionFeatureState
    val hkgAgentFeatureState = appState.featureStates["HkgAgentFeature"] as? HkgAgentFeatureState

    val activeSession = sessionFeatureState?.sessions?.values?.firstOrNull()
    val activeAgent = hkgAgentFeatureState?.agents?.values?.firstOrNull()
    val isProcessing = activeAgent?.isProcessing ?: false
    val isChatActive = activeAgent != null
    val displayedTranscript = activeSession?.transcript ?: emptyList()

    var userMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(displayedTranscript.size) {
        coroutineScope.launch {
            if (displayedTranscript.isNotEmpty()) {
                lazyListState.animateScrollToItem(index = displayedTranscript.size - 1)
            }
        }
    }

    val sendMessageAction = {
        if (userMessage.isNotBlank() && !isProcessing && activeSession != null) {
            // CORRECTED: Dispatch specific action
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
                Text( "No Active Session" )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            features.forEach { feature ->
                feature.composableProvider?.SessionHeader(stateManager)
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
                            if (!event.isShiftPressed) {
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
                colors = if (isProcessing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.buttonColors()
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