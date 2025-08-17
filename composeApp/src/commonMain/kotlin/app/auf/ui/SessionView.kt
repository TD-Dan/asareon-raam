package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.AppState
import app.auf.core.ViewMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionView(
    appState: AppState,
    onFilter: (String?) -> Unit,
    onHolonSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val holonGraph = appState.holonGraph
    val activeContextualHolonIds = appState.contextualHolonIds
    val activeAiPersonaId = appState.aiPersonaId
    val activeFilter = appState.catalogueFilter
    val holonIdsForExport = appState.holonIdsForExport
    val currentViewMode = appState.currentViewMode

    val holonTypes = holonGraph.map { it.header.type }.distinct().sorted()
    val filteredGraph = if (activeFilter == null) {
        holonGraph
    } else {
        holonGraph.filter { it.header.type == activeFilter }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        appState.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp)
                    .padding(bottom = 12.dp)
            )
        }

        Text(
            text = "Knowledge Graph",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            val buttonTextStyle = MaterialTheme.typography.labelSmall

            // --- MODIFIED: Use distinct M3 Button and OutlinedButton for active/inactive states ---
            if (activeFilter == null) {
                Button(onClick = { onFilter(null) }, contentPadding = buttonPadding) {
                    Text("All", style = buttonTextStyle)
                }
            } else {
                OutlinedButton(onClick = { onFilter(null) }, contentPadding = buttonPadding) {
                    Text("All", style = buttonTextStyle)
                }
            }

            holonTypes.forEach { type ->
                if (activeFilter == type) {
                    Button(onClick = { onFilter(type) }, contentPadding = buttonPadding) {
                        Text(type, style = buttonTextStyle)
                    }
                } else {
                    OutlinedButton(onClick = { onFilter(type) }, contentPadding = buttonPadding) {
                        Text(type, style = buttonTextStyle)
                    }
                }
            }
            // --- END MODIFICATION ---
        }

        LazyColumn {
            items(
                items = filteredGraph,
                key = { holon -> holon.header.id }
            ) { holon ->
                val isTheActiveAgent = activeAiPersonaId == holon.header.id
                val isInChatContext = activeContextualHolonIds.contains(holon.header.id)
                val isSelectedForExport = holonIdsForExport.contains(holon.header.id)

                // --- MODIFIED: Use M3 theme colors for row backgrounds ---
                val backgroundColor = when {
                    currentViewMode == ViewMode.EXPORT && isSelectedForExport -> MaterialTheme.colorScheme.primaryContainer
                    isTheActiveAgent -> MaterialTheme.colorScheme.surfaceVariant
                    isInChatContext -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                }
                // --- END MODIFICATION ---

                val fontWeight = if (isTheActiveAgent || (currentViewMode == ViewMode.CHAT && isInChatContext) || (currentViewMode == ViewMode.EXPORT && isSelectedForExport)) FontWeight.Bold else FontWeight.Normal
                val fontStyle = if (isTheActiveAgent) FontStyle.Italic else FontStyle.Normal
                val displayText = if (isTheActiveAgent) "${holon.header.name} (Active Agent)" else holon.header.name
                val indentation = (holon.header.depth * 16).dp

                // --- MODIFIED: Use M3 theme colors for text ---
                val textColor = when {
                    isTheActiveAgent -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                // --- END MODIFICATION ---

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .clickable { onHolonSelected(holon.header.id) }
                        .padding(start = indentation, top = 8.dp, bottom = 8.dp, end = 4.dp)
                ){
                    Text(
                        text = displayText,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}