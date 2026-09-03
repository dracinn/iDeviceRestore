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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var selected: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()

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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scan()
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
        apple.forEach { log(AppleUsb.describe(it)) }
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
        log(AppleUsb.interfaceSummary(device))
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun probeSelected() {
        val device = selected ?: return
        worker.execute {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                logUi("openDevice failed")
                return@execute
            }
            try {
                val claimed = AppleUsb.claimBestInterface(device, connection)
                if (claimed == null) {
                    logUi("Could not claim a USB interface")
                    return@execute
                }
                logUi("Claimed interface ${claimed.intf.id}")
                when (AppleUsb.mode(device)) {
                    AppleUsb.Mode.DFU -> {
                        runCatching { DfuTransport(connection).getStatus() }
                            .onSuccess { s -> logUi("DFU status=${s.status}, state=${s.state}, poll=${s.pollTimeoutMs}ms, iString=${s.iString}") }
                            .onFailure { logUi("DFU probe failed: ${it.message}") }
                    }
                    AppleUsb.Mode.RECOVERY, AppleUsb.Mode.WTF -> {
                        val recovery = RecoveryTransport(connection, claimed.bulkIn)
                        val sent = recovery.sendCommand("getenv build-version")
                        logUi("Recovery command write: $sent bytes")
                        logUi("Response: ${recovery.readConsole()}")
                    }
                    AppleUsb.Mode.APPLE_OTHER -> logUi("Apple device is not classified as DFU/recovery; no command sent.")
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun log(message: String) {
        binding.logView.append(message.trimEnd() + "\n")
    }

    private fun logUi(message: String) = runOnUiThread { log(message) }
}
