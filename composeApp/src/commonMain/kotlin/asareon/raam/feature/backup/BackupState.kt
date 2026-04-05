package asareon.raam.feature.backup

import asareon.raam.core.FeatureState
import kotlinx.serialization.Serializable

/**
 * Immutable state for the BackupFeature.
 */
@Serializable
data class BackupState(
    /** Inventory of available backups, sorted newest-first. */
    val backups: List<BackupEntry> = emptyList(),

    /** True while a backup creation is in progress. */
    val isCreating: Boolean = false,

    /** True while a restore operation is in progress. */
    val isRestoring: Boolean = false,

    /** Filename of the backup pending user confirmation for restore. */
    val pendingRestoreFilename: String? = null,

    /** Result message from the last operation (for toast display). */
    val lastResultMessage: String? = null,

    // --- Settings (synced from hint file and settings.VALUE_CHANGED) ---
    val autoBackupEnabled: Boolean = true,
    val maxBackups: Int = 20
) : FeatureState

@Serializable
data class BackupEntry(
    /** Filename only, not full path (e.g., "raam-backup-20260404T150000Z.zip"). */
    val filename: String,
    /** File size in bytes. */
    val sizeBytes: Long = 0L,
    /** File creation/modification time in epoch millis. */
    val createdAt: Long = 0L
)
