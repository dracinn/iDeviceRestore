package com.idevicerestore.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.idevicerestore.android.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var selected: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val logBuffer = StringBuilder()

    private val permissionAction by lazy { "${packageName}.USB_PERMISSION" }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("USB permission ${if (granted) "granted" else "denied"}: ${device?.deviceName}")
                    if (granted && device != null) {
                        selected = device
                        showSelected(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("USB device attached")
                    scan()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("USB device detached")
                    scan()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        binding.scanButton.setOnClickListener { scan() }
        binding.probeButton.setOnClickListener { probeSelected() }
        binding.shareLogsButton.setOnClickListener { shareLogs() }

        log("iDeviceRestore diagnostic session started")
        log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} ${Build.MODEL}")
        scan()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun scan() {
        val apple = usbManager.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID }
        log("Scan: ${apple.size} Apple USB device(s)")
        apple.forEach { device ->
            log(AppleUsb.describe(device))
            log(AppleUsb.interfaceSummary(device))
        }
        val preferred = apple.firstOrNull { AppleUsb.mode(it) != AppleUsb.Mode.APPLE_OTHER } ?: apple.firstOrNull()
        selected = preferred
        if (preferred == null) {
            binding.status.text = "No Apple USB device found"
            binding.probeButton.isEnabled = false
            return
        }
        showSelected(preferred)
        if (!usbManager.hasPermission(preferred)) requestPermission(preferred)
    }

    private fun showSelected(device: UsbDevice) {
        binding.status.text = AppleUsb.describe(device)
        binding.probeButton.isEnabled = usbManager.hasPermission(device)
        log("Selected: ${AppleUsb.describe(device)}")
        log("USB permission present: ${usbManager.hasPermission(device)}")
    }

    private fun requestPermission(device: UsbDevice) {
        log("Requesting USB permission for ${device.deviceName}")
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun probeSelected() {
        val device = selected ?: return
        log("Probe requested: mode=${AppleUsb.mode(device)} VID=%04x PID=%04x".format(device.vendorId, device.productId))
        worker.execute {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                logUi("openDevice failed")
                return@execute
            }
            logUi("openDevice succeeded")
            try {
                val claimed = AppleUsb.claimBestInterface(device, connection)
                if (claimed == null) {
                    logUi("Could not claim a USB interface")
                    return@execute
                }
                logUi("Claimed interface ${claimed.intf.id}; class=${claimed.intf.interfaceClass} subclass=${claimed.intf.interfaceSubclass} protocol=${claimed.intf.interfaceProtocol}")
                logUi("Claimed bulk IN: ${claimed.bulkIn?.let { "0x%02x maxPacket=${it.maxPacketSize}".format(it.address) } ?: "none"}")
                when (AppleUsb.mode(device)) {
                    AppleUsb.Mode.DFU -> {
                        logUi("Sending non-destructive DFU_GETSTATUS")
                        runCatching { DfuTransport(connection).getStatus() }
                            .onSuccess { s -> logUi("DFU status=${s.status}, state=${s.state}, poll=${s.pollTimeoutMs}ms, iString=${s.iString}") }
                            .onFailure { logUi("DFU probe failed: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    AppleUsb.Mode.RECOVERY, AppleUsb.Mode.WTF -> {
                        logUi("Sending recovery command: getenv build-version")
                        val recovery = RecoveryTransport(connection, claimed.bulkIn)
                        val sent = recovery.sendCommand("getenv build-version")
                        logUi("Recovery command write: $sent bytes")
                        logUi("Response: ${recovery.readConsole()}")
                    }
                    AppleUsb.Mode.APPLE_OTHER -> logUi("Apple device is not classified as DFU/recovery; no command sent.")
                }
            } catch (t: Throwable) {
                logUi("Probe exception: ${t.javaClass.name}: ${t.message}")
                logUi(t.stackTraceToString())
            } finally {
                connection.close()
                logUi("USB connection closed")
            }
        }
    }

    private fun shareLogs() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("iDeviceRestore verbose diagnostic log")
            appendLine("Generated: $timestamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            appendLine("Host device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("---")
            append(logBuffer.toString())
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "iDeviceRestore diagnostic log")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(share, "Share verbose logs"))
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun log(message: String) {
        val clean = message.trimEnd()
        logBuffer.append(clean).append('\n')
        binding.logView.append(clean + "\n")
    }

    private fun logUi(message: String) = runOnUiThread { log(message) }
}
