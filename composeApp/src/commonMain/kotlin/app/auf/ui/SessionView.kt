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
import app.auf.feature.knowledgegraph.KnowledgeGraphState
import app.auf.feature.knowledgegraph.KnowledgeGraphViewMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionView(
    kgState: KnowledgeGraphState,
    onFilter: (String?) -> Unit,
    onHolonSelected: (String) -> Unit,
    modifier : Modifier = Modifier
        .background(color = MaterialTheme.colorScheme.surfaceContainer)
) {
    val holonGraph = kgState.holonGraph
    val activeContextualHolonIds = kgState.contextualHolonIds
    val activeAiPersonaId = kgState.aiPersonaId
    val activeFilter = kgState.catalogueFilter
    val holonIdsForExport = kgState.holonIdsForExport
    val currentViewMode = kgState.viewMode

    val holonTypes = holonGraph.map { it.header.type }.distinct().sorted()
    val filteredGraph = if (activeFilter == null) {
        holonGraph
    } else {
        holonGraph.filter { it.header.type == activeFilter }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
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
        }

        LazyColumn {
            items(
                items = filteredGraph,
                key = { holon -> holon.header.id }
            ) { holon ->
                val isTheActiveAgent = activeAiPersonaId == holon.header.id
                val isInChatContext = activeContextualHolonIds.contains(holon.header.id)
                val isSelectedForExport = holonIdsForExport.contains(holon.header.id)

                val backgroundColor = when {
                    currentViewMode == KnowledgeGraphViewMode.EXPORT && isSelectedForExport -> MaterialTheme.colorScheme.primaryContainer
                    isTheActiveAgent -> MaterialTheme.colorScheme.surfaceVariant
                    isInChatContext -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                }

                val fontWeight = if (isTheActiveAgent || (currentViewMode == KnowledgeGraphViewMode.INSPECTOR && isInChatContext) || (currentViewMode == KnowledgeGraphViewMode.EXPORT && isSelectedForExport)) FontWeight.Bold else FontWeight.Normal
                val fontStyle = if (isTheActiveAgent) FontStyle.Italic else FontStyle.Normal
                val displayText = if (isTheActiveAgent) "${holon.header.name} (Active Agent)" else holon.header.name
                val indentation = (holon.header.depth * 16).dp

                val textColor = when {
                    isTheActiveAgent -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }

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