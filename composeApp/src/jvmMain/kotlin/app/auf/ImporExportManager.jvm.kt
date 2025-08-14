package app.auf

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JVM-specific implementation of the ImportExportManager.
 * This class handles all file system interactions using java.io.File.
 */
actual class ImportExportManager actual constructor(
    private val frameworkBasePath: String,
    private val jsonParser: Json
) {

    actual fun analyzeFolder(sourcePath: String, currentGraph: List<HolonHeader>): List<ImportItem> {
        val sourceDir = File(sourcePath)
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return emptyList()
        }

        val currentGraphMap = currentGraph.associateBy { it.id }
        val parentMap = currentGraph.flatMap { parent -> parent.subHolons.map { child -> child.id to parent.id } }.toMap()
        val sourceFiles = sourceDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()

        return sourceFiles.mapNotNull { sourceFile ->
            try {
                val fileContent = sourceFile.readText()
                jsonParser.parseToJsonElement(fileContent)

                val holonId = sourceFile.nameWithoutExtension
                val existingHeader = currentGraphMap[holonId]

                // --- BUG FIX ---
                // The compiler error occurred because ImportItem now expects a String path,
                // not a File object. The fix is to pass `sourceFile.absolutePath`.
                if (existingHeader != null) {
                    val existingFile = File(existingHeader.filePath)
                    if (existingFile.exists() && sourceFile.lastModified() > existingFile.lastModified()) {
                        ImportItem(sourceFile.absolutePath, Update(holonId), existingHeader.filePath)
                    } else {
                        ImportItem(sourceFile.absolutePath, Ignore())
                    }
                } else {
                    val knownParentId = parentMap[holonId]
                    if (knownParentId != null) {
                        val parentPath = currentGraphMap[knownParentId]?.filePath?.let { File(it).parent } ?: ""
                        ImportItem(sourceFile.absolutePath, Integrate(knownParentId), "$parentPath/$holonId/")
                    } else {
                        ImportItem(sourceFile.absolutePath, AssignParent())
                    }
                }
            } catch (e: SerializationException) {
                ImportItem(sourceFile.absolutePath, Quarantine("Malformed JSON: ${e.message?.substringBefore('\n')}"))
            } catch (e: Exception) {
                println("Could not analyze file ${sourceFile.name}, ignoring. Error: ${e.message}")
                null
            }
        }
    }

    actual fun executeExport(destinationPath: String, holonsToExport: List<HolonHeader>) {
        val destDir = File(destinationPath)
        if (!destDir.exists()) destDir.mkdirs()
        try {
            val manualProtocolFile = File("$frameworkBasePath/framework_protocol_manual.md")
            if (manualProtocolFile.exists()) {
                Files.copy(
                    manualProtocolFile.toPath(),
                    File(destDir, manualProtocolFile.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            println("Failed to copy manual protocol file: ${e.message}")
        }
        holonsToExport.forEach { holonHeader ->
            val sourceFile = File(holonHeader.filePath)
            val destFile = File(destDir, sourceFile.name)
            try {
                Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                println("Failed to copy ${sourceFile.name}: ${e.message}")
            }
        }
    }

    actual fun executeImport(
        importState: ImportState,
        currentGraph: List<HolonHeader>,
        personaId: String,
        holonsBasePath: String
    ) {
        val personaRoot = File(holonsBasePath, personaId)
        val quarantineDir = File(personaRoot, "quarantined-imports").apply { mkdirs() }

        importState.items.forEach { item ->
            // Reconstitute the File object from the platform-agnostic string path
            val sourceFile = File(item.sourcePath)
            if (!sourceFile.exists()) {
                println("Skipping import for ${item.sourcePath} as it does not exist.")
                return@forEach // 'continue' in a forEach loop
            }

            val finalAction = importState.selectedActions[item.sourcePath] ?: item.initialAction
            try {
                when (finalAction) {
                    is Update -> {
                        val targetHolon = currentGraph.find { it.id == finalAction.targetHolonId }
                        if (targetHolon != null) {
                            val destFile = File(targetHolon.filePath)
                            sourceFile.copyTo(destFile, overwrite = true)
                        }
                    }
                    is Integrate -> {
                        val parentHolon = currentGraph.find { it.id == finalAction.parentHolonId }
                        if (parentHolon != null) {
                            val parentDir = File(parentHolon.filePath).parentFile
                            val newHolonDir = File(parentDir, sourceFile.nameWithoutExtension)
                            newHolonDir.mkdirs()
                            val destFile = File(newHolonDir, sourceFile.name)
                            sourceFile.copyTo(destFile, overwrite = true)
                        }
                    }
                    is AssignParent -> {
                        finalAction.assignedParentId?.let { parentId ->
                            val parentHolonHeader = currentGraph.find { it.id == parentId }
                            if (parentHolonHeader != null) {
                                val parentFile = File(parentHolonHeader.filePath)
                                val parentContent = jsonParser.decodeFromString<Holon>(parentFile.readText())
                                val newHolonHeader = jsonParser.decodeFromString<Holon>(sourceFile.readText()).header

                                val newSummary = "[IMPORTED-UNVALIDATED]: ${newHolonHeader.summary}. AI must treat this as foreign material until reviewed and integrated."
                                val newSubRef = SubHolonRef(newHolonHeader.id, newHolonHeader.type, newSummary)

                                if (parentContent.header.subHolons.none { it.id == newSubRef.id }) {
                                    val updatedSubHolons = parentContent.header.subHolons + newSubRef
                                    val updatedParent = parentContent.copy(header = parentContent.header.copy(subHolons = updatedSubHolons))
                                    parentFile.writeText(jsonParser.encodeToString(updatedParent))
                                }

                                val parentDir = parentFile.parentFile
                                val newHolonDir = File(parentDir, sourceFile.nameWithoutExtension)
                                newHolonDir.mkdirs()
                                val destFile = File(newHolonDir, sourceFile.name)
                                sourceFile.copyTo(destFile, overwrite = true)
                            }
                        }
                    }
                    is Quarantine -> {
                        val destFile = File(quarantineDir, sourceFile.name)
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                    is Ignore -> { /* Do nothing */
                    }
                }
            } catch (e: Exception) {
                println("Error processing import for ${sourceFile.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}