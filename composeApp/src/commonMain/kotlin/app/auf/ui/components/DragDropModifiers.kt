package app.auf.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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

// ═══════════════════════════════════════════════════════════════════════════
// DragDropModifiers — JVM Desktop drag-and-drop integration for Compose
// ═══════════════════════════════════════════════════════════════════════════
//
// Compose Desktop–specific utilities that bridge AWT drag/drop to Compose
// Modifiers. Lives in the UI layer because it depends on Compose + AWT APIs.
// ═══════════════════════════════════════════════════════════════════════════


// ─── DROP IN ─────────────────────────────────────────────────────────────

/**
 * Modifier that makes a composable accept file drops from the OS.
 *
 * When files are dropped onto the composable, they are read into memory
 * as [DroppedFile] instances and passed to [onFilesDropped]. The caller
 * is responsible for persisting them (typically via writeFileBytes + refresh).
 *
 * [onDragEntered] / [onDragExited] fire when a drag cursor enters/leaves
 * the composable bounds — use these to toggle a visual drop zone indicator.
 *
 * Only files (not directories) are included. Unreadable files are skipped.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.fileDropTarget(
    onDragEntered: () -> Unit = {},
    onDragExited: () -> Unit = {},
    onFilesDropped: (List<DroppedFile>) -> Unit
): Modifier = this.dragAndDropTarget(
    shouldStartDragAndDrop = { event ->
        try {
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        } catch (_: Exception) {
            false
        }
    },
    target = object : DragAndDropTarget {

        override fun onStarted(event: DragAndDropEvent) {
            onDragEntered()
        }

        override fun onEnded(event: DragAndDropEvent) {
            onDragExited()
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            onDragExited()
            return try {
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false

                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                    ?: return false

                val droppedFiles = files
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        try {
                            DroppedFile(name = file.name, bytes = file.readBytes())
                        } catch (_: Exception) {
                            null
                        }
                    }

                if (droppedFiles.isNotEmpty()) {
                    onFilesDropped(droppedFiles)
                }
                droppedFiles.isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }
)


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
 *
 * The temp file preserves the original name so external apps see the
 * correct filename. Files are cleaned up on JVM exit.
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