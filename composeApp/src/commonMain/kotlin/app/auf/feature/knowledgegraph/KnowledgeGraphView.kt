package app.auf.feature.knowledgegraph

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auf.core.StateManager
import app.auf.ui.ExportView
import app.auf.ui.HolonInspectorView
import app.auf.ui.ImportView

@Composable
fun KnowledgeGraphView(
    stateManager: StateManager
) {
    val appState by stateManager.state.collectAsState()
    val kgState = appState.featureStates["KnowledgeGraphFeature"] as? KnowledgeGraphState ?: return

    Row {
        KnowledgeGraphCatalogueView(
            kgState = kgState,
            onFilter = { type -> stateManager.dispatch(KnowledgeGraphAction.SetCatalogueFilter(type)) },
            onHolonSelected = { holonId ->
                when (kgState.viewMode) {
                    KnowledgeGraphViewMode.INSPECTOR -> {
                        stateManager.dispatch(KnowledgeGraphAction.ToggleHolonActive(holonId))
                        stateManager.dispatch(KnowledgeGraphAction.InspectHolon(holonId))
                    }

                    KnowledgeGraphViewMode.EXPORT -> {
                        stateManager.dispatch(KnowledgeGraphAction.ToggleHolonForExport(holonId))
                    }

                    else -> {
                        // No-op on selection in other modes for now
                    }
                }
            },
            modifier = Modifier.width(350.dp).fillMaxHeight()
        )

        VerticalDivider()

        when (kgState.viewMode) {
            KnowledgeGraphViewMode.INSPECTOR -> HolonInspectorView(kgState)
            KnowledgeGraphViewMode.IMPORT -> ImportView(kgState, stateManager)
            KnowledgeGraphViewMode.EXPORT -> ExportView(kgState, stateManager)
            KnowledgeGraphViewMode.SETTINGS -> { /* Placeholder for future KG-specific settings */
            }
        }
    }
}