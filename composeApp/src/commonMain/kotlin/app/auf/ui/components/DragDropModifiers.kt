package app.auf.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import app.auf.util.DroppedFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════
// DragDropModifiers — JVM Desktop drag-and-drop integration for Compose
// ═══════════════════════════════════════════════════════════════════════════
//
// IMPORTANT: fileDropTargetModifier is @Composable (not a Modifier extension)
// because it must `remember` the DragAndDropTarget to keep it stable across
// recompositions. Without this, Compose Desktop loses the target registration
// every time the parent recomposes, causing every-other-drop to be rejected.
// ═══════════════════════════════════════════════════════════════════════════


// ─── DROP IN ─────────────────────────────────────────────────────────────

/**
 * Supported AWT [DataFlavor]s, checked in priority order during drop.
 *
 * 1. [DataFlavor.javaFileListFlavor] — OS file manager drags (Windows Explorer, macOS Finder).
 * 2. URI list — Linux file managers (Nautilus, Dolphin) often provide `text/uri-list`.
 * 3. [DataFlavor.stringFlavor] — fallback for text/URL drags from browsers; saved as a .txt file.
 */
private val uriListFlavor: DataFlavor? = try {
    DataFlavor("text/uri-list;class=java.lang.String")
} catch (_: Exception) {
    null
}

/**
 * Creates a Modifier that makes a composable accept file drops from the OS.
 *
 * **Must be called from a @Composable context** — the [DragAndDropTarget] is
 * `remember`ed so it survives recomposition. Callbacks are kept fresh via
 * [rememberUpdatedState] without recreating the target.
 *
 * Accepts all drag flavors — files, URI lists, and plain text. Files and
 * URIs are resolved to [DroppedFile] instances; plain text is wrapped as
 * a `.txt` file. The caller is responsible for persisting them.
 *
 * [onDragEntered] / [onDragExited] track when the drag cursor is within
 * the composable bounds — use these to toggle a visual drop zone overlay.
 *
 * Usage:
 * ```
 * Column(modifier = Modifier.fillMaxSize().then(fileDropTargetModifier(...))) { }
 * ```
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun fileDropTargetModifier(
    onDragEntered: () -> Unit = {},
    onDragExited: () -> Unit = {},
    onFilesDropped: (List<DroppedFile>) -> Unit
): Modifier {
    // rememberUpdatedState keeps the lambda references fresh on each
    // recomposition without recreating the remembered target object.
    val currentOnDragEntered by rememberUpdatedState(onDragEntered)
    val currentOnDragExited by rememberUpdatedState(onDragExited)
    val currentOnFilesDropped by rememberUpdatedState(onFilesDropped)

    // Single stable target instance — survives all recompositions.
    // Reads the `current*` state refs (not the raw lambdas) so it always
    // invokes the latest callback without being recreated.
    val target = remember {
        object : DragAndDropTarget {

            override fun onEntered(event: DragAndDropEvent) {
                currentOnDragEntered()
            }

            override fun onExited(event: DragAndDropEvent) {
                currentOnDragExited()
            }

            override fun onEnded(event: DragAndDropEvent) {
                // Safety net: clear hover state when the drag session ends,
                // regardless of whether the cursor was inside our bounds.
                currentOnDragExited()
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnDragExited() // clear hover state first
                return try {
                    val droppedFiles = extractFiles(event.awtTransferable)
                    if (droppedFiles.isNotEmpty()) {
                        currentOnFilesDropped(droppedFiles)
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    return Modifier.dragAndDropTarget(
        // Accept ALL drags — we sort out what we can handle in onDrop.
        shouldStartDragAndDrop = { true },
        target = target
    )
}

/**
 * Attempts to extract [DroppedFile] instances from an AWT [Transferable],
 * trying multiple flavors in priority order.
 */
private fun extractFiles(transferable: Transferable): List<DroppedFile> {
    // 1. Standard file list (Windows, macOS, some Linux)
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val result = extractFromFileList(transferable)
        if (result.isNotEmpty()) return result
    }

    // 2. URI list (Linux file managers: Nautilus, Dolphin, Thunar)
    if (uriListFlavor != null && transferable.isDataFlavorSupported(uriListFlavor)) {
        val result = extractFromUriList(transferable)
        if (result.isNotEmpty()) return result
    }

    // 3. Plain text fallback (browser text selections, URLs)
    if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        val result = extractFromString(transferable)
        if (result.isNotEmpty()) return result
    }

    return emptyList()
}

/** Extracts files from [DataFlavor.javaFileListFlavor]. Skips directories and unreadable files. */
private fun extractFromFileList(transferable: Transferable): List<DroppedFile> {
    @Suppress("UNCHECKED_CAST")
    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        ?: return emptyList()

    return files.mapNotNull { file ->
        if (!file.isFile) return@mapNotNull null
        try {
            DroppedFile(name = file.name, bytes = file.readBytes())
        } catch (_: Exception) {
            null
        }
    }
}

/** Extracts files from a `text/uri-list` string. Each line is a `file://` URI. */
private fun extractFromUriList(transferable: Transferable): List<DroppedFile> {
    val uriText = transferable.getTransferData(uriListFlavor!!) as? String
        ?: return emptyList()

    return uriText.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            try {
                val file = File(URI(line))
                if (!file.isFile) return@mapNotNull null
                DroppedFile(name = file.name, bytes = file.readBytes())
            } catch (_: Exception) {
                null
            }
        }
}

/** Wraps plain text as a dropped `.txt` file. Non-empty text only. */
private fun extractFromString(transferable: Transferable): List<DroppedFile> {
    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
        ?: return emptyList()

    if (text.isBlank()) return emptyList()

    val firstLine = text.lineSequence().firstOrNull()?.trim() ?: "dropped"
    val safeName = firstLine
        .take(40)
        .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
        .trim()
        .ifEmpty { "dropped" }

    return listOf(DroppedFile(name = "$safeName.txt", bytes = text.toByteArray(Charsets.UTF_8)))
}


// ─── DRAG OUT ────────────────────────────────────────────────────────────

/**
 * AWT [Transferable] that provides a list of files to the OS drag layer.
 * Wrapped in [DragAndDropTransferable] before being handed to Compose.
 */
private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> =
        arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.javaFileListFlavor) {
            throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        }
        return files
    }
}

/**
 * Modifier that makes a composable draggable as an OS file.
 *
 * On drag initiation, copies the sandbox file to a temp directory (so the
 * OS sees a real file path with the correct name) and provides it to the
 * system drag layer via AWT [Transferable] wrapped in [DragAndDropTransferable].
 *
 * @param absolutePath The absolute filesystem path to the source file in the sandbox.
 * @param fileName The display name for the dragged file.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.fileDragSource(
    absolutePath: String,
    fileName: String
): Modifier = this.dragAndDropSource(
    drawDragDecoration = {
        drawRect(
            color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f),
            size = size
        )
    },
    transferData = { _: Offset ->
        val tempFile = prepareDragSourceFile(absolutePath, fileName)
        if (tempFile != null) {
            DragAndDropTransferData(
                transferable = DragAndDropTransferable(FileListTransferable(listOf(tempFile))),
                supportedActions = listOf(DragAndDropTransferAction.Copy)
            )
        } else {
            null
        }
    }
)

/**
 * Creates an AWT-compatible temporary file from sandbox content that can
 * be used as a drag source. Returns the temp file, or null on failure.
 */
fun prepareDragSourceFile(absolutePath: String, fileName: String): File? {
    return try {
        val source = File(absolutePath)
        if (!source.exists() || !source.isFile) return null

        val tempDir = File(System.getProperty("java.io.tmpdir"), "auf-drag")
        tempDir.mkdirs()
        val tempFile = File(tempDir, fileName)
        source.copyTo(tempFile, overwrite = true)
        tempFile.deleteOnExit()
        tempFile
    } catch (_: Exception) {
        null
    }
}