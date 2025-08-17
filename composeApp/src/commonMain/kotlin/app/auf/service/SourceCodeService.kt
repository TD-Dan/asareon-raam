// --- NEW FILE: commonMain/kotlin/app/auf/service/SourceCodeService.kt ---

package app.auf.service

import app.auf.util.PlatformDependencies

/**
 * ## Mandate
 * This service is responsible for all operations related to accessing and collating
 * the application's own source code for analysis or export.
 *
 * ## Dependencies
 * - `app.auf.util.PlatformDependencies`: To access the file system.
 */
class SourceCodeService(private val platform: PlatformDependencies) {

    /**
     * Scans the 'src' directory, reads all .kt files, and collates them into a
     * single, formatted string.
     */
    fun collateKtFilesToString(): String {
        val srcRootPath = "src" // Assumes the app runs from the project root.
        val srcDir = platform.getBasePathFor(app.auf.util.BasePath.FRAMEWORK).replace("framework", srcRootPath)


        if (!platform.fileExists(srcDir) || !platform.listDirectory(srcDir).any()) {
            return "ERROR: Could not find the 'src' directory at the expected path: $srcDir"
        }

        val stringBuilder = StringBuilder()
        val ktFiles = mutableListOf<String>()

        fun findKtFilesRecursive(directoryPath: String) {
            platform.listDirectory(directoryPath).forEach { entry ->
                if (entry.isDirectory) {
                    findKtFilesRecursive(entry.path)
                } else if (entry.path.endsWith(".kt")) {
                    ktFiles.add(entry.path)
                }
            }
        }

        findKtFilesRecursive(srcDir)

        ktFiles.sorted().forEach { filePath ->
            val relativePath = filePath.substringAfter(srcDir + platform.pathSeparator)
            val content = platform.readFileContent(filePath)
            stringBuilder.append("--- START OF FILE $relativePath ---\n\n")
            stringBuilder.append(content)
            stringBuilder.append("\n\n--- END OF FILE $relativePath ---\n\n")
        }

        return stringBuilder.toString().trim()
    }
}