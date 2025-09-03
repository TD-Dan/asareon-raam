package app.auf.feature.knowledgegraph

import app.auf.core.AppAction
import app.auf.core.FeatureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

// --- 1. MODEL ---
/**
 * The state slice for the KnowledgeGraph feature. This is the single source of truth for
 * all data related to the holon graph, including its in-memory representation, UI state,
 * and the state of any import/export operations.
 */
@Serializable
data class KnowledgeGraphState(
    val holonGraph: List<Holon> = emptyList(),
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val aiPersonaId: String? = null,
    val isLoading: Boolean = false,
    val fatalError: String? = null,
    val parsingErrors: List<String> = emptyList(),

    // UI State
    val catalogueFilter: String? = null,
    val inspectedHolonId: String? = null,
    val contextualHolonIds: Set<String> = emptySet(),

    // Import/Export State
    val viewMode: KnowledgeGraphViewMode = KnowledgeGraphViewMode.INSPECTOR,
    val holonIdsForExport: Set<String> = emptySet(),
    val importSourcePath: String = "",
    val importItems: List<ImportItem> = emptyList(),
    val importSelectedActions: Map<String, ImportAction> = emptyMap(),
    val isImportRecursive: Boolean = true,
    val showOnlyChangedImportItems: Boolean = false
) : FeatureState

enum class KnowledgeGraphViewMode {
    INSPECTOR, IMPORT, EXPORT, SETTINGS
}

// --- Data classes for the feature's domain ---

data class GraphLoadResult(
    val holonGraph: List<Holon> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val fatalError: String? = null,
    val availableAiPersonas: List<HolonHeader> = emptyList(),
    val determinedPersonaId: String? = null
)

@Serializable
data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String? = null
)

data class ImportResult(
    val successfulImports: List<String>,
    val failedImports: Map<String, String>
)

@Serializable
sealed interface ImportAction {
    val actionType: ImportActionType
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE, CREATE_ROOT
}

@Serializable
@SerialName("Update")
data class Update(
    val targetHolonId: String,
    override val actionType: ImportActionType = ImportActionType.UPDATE,
    override val summary: String = "Update existing holon."
) : ImportAction

@Serializable
@SerialName("Integrate")
data class Integrate(
    val parentHolonId: String,
    override val actionType: ImportActionType = ImportActionType.INTEGRATE,
    override val summary: String = "Integrate with known parent."
) : ImportAction

@Serializable
@SerialName("AssignParent")
data class AssignParent(
    var assignedParentId: String? = null,
    override val actionType: ImportActionType = ImportActionType.ASSIGN_PARENT,
    override val summary: String = "New holon - requires parent."
) : ImportAction

@Serializable
@SerialName("Quarantine")
data class Quarantine(
    val reason: String,
    override val actionType: ImportActionType = ImportActionType.QUARANTINE,
    override val summary: String = "Quarantine File"
) : ImportAction

@Serializable
@SerialName("Ignore")
data class Ignore(
    override val actionType: ImportActionType = ImportActionType.IGNORE,
    override val summary: String = "Do not import."
) : ImportAction

@Serializable
@SerialName("CreateRoot")
data class CreateRoot(
    override val actionType: ImportActionType = ImportActionType.CREATE_ROOT,
    override val summary: String = "IMPORT AS NEW ROOT PERSONA"
) : ImportAction

@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonElement
)

@Serializable
data class HolonUsage(
    val scope: String? = null,
    val description: String? = null
)

@Serializable
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String,
    val version: String = "0.0.0",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("modified_at")
    val modifiedAt: String? = null,
    @SerialName("holon_usage")
    val holonUsage: HolonUsage? = null,
    val relationships: List<Relationship> = emptyList(),
    @SerialName("sub_holons")
    val subHolons: List<SubHolonRef> = emptyList(),
    @Transient val filePath: String = "",
    @Transient val parentId: String? = null,
    @Transient val depth: Int = 0
)

@Serializable
data class Relationship(
    @SerialName("target_id")
    val targetId: String,
    val type: String
)

@Serializable
data class SubHolonRef(
    val id: String,
    val type: String,
    val summary: String
)


// --- 2. ACTIONS ---
sealed interface KnowledgeGraphAction : AppAction {
    data object LoadGraph : KnowledgeGraphAction
    data class LoadGraphSuccess(val result: GraphLoadResult) : KnowledgeGraphAction
    data class LoadGraphFailure(val error: String) : KnowledgeGraphAction
    data object RetryLoadGraph : KnowledgeGraphAction
    data class SelectAiPersona(val holonId: String?) : KnowledgeGraphAction
    data class InspectHolon(val holonId: String?) : KnowledgeGraphAction
    data class ToggleHolonActive(val holonId: String) : KnowledgeGraphAction
    data class SetCatalogueFilter(val type: String?) : KnowledgeGraphAction
    data class SetViewMode(val mode: KnowledgeGraphViewMode) : KnowledgeGraphAction
    data class ToggleHolonForExport(val holonId: String) : KnowledgeGraphAction
    data object SelectAllForExport : KnowledgeGraphAction
    data object DeselectAllForExport : KnowledgeGraphAction
    data class ExecuteExport(val destinationPath: String) : KnowledgeGraphAction
    data class StartImportAnalysis(val sourcePath: String) : KnowledgeGraphAction
    data class SetImportRecursive(val isRecursive: Boolean) : KnowledgeGraphAction
    data object ToggleShowOnlyChangedImportItems : KnowledgeGraphAction
    data class UpdateImportAction(val sourcePath: String, val action: ImportAction) : KnowledgeGraphAction
    data class AnalysisComplete(val items: List<ImportItem>) : KnowledgeGraphAction
    data object ExecuteImport : KnowledgeGraphAction
    data class ImportComplete(val result: ImportResult) : KnowledgeGraphAction
}