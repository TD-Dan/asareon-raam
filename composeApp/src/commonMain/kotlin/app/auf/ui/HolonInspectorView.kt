package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.AppState
import app.auf.util.JsonProvider
import kotlinx.serialization.json.JsonElement

@Composable
fun HolonInspectorView(
    appState: AppState,
    modifier: Modifier = Modifier
) {
    val inspectedHolon = appState.inspectedHolonId?.let { appState.activeHolons[it] }
    val jsonPrettyPrinter = remember { JsonProvider.appJson }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (inspectedHolon != null) {
            val scrollState = rememberScrollState()
            Column(Modifier.verticalScroll(scrollState)) {
                Text(
                    text = inspectedHolon.header.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "ID: ${inspectedHolon.header.id}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Type: ${inspectedHolon.header.type}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Use an appropriate M3 theme color for the background.
                // 'surfaceContainer' is a good choice for a subtle containing background.
                Text(
                    text = jsonPrettyPrinter.encodeToString(JsonElement.serializer(), inspectedHolon.payload),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(8.dp)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select a holon from the catalogue to see its details.")
            }
        }
    }
}