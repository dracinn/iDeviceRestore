package com.idevicerestore.android

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Extracts one BuildManifest-resolved component from a local IPSW without loading it fully into RAM.
 *
 * Extraction is deliberately separate from personalization and USB transport. A raw component
 * produced here must never be sent directly to DFU/Recovery on a production restore path.
 */
class IpswComponentExtractor(
    private val logger: (String) -> Unit = {}
) {
    data class ExtractedComponent(
        val name: String,
        val manifestPath: String,
        val file: File,
        val bytes: Long,
        val identityIndex: Int
    )

    fun extract(
        ipsw: File,
        preflight: IpswPreflight.Result,
        component: String,
        destinationDirectory: File
    ): ExtractedComponent {
        require(ipsw.isFile) { "IPSW not found: ${ipsw.absolutePath}" }
        require(component.isNotBlank()) { "Component name cannot be blank" }
        val manifestPath = preflight.componentPaths[component]
            ?: error("Component '$component' is not present in selected BuildIdentity ${preflight.identityIndex}")

        check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            "Could not create component workspace: ${destinationDirectory.absolutePath}"
        }

        val safeName = component.replace(Regex("[^A-Za-z0-9,._-]+"), "_")
        val destination = File(destinationDirectory, "identity-${preflight.identityIndex}-$safeName.raw")
        val temporary = File(destination.parentFile, destination.name + ".part")

        logger("IPSW component: extracting $component from $manifestPath")
        ZipFile(ipsw).use { zip ->
            val entry = zip.getEntry(manifestPath)
                ?: throw IOException("IPSW entry is missing for $component: $manifestPath")
            zip.getInputStream(entry).buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            if (entry.size >= 0 && temporary.length() != entry.size) {
                temporary.delete()
                throw IOException(
                    "Extracted $component size mismatch: expected ${entry.size}, got ${temporary.length()}"
                )
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IOException("Could not replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not finalize extracted component ${destination.absolutePath}")
        }

        logger("IPSW component: extracted $component bytes=${destination.length()} identity=${preflight.identityIndex}")
        return ExtractedComponent(
            name = component,
            manifestPath = manifestPath,
            file = destination,
            bytes = destination.length(),
            identityIndex = preflight.identityIndex
        )
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
