package app.auf.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import kotlinx.coroutines.launch

@Composable
fun SessionView(
    stateManager: StateManager,
    features: List<Feature>, // We need the list of features to find the right one to render a TurnView
    modifier: Modifier = Modifier
) {
    val appState by stateManager.state.collectAsState()
    val sessionFeatureState = appState.featureStates["SessionFeature"] as? SessionFeatureState
    val clipboardManager = LocalClipboardManager.current

    val activeSession = sessionFeatureState?.sessions?.values?.firstOrNull()
    val displayedTranscript = activeSession?.transcript ?: emptyList()
    val isRawContentVisible = sessionFeatureState?.isRawContentVisible ?: false
    val rawContentViewIds = sessionFeatureState?.rawContentViewIds ?: emptySet()

    var userMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Scroll to bottom when new messages are added.
    LaunchedEffect(displayedTranscript.size) {
        coroutineScope.launch {
            if (displayedTranscript.isNotEmpty()) {
                lazyListState.animateScrollToItem(index = displayedTranscript.size - 1)
            }
        }
    }

    val sendMessageAction = {
        if (userMessage.isNotBlank() && activeSession != null) {
            stateManager.dispatch(SessionAction.PostUserMessage(
                sessionId = activeSession.id,
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
        // --- TRANSCRIPT AREA ---
        if (activeSession != null) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = displayedTranscript,
                    key = { entry -> entry.entryId }
                ) { entry ->
                    when (entry) {
                        is LedgerEntry.Message -> {
                            // Raw view logic is now handled internally by MessageCard
                            MessageCard(
                                entry = entry,
                                sessionId = activeSession.id,
                                stateManager = stateManager,
                                rawContentViewIds = rawContentViewIds // This is now the correct type
                            )
                        }
                        is LedgerEntry.AgentTurn -> {
                            // NEW: Delegate rendering to the correct feature
                            val agentFeature = features.find { it.name == entry.agentId }
                            agentFeature?.composableProvider?.TurnView(
                                stateManager = stateManager,
                                turnId = entry.entryId
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No Active Session")
            }
        }

        // --- HEADER CONTROLS ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* TODO: Copy Full Transcript Logic */ }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Full Transcript")
            }
            IconButton(onClick = { stateManager.dispatch(SessionAction.ToggleRawContentView) }) {
                Icon(Icons.Default.Code, contentDescription = "Toggle All Raw Content Views")
            }
        }

        // --- INPUT AREA ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            sendMessageAction()
                            return@onKeyEvent true
                        }
                        false
                    },
                placeholder = { Text("Enter message...") },
                enabled = activeSession != null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = sendMessageAction,
                modifier = Modifier.height(56.dp),
                enabled = activeSession != null && userMessage.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }

    // --- FOCUS MANAGEMENT ---
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}