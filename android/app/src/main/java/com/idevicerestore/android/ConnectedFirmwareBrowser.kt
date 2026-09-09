package com.idevicerestore.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Device-driven firmware browser.
 *
 * Only products that this app has previously identified from a real Apple USB connection are
 * presented. The global device catalog remains an internal lookup source and is never exposed as
 * a general firmware-browsing list here.
 */
object ConnectedFirmwareBrowser {
    private const val GENERATED_TAG = "connected_firmware_browser"
    private const val REQUEST_NOTIFICATIONS = 4110
    private val worker = Executors.newSingleThreadExecutor()

    fun render(root: View, context: Context) {
        val screen = root.findViewById<ScrollView?>(R.id.screenFirmware) ?: return
        val content = screen.getChildAt(0) as? LinearLayout ?: return

        // Remove the reference mockup's global search, platform filters, and sample device rows.
        // Their positions are stable in activity_main.xml: header/subtitle first, then browse UI.
        for (index in 2..4) content.getChildAt(index)?.visibility = View.GONE

        // The old "Available Firmware" section is tied to MainActivity's currently connected
        // device. Historical rows below own their firmware chooser/download flow instead.
        content.getChildAt(5)?.visibility = View.GONE
        content.getChildAt(6)?.visibility = View.GONE

        val existing = content.findViewWithTag<View?>(GENERATED_TAG)
        if (existing != null) content.removeView(existing)

        val container = LinearLayout(context).apply {
            tag = GENERATED_TAG
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10) }
        }

        val entries = ConnectedDeviceHistory(context).entries()
        container.addView(TextView(context).apply {
            text = "Devices you've connected"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.mock_text_primary))
            setPadding(0, 0, 0, dp(context, 7))
        })

        if (entries.isEmpty()) {
            container.addView(MaterialCardView(context).apply {
                addView(TextView(context).apply {
                    text = "No identified devices yet. Connect a supported Apple device by USB and let iDeviceRestore identify it; firmware for that model will then appear here."
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.mock_text_secondary))
                    setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                })
            })
        } else {
            entries.forEachIndexed { index, entry ->
                container.addView(deviceRow(context, entry).apply {
                    if (index > 0) {
                        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 7)
                    }
                })
            }
        }

        // Insert directly after the firmware screen subtitle.
        content.addView(container, 2)
    }

    private fun deviceRow(context: Context, entry: ConnectedDeviceHistory.Entry): View {
        return MaterialCardView(context).apply {
            radius = dp(context, 12).toFloat()
            strokeWidth = dp(context, 1)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.mock_surface))
            setContentPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = entry.name
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.mock_text_primary))
                })
                addView(TextView(context).apply {
                    text = "${entry.identifier}  •  previously connected"
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(context, R.color.mock_text_secondary))
                })
                addView(MaterialButton(context).apply {
                    text = "Choose firmware to download"
                    isAllCaps = false
                    setOnClickListener { loadFirmwareChoices(context, entry) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 42)
                    ).apply { topMargin = dp(context, 6) }
                })
            })
        }
    }

    private fun loadFirmwareChoices(context: Context, entry: ConnectedDeviceHistory.Entry) {
        if (FirmwareDownloadService.isDownloadActive()) {
            AlertDialog.Builder(context)
                .setTitle("Firmware download active")
                .setMessage("Finish or cancel the current firmware download before starting another one.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val loading = AlertDialog.Builder(context)
            .setTitle(entry.name)
            .setMessage("Checking currently signed firmware…")
            .setCancelable(false)
            .create()
        loading.show()

        worker.execute {
            val catalog = FirmwareCatalog()
            val choices = runCatching { catalog.firmwares(entry.identifier, signedOnly = true) }
                .getOrElse { emptyList() }
                .sortedWith(
                    compareByDescending<FirmwareCatalog.Firmware> { it.releaseDate ?: Instant.EPOCH }
                        .thenByDescending { it.version }
                        .thenByDescending { it.buildId }
                )

            runOnActivity(context) {
                loading.dismiss()
                if (choices.isEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle(entry.name)
                        .setMessage("No currently signed firmware was reported for ${entry.identifier}.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnActivity
                }

                val labels = choices.map { firmware ->
                    buildString {
                        append(firmware.version)
                        append(" (")
                        append(firmware.buildId)
                        append(")")
                        if (firmware.fileSize > 0L) {
                            append(" — ")
                            append(FirmwareDownloadService.formatBytes(firmware.fileSize))
                        }
                    }
                }.toTypedArray()

                AlertDialog.Builder(context)
                    .setTitle("${entry.name} firmware")
                    .setItems(labels) { _, which ->
                        reverifyAndDownload(context, entry, choices[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun reverifyAndDownload(
        context: Context,
        entry: ConnectedDeviceHistory.Entry,
        choice: FirmwareCatalog.Firmware
    ) {
        val checking = AlertDialog.Builder(context)
            .setTitle("Verify firmware")
            .setMessage("Rechecking signing status and Apple's download payload…")
            .setCancelable(false)
            .create()
        checking.show()

        worker.execute {
            val verified = runCatching {
                FirmwareCatalog().reverifySigned(entry.identifier, choice.buildId)
            }.getOrNull()

            runOnActivity(context) {
                checking.dismiss()
                if (verified == null) {
                    AlertDialog.Builder(context)
                        .setTitle("Firmware unavailable")
                        .setMessage("That build is no longer signed or its Apple CDN payload could not be verified.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnActivity
                }
                prepareDownload(context, entry, verified)
            }
        }
    }

    private fun prepareDownload(
        context: Context,
        entry: ConnectedDeviceHistory.Entry,
        firmware: FirmwareCatalog.Firmware
    ) {
        val storage = FirmwareStorage(context)
        if (!storage.hasSharedStorageAccess()) {
            AlertDialog.Builder(context)
                .setTitle("Storage access required")
                .setMessage("iDeviceRestore needs All files access to save firmware in its shared firmware folder.")
                .setPositiveButton("Open settings") { _, _ -> openStorageSettings(context) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val location = runCatching { storage.locationFor(firmware) }.getOrElse { error ->
            AlertDialog.Builder(context)
                .setTitle("Can't prepare download")
                .setMessage(error.message ?: error.javaClass.simpleName)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val existingBytes = runCatching { storage.partialBytes(firmware) }.getOrDefault(0L)
        val remaining = if (firmware.fileSize > 0L) {
            (firmware.fileSize - existingBytes).coerceAtLeast(0L)
        } else {
            -1L
        }
        if (remaining > 0L && !storage.hasEnoughSpace(entry.identifier, remaining)) {
            AlertDialog.Builder(context)
                .setTitle("Not enough storage")
                .setMessage("There isn't enough free space for the remaining firmware download.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Download ${firmware.version}?")
            .setMessage(
                "${entry.name} (${entry.identifier})\n" +
                    "Build ${firmware.buildId}\n" +
                    "${FirmwareDownloadService.formatBytes(firmware.fileSize)}\n\n" +
                    "The IPSW will be downloaded from Apple's CDN."
            )
            .setPositiveButton(if (existingBytes > 0L) "Resume download" else "Download") { _, _ ->
                startDownload(context, firmware, location.file.absolutePath)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(context: Context, firmware: FirmwareCatalog.Firmware, destination: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidUiBridge.activity(context)?.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }

        val intent = Intent(context, FirmwareDownloadService::class.java)
            .setAction(FirmwareDownloadService.ACTION_START)
            .putExtra(FirmwareDownloadService.EXTRA_URL, firmware.url)
            .putExtra(FirmwareDownloadService.EXTRA_DESTINATION, destination)
            .putExtra(FirmwareDownloadService.EXTRA_EXPECTED_SIZE, firmware.fileSize)
            .putExtra(FirmwareDownloadService.EXTRA_SHA1, firmware.sha1)
            .putExtra(FirmwareDownloadService.EXTRA_VERSION, firmware.version)
            .putExtra(FirmwareDownloadService.EXTRA_BUILD_ID, firmware.buildId)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun openStorageSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        runCatching { context.startActivity(intent) }
    }

    private fun runOnActivity(context: Context, action: () -> Unit) {
        val activity = AndroidUiBridge.activity(context)
        if (activity != null) activity.runOnUiThread(action)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
