package app.auf.feature.filesystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.auf.core.Action
import app.auf.core.AppState
import app.auf.core.Feature
import app.auf.core.Store
import app.auf.util.FileEntry
import app.auf.util.PlatformDependencies
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

/**
 * ## Mandate
 * To act as the sole, auditable gateway to the user's file system for the AUF App.
 *
 * This feature provides a transactional buffer for all file I/O operations, enforcing
 * the constitutional `DIRECTIVE_ALIGNMENT_AND_RATIFICATION` through a "staging" and
 * "commit" model. No file is ever touched without being part of an explicit,
 * user-approved transaction. It also enforces a strict security sandbox via a
 * user-configurable path whitelist.
 */
class FileSystemFeature(
    private val platformDependencies: PlatformDependencies
) : Feature {
    override val name: String = "FileSystemFeature"
    override val composableProvider: Feature.ComposableProvider = FileSystemComposableProvider()

    // --- Private serializable classes for decoding action payloads ---
    @Serializable private data class NavigatePayload(val path: String)
    @Serializable private data class NavigationUpdatedPayload(val path: String, val listing: List<FileEntry>)
    @Serializable private data class NavigationFailedPayload(val path: String, val error: String)
    @Serializable private data class StageCreatePayload(val path: String, val content: String)
    @Serializable private data class StageDeletePayload(val path: String)

    override fun onAction(action: Action, store: Store) {
        when (action.name) {
            "app.STARTING" -> {
                // When the app starts, automatically navigate to the user's home directory.
                val homePath = platformDependencies.getUserHomePath()
                val payload = buildJsonObject { put("path", homePath) }
                store.dispatch(Action("filesystem.NAVIGATE", payload, name))
            }
            "filesystem.NAVIGATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigatePayload>(it) } ?: return
                try {
                    val listing = platformDependencies.listDirectory(payload.path)
                    // The payload for NAVIGATION_UPDATED must now be a proper JSON array of objects
                    val listingJson = buildJsonArray {
                        listing.forEach { entry ->
                            add(Json.encodeToJsonElement(entry))
                        }
                    }
                    val successPayload = buildJsonObject {
                        put("path", payload.path)
                        put("listing", listingJson)
                    }
                    store.dispatch(Action("filesystem.NAVIGATION_UPDATED", successPayload, name))
                } catch (e: Exception) {
                    val errorPayload = buildJsonObject {
                        put("path", payload.path)
                        put("error", e.message ?: "An unknown error occurred.")
                    }
                    store.dispatch(Action("filesystem.NAVIGATION_FAILED", errorPayload, name))
                }
            }
            "filesystem.SELECT_DIRECTORY_UI" -> {
                val selectedPath = platformDependencies.selectDirectoryPath()
                if (selectedPath != null) {
                    val payload = buildJsonObject { put("path", selectedPath) }
                    store.dispatch(Action("filesystem.NAVIGATE", payload, name))
                }
            }
        }
    }

    override fun reducer(state: AppState, action: Action): AppState {
        val currentFeatureState = state.featureStates[name] as? FileSystemState
        // If the feature state doesn't exist yet, we start with a default one.
        val resolvedFeatureState = currentFeatureState ?: FileSystemState()
        var newFeatureState: FileSystemState? = null

        when (action.name) {
            // --- THE FIX: Initialize our state on app startup ---
            "app.STARTING" -> {
                // If our state doesn't exist in the map, this is our chance to add it.
                if (currentFeatureState == null) {
                    newFeatureState = resolvedFeatureState
                }
            }
            "filesystem.NAVIGATION_UPDATED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigationUpdatedPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(
                        currentPath = it.path,
                        currentDirectoryListing = it.listing,
                        error = null
                    )
                }
            }
            "filesystem.NAVIGATION_FAILED" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<NavigationFailedPayload>(it) }
                payload?.let {
                    newFeatureState = resolvedFeatureState.copy(
                        error = it.error,
                        currentDirectoryListing = emptyList()
                    )
                }
            }
            "filesystem.STAGE_CREATE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<StageCreatePayload>(it) }
                payload?.let {
                    val operation = FileOperation.Create(it.path, it.content)
                    newFeatureState = resolvedFeatureState.copy(
                        stagedOperations = resolvedFeatureState.stagedOperations + operation
                    )
                }
            }
            "filesystem.STAGE_DELETE" -> {
                val payload = action.payload?.let { Json.decodeFromJsonElement<StageDeletePayload>(it) }
                payload?.let {
                    val operation = FileOperation.Delete(it.path)
                    newFeatureState = resolvedFeatureState.copy(
                        stagedOperations = resolvedFeatureState.stagedOperations + operation
                    )
                }
            }
            "filesystem.DISCARD" -> {
                newFeatureState = resolvedFeatureState.copy(stagedOperations = emptyList())
            }
        }

        return newFeatureState?.let {
            state.copy(featureStates = state.featureStates + (name to it))
        } ?: state
    }


    inner class FileSystemComposableProvider : Feature.ComposableProvider {
        override val viewKey: String = "feature.filesystem.main"

        @Composable
        override fun RibbonButton(store: Store, isActive: Boolean) {
            val payload = buildJsonObject { put("key", viewKey) }
            IconButton(onClick = { store.dispatch(Action("core.SET_ACTIVE_VIEW", payload, "filesystem.ui")) }) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "File System Browser",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        @Composable
        override fun StageContent(store: Store) {
            // THE FIX: Pass the dependency from the feature down into the view.
            FileSystemView(store, platformDependencies)
        }
    }
}