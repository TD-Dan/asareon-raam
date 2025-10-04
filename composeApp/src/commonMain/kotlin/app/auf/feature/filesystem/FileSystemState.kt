package app.auf.feature.filesystem

import app.auf.core.FeatureState
import app.auf.util.FileEntry
import kotlinx.serialization.Serializable

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
 * The state container for the FileSystemFeature.
 *
 * It models the state of the browser (current path, bookmarks, listing) and, critically,
 * the transactional buffer (stagedOperations) and security context (whitelistedPaths).
 */
@Serializable
data class FileSystemState(
    // --- Browser State ---
    val currentPath: String? = null,
    val currentDirectoryListing: List<FileEntry> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val error: String? = null,

    // --- Transactional State ---
    /** The list of pending file changes to be committed. This is the "staging area". */
    val stagedOperations: List<FileOperation> = emptyList(),

    // --- Security State ---
    /** A set of absolute directory paths where file operations are permitted. */
    val whitelistedPaths: Set<String> = emptySet()

) : FeatureState