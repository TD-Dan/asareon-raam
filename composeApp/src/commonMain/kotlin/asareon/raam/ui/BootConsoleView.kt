package asareon.raam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import asareon.raam.feature.core.BootLogEntry
import asareon.raam.util.LogLevel

@Composable
fun BootConsoleView(bootLog: List<BootLogEntry>) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom as new entries arrive.
    LaunchedEffect(bootLog.size) {
        if (bootLog.isNotEmpty()) {
            listState.animateScrollToItem(bootLog.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(bootLog, key = { it.id }) { entry ->
                Text(
                    text = "[${entry.tag}] ${entry.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when (entry.level) {
                        LogLevel.ERROR, LogLevel.FATAL -> Color(0xFFFF4444)
                        LogLevel.WARN -> Color(0xFFFF9800)
                        else -> Color.White
                    },
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}