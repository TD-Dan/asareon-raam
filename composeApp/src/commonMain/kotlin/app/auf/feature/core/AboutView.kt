package app.auf.feature.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.auf.core.*

@Composable
fun AboutView(store: Store) {
    val appState by store.state.collectAsState()
    val loadedFeatures = appState.featureStates.keys.sorted()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // --- Header ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = { store.dispatch("core.ui", Action("core.SHOW_DEFAULT_VIEW")) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(16.dp))
            Text("About", style = MaterialTheme.typography.headlineSmall)
        }

        // --- Content ---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Version Info
            item {
                Text(
                    text = "Version Information",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // Wrap in SelectionContainer to make it easily copyable
                SelectionContainer {
                    Text("AUF App Version: ${Version.APP_VERSION}")
                }
            }

            // Loaded Features
            item {
                Text(
                    text = "Loaded Features",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                HorizontalDivider()
            }
            items(loadedFeatures) { featureName ->
                SelectionContainer {
                    Text("• $featureName", modifier = Modifier.padding(start = 16.dp))
                }
            }

            // Copyright and Links
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Copyright & Links",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text("AUF (Ai User Framework) and AUF App copyright 2025 Daniel Herkert")
                }
                Spacer(Modifier.height(12.dp))
                ClickableLink(
                    text = "Framework Repository",
                    url = "https://github.com/TD-Dan/Ai-User-Framework"
                )
                Spacer(Modifier.height(8.dp))
                ClickableLink(
                    text = "Application Repository",
                    url = "https://github.com/TD-Dan/AUF-App"
                )
            }

            // Diagnostic Tools
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Diagnostic Tools",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { store.dispatch("core.ui", Action("core.OPEN_LOGS_FOLDER")) }
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Open Logs Folder")
                }
            }
        }
    }
}

/**
 * A composable that renders a styled, clickable hyperlink using the modern LinkAnnotation API.
 * The underlying Text composable handles opening the URL in the system's default browser.
 */
@Composable
private fun ClickableLink(text: String, url: String) {
    val annotatedString = buildAnnotatedString {
        // Apply the hyperlink style
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )
        ) {
            // Attach the URL to the text.
            // The Text composable will automatically handle the click.
            withLink(link = LinkAnnotation.Url(url)) {
                append(text)
            }
        }
    }

    // A standard Text composable is all that's needed.
    Text(text = annotatedString)
}