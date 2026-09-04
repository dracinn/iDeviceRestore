package com.idevicerestore.android

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Firmware workspace rooted in the user's shared-storage iDeviceRestore directory.
 *
 * Canonical layout:
 *   /storage/emulated/0/iDeviceRestore/Firmware/IPSW/<version>-<build>/<firmware>.ipsw
 *   /storage/emulated/0/iDeviceRestore/Firmware/<identifier>/Metadata/catalog.json
 *   /storage/emulated/0/iDeviceRestore/Firmware/<identifier>/Logs/
 *
 * IPSW payloads are deliberately device-independent. A UniversalMac or other restore image that is
 * valid for multiple identifiers is stored once in the shared IPSW cache instead of being copied
 * into every device workspace. Device-specific metadata and logs remain isolated by identifier.
 *
 * Storage is deliberately passive: it never starts firmware verification or BuildManifest parsing.
 * The automatic preparation pipeline owns those operations after a firmware is selected and ready.
 *
 * Android 11+ requires MANAGE_EXTERNAL_STORAGE (All files access) for direct File access here.
 */
class FirmwareStorage(
    private val context: Context,
    private val logger: (String) -> Unit = {}
) {
    data class Workspace(
        val root: File,
        val device: File,
        /** Shared physical IPSW cache used by every device identifier. */
        val firmware: File,
        val metadata: File,
        val logs: File
    )

    data class FirmwareLocation(
        val workspace: Workspace,
        val buildDirectory: File,
        val file: File,
        val catalogCache: File
    )

    /** Existing user-visible project folder at the root of primary shared storage. */
    val projectRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "iDeviceRestore")

    fun hasSharedStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED && projectRoot.canWrite()
        }

    fun requireSharedStorageAccess() {
        check(hasSharedStorageAccess()) {
            "Shared storage access is required for ${projectRoot.absolutePath}. " +
                "Enable 'Allow access to manage all files' for iDeviceRestore."
        }
    }

    fun prepare(identifier: String): Workspace {
        requireSharedStorageAccess()
        val safeIdentifier = safeComponent(identifier)
        val root = File(projectRoot, "Firmware")
        val device = File(root, safeIdentifier)
        val firmware = File(root, "IPSW")
        val metadata = File(device, "Metadata")
        val logs = File(device, "Logs")
        listOf(projectRoot, root, device, firmware, metadata, logs).forEach(::ensureDirectory)
        logger("FirmwareStorage: shared project root=${projectRoot.absolutePath}")
        logger("FirmwareStorage: device workspace=${device.absolutePath}")
        logger("FirmwareStorage: shared IPSW cache=${firmware.absolutePath}")
        return Workspace(root, device, firmware, metadata, logs)
    }

    fun locationFor(firmware: FirmwareCatalog.Firmware): FirmwareLocation {
        val workspace = prepare(firmware.identifier)
        val buildName = safeComponent("${firmware.version}-${firmware.buildId}")
        val buildDirectory = File(workspace.firmware, buildName)
        ensureDirectory(buildDirectory)
        val fileName = safeFileName(firmware.fileName).ifBlank {
            safeFileName("${firmware.identifier}_${firmware.version}_${firmware.buildId}.ipsw")
        }
        val destination = File(buildDirectory, fileName)
        migrateLegacyDevicePayload(firmware, buildName, fileName, destination)
        return FirmwareLocation(
            workspace = workspace,
            buildDirectory = buildDirectory,
            file = destination,
            catalogCache = File(workspace.metadata, "catalog.json")
        )
    }

    fun catalogCacheFor(identifier: String): File = File(prepare(identifier).metadata, "catalog.json")

    fun availableBytes(identifier: String): Long = prepare(identifier).firmware.usableSpace

    fun hasEnoughSpace(
        identifier: String,
        expectedBytes: Long,
        reserveBytes: Long = 256L * 1024 * 1024
    ): Boolean {
        if (expectedBytes <= 0) return true
        val available = availableBytes(identifier)
        val required = expectedBytes + reserveBytes
        logger("FirmwareStorage: free=$available required=$required payload=$expectedBytes reserve=$reserveBytes")
        return available >= required
    }

    fun isComplete(firmware: FirmwareCatalog.Firmware): Boolean {
        val file = locationFor(firmware).file
        return file.isFile && (firmware.fileSize <= 0 || file.length() == firmware.fileSize)
    }

    fun partialBytes(firmware: FirmwareCatalog.Firmware): Long {
        val destination = locationFor(firmware).file
        return partialFiles(destination).sumOf { it.length() }
    }

    fun removePartial(firmware: FirmwareCatalog.Firmware): Int {
        val destination = locationFor(firmware).file
        var deleted = 0
        partialFiles(destination).forEach { if (it.delete()) deleted++ }
        logger("FirmwareStorage: removed $deleted shared partial file(s) for ${firmware.buildId}")
        return deleted
    }

    /**
     * Builds before the shared cache stored payloads under Firmware/<identifier>/IPSW. Move those
     * files into the canonical cache without duplicating multi-gigabyte IPSWs. Because both paths
     * are on the same shared-storage volume, renameTo is normally an in-place filesystem rename.
     *
     * We never delete a legacy full file when a canonical destination already exists. That avoids
     * treating a byte-count match as cryptographic identity. The canonical file is simply preferred
     * and a diagnostic is emitted so a later maintenance UI can offer safe duplicate cleanup.
     */
    private fun migrateLegacyDevicePayload(
        firmware: FirmwareCatalog.Firmware,
        buildName: String,
        fileName: String,
        destination: File
    ) {
        val safeIdentifier = safeComponent(firmware.identifier)
        val legacyBuildDirectory = File(File(File(projectRoot, "Firmware"), safeIdentifier), "IPSW/$buildName")
        val legacyFile = File(legacyBuildDirectory, fileName)

        if (!destination.exists() && legacyFile.isFile) {
            ensureDirectory(destination.parentFile ?: return)
            if (legacyFile.renameTo(destination)) {
                logger("FirmwareStorage: migrated legacy IPSW into shared cache: ${destination.absolutePath}")
            } else {
                logger("FirmwareStorage: legacy IPSW remains at ${legacyFile.absolutePath}; shared migration rename failed")
            }
        } else if (destination.isFile && legacyFile.isFile) {
            logger("FirmwareStorage: duplicate legacy IPSW detected at ${legacyFile.absolutePath}; using shared cache copy")
        }

        migrateLegacyPartial(File(legacyFile.absolutePath + ".part"), File(destination.absolutePath + ".part"))
        legacyBuildDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(fileName + ".part.") }
            .forEach { legacyPart ->
                val suffix = legacyPart.name.removePrefix(fileName)
                migrateLegacyPartial(legacyPart, File(destination.absolutePath + suffix))
            }
    }

    private fun migrateLegacyPartial(legacy: File, destination: File) {
        if (!legacy.isFile || destination.exists()) return
        ensureDirectory(destination.parentFile ?: return)
        if (legacy.renameTo(destination)) {
            logger("FirmwareStorage: migrated legacy partial download into shared cache: ${destination.name}")
        } else {
            logger("FirmwareStorage: could not migrate legacy partial download: ${legacy.absolutePath}")
        }
    }

    private fun partialFiles(destination: File): List<File> =
        destination.parentFile?.listFiles().orEmpty().filter {
            it.name == destination.name + ".part" || it.name.startsWith(destination.name + ".part.")
        }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create ${directory.absolutePath}"
        }
    }

    private fun safeComponent(value: String): String {
        val cleaned = value.trim().replace(Regex("[^A-Za-z0-9,._-]+"), "_").trim('_', '.')
        return cleaned.ifBlank { "unknown" }.take(120)
    }

    private fun safeFileName(value: String): String {
        val base = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9,._()+ -]+"), "_").trim()
        return cleaned.ifBlank { "firmware.ipsw" }.takeLast(180)
    }
}
