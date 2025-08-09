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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionView(stateManager: StateManager, modifier: Modifier = Modifier) {
    val appState by stateManager.state.collectAsState()
    // --- MODIFIED: We now get the graph from the new state property ---
    val holonGraph = appState.holonGraph
    val activeContextualHolonIds = appState.contextualHolonIds
    val activeAiPersonaId = appState.aiPersonaId
    val activeFilter = appState.catalogueFilter

    // Filtering logic remains the same, but operates on the new graph structure
    val holonTypes = holonGraph.map { it.type }.distinct().sorted()
    val filteredGraph = if (activeFilter == null) {
        holonGraph
    } else {
        holonGraph.filter { it.type == activeFilter }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Knowledge Graph", // Renamed for accuracy
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Filter buttons remain the same
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            val buttonFontSize = 12.sp
            Button(
                onClick = { stateManager.setCatalogueFilter(null) },
                contentPadding = buttonPadding,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (activeFilter == null) Color.DarkGray else Color.LightGray,
                    contentColor = if (activeFilter == null) Color.White else Color.Black
                )
            ) { Text("All", fontSize = buttonFontSize) }
            holonTypes.forEach { type ->
                Button(
                    onClick = { stateManager.setCatalogueFilter(type) },
                    contentPadding = buttonPadding,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (activeFilter == type) Color.DarkGray else Color.LightGray,
                        contentColor = if (activeFilter == type) Color.White else Color.Black
                    )
                ) { Text(type, fontSize = buttonFontSize) }
            }
        }

        // --- MODIFIED: The LazyColumn now renders the hierarchy visually ---
        LazyColumn {
            items(filteredGraph) { holon ->
                val isTheActiveAgent = activeAiPersonaId == holon.id
                val isInContext = activeContextualHolonIds.contains(holon.id)
                val isSelected = isTheActiveAgent || isInContext

                val backgroundColor = when {
                    isTheActiveAgent -> Color(0xFFD3D3D3) // Light grey if active agent
                    isInContext -> Color(0xFFE0E0E0) // Slightly lighter grey if in context
                    else -> Color.Transparent
                }
                val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                val fontStyle = if (isTheActiveAgent) FontStyle.Italic else FontStyle.Normal
                val displayText = if (isTheActiveAgent) "${holon.name} (Active Agent)" else holon.name

                // --- KEY CHANGE: Indentation based on depth ---
                val indentation = (holon.depth * 16).dp

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .clickable {
                            // Logic is now simpler: always inspect on click.
                            // The context toggle happens independently in the StateManager.
                            stateManager.inspectHolon(holon.id)
                            stateManager.toggleHolonActive(holon.id)
                        }
                        // Apply the calculated indentation here
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