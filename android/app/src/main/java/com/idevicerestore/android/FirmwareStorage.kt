package com.idevicerestore.android

import android.content.Context
import android.os.Environment
import java.io.File

/** App-owned firmware workspace. No broad storage permission is required. */
class FirmwareStorage(private val context: Context) {
    data class Workspace(
        val root: File,
        val device: File,
        val firmware: File,
        val metadata: File,
        val logs: File
    )

    fun prepare(identifier: String): Workspace {
        val safeIdentifier = identifier.replace(Regex("[^A-Za-z0-9,._-]"), "_")
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val root = File(external, "iDeviceRestore/Firmware")
        val device = File(root, safeIdentifier)
        val firmware = File(device, "IPSW")
        val metadata = File(device, "Metadata")
        val logs = File(device, "Logs")
        listOf(root, device, firmware, metadata, logs).forEach { dir ->
            check(dir.exists() || dir.mkdirs()) { "Could not create ${dir.absolutePath}" }
        }
        return Workspace(root, device, firmware, metadata, logs)
    }
}
