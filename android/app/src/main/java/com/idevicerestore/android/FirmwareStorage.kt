package com.idevicerestore.android

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * App-owned firmware workspace. No broad storage permission is required.
 *
 * Layout:
 *   iDeviceRestore/Firmware/<identifier>/IPSW/<version>-<build>/<firmware>.ipsw
 *   iDeviceRestore/Firmware/<identifier>/Metadata/catalog.json
 *   iDeviceRestore/Firmware/<identifier>/Logs/
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

    fun prepare(identifier: String): Workspace {
        val safeIdentifier = safeComponent(identifier)
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val root = File(external, "iDeviceRestore/Firmware")
        val device = File(root, safeIdentifier)
        val firmware = File(device, "IPSW")
        val metadata = File(device, "Metadata")
        val logs = File(device, "Logs")
        listOf(root, device, firmware, metadata, logs).forEach(::ensureDirectory)
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
        return FirmwareLocation(
            workspace = workspace,
            buildDirectory = buildDirectory,
            file = File(buildDirectory, fileName),
            catalogCache = File(workspace.metadata, "catalog.json")
        )
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
