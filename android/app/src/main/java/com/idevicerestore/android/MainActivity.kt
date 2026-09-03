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
    private val firmwareCatalog by lazy { FirmwareCatalog(logger = { message -> logUi(message) }) }

    @Volatile
    private var probeInFlight = false
    private var lastAutoProbeDeviceName: String? = null
    private var identifiedDevice: FirmwareCatalog.Device? = null
    private var latestSignedFirmware: FirmwareCatalog.Firmware? = null

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
                        log(AppleUsb.bootIdentifierSummary(device))
                        maybeAutoProbe(device, "permission granted")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    log("USB device attached: ${device?.deviceName ?: "unknown"}")
                    scan()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    log("USB device detached: ${device?.deviceName ?: "unknown"}")
                    if (device != null && device.deviceName == lastAutoProbeDeviceName) {
                        lastAutoProbeDeviceName = null
                        identifiedDevice = null
                        latestSignedFirmware = null
                    }
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
        binding.probeButton.setOnClickListener { probeSelected(manual = true) }
        binding.shareLogsButton.setOnClickListener { shareLogs() }

        log("iDeviceRestore diagnostic session started")
        log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} ${Build.MODEL}")
        log("Automatic DFU / Recovery probing: enabled")
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
            if (usbManager.hasPermission(device)) log(AppleUsb.bootIdentifierSummary(device))
        }
        val preferred = apple.firstOrNull { AppleUsb.mode(it) != AppleUsb.Mode.APPLE_OTHER } ?: apple.firstOrNull()
        selected = preferred
        if (preferred == null) {
            lastAutoProbeDeviceName = null
            identifiedDevice = null
            latestSignedFirmware = null
            binding.status.text = "No Apple USB device found"
            binding.probeButton.isEnabled = false
            return
        }
        showSelected(preferred)
        if (!usbManager.hasPermission(preferred)) {
            requestPermission(preferred)
        } else {
            maybeAutoProbe(preferred, "device discovered")
        }
    }

    private fun showSelected(device: UsbDevice) {
        binding.status.text = AppleUsb.describe(device)
        binding.probeButton.isEnabled = usbManager.hasPermission(device) && !probeInFlight
        log("Selected: ${AppleUsb.describe(device)}")
        log("USB permission present: ${usbManager.hasPermission(device)}")
    }

    private fun requestPermission(device: UsbDevice) {
        log("Requesting USB permission for ${device.deviceName}")
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun maybeAutoProbe(device: UsbDevice, reason: String) {
        if (!usbManager.hasPermission(device)) return
        if (AppleUsb.mode(device) == AppleUsb.Mode.APPLE_OTHER) {
            log("Automatic probe skipped: unsupported Apple USB mode")
            return
        }
        if (probeInFlight) {
            log("Automatic probe skipped: probe already in progress")
            return
        }
        if (lastAutoProbeDeviceName == device.deviceName) {
            log("Automatic probe skipped: device already probed this connection")
            return
        }

        lastAutoProbeDeviceName = device.deviceName
        selected = device
        log("Automatic probe starting ($reason)")
        probeSelected(manual = false)
    }

    private fun probeSelected(manual: Boolean = false) {
        val device = selected ?: return
        if (!usbManager.hasPermission(device)) {
            log("Probe skipped: USB permission is not available")
            requestPermission(device)
            return
        }
        if (probeInFlight) {
            log("Probe skipped: another probe is already in progress")
            return
        }

        probeInFlight = true
        binding.probeButton.isEnabled = false
        val source = if (manual) "manual" else "automatic"
        log("Probe requested ($source): mode=${AppleUsb.mode(device)} VID=%04x PID=%04x".format(device.vendorId, device.productId))
        worker.execute {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                logUi("openDevice failed")
                identifyDeviceAndFirmware(device)
                finishProbe(device)
                return@execute
            }
            logUi("openDevice succeeded")
            logUi(AppleUsb.bootIdentifierSummary(device))
            try {
                val claimed = AppleUsb.claimBestInterface(device, connection)
                if (claimed == null) {
                    logUi("Could not claim a USB interface")
                    return@execute
                }
                logUi(
                    "Claimed interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting}; " +
                        "class=${claimed.intf.interfaceClass} subclass=${claimed.intf.interfaceSubclass} " +
                        "protocol=${claimed.intf.interfaceProtocol}"
                )
                logUi("Claimed bulk IN: ${claimed.bulkIn?.let { "0x%02x maxPacket=${it.maxPacketSize}".format(it.address) } ?: "none"}")
                logUi("Claimed bulk OUT: ${claimed.bulkOut?.let { "0x%02x maxPacket=${it.maxPacketSize}".format(it.address) } ?: "none"}")

                when (AppleUsb.mode(device)) {
                    AppleUsb.Mode.DFU -> {
                        logUi("Sending non-destructive DFU_GETSTATUS")
                        runCatching { DfuTransport(connection).getStatus() }
                            .onSuccess { s -> logUi("DFU status=${s.status}, state=${s.state}, poll=${s.pollTimeoutMs}ms, iString=${s.iString}") }
                            .onFailure { logUi("DFU probe failed: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    AppleUsb.Mode.RECOVERY, AppleUsb.Mode.WTF -> {
                        val recovery = RecoveryTransport(connection, claimed.bulkIn)
                        listOf("build-version", "build-style", "auto-boot").forEach { variable ->
                            logUi("Recovery getenv probe: $variable")
                            runCatching { recovery.getenv(variable) }
                                .onSuccess { result ->
                                    logUi("$variable control-OUT=${result.commandBytes} control-IN=${result.responseBytes} value=${result.value.ifEmpty { "(empty)" }}")
                                }
                                .onFailure { error ->
                                    logUi("$variable failed: ${error.javaClass.simpleName}: ${error.message}")
                                }
                        }
                    }
                    AppleUsb.Mode.APPLE_OTHER -> logUi("Apple device is not classified as DFU/recovery; no command sent.")
                }
            } catch (t: Throwable) {
                logUi("Probe exception: ${t.javaClass.name}: ${t.message}")
                logUi(t.stackTraceToString())
            } finally {
                connection.close()
                logUi("USB connection closed")
                identifyDeviceAndFirmware(device)
                finishProbe(device)
            }
        }
    }

    private fun identifyDeviceAndFirmware(device: UsbDevice) {
        val ids = AppleUsb.bootIdentifiers(device)
        val cpid = ids?.cpid
        val bdid = ids?.bdid
        if (cpid == null || bdid == null) {
            logUi("Device identification skipped: CPID/BDID unavailable")
            return
        }

        logUi("Device identification: querying catalog from CPID/BDID")
        runCatching { firmwareCatalog.findDeviceByBootIds(cpid, bdid) }
            .onFailure { error ->
                logUi("Device identification failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            .onSuccess { catalogDevice ->
                if (catalogDevice == null) {
                    logUi("Device identification: no catalog match for CPID=0x%04X BDID=0x%02X".format(cpid, bdid))
                    return@onSuccess
                }

                identifiedDevice = catalogDevice
                logUi(
                    "Identified device: ${catalogDevice.name} (${catalogDevice.identifier})" +
                        (catalogDevice.boardConfig?.let { " board=$it" } ?: "") +
                        (catalogDevice.platform?.let { " platform=$it" } ?: "")
                )
                runOnUiThread {
                    if (selected?.deviceName == device.deviceName) {
                        binding.status.text = "${catalogDevice.name} (${catalogDevice.identifier}) — ${AppleUsb.mode(device)}"
                    }
                }

                logUi("Firmware catalog: checking latest signed IPSW for ${catalogDevice.identifier}")
                runCatching { firmwareCatalog.latestSigned(catalogDevice.identifier) }
                    .onFailure { error ->
                        logUi("Signed firmware lookup failed: ${error.javaClass.simpleName}: ${error.message}")
                    }
                    .onSuccess { firmware ->
                        latestSignedFirmware = firmware
                        if (firmware == null) {
                            logUi("Latest signed firmware: none reported")
                        } else {
                            val size = if (firmware.fileSize >= 0) " size=${firmware.fileSize} bytes" else ""
                            logUi("Latest signed firmware: ${firmware.version} (${firmware.buildId})$size")
                            logUi("Latest signed firmware URL: ${firmware.url}")
                        }
                    }
            }
    }

    private fun finishProbe(device: UsbDevice) = runOnUiThread {
        probeInFlight = false
        binding.probeButton.isEnabled = selected?.deviceName == device.deviceName && usbManager.hasPermission(device)
        log("Probe finished")
    }

    private fun shareLogs() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("iDeviceRestore verbose diagnostic log")
            appendLine("Generated: $timestamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            appendLine("Host device: ${Build.MANUFACTURER} ${Build.MODEL}")
            identifiedDevice?.let { appendLine("Identified device: ${it.name} (${it.identifier})") }
            latestSignedFirmware?.let { appendLine("Latest signed firmware: ${it.version} (${it.buildId})") }
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
