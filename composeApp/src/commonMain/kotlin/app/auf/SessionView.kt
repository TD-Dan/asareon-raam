package app.auf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // --- ADDED: Get export selection state ---
    val holonIdsForExport = appState.holonIdsForExport
    val currentViewMode = appState.currentViewMode


    val holonTypes = holonGraph.map { it.type }.distinct().sorted()
    val filteredGraph = if (activeFilter == null) {
        holonGraph
    } else {
        holonGraph.filter { it.type == activeFilter }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        appState.errorMessage?.let { error ->
            Text(
                text = error,
                color = Color.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Red.copy(alpha = 0.1f))
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
            Button(
                onClick = { onFilter(null) },
                contentPadding = buttonPadding,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (activeFilter == null) Color.DarkGray else Color.LightGray,
                    contentColor = if (activeFilter == null) Color.White else Color.Black
                )
            ) { Text("All", fontSize = buttonFontSize) }
            holonTypes.forEach { type ->
                Button(
                    onClick = { onFilter(type) },
                    contentPadding = buttonPadding,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (activeFilter == type) Color.DarkGray else Color.LightGray,
                        contentColor = if (activeFilter == type) Color.White else Color.Black
                    )
                ) { Text(type, fontSize = buttonFontSize) }
            }
        }

        LazyColumn {
            items(filteredGraph) { holon ->
                val isTheActiveAgent = activeAiPersonaId == holon.id
                val isInChatContext = activeContextualHolonIds.contains(holon.id)
                // --- ADDED: Check if selected for export ---
                val isSelectedForExport = holonIdsForExport.contains(holon.id)

                val backgroundColor = when {
                    // --- MODIFIED: Prioritize export selection color ---
                    currentViewMode == ViewMode.EXPORT && isSelectedForExport -> Color(0xFFC5CAE9) // Light Indigo for Export
                    isTheActiveAgent -> Color(0xFFD3D3D3)
                    isInChatContext -> Color(0xFFE0E0E0)
                    else -> Color.Transparent
                }
                val fontWeight = if (isTheActiveAgent || (currentViewMode == ViewMode.CHAT && isInChatContext) || (currentViewMode == ViewMode.EXPORT && isSelectedForExport)) FontWeight.Bold else FontWeight.Normal
                val fontStyle = if (isTheActiveAgent) FontStyle.Italic else FontStyle.Normal
                val displayText = if (isTheActiveAgent) "${holon.name} (Active Agent)" else holon.name
                val indentation = (holon.depth * 16).dp

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .clickable { onHolonSelected(holon.id) }
                        .padding(start = indentation, top = 8.dp, bottom = 8.dp, end = 4.dp)
                ){
                    Text(
                        text = displayText,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        color = if(isTheActiveAgent) Color.DarkGray else Color.Black,
                    )
                }
            }
        }
    }
}