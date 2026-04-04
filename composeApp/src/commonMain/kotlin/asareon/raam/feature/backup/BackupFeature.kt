package asareon.raam.feature.backup

import asareon.raam.core.*
import asareon.raam.core.generated.ActionRegistry
import asareon.raam.util.BasePath
import asareon.raam.util.LogLevel
import asareon.raam.util.PlatformDependencies
import kotlinx.serialization.json.*
import kotlin.text.compareTo
import kotlin.text.get
import kotlin.toString

/**
 * ## BackupFeature
 *
 * Creates protective snapshots of application data during [preInit] and manages
 * backup creation, restoration, retention, and browsing at runtime.
 *
 * **Layer:** L1 (Platform Services)
 *
 * **Lifecycle:**
 * - [preInit]: Creates startup backup before Store is operational. Reads `.backup-config`
 *   hint file to determine if auto-backup is enabled. Checks for `.skip-next-backup`
 *   marker (written by restore flow) to skip unnecessary backup of just-restored state.
 * - [init]: No-op.
 * - `system.INITIALIZING`: Registers settings, scans backup directory, applies retention.
 * - Runtime: Handles CREATE, RESTORE, DELETE, PRUNE, OPEN_FOLDER via action bus.
 */
class BackupFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {

    override val identity = Identity(uuid = null, handle = "backup", localHandle = "backup", name = "Backup Manager")

    private val tag = "BackupFeature"

    // --- Path helpers ---
    private val appZonePath: String by lazy { platformDependencies.getBasePathFor(BasePath.APP_ZONE) }
    private val sep: Char get() = platformDependencies.pathSeparator
    private val backupsDir: String by lazy { "$appZonePath${sep}_backups" }
    private val configFilePath: String by lazy { "$backupsDir${sep}.backup-config" }
    private val skipMarkerPath: String by lazy { "$backupsDir${sep}.skip-next-backup" }

    // ========================================================================
    // PRE_INIT
    // ========================================================================

    override fun preInit() {
        try {
            platformDependencies.createDirectories(backupsDir)

            // Check skip marker (written by restore flow)
            if (platformDependencies.fileExists(skipMarkerPath)) {
                platformDependencies.deleteFile(skipMarkerPath)
                platformDependencies.log(LogLevel.INFO, tag,
                    "Skip marker found - skipping startup backup (post-restore restart).")
                return
            }

            // Read hint file to check if auto-backup is enabled
            if (platformDependencies.fileExists(configFilePath)) {
                try {
                    val configJson = Json.parseToJsonElement(
                        platformDependencies.readFileContent(configFilePath)
                    ).jsonObject
                    val autoEnabled = configJson["autoBackupEnabled"]
                        ?.jsonPrimitive?.booleanOrNull ?: true
                    if (!autoEnabled) {
                        platformDependencies.log(LogLevel.INFO, tag,
                            "Auto-backup disabled via hint file. Skipping startup backup.")
                        return
                    }
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.WARN, tag,
                        "Failed to read .backup-config, defaulting to auto-backup enabled.", e)
                }
            }

            // Generate timestamped filename
            val timestamp = platformDependencies
                .formatIsoTimestamp(platformDependencies.currentTimeMillis())
                .replace(":", "")
            val filename = "raam-backup-$timestamp.zip"
            val destinationPath = "$backupsDir$sep$filename"

            platformDependencies.log(LogLevel.INFO, tag, "Creating startup backup: $filename")
            platformDependencies.createZipArchive(
                sourceDirectoryPath = appZonePath,
                destinationZipPath = destinationPath,
                excludeDirectoryName = "_backups"
            )
            platformDependencies.log(LogLevel.INFO, tag,
                "Startup backup created successfully: $filename")

        } catch (e: Exception) {
            // NEVER block startup.
            platformDependencies.log(LogLevel.ERROR, tag,
                "Failed to create startup backup. Application will continue.", e)
        }
    }

    // ========================================================================
    // REDUCER
    // ========================================================================

    override fun reducer(state: FeatureState?, action: Action): FeatureState? {
        val s = state as? BackupState ?: BackupState()

        return when (action.name) {
            ActionRegistry.Names.SYSTEM_INITIALIZING -> s

            ActionRegistry.Names.BACKUP_INVENTORY_UPDATED -> {
                val arr = action.payload?.get("backups")?.jsonArray ?: return s
                val entries = arr.map { el ->
                    val obj = el.jsonObject
                    BackupEntry(
                        filename = obj["filename"]?.jsonPrimitive?.content ?: "",
                        sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
                s.copy(backups = entries)
            }

            ActionRegistry.Names.BACKUP_CREATE -> s.copy(isCreating = true)

            ActionRegistry.Names.BACKUP_RESTORE -> {
                val fn = action.payload?.get("filename")?.jsonPrimitive?.content ?: return s
                s.copy(pendingRestoreFilename = fn)
            }

            ActionRegistry.Names.BACKUP_EXECUTE_RESTORE -> s.copy(isRestoring = true)

            ActionRegistry.Names.BACKUP_OPERATION_RESULT -> {
                val msg = action.payload?.get("message")?.jsonPrimitive?.content
                s.copy(isCreating = false, isRestoring = false,
                    pendingRestoreFilename = null, lastResultMessage = msg)
            }

            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content ?: return s
                val value = action.payload?.get("value")?.jsonPrimitive?.content ?: return s
                when (key) {
                    "backup.autoBackupEnabled" -> s.copy(
                        autoBackupEnabled = value.toBooleanStrictOrNull() ?: true)
                    "backup.maxBackups" -> s.copy(
                        maxBackups = value.toIntOrNull() ?: 20)
                    else -> s
                }
            }

            ActionRegistry.Names.SETTINGS_LOADED -> {
                val p = action.payload ?: return s
                s.copy(
                    autoBackupEnabled = p["backup.autoBackupEnabled"]
                        ?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: s.autoBackupEnabled,
                    maxBackups = p["backup.maxBackups"]
                        ?.jsonPrimitive?.content?.toIntOrNull() ?: s.maxBackups
                )
            }

            else -> s
        }
    }

    // ========================================================================
    // SIDE EFFECTS
    // ========================================================================

    override fun handleSideEffects(
        action: Action, store: Store,
        previousState: FeatureState?, newState: FeatureState?
    ) {
        val state = newState as? BackupState ?: return

        when (action.name) {
            ActionRegistry.Names.SYSTEM_INITIALIZING -> {
                registerSettings(store)
                scanAndDispatchInventory(store)
            }

            ActionRegistry.Names.SETTINGS_VALUE_CHANGED -> {
                val key = action.payload?.get("key")?.jsonPrimitive?.content
                if (key == "backup.autoBackupEnabled" || key == "backup.maxBackups") {
                    writeHintFile(state)
                    if (key == "backup.maxBackups") pruneBackups(store, state)
                }
            }

            ActionRegistry.Names.SETTINGS_LOADED -> {
                writeHintFile(state)
                pruneBackups(store, state)
            }

            ActionRegistry.Names.BACKUP_CREATE -> {
                val label = action.payload?.get("label")?.jsonPrimitive?.content
                performCreateBackup(store, state, label, action.name)
            }

            ActionRegistry.Names.BACKUP_RESTORE -> {
                val filename = action.payload?.get("filename")?.jsonPrimitive?.content ?: return
                store.deferredDispatch(identity.handle, Action(
                    name = ActionRegistry.Names.CORE_SHOW_CONFIRMATION_DIALOG,
                    payload = buildJsonObject {
                        put("title", "Restore Backup")
                        put("message", "Restore from '$filename'? This replaces ALL current data. A safety backup will be created first.")
                        put("confirmActionName", ActionRegistry.Names.BACKUP_EXECUTE_RESTORE)
                        put("confirmActionPayload", buildJsonObject { put("filename", filename) })
                    }
                ))
                publishActionResult(store, action.name, success = true,
                    summary = "Restore confirmation requested for $filename")
            }

            ActionRegistry.Names.BACKUP_EXECUTE_RESTORE -> {
                val filename = action.payload?.get("filename")?.jsonPrimitive?.content ?: return
                performRestore(store, filename, action.name)
            }

            ActionRegistry.Names.BACKUP_DELETE -> {
                val filename = action.payload?.get("filename")?.jsonPrimitive?.content ?: return
                performDelete(store, filename, action.name)
            }

            ActionRegistry.Names.BACKUP_PRUNE -> {
                pruneBackups(store, state)
                publishActionResult(store, action.name, success = true,
                    summary = "Pruning complete")
            }

            ActionRegistry.Names.BACKUP_OPEN_FOLDER -> {
                try {
                    platformDependencies.openFolderInExplorer(backupsDir)
                    publishActionResult(store, action.name, success = true,
                        summary = "Opened backup folder")
                } catch (e: Exception) {
                    platformDependencies.log(LogLevel.ERROR, tag, "Failed to open backup folder.", e)
                    publishActionResult(store, action.name, success = false,
                        error = "Failed to open backup folder: ${e.message}")
                }
            }

            ActionRegistry.Names.BACKUP_REFRESH -> scanAndDispatchInventory(store)
        }
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private fun registerSettings(store: Store) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.SETTINGS_ADD,
            payload = buildJsonObject {
                put("key", "backup.autoBackupEnabled")
                put("type", "BOOLEAN")
                put("label", "Auto-backup on startup")
                put("description", "Automatically create a backup snapshot each time the application starts.")
                put("section", "Backup")
                put("defaultValue", "true")
            }
        ))
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.SETTINGS_ADD,
            payload = buildJsonObject {
                put("key", "backup.maxBackups")
                put("type", "NUMERIC_LONG")
                put("label", "Maximum backups to retain")
                put("description", "Oldest backups are pruned automatically when this limit is exceeded.")
                put("section", "Backup")
                put("defaultValue", "20")
            }
        ))
    }

    private fun writeHintFile(state: BackupState) {
        try {
            val json = buildJsonObject {
                put("autoBackupEnabled", state.autoBackupEnabled)
                put("maxBackups", state.maxBackups)
            }
            platformDependencies.writeFileContent(configFilePath, json.toString())
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.WARN, tag, "Failed to write .backup-config hint file.", e)
        }
    }

    private fun scanAndDispatchInventory(store: Store) {
        try {
            if (!platformDependencies.fileExists(backupsDir)) return
            val entries = platformDependencies.listDirectory(backupsDir)
                .filter { !it.isDirectory && it.path.endsWith(".zip") }
                .map { entry ->
                    BackupEntry(
                        filename = platformDependencies.getFileName(entry.path),
                        sizeBytes = platformDependencies.fileSize(entry.path),
                        createdAt = entry.lastModified ?: 0L
                    )
                }
                .sortedByDescending { it.createdAt }

            val backupsArray = buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("filename", e.filename)
                        put("sizeBytes", e.sizeBytes)
                        put("createdAt", e.createdAt)
                    })
                }
            }
            store.deferredDispatch(identity.handle, Action(
                name = ActionRegistry.Names.BACKUP_INVENTORY_UPDATED,
                payload = buildJsonObject { put("backups", backupsArray) }
            ))
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, tag, "Failed to scan backup directory.", e)
        }
    }

    private fun performCreateBackup(store: Store, state: BackupState, label: String?, requestAction: String) {
        try {
            val timestamp = platformDependencies
                .formatIsoTimestamp(platformDependencies.currentTimeMillis())
                .replace(":", "")
            val suffix = if (label != null) "-$label" else ""
            val filename = "raam-backup-$timestamp$suffix.zip"
            val path = "$backupsDir$sep$filename"

            platformDependencies.createZipArchive(
                sourceDirectoryPath = appZonePath,
                destinationZipPath = path,
                excludeDirectoryName = "_backups"
            )
            dispatchResult(store, "create", true, "Backup created: $filename")
            publishActionResult(store, requestAction, success = true,
                summary = "Backup created: $filename")
            scanAndDispatchInventory(store)
            pruneBackups(store, state)
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, tag, "Failed to create manual backup.", e)
            dispatchResult(store, "create", false, "Backup creation failed: ${e.message}")
            publishActionResult(store, requestAction, success = false,
                error = "Backup creation failed: ${e.message}")
        }
    }

    private fun performRestore(store: Store, filename: String, requestAction: String) {
        try {
            val backupPath = "$backupsDir$sep$filename"
            if (!platformDependencies.fileExists(backupPath)) {
                dispatchResult(store, "restore", false, "Backup file not found: $filename")
                publishActionResult(store, requestAction, success = false,
                    error = "Backup file not found: $filename")
                return
            }

            // 1. Safety backup before restore
            val safetyTimestamp = platformDependencies
                .formatIsoTimestamp(platformDependencies.currentTimeMillis())
                .replace(":", "")
            val safetyFilename = "raam-backup-pre-restore-$safetyTimestamp.zip"
            val safetyPath = "$backupsDir$sep$safetyFilename"
            platformDependencies.createZipArchive(
                sourceDirectoryPath = appZonePath,
                destinationZipPath = safetyPath,
                excludeDirectoryName = "_backups"
            )
            platformDependencies.log(LogLevel.INFO, tag, "Safety backup created: $safetyFilename")

            // 2. Write skip marker so next startup skips backup
            platformDependencies.writeFileContent(skipMarkerPath, "skip")

            // 3. Delete all APP_ZONE content EXCEPT _backups
            platformDependencies.listDirectory(appZonePath)
                .filter { platformDependencies.getFileName(it.path) != "_backups" }
                .forEach { entry ->
                    if (entry.isDirectory) {
                        platformDependencies.deleteDirectory(entry.path)
                    } else {
                        platformDependencies.deleteFile(entry.path)
                    }
                }

            // 4. Extract backup over APP_ZONE
            platformDependencies.extractZipArchive(
                zipPath = backupPath,
                targetDirectoryPath = appZonePath
            )
            platformDependencies.log(LogLevel.INFO, tag, "Restore complete. Restarting application.")

            // 5. Restart application
            platformDependencies.restartApplication()

        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, tag, "Restore failed.", e)
            dispatchResult(store, "restore", false, "Restore failed: ${e.message}")
            publishActionResult(store, requestAction, success = false,
                error = "Restore failed: ${e.message}")
        }
    }

    private fun performDelete(store: Store, filename: String, requestAction: String) {
        try {
            val path = "$backupsDir$sep$filename"
            if (platformDependencies.fileExists(path)) {
                platformDependencies.deleteFile(path)
                dispatchResult(store, "delete", true, "Deleted: $filename")
                publishActionResult(store, requestAction, success = true,
                    summary = "Deleted: $filename")
                scanAndDispatchInventory(store)
            } else {
                dispatchResult(store, "delete", false, "File not found: $filename")
                publishActionResult(store, requestAction, success = false,
                    error = "File not found: $filename")
            }
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, tag, "Failed to delete backup.", e)
            dispatchResult(store, "delete", false, "Delete failed: ${e.message}")
            publishActionResult(store, requestAction, success = false,
                error = "Delete failed: ${e.message}")
        }
    }

    private fun pruneBackups(store: Store, state: BackupState) {
        try {
            if (!platformDependencies.fileExists(backupsDir)) return
            val zips = platformDependencies.listDirectory(backupsDir)
                .filter { !it.isDirectory && it.path.endsWith(".zip") }
                .sortedByDescending { it.lastModified ?: 0L }

            if (zips.size > state.maxBackups) {
                val toDelete = zips.drop(state.maxBackups)
                toDelete.forEach { entry ->
                    platformDependencies.deleteFile(entry.path)
                    platformDependencies.log(LogLevel.INFO, tag,
                        "Pruned old backup: ${platformDependencies.getFileName(entry.path)}")
                }
                scanAndDispatchInventory(store)
            }
        } catch (e: Exception) {
            platformDependencies.log(LogLevel.ERROR, tag, "Pruning failed.", e)
        }
    }

    private fun dispatchResult(store: Store, operation: String, success: Boolean, message: String) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.BACKUP_OPERATION_RESULT,
            payload = buildJsonObject {
                put("operation", operation)
                put("success", success)
                put("message", message)
            }
        ))
    }

    /**
     * Publishes a lightweight, standardised broadcast notification after completing
     * a command-dispatchable action. Follows the same contract as FileSystemFeature's
     * ACTION_RESULT to enable cross-feature observability.
     */
    private fun publishActionResult(
        store: Store,
        requestAction: String,
        success: Boolean,
        summary: String? = null,
        error: String? = null
    ) {
        store.deferredDispatch(identity.handle, Action(
            name = ActionRegistry.Names.BACKUP_ACTION_RESULT,
            payload = buildJsonObject {
                put("requestAction", requestAction)
                put("success", success)
                summary?.let { put("summary", it) }
                error?.let { put("error", it) }
            }
        ))
    }
}