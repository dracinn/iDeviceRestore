package com.idevicerestore.android

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/**
 * Firmware workspace rooted in the user's shared-storage iDeviceRestore directory.
 *
 * The active payload layout follows the user's "Organize firmware by device" setting:
 *   enabled:  Firmware/<identifier>/IPSW/<version>-<build>/<firmware>.ipsw
 *   disabled: Firmware/IPSW/<version>-<build>/<firmware>.ipsw
 *
 * Metadata and diagnostic logs remain device-scoped in both modes. When the setting changes,
 * locationFor() migrates the selected payload and its resumable-download sidecars from the previous
 * layout by rename on the same shared-storage volume. Existing duplicates are never deleted
 * automatically because matching names or sizes are not sufficient proof of byte identity.
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
    private val appSettings by lazy { AppSettings(context) }

    data class Workspace(
        val root: File,
        val device: File,
        /** Active IPSW root selected by the current storage organization preference. */
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
        val firmware = if (appSettings.organizeFirmwareByDevice) {
            File(device, "IPSW")
        } else {
            File(root, "IPSW")
        }
        val metadata = File(device, "Metadata")
        val logs = File(device, "Logs")
        listOf(projectRoot, root, device, firmware, metadata, logs).forEach(::ensureDirectory)
        logger("FirmwareStorage: project root=${projectRoot.absolutePath}")
        logger("FirmwareStorage: device workspace=${device.absolutePath}")
        logger(
            "FirmwareStorage: IPSW organization=${if (appSettings.organizeFirmwareByDevice) "by-device" else "shared"} " +
                "path=${firmware.absolutePath}"
        )
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
        migrateAlternatePayloadLayout(firmware, buildName, fileName, destination)
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
        val part = File(destination.absolutePath + ".part")
        if (!part.isFile) return 0L

        val aria2Control = File(part.absolutePath + ".aria2")
        if (aria2Control.isFile) {
            // aria2 split downloads can write high ranges first, so logical file length is not a
            // progress signal. With --file-allocation=none, filesystem allocated blocks represent
            // storage already consumed by the sparse partial, which is exactly what the free-space
            // check needs when calculating how much additional capacity is required to finish.
            val allocated = allocatedBytes(part)
            val accounted = firmware.fileSize.takeIf { it > 0L }?.let { min(allocated, it) } ?: allocated
            logger(
                "FirmwareStorage: aria2 partial logical=${part.length()} allocated=$allocated " +
                    "accounted=$accounted control=${aria2Control.name}"
            )
            return accounted
        }

        adaptivePartialBytes(destination)?.let { return it }

        val hasAdaptiveMetadata = File(destination.absolutePath + ".part.meta").exists() ||
            File(destination.absolutePath + ".part.meta.tmp").exists()
        if (hasAdaptiveMetadata) {
            logger("FirmwareStorage: adaptive partial metadata invalid; reporting zero trusted bytes")
            return 0L
        }

        // A full-length untagged .part is ambiguous because older adaptive transfers preallocated
        // their payload. Never treat logical length alone as completed data for free-space checks.
        if (firmware.fileSize > 0L && part.length() >= firmware.fileSize) return 0L
        return part.length()
    }

    private fun allocatedBytes(file: File): Long = runCatching {
        Math.multiplyExact(Os.stat(file.absolutePath).st_blocks, 512L)
    }.getOrElse { error ->
        logger("FirmwareStorage: could not read allocated blocks for ${file.name}: ${error.message}")
        0L
    }

    private fun adaptivePartialBytes(destination: File): Long? {
        val part = File(destination.absolutePath + ".part")
        val candidates = listOf(
            File(destination.absolutePath + ".part.meta"),
            File(destination.absolutePath + ".part.meta.tmp")
        )
        for (meta in candidates) {
            if (!meta.isFile) continue
            val parsed = runCatching {
                val lines = meta.readLines()
                if (lines.firstOrNull() != "version=1") return@runCatching null
                val total = lines.find { it.startsWith("size=") }
                    ?.substringAfter("size=")?.toLongOrNull() ?: return@runCatching null
                val chunk = lines.find { it.startsWith("chunk=") }
                    ?.substringAfter("chunk=")?.toLongOrNull() ?: return@runCatching null
                val bits = lines.find { it.startsWith("completed=") }
                    ?.substringAfter("completed=") ?: return@runCatching null
                if (total <= 0L || chunk <= 0L || !part.isFile || part.length() != total) return@runCatching null
                val expectedChunks = ceil(total.toDouble() / chunk.toDouble()).toInt().coerceAtLeast(1)
                if (bits.length != expectedChunks || bits.any { it != '0' && it != '1' }) return@runCatching null
                bits.indices.sumOf { index ->
                    if (bits[index] != '1') 0L
                    else {
                        val start = index * chunk
                        val endExclusive = min(total, start + chunk)
                        endExclusive - start
                    }
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun removePartial(firmware: FirmwareCatalog.Firmware): Int {
        val destination = locationFor(firmware).file
        var deleted = 0
        partialFiles(destination).forEach { if (it.delete()) deleted++ }
        logger("FirmwareStorage: removed $deleted partial file(s) for ${firmware.buildId}")
        return deleted
    }

    /**
     * Migrate the selected firmware between the old shared cache and the by-device layout when the
     * preference changes. This is intentionally lazy so a setting change does not walk or rewrite
     * a potentially huge firmware library on the UI thread.
     */
    private fun migrateAlternatePayloadLayout(
        firmware: FirmwareCatalog.Firmware,
        buildName: String,
        fileName: String,
        destination: File
    ) {
        val root = File(projectRoot, "Firmware")
        val safeIdentifier = safeComponent(firmware.identifier)
        val deviceRoot = File(root, safeIdentifier)
        val alternateFirmwareRoot = if (appSettings.organizeFirmwareByDevice) {
            File(root, "IPSW")
        } else {
            File(deviceRoot, "IPSW")
        }
        val alternateBuildDirectory = File(alternateFirmwareRoot, buildName)
        val alternateFile = File(alternateBuildDirectory, fileName)
        val targetLabel = if (appSettings.organizeFirmwareByDevice) "by-device" else "shared"

        if (!destination.exists() && alternateFile.isFile) {
            ensureDirectory(destination.parentFile ?: return)
            if (alternateFile.renameTo(destination)) {
                logger("FirmwareStorage: migrated IPSW to $targetLabel layout: ${destination.absolutePath}")
            } else {
                logger(
                    "FirmwareStorage: IPSW remains at ${alternateFile.absolutePath}; " +
                        "$targetLabel layout migration rename failed"
                )
            }
        } else if (destination.isFile && alternateFile.isFile) {
            logger(
                "FirmwareStorage: duplicate IPSW found in alternate layout at ${alternateFile.absolutePath}; " +
                    "using ${destination.absolutePath}"
            )
        }

        migrateSidecar(File(alternateFile.absolutePath + ".part"), File(destination.absolutePath + ".part"), targetLabel)
        alternateBuildDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(fileName + ".part.") }
            .forEach { alternatePart ->
                val suffix = alternatePart.name.removePrefix(fileName)
                migrateSidecar(alternatePart, File(destination.absolutePath + suffix), targetLabel)
            }
    }

    private fun migrateSidecar(source: File, destination: File, targetLabel: String) {
        if (!source.isFile || destination.exists()) return
        ensureDirectory(destination.parentFile ?: return)
        if (source.renameTo(destination)) {
            logger("FirmwareStorage: migrated partial sidecar to $targetLabel layout: ${destination.name}")
        } else {
            logger("FirmwareStorage: could not migrate partial sidecar: ${source.absolutePath}")
        }
    }

    private fun partialFiles(destination: File): List<File> =
        destination.parentFile?.listFiles().orEmpty().filter {
            it.name == destination.name + ".part" || it.name.startsWith(destination.name + ".part.")
        }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        directory.mkdirs()
        check(directory.isDirectory) {
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
