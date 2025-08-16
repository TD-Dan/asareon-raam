package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auf.core.AppState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject // --- ADDED: Import for the serializer

@Composable
fun HolonInspectorView(
    appState: AppState,
    modifier: Modifier = Modifier
) {
    val inspectedHolon = appState.inspectedHolonId?.let { appState.activeHolons[it] }
    val jsonPrettyPrinter = remember { Json { prettyPrint = true } }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (inspectedHolon != null) {
            val scrollState = rememberScrollState()
            Column(Modifier.verticalScroll(scrollState)) {
                Text(
                    text = inspectedHolon.header.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "ID: ${inspectedHolon.header.id}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Type: ${inspectedHolon.header.type}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                // --- FIX IS HERE: Explicitly providing the serializer for JsonObject. ---
                Text(
                    text = jsonPrettyPrinter.encodeToString(JsonObject.serializer(), inspectedHolon.payload),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha=0.05f)).padding(8.dp)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select a holon from the catalogue to see its details.")
            }
        }
    }
}