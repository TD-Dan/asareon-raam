package app.auf.feature.knowledgegraph

import app.auf.core.FeatureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

// --- CANONICAL HOLON MODELS (ADAPTED FROM V1.5.0 & SAGE HKG) ---
@Serializable
data class Holon(
    val header: HolonHeader,
    val payload: JsonElement,
    val execute: JsonElement? = null,
    val content: String
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
    @Transient val filePath: String = "",
    @Transient val parentId: String? = null,
    @Transient val depth: Int = 0
)

@Serializable
data class Relationship(@SerialName("target_id") val targetId: String, val type: String)

@Serializable
data class SubHolonRef(val id: String, val type: String, val summary: String)

// --- IMPORT/EXPORT MODELS (ADAPTED FROM V1.5.0) ---

@Serializable
enum class KnowledgeGraphViewMode { INSPECTOR, IMPORT, EXPORT }

@Serializable
data class ImportItem(
    val sourcePath: String,
    val initialAction: ImportAction,
    val targetPath: String?
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
data class AssignParent(var assignedParentId: String? = null, override val summary: String = "New holon - requires parent") : ImportAction
@Serializable @SerialName("Quarantine")
data class Quarantine(val reason: String, override val summary: String = "Quarantine File") : ImportAction
@Serializable @SerialName("Ignore")
data class Ignore(override val summary: String = "Do not import") : ImportAction
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

    // --- Import State ---
    val importSourcePath: String = "",
    val importItems: List<ImportItem> = emptyList(),
    val importSelectedActions: Map<String, ImportAction> = emptyMap(),
    val importFileContents: Map<String, String> = emptyMap(),
    val isImportRecursive: Boolean = true,
    val showOnlyChangedImportItems: Boolean = false,

    // --- Loading & Error State ---
    val isLoading: Boolean = false,
    val fatalError: String? = null
) : FeatureState