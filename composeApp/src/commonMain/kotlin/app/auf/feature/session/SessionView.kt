package app.auf.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.*
import app.auf.feature.hkgagent.HkgAgentFeatureState
import kotlinx.coroutines.launch

/**
 * --- NEW: Centralized function for generating the "ground truth" format for a single entry. ---
 * This is the Single Source of Truth for raw transcript formatting.
 */
private fun generateRawEntry(entry: LedgerEntry, formattedTimestamp: String): String {
    val header = "--- ${entry.agentId} @ $formattedTimestamp ---"
    return "$header\n${entry.content}"
}

@Composable
fun SessionView(
    stateManager: StateManager,
    features: List<Feature>,
    modifier: Modifier = Modifier
) {
    val appState by stateManager.state.collectAsState()
    val sessionFeatureState = appState.featureStates["SessionFeature"] as? SessionFeatureState
    val hkgAgentFeatureState = appState.featureStates["HkgAgentFeature"] as? HkgAgentFeatureState
    val clipboardManager = LocalClipboardManager.current

    val activeSession = sessionFeatureState?.sessions?.values?.firstOrNull()
    val isRawContentVisible = sessionFeatureState?.isRawContentVisible ?: false
    val rawContentViewIds = sessionFeatureState?.rawContentViewIds ?: emptySet()
    val activeAgent = hkgAgentFeatureState?.agents?.values?.firstOrNull()
    val isAgentProcessing = activeAgent?.isProcessing ?: false
    val isChatInputEnabled = activeSession != null
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
        if (userMessage.isNotBlank() && !isAgentProcessing && activeSession != null) {
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
                    if (isRawContentVisible) {
                        val formattedTimestamp = remember(entry.timestamp) { stateManager.formatDisplayTimestamp(entry.timestamp) }
                        RawTranscriptEntry(entry, formattedTimestamp)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    } else {
                        MessageCard(
                            entry = entry,
                            stateManager = stateManager,
                            rawContentViewIds = rawContentViewIds
                        )
                    }
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
            Row(modifier = Modifier.weight(1f)) {
                features.forEach { feature ->
                    feature.composableProvider?.SessionHeader(stateManager)
                }
            }
            IconButton(onClick = {
                // --- CORRECTED: Use the unified header function for the copy action ---
                val rawPrompt = displayedTranscript.joinToString("\n\n---\n\n") { entry ->
                    val timestamp = stateManager.formatDisplayTimestamp(entry.timestamp)
                    generateRawEntry(entry, timestamp)
                }
                clipboardManager.setText(AnnotatedString(rawPrompt))
                stateManager.dispatch(AppAction.ShowToast("Full transcript copied!"))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Full Transcript")
            }
            IconButton(onClick = { stateManager.dispatch(SessionAction.ToggleRawContentView) }) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = "Toggle All Raw Content Views",
                    tint = if (isRawContentVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                enabled = isChatInputEnabled && !isAgentProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (isAgentProcessing) stateManager.cancelMessage() else sendMessageAction() },
                modifier = Modifier.height(56.dp),
                enabled = isChatInputEnabled,
                colors = if (isAgentProcessing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.buttonColors()
            ) {
                if (isAgentProcessing) {
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

    LaunchedEffect(isAgentProcessing) {
        if (!isAgentProcessing) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun RawTranscriptEntry(entry: LedgerEntry, formattedTimestamp: String) {
    // --- CORRECTED: Use the unified header function for the raw view rendering ---
    val fullEntryText = remember(entry, formattedTimestamp) {
        generateRawEntry(entry, formattedTimestamp)
    }
    val header = fullEntryText.substringBefore('\n')
    val content = fullEntryText.substringAfter('\n')

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(
            text = header,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = content,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}