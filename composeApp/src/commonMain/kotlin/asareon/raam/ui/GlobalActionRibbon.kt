package app.auf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.auf.core.*
import app.auf.core.generated.ActionRegistry
import app.auf.feature.core.CoreState
import aufapp.composeapp.generated.resources.Res
import aufapp.composeapp.generated.resources.icon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.painterResource

@Composable
fun GlobalActionRibbon(
    store: Store,
    features: List<Feature>,
    activeViewKey: String?
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val appState by store.state.collectAsState()
    val coreState = remember(appState.featureStates) {
        appState.featureStates["core"] as? CoreState
    }
    val defaultViewKey = coreState?.defaultViewKey ?: "feature.session.main"

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(50.dp)
            .padding(vertical = 8.dp)
            .height(12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- CORRECTED: Master Home Button using typesafe resources ---
        val payload = buildJsonObject { put("key", defaultViewKey) }
        IconButton(onClick = { store.deferredDispatch("system", Action(ActionRegistry.Names.CORE_SET_ACTIVE_VIEW, payload)) }) {
            Icon(
                painter = painterResource(Res.drawable.icon),
                contentDescription = "Go to Default View (Session)",
                // Using Color.Unspecified to render the PNG with its original colors, as per your working example.
                tint = Color.Unspecified
            )
        }

        // CORRECTED: Render ribbon content from all features using the new flexible slot.
        features.forEach { feature ->
            feature.composableProvider?.RibbonContent(
                store = store,
                activeViewKey = activeViewKey
            )
        }

        // The main application menu
        Box {
            IconButton(onClick = { isMenuExpanded = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Application Menu")
            }
            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false }
            ) {
                // Render menu content from all features
                features.forEach { feature ->
                    feature.composableProvider?.MenuContent(
                        store = store,
                        onDismiss = { isMenuExpanded = false }
                    )
                }
            }
        }
    }
}