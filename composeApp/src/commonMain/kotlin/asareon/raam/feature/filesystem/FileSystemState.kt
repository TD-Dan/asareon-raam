package asareon.raam.feature.filesystem

import asareon.raam.core.FeatureState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Defines a single, atomic operation that can be staged for later execution.
 * This is the core of the feature's transactional nature.
 */
@Serializable
sealed interface FileOperation {
    val path: String

    @Serializable
    data class Create(override val path: String, val content: String) : FileOperation

    @Serializable
    data class Update(override val path: String, val newContent: String) : FileOperation

    @Serializable
    data class Delete(override val path: String) : FileOperation

    @Serializable
    data class Rename(override val path: String, val newPath: String) : FileOperation
}

/**
 * A recursive data class representing a single node in the file system tree view.
 * It contains state for selection, expansion, and its children.
 */
@Serializable
data class FileSystemItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val children: List<FileSystemItem>? = null, // Null means not yet loaded
    val isExpanded: Boolean = false,
    val isSelected: Boolean = false
)

/**
 * A transient data class to hold the state of a pending scoped read request
 * while waiting for user confirmation.
 */
@Serializable
data class PendingScopedRead(
    val requestId: String,
    val originator: String,
    val payload: FileSystemFeature.RequestScopedReadUiPayload,
    val correlationId: String? = null
)


/**
 * The state container for the FileSystemFeature.
 *
 * It models the state of the browser (current path, bookmarks, listing) and, critically,
 * the transactional buffer (stagedOperations) and security context (whitelistedPaths).
 */
@Serializable
data class FileSystemState(
    // --- Browser State ---
    val currentPath: String? = null,
    val rootItems: List<FileSystemItem> = emptyList(), // The root of the file tree
    val error: String? = null,

    // --- Transactional State ---
    /** The list of pending file changes to be committed. This is the "staging area". */
    val stagedOperations: List<FileOperation> = emptyList(),

    // --- Security & Personalization State ---
    /** A set of absolute directory paths where file operations are permitted. */
    val whitelistedPaths: Set<String> = emptySet(),
    /** A set of absolute directory paths marked as favorites by the user. */
    val favoritePaths: Set<String> = emptySet(),
    /** The runtime ACL mapping a feature's name to its secure, sandboxed directory path. */
    @Transient val sandboxedPaths: Map<String, String> = emptyMap(),

    // A transient field to hold the state of a pending scoped read request.
    @Transient val pendingScopedRead: PendingScopedRead? = null

) : FeatureState