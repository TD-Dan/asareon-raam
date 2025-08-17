package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ContentAlpha
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
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
                color = MaterialTheme.colors.error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.error.copy(alpha = 0.1f))
                    .padding(8.dp)
                    .padding(bottom = 12.dp)
            )
        }

        Text(
            text = "Knowledge Graph",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            val buttonFontSize = 12.sp
            // --- MODIFIED: Use theme colors for filter buttons ---
            val allButtonColors = if (activeFilter == null) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            val allButtonBorder = if (activeFilter == null) null else ButtonDefaults.outlinedBorder
            Button(
                onClick = { onFilter(null) },
                contentPadding = buttonPadding,
                colors = allButtonColors,
                border = allButtonBorder,
                elevation = ButtonDefaults.elevation(defaultElevation = 0.dp)
            ) { Text("All", fontSize = buttonFontSize) }

            holonTypes.forEach { type ->
                val typeButtonColors = if (activeFilter == type) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                val typeButtonBorder = if (activeFilter == type) null else ButtonDefaults.outlinedBorder
                Button(
                    onClick = { onFilter(type) },
                    contentPadding = buttonPadding,
                    colors = typeButtonColors,
                    border = typeButtonBorder,
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp)
                ) { Text(type, fontSize = buttonFontSize) }
            }
            // --- END MODIFICATION ---
        }

        LazyColumn {
            items(filteredGraph) { holon ->
                val isTheActiveAgent = activeAiPersonaId == holon.header.id
                val isInChatContext = activeContextualHolonIds.contains(holon.header.id)
                val isSelectedForExport = holonIdsForExport.contains(holon.header.id)

                // --- MODIFIED: Use theme colors for row backgrounds ---
                val backgroundColor = when {
                    currentViewMode == ViewMode.EXPORT && isSelectedForExport -> MaterialTheme.colors.primary.copy(alpha = 0.1f)
                    isTheActiveAgent -> MaterialTheme.colors.onSurface.copy(alpha = 0.2f)
                    isInChatContext -> MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
                    else -> Color.Transparent
                }
                // --- END MODIFICATION ---
                val fontWeight = if (isTheActiveAgent || (currentViewMode == ViewMode.CHAT && isInChatContext) || (currentViewMode == ViewMode.EXPORT && isSelectedForExport)) FontWeight.Bold else FontWeight.Normal
                val fontStyle = if (isTheActiveAgent) FontStyle.Italic else FontStyle.Normal
                val displayText = if (isTheActiveAgent) "${holon.header.name} (Active Agent)" else holon.header.name
                val indentation = (holon.header.depth * 16).dp
                // --- MODIFIED: Use theme colors for text ---
                val textColor = when {
                    isTheActiveAgent -> MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium)
                    else -> MaterialTheme.colors.onSurface
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
                    )
                }
            }
        }
    }
}