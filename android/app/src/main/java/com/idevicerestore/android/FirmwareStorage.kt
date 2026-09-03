package com.idevicerestore.android

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Firmware workspace rooted in the user's shared-storage iDeviceRestore directory.
 *
 * Canonical layout:
 *   /storage/emulated/0/iDeviceRestore/Firmware/<identifier>/IPSW/<version>-<build>/<firmware>.ipsw
 *   /storage/emulated/0/iDeviceRestore/Firmware/<identifier>/Metadata/catalog.json
 *   /storage/emulated/0/iDeviceRestore/Firmware/<identifier>/Logs/
 *
 * Android 11+ requires MANAGE_EXTERNAL_STORAGE (All files access) for direct File access here.
 * Direct File access is intentional because segmented/resumable IPSW transfers use independent
 * .part files, random-access-friendly paths, atomic rename, and later restore components need a
 * stable filesystem path to the IPSW.
 */
class FirmwareStorage(
    private val context: Context,
    private val logger: (String) -> Unit = {}
) {
    data class Workspace(
        val root: File,
        val device: File,
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

    private val preflightStarted = ConcurrentHashMap.newKeySet<String>()
    private val preflightExecutor = Executors.newSingleThreadExecutor()

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
        val firmware = File(device, "IPSW")
        val metadata = File(device, "Metadata")
        val logs = File(device, "Logs")
        listOf(projectRoot, root, device, firmware, metadata, logs).forEach(::ensureDirectory)
        logger("FirmwareStorage: shared project root=${projectRoot.absolutePath}")
        logger("FirmwareStorage: workspace=${device.absolutePath}")
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
        val location = FirmwareLocation(
            workspace = workspace,
            buildDirectory = buildDirectory,
            file = File(buildDirectory, fileName),
            catalogCache = File(workspace.metadata, "catalog.json")
        )
        maybePreflightComplete(firmware, location.file)
        return location
    }

    fun catalogCacheFor(identifier: String): File = File(prepare(identifier).metadata, "catalog.json")

    fun availableBytes(identifier: String): Long = prepare(identifier).device.usableSpace

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
        logger("FirmwareStorage: removed $deleted partial file(s) for ${firmware.identifier} ${firmware.buildId}")
        return deleted
    }

    private fun maybePreflightComplete(firmware: FirmwareCatalog.Firmware, file: File) {
        if (!file.isFile) return
        if (firmware.fileSize > 0L && file.length() != firmware.fileSize) return
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (!preflightStarted.add(key)) return

        preflightExecutor.execute {
            logger("IPSW preflight: local firmware is complete; starting read-only manifest inspection")
            runCatching {
                val device = FirmwareCatalog(logger = { message -> logger("IPSW preflight catalog: $message") })
                    .listDevices()
                    .firstOrNull { it.identifier.equals(firmware.identifier, ignoreCase = true) }
                    ?: error("No device metadata found for ${firmware.identifier}")

                IpswPreflight(logger = logger).inspect(
                    IpswPreflight.Request(
                        ipsw = file,
                        identifier = firmware.identifier,
                        boardConfig = device.boardConfig,
                        chipId = device.cpid,
                        boardId = device.bdid
                    )
                )
            }.onSuccess { result ->
                logger(
                    "IPSW preflight: READY identity=${result.identityIndex} " +
                        "board=${result.boardConfig ?: "unknown"} components=${result.componentPaths.size}; " +
                        "no USB restore command sent"
                )
            }.onFailure { error ->
                logger(
                    "IPSW preflight: FAILED ${error.javaClass.simpleName}: ${error.message}; " +
                        "no USB restore command sent"
                )
            }
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
