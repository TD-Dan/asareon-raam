package app.auf.feature.knowledgegraph

import app.auf.core.FeatureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

// --- CANONICAL HOLON MODELS ---
@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonElement,
    val execute: JsonElement? = null,
    // [REFACTOR] Renamed for clarity per our plan. This field holds the original,
    // unprocessed string content from the file, used primarily as a cache for the UI.
    // The canonical source of truth is the structured data above.
    val rawContent: String = ""
)

@Serializable
data class HolonHeader(
    val id: String,
    val type: String,
    val name: String,
    val summary: String? = null,
    val version: String = "0.0.0",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val relationships: List<Relationship> = emptyList(),
    @SerialName("sub_holons") val subHolons: List<SubHolonRef> = emptyList(),
    // These fields are essential runtime metadata, not transient UI state.
    // They are enriched at load time and persisted back to the file on any modification.
    val filePath: String = "",
    val parentId: String? = null,
    val depth: Int = 0
)

@Serializable
data class Relationship(@SerialName("target_id") val targetId: String, val type: String)

@Serializable
data class SubHolonRef(val id: String, val type: String, val summary: String)

// --- IMPORT/EXPORT MODELS ---

@Serializable
enum class KnowledgeGraphViewMode { INSPECTOR, IMPORT, EXPORT }

@Serializable
data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String?,
    // [THE FIX] The analyzer, not the UI, is now the source of truth for which
    // actions are valid for this specific item.
    val availableActions: List<ImportActionType> = emptyList()
)

@Serializable
sealed interface ImportAction {
    val summary: String
}

enum class ImportActionType {
    UPDATE, INTEGRATE, ASSIGN_PARENT, QUARANTINE, IGNORE, CREATE_ROOT;

    fun toInstance(initialAction: ImportAction): ImportAction {
        return when (this) {
            UPDATE -> initialAction as? Update ?: Update("")
            INTEGRATE -> initialAction as? Integrate ?: Integrate("")
            ASSIGN_PARENT -> AssignParent()
            QUARANTINE -> Quarantine("Manual Quarantine")
            IGNORE -> Ignore()
            CREATE_ROOT -> CreateRoot()
        }
    }
}


@Serializable @SerialName("Update")
data class Update(val targetHolonId: String, override val summary: String = "Update existing holon") : ImportAction
@Serializable @SerialName("Integrate")
data class Integrate(val parentHolonId: String, override val summary: String = "Integrate with known parent") : ImportAction
@Serializable @SerialName("AssignParent")
data class AssignParent(var assignedParentId: String? = null, override val summary: String = "Orphan - select parent") : ImportAction
@Serializable @SerialName("Quarantine")
data class Quarantine(val reason: String, override val summary: String = "Quarantine (fix later)") : ImportAction
@Serializable @SerialName("Ignore")
data class Ignore(override val summary: String = "Ignore - Do nothing") : ImportAction
@Serializable @SerialName("CreateRoot")
data class CreateRoot(override val summary: String = "IMPORT AS NEW ROOT PERSONA") : ImportAction

// --- FEATURE STATE ---
@Serializable
data class KnowledgeGraphState(
    val holons: Map<String, Holon> = emptyMap(),
    val personaRoots: Map<String, String> = emptyMap(),

    // --- UI State ---
    val activePersonaIdForView: String? = null,
    val activeHolonIdForView: String? = null,
    val viewMode: KnowledgeGraphViewMode = KnowledgeGraphViewMode.INSPECTOR,
    val showSummariesInTreeView: Boolean = true,
    val activeTypeFilters: Set<String> = emptySet(),

    // --- Import State ---
    val importItems: List<ImportItem> = emptyList(),
    /** The final, calculated set of actions for the import plan after consistency checks. */
    val importSelectedActions: Map<String, ImportAction> = emptyMap(),
    /** A map of user-explicit choices that override the initial analysis. */
    val importUserOverrides: Map<String, ImportAction> = emptyMap(),
    val importFileContents: Map<String, String> = emptyMap(),
    val isImportRecursive: Boolean = true,
    val showOnlyChangedImportItems: Boolean = false,

    // --- Transient State for UI Interactions ---
    @Transient val personaIdToDelete: String? = null,
    @Transient val isCreatingPersona: Boolean = false,
    @Transient val holonIdToDelete: String? = null,
    @Transient val holonIdToEdit: String? = null,
    @Transient val holonIdToRename: String? = null,
    @Transient val pendingImportCorrelationId: String? = null,

    // --- Loading & Error State ---
    val isLoading: Boolean = false,
    val fatalError: String? = null
) : FeatureState