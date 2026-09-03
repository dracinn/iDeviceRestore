package com.idevicerestore.android

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.idevicerestore.android.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private var selected: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val logBuffer = StringBuilder()
    private val probeLogBuffer = StringBuilder()
    private val firmwareCatalog by lazy { FirmwareCatalog(logger = { message -> logUi(message) }) }
    private val betaFirmwareCatalog by lazy { BetaFirmwareCatalog(logger = { message -> logUi(message) }) }
    private val firmwareStorage by lazy { FirmwareStorage(this, logger = { message -> logUi(message) }) }
    private val firmwareMetadataCache by lazy { FirmwareMetadataCache(logger = { message -> logUi(message) }) }
    private val appSettings by lazy { AppSettings(this) }

    @Volatile
    private var probeInFlight = false
    @Volatile
    private var probeLogging = false
    @Volatile
    private var firmwareSelectionInFlight = false
    private var lastAutoProbeDeviceName: String? = null
    private var identifiedDevice: FirmwareCatalog.Device? = null
    private var latestSignedFirmware: FirmwareCatalog.Firmware? = null
    private var firmwareWorkspace: FirmwareStorage.Workspace? = null
    private var firmwareDestination: File? = null
    private var sharedStorageSettingsOpened = false
    private var sharedStorageAccessLogged = false
    private var lastIncludeBetaSetting = false
    private var firmwareDownloadActive = false

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
                        clearFirmwareSelection("device detached", logChange = false)
                        firmwareWorkspace = null
                        updateFirmwareUi()
                    }
                    scan()
                }
                FirmwareDownloadService.ACTION_STATE -> handleFirmwareDownloadState(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        lastIncludeBetaSetting = appSettings.includeBetaFirmware

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(FirmwareDownloadService.ACTION_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        binding.scanButton.setOnClickListener { scan() }
        binding.probeButton.setOnClickListener { probeSelected(manual = true) }
        binding.selectFirmwareButton.setOnClickListener { selectFirmwareManually() }
        binding.downloadFirmwareButton.setOnClickListener { startFirmwareDownload() }
        binding.cancelDownloadButton.setOnClickListener { cancelFirmwareDownload() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.shareLogsButton.setOnClickListener { shareLogs() }

        log("iDeviceRestore diagnostic session started")
        log("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        log("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} ${Build.MODEL}")
        log("Automatic DFU / Recovery probing: enabled")
        log("Firmware selection: manual catalog choice required each app session")
        log("Firmware metadata: persistent cache with current-index refresh")
        log("Firmware activation: fresh signing and Apple CDN verification required")
        log("Automatic pseudo-release pipeline: enabled")
        log("Shared diagnostic privacy redaction: enabled")
        log("Beta/RC firmware lookup: ${if (appSettings.includeBetaFirmware) "enabled" else "disabled"}")
        log("Firmware project root: ${firmwareStorage.projectRoot.absolutePath}")
        log("Firmware downloads: Apple CDN only; resumable single-stream mode")
        ensureSharedStorageAccess(openSettings = true)
        updateFirmwareUi()
        scan()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

        val includeBeta = appSettings.includeBetaFirmware
        if (includeBeta != lastIncludeBetaSetting) {
            lastIncludeBetaSetting = includeBeta
            log("Beta/RC firmware lookup changed: ${if (includeBeta) "enabled" else "disabled"}")
            clearFirmwareSelection("firmware channel preference changed")
            updateFirmwareUi()
        }

        val granted = ensureSharedStorageAccess(openSettings = !sharedStorageSettingsOpened)
        if (granted && sharedStorageSettingsOpened) {
            sharedStorageSettingsOpened = false
            identifiedDevice?.let { device ->
                runCatching { firmwareStorage.prepare(device.identifier) }
                    .onSuccess { workspace ->
                        firmwareWorkspace = workspace
                        updateFirmwareUi()
                    }
                    .onFailure { error -> log("Firmware storage setup failed after permission grant: ${error.message}") }
            }
        } else if (granted) {
            updateFirmwareUi()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun ensureSharedStorageAccess(openSettings: Boolean): Boolean {
        if (firmwareStorage.hasSharedStorageAccess()) {
            if (!sharedStorageAccessLogged) {
                sharedStorageAccessLogged = true
                log("Shared storage access: granted")
                log("Using existing iDeviceRestore folder: ${firmwareStorage.projectRoot.absolutePath}")
            }
            return true
        }

        sharedStorageAccessLogged = false
        log("Shared storage access: required for ${firmwareStorage.projectRoot.absolutePath}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("Shared storage permission must be granted in Android app permissions")
            return false
        }
        if (!openSettings) return false

        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        val launched = runCatching {
            startActivity(appIntent)
            true
        }.getOrElse {
            runCatching {
                startActivity(fallbackIntent)
                true
            }.getOrDefault(false)
        }
        if (launched) {
            sharedStorageSettingsOpened = true
            log("Opened Android 'All files access' settings for iDeviceRestore")
        } else {
            log("Could not open Android 'All files access' settings")
        }
        return false
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
            clearFirmwareSelection("no Apple USB device", logChange = false)
            firmwareWorkspace = null
            binding.status.text = if (firmwareStorage.hasSharedStorageAccess()) {
                "No Apple USB device found"
            } else {
                "Storage access required — enable All files access"
            }
            binding.probeButton.isEnabled = false
            updateFirmwareUi()
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
        probeLogging = true
        binding.probeButton.isEnabled = false
        val source = if (manual) "manual" else "automatic"
        probeLog("Probe requested ($source): mode=${AppleUsb.mode(device)} VID=%04x PID=%04x".format(device.vendorId, device.productId))
        worker.execute {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                logUi("openDevice failed")
                probeLogging = false
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
                        val dfu = DfuTransport(connection)
                        logUi("DFU diagnostic: sending read-only DFU_GETSTATUS + DFU_GETSTATE")
                        runCatching { dfu.getStatus() }
                            .onSuccess { s ->
                                logUi(
                                    "DFU status=${s.status}(${s.statusName}) " +
                                        "GETSTATE=${s.state}(${s.stateName}) " +
                                        "GETSTATUS.state=${s.statusState}(${s.statusStateName}) " +
                                        "consistent=${s.stateConsistent} poll=${s.pollTimeoutMs}ms iString=${s.iString}"
                                )
                            }
                            .onFailure { logUi("DFU state/status probe failed: ${it.javaClass.simpleName}: ${it.message}") }

                        logUi("DFU diagnostic: requesting read-only functional descriptor")
                        runCatching { dfu.getFunctionalDescriptor(claimed.intf.id) }
                            .onSuccess { descriptor ->
                                logUi(
                                    "DFU functional descriptor: version=${descriptor.versionText} " +
                                        "transferSize=${descriptor.transferSize} detachTimeout=${descriptor.detachTimeoutMs}ms " +
                                        "canDownload=${descriptor.canDownload} canUpload=${descriptor.canUpload} " +
                                        "manifestationTolerant=${descriptor.manifestationTolerant} willDetach=${descriptor.willDetach}"
                                )
                            }
                            .onFailure {
                                logUi("DFU functional descriptor unavailable: ${it.javaClass.simpleName}: ${it.message}")
                            }
                        logUi("DFU diagnostic complete: no state-changing DFU request sent")
                    }
                    AppleUsb.Mode.RECOVERY, AppleUsb.Mode.WTF -> {
                        val recovery = RecoveryTransport(connection, claimed.bulkIn)
                        val snapshot = RecoveryDiagnosticSession(device, connection, recovery).snapshot()
                        snapshot.variables.forEach { variable ->
                            logUi("Recovery getenv probe: ${variable.name}")
                            val result = variable.result
                            if (result != null) {
                                logUi(
                                    "${variable.name} control-OUT=${result.commandBytes} " +
                                        "control-IN=${result.responseBytes} value=${result.value.ifEmpty { "(empty)" }}"
                                )
                            } else {
                                val error = variable.error
                                logUi("${variable.name} failed: ${error?.javaClass?.simpleName ?: "unknown"}: ${error?.message ?: "unknown error"}")
                            }
                        }

                        if (AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) {
                            val console = snapshot.console
                            when {
                                console != null -> {
                                    logUi(
                                        "Recovery console: claimed interface id=${console.interfaceId} " +
                                            "alt=${console.alternateSetting} bulk-IN=0x%02x".format(console.endpointAddress)
                                    )
                                    logUi("Recovery console: read ${console.bytes} byte(s) from bulk-IN")
                                    if (console.text.isNotEmpty()) {
                                        logUi("Recovery console data:\n${console.text}")
                                    } else {
                                        logUi("Recovery console: no pending data within read timeout")
                                    }
                                }
                                snapshot.consoleError != null -> {
                                    val error = snapshot.consoleError
                                    logUi("Recovery console failed: ${error.javaClass.simpleName}: ${error.message}")
                                }
                                else -> logUi("Recovery console: secondary bulk-IN interface could not be claimed")
                            }
                        } else {
                            logUi("Recovery console: skipped in WTF mode")
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
                logUi("Probe finished")
                probeLogging = false
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

                val previousIdentifier = identifiedDevice?.identifier
                identifiedDevice = catalogDevice
                if (previousIdentifier != null && previousIdentifier != catalogDevice.identifier) {
                    clearFirmwareSelection("identified device changed")
                }
                logUi(
                    "Identified device: ${catalogDevice.name} (${catalogDevice.identifier})" +
                        (catalogDevice.boardConfig?.let { " board=$it" } ?: "") +
                        (catalogDevice.platform?.let { " platform=$it" } ?: "")
                )

                if (!firmwareStorage.hasSharedStorageAccess()) {
                    logUi("Firmware storage unavailable until All files access is granted")
                    runOnUiThread { ensureSharedStorageAccess(openSettings = true) }
                } else {
                    runCatching { firmwareStorage.prepare(catalogDevice.identifier) }
                        .onFailure { error ->
                            logUi("Firmware storage setup failed: ${error.javaClass.simpleName}: ${error.message}")
                        }
                        .onSuccess { workspace ->
                            firmwareWorkspace = workspace
                            logUi("Firmware storage root: ${workspace.root.absolutePath}")
                            logUi("Device firmware directory: ${workspace.firmware.absolutePath}")
                            val cacheFile = firmwareStorage.catalogCacheFor(catalogDevice.identifier)
                            val cachedCount = firmwareMetadataCache.load(cacheFile).size
                            logUi("Firmware metadata cache: $cachedCount build(s) at ${cacheFile.absolutePath}")
                        }
                }

                runOnUiThread {
                    if (selected?.deviceName == device.deviceName) {
                        binding.status.text = "${catalogDevice.name} (${catalogDevice.identifier}) — ${AppleUsb.mode(device)}"
                    }
                    updateFirmwareUi()
                }
                logUi("Firmware selection waiting for user: tap Select signed firmware")
            }
    }

    private fun selectFirmwareManually() {
        val device = identifiedDevice
        if (device == null) {
            log("Firmware selection unavailable: identify a connected device first")
            updateFirmwareUi()
            return
        }
        if (firmwareSelectionInFlight) {
            log("Firmware selection already in progress")
            return
        }

        firmwareSelectionInFlight = true
        binding.selectFirmwareButton.isEnabled = false
        binding.firmwareStatus.text = "Refreshing firmware index…"
        log("Firmware catalog chooser requested by user for ${device.identifier}")
        worker.execute {
            val choices = loadFirmwareChoices(device.identifier)
            firmwareSelectionInFlight = false
            runOnUiThread {
                updateFirmwareUi()
                if (identifiedDevice?.identifier != device.identifier) {
                    log("Firmware chooser discarded: connected device changed")
                } else if (choices.isEmpty()) {
                    log("Firmware catalog chooser: no currently signed firmware entries available")
                    binding.firmwareStatus.text = "No signed firmware reported"
                } else {
                    showFirmwareChooser(device.identifier, choices)
                }
            }
        }
    }

    private fun loadFirmwareChoices(identifier: String): List<FirmwareSelectionCandidate> {
        val cacheFile = runCatching { firmwareStorage.catalogCacheFor(identifier) }
            .onFailure { logUi("Firmware metadata cache unavailable: ${it.message}") }
            .getOrNull()
        val cached = cacheFile?.let { firmwareMetadataCache.load(it) }.orEmpty()
        logUi("Firmware metadata cache: loaded ${cached.size} known build(s)")
        logUi("Firmware catalog refresh: probing current indexes for newly published/signing changes")

        val currentEntries = mutableListOf<FirmwareMetadataCache.Entry>()
        val refreshedChannels = mutableSetOf(FirmwareSelectionCandidate.Channel.STABLE)

        val stable = runCatching { firmwareCatalog.firmwares(identifier, signedOnly = true) }
            .onFailure { error ->
                logUi("Signed stable firmware index refresh failed: ${error.javaClass.simpleName}: ${error.message}")
            }
            .getOrDefault(emptyList())
        stable.forEach { firmware ->
            currentEntries += FirmwareMetadataCache.Entry(
                channel = FirmwareSelectionCandidate.Channel.STABLE,
                version = firmware.version,
                buildId = firmware.buildId,
                releaseDate = firmware.releaseDate,
                fileSize = firmware.fileSize,
                url = firmware.url,
                sha1 = firmware.sha1,
                currentlySigned = true,
                verifiedAt = null
            )
        }
        logUi("Firmware catalog refresh: ${stable.size} stable build(s) currently signed")

        if (appSettings.includeBetaFirmware) {
            refreshedChannels += FirmwareSelectionCandidate.Channel.BETA
            refreshedChannels += FirmwareSelectionCandidate.Channel.RC
            val betaCandidates = runCatching { betaFirmwareCatalog.signedCandidates(identifier) }
                .onFailure { error ->
                    logUi("Beta/RC firmware index refresh failed: ${error.javaClass.simpleName}: ${error.message}")
                }
                .getOrDefault(emptyList())
            betaCandidates.forEach { candidate ->
                val channel = if (candidate.version.contains("RC", ignoreCase = true)) {
                    FirmwareSelectionCandidate.Channel.RC
                } else {
                    FirmwareSelectionCandidate.Channel.BETA
                }
                currentEntries += FirmwareMetadataCache.Entry(
                    channel = channel,
                    version = candidate.version,
                    buildId = candidate.buildId,
                    releaseDate = candidate.releaseDate,
                    fileSize = candidate.fileSize,
                    url = null,
                    sha1 = null,
                    currentlySigned = true,
                    verifiedAt = null
                )
            }
            logUi("Firmware catalog refresh: ${betaCandidates.size} beta/RC build(s) currently signed")
        }

        val merged = if (cacheFile != null) {
            firmwareMetadataCache.mergeCurrent(cacheFile, currentEntries, refreshedChannels)
        } else {
            FirmwareMetadataCache.MergeResult(currentEntries, currentEntries)
        }
        if (merged.newBuilds.isEmpty()) {
            logUi("Firmware catalog refresh: no new firmware builds discovered")
        } else {
            merged.newBuilds.forEach { entry ->
                logUi("Firmware catalog NEW: ${entry.channel} ${entry.version} (${entry.buildId})")
            }
        }

        return merged.entries
            .filter { entry ->
                entry.currentlySigned &&
                    (entry.channel == FirmwareSelectionCandidate.Channel.STABLE || appSettings.includeBetaFirmware)
            }
            .map { entry ->
                FirmwareSelectionCandidate(
                    channel = entry.channel,
                    version = entry.version,
                    buildId = entry.buildId,
                    releaseDate = entry.releaseDate,
                    fileSize = entry.fileSize
                )
            }
            .distinctBy { "${it.channel}:${it.buildId}" }
            .sortedWith(
                compareByDescending<FirmwareSelectionCandidate> { it.releaseDate ?: Instant.EPOCH }
                    .thenByDescending { it.version }
                    .thenByDescending { it.buildId }
            )
    }

    private fun showFirmwareChooser(identifier: String, choices: List<FirmwareSelectionCandidate>) {
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select signed firmware")
            .setItems(labels) { _, index ->
                resolveFirmwareChoice(identifier, choices[index])
            }
            .setNegativeButton("Cancel") { _, _ ->
                log("Firmware catalog chooser cancelled")
            }
            .show()
        log("Firmware catalog chooser: showing ${choices.size} currently signed build(s)")
    }

    private fun resolveFirmwareChoice(identifier: String, choice: FirmwareSelectionCandidate) {
        if (firmwareSelectionInFlight) return
        firmwareSelectionInFlight = true
        binding.selectFirmwareButton.isEnabled = false
        binding.firmwareStatus.text = "Reverifying ${choice.version} (${choice.buildId})…"
        log("Firmware chosen by user: ${choice.channel} ${choice.version} (${choice.buildId})")
        log("Firmware verification gate: cached signing/URL/size metadata will not be trusted")

        worker.execute {
            val firmware = when (choice.channel) {
                FirmwareSelectionCandidate.Channel.STABLE -> runCatching {
                    firmwareCatalog.reverifySigned(identifier, choice.buildId)
                }.onFailure { error ->
                    logUi("Selected stable firmware reverify failed: ${error.javaClass.simpleName}: ${error.message}")
                }.getOrNull()

                FirmwareSelectionCandidate.Channel.BETA,
                FirmwareSelectionCandidate.Channel.RC -> runCatching {
                    betaFirmwareCatalog.reverifySigned(identifier, choice.buildId)
                }.onFailure { error ->
                    logUi("Selected beta/RC firmware reverify failed: ${error.javaClass.simpleName}: ${error.message}")
                }.getOrNull()
            }

            if (firmware == null) {
                clearFirmwareSelection("fresh firmware verification failed")
                logUi("Firmware selection BLOCKED: build is no longer signed or Apple payload verification failed")
            } else if (identifiedDevice?.identifier != identifier) {
                logUi("Firmware selection BLOCKED: connected device changed during verification")
            } else {
                runCatching {
                    val cacheFile = firmwareStorage.catalogCacheFor(identifier)
                    firmwareMetadataCache.recordVerification(cacheFile, firmware, choice.channel)
                }.onFailure { error ->
                    logUi("Firmware metadata verification cache update failed: ${error.message}")
                }
                logUi("Firmware verification PASSED: current signing state and Apple CDN payload confirmed")
                applyFirmwareSelection(firmware, choice.channel)
            }

            firmwareSelectionInFlight = false
            runOnUiThread { updateFirmwareUi() }
        }
    }

    private fun applyFirmwareSelection(
        firmware: FirmwareCatalog.Firmware,
        channel: FirmwareSelectionCandidate.Channel
    ) {
        latestSignedFirmware = firmware
        logUi("Selected signed firmware (${channel.name.lowercase(Locale.US)}): ${firmware.version} (${firmware.buildId})")
        logUi("Selected signed firmware URL: ${firmware.url}")

        if (firmwareStorage.hasSharedStorageAccess()) {
            runCatching { firmwareStorage.locationFor(firmware) }
                .onSuccess { location ->
                    firmwareWorkspace = location.workspace
                    firmwareDestination = location.file
                    logUi("Firmware download destination: ${location.file.absolutePath}")
                    val present = location.file.isFile
                    if (present) {
                        logUi("Firmware file found: ${location.file.length()} bytes")
                        if (firmware.fileSize > 0L && location.file.length() == firmware.fileSize) {
                            logUi("Firmware file status: ready (fresh Apple size match)")
                        } else if (firmware.fileSize > 0L) {
                            logUi("Firmware file status: size mismatch expected=${firmware.fileSize} actual=${location.file.length()}")
                        }
                    } else {
                        val partial = firmwareStorage.partialBytes(firmware)
                        if (partial > 0L) logUi("Firmware partial download found: $partial bytes; resume available")
                    }
                }
                .onFailure { error ->
                    firmwareDestination = null
                    logUi("Firmware destination setup failed: ${error.javaClass.simpleName}: ${error.message}")
                }
        } else {
            firmwareDestination = null
            logUi("Firmware destination pending shared storage permission")
        }
    }

    private fun clearFirmwareSelection(reason: String, logChange: Boolean = true) {
        val hadSelection = latestSignedFirmware != null || firmwareDestination != null
        latestSignedFirmware = null
        firmwareDestination = null
        if (logChange && hadSelection) logUi("Firmware selection cleared: $reason")
    }

    private fun updateFirmwareUi() {
        val firmware = latestSignedFirmware
        val destination = firmwareDestination
        binding.selectFirmwareButton.isEnabled = identifiedDevice != null && !firmwareSelectionInFlight && !firmwareDownloadActive

        if (firmware == null || destination == null) {
            binding.firmwareTitle.text = firmware?.let { "Firmware ${it.version} (${it.buildId})" } ?: "Firmware"
            binding.firmwareStatus.text = when {
                firmwareSelectionInFlight -> "Refreshing catalog or reverifying firmware…"
                firmware != null -> "Firmware selected — storage destination unavailable"
                identifiedDevice != null -> "Device identified — tap Select signed firmware"
                else -> "Waiting for device identification"
            }
            binding.firmwareProgress.progress = 0
            binding.firmwareProgressText.text = ""
            binding.downloadFirmwareButton.isEnabled = false
            binding.cancelDownloadButton.isEnabled = firmwareDownloadActive
            return
        }

        binding.firmwareTitle.text = "Firmware ${firmware.version} (${firmware.buildId})"
        val expected = firmware.fileSize
        val actual = destination.takeIf { it.isFile }?.length() ?: 0L
        val complete = destination.isFile && (expected <= 0L || actual == expected)
        val partial = if (!complete && firmwareStorage.hasSharedStorageAccess()) {
            runCatching { firmwareStorage.partialBytes(firmware) }.getOrDefault(0L)
        } else 0L

        when {
            firmwareDownloadActive -> {
                binding.firmwareStatus.text = "Downloading from Apple CDN"
                binding.selectFirmwareButton.isEnabled = false
                binding.downloadFirmwareButton.isEnabled = false
                binding.cancelDownloadButton.isEnabled = true
            }
            complete -> {
                binding.firmwareStatus.text = "Ready — selected firmware freshly verified"
                binding.firmwareProgress.progress = 1000
                binding.firmwareProgressText.text = "${FirmwareDownloadService.formatBytes(actual)} verified by fresh Apple size"
                binding.downloadFirmwareButton.isEnabled = false
                binding.cancelDownloadButton.isEnabled = false
            }
            partial > 0L -> {
                binding.firmwareStatus.text = "Verified selection — partial download found"
                binding.firmwareProgress.progress = if (expected > 0L) ((partial * 1000L) / expected).toInt().coerceIn(0, 1000) else 0
                binding.firmwareProgressText.text = "${FirmwareDownloadService.formatBytes(partial)} / ${FirmwareDownloadService.formatBytes(expected)}"
                binding.downloadFirmwareButton.text = "Resume firmware download"
                binding.downloadFirmwareButton.isEnabled = firmwareStorage.hasSharedStorageAccess()
                binding.cancelDownloadButton.isEnabled = false
            }
            else -> {
                binding.firmwareStatus.text = if (destination.isFile) "Existing file has the wrong size" else "Verified selection — firmware not downloaded"
                binding.firmwareProgress.progress = 0
                binding.firmwareProgressText.text = "Expected ${FirmwareDownloadService.formatBytes(expected)}"
                binding.downloadFirmwareButton.text = "Download firmware"
                binding.downloadFirmwareButton.isEnabled = firmwareStorage.hasSharedStorageAccess()
                binding.cancelDownloadButton.isEnabled = false
            }
        }
    }

    private fun startFirmwareDownload() {
        val firmware = latestSignedFirmware ?: return
        val destination = firmwareDestination ?: return
        if (!firmwareStorage.hasSharedStorageAccess()) {
            log("Firmware download blocked: shared storage access is unavailable")
            ensureSharedStorageAccess(openSettings = true)
            return
        }
        if (!firmware.url.startsWith("https://updates.cdn-apple.com/")) {
            log("Firmware download blocked: selected payload is not on Apple's CDN")
            return
        }

        val partial = runCatching { firmwareStorage.partialBytes(firmware) }.getOrDefault(0L)
        val remaining = if (firmware.fileSize > 0L) (firmware.fileSize - partial).coerceAtLeast(0L) else -1L
        if (remaining > 0L && !firmwareStorage.hasEnoughSpace(firmware.identifier, remaining)) {
            log("Firmware download blocked: insufficient free storage for remaining $remaining bytes")
            binding.firmwareStatus.text = "Not enough free storage"
            return
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        val intent = Intent(this, FirmwareDownloadService::class.java)
            .setAction(FirmwareDownloadService.ACTION_START)
            .putExtra(FirmwareDownloadService.EXTRA_URL, firmware.url)
            .putExtra(FirmwareDownloadService.EXTRA_DESTINATION, destination.absolutePath)
            .putExtra(FirmwareDownloadService.EXTRA_EXPECTED_SIZE, firmware.fileSize)
            .putExtra(FirmwareDownloadService.EXTRA_SHA1, firmware.sha1)
            .putExtra(FirmwareDownloadService.EXTRA_VERSION, firmware.version)
            .putExtra(FirmwareDownloadService.EXTRA_BUILD_ID, firmware.buildId)

        firmwareDownloadActive = true
        binding.selectFirmwareButton.isEnabled = false
        binding.downloadFirmwareButton.isEnabled = false
        binding.cancelDownloadButton.isEnabled = true
        binding.firmwareStatus.text = "Starting Apple CDN download"
        log("Firmware download requested: ${firmware.version} (${firmware.buildId})")
        log("Firmware download mode: single-stream resumable (connections=1)")
        ContextCompat.startForegroundService(this, intent)
    }

    private fun cancelFirmwareDownload() {
        log("Firmware download cancellation requested")
        startService(Intent(this, FirmwareDownloadService::class.java).setAction(FirmwareDownloadService.ACTION_CANCEL))
    }

    private fun handleFirmwareDownloadState(intent: Intent) {
        val state = intent.getStringExtra(FirmwareDownloadService.EXTRA_STATE).orEmpty()
        val message = intent.getStringExtra(FirmwareDownloadService.EXTRA_MESSAGE).orEmpty()
        val downloaded = intent.getLongExtra(FirmwareDownloadService.EXTRA_DOWNLOADED, 0L)
        val total = intent.getLongExtra(FirmwareDownloadService.EXTRA_TOTAL, -1L)
        val speed = intent.getLongExtra(FirmwareDownloadService.EXTRA_BYTES_PER_SECOND, 0L)

        if (state == FirmwareDownloadService.STATE_LOG) {
            if (message.isNotBlank()) log(message)
            return
        }

        if (message.isNotBlank()) log("FirmwareDownloadService: $message")
        when (state) {
            FirmwareDownloadService.STATE_RUNNING -> {
                firmwareDownloadActive = true
                binding.firmwareStatus.text = message.ifBlank { "Downloading from Apple CDN" }
                binding.firmwareProgress.progress = if (total > 0L) ((downloaded * 1000L) / total).toInt().coerceIn(0, 1000) else 0
                binding.firmwareProgressText.text = buildString {
                    append(FirmwareDownloadService.formatBytes(downloaded))
                    append(" / ")
                    append(FirmwareDownloadService.formatBytes(total))
                    if (speed > 0L) append(" — ${FirmwareDownloadService.formatBytes(speed)}/s")
                }
                binding.selectFirmwareButton.isEnabled = false
                binding.downloadFirmwareButton.isEnabled = false
                binding.cancelDownloadButton.isEnabled = true
            }
            FirmwareDownloadService.STATE_READY -> {
                firmwareDownloadActive = false
                updateFirmwareUi()
            }
            FirmwareDownloadService.STATE_FAILED, FirmwareDownloadService.STATE_CANCELLED -> {
                firmwareDownloadActive = false
                binding.firmwareStatus.text = message.ifBlank { "Download stopped; resume available" }
                binding.cancelDownloadButton.isEnabled = false
                updateFirmwareUi()
            }
        }
    }

    private fun finishProbe(device: UsbDevice) = runOnUiThread {
        probeInFlight = false
        binding.probeButton.isEnabled = selected?.deviceName == device.deviceName && usbManager.hasPermission(device)
        log("Probe completed")
    }

    private fun shareLogs() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("iDeviceRestore verbose diagnostic log")
            appendLine("Generated: $timestamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            appendLine("Host device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Shared storage access: ${if (firmwareStorage.hasSharedStorageAccess()) "granted" else "not granted"}")
            appendLine("Project root: ${firmwareStorage.projectRoot.absolutePath}")
            appendLine("Beta/RC firmware lookup: ${if (appSettings.includeBetaFirmware) "enabled" else "disabled"}")
            appendLine("Firmware selection mode: manual catalog chooser")
            appendLine("Firmware metadata mode: persistent cache + current index refresh + selection reverify")
            identifiedDevice?.let { appendLine("Identified device: ${it.name} (${it.identifier})") }
            latestSignedFirmware?.let { appendLine("Selected freshly verified firmware: ${it.version} (${it.buildId})") }
            firmwareWorkspace?.let { appendLine("Firmware directory: ${it.firmware.absolutePath}") }
            firmwareDestination?.let {
                appendLine("Firmware destination: ${it.absolutePath}")
                appendLine("Firmware file present: ${it.isFile}")
                if (it.isFile) appendLine("Firmware file size: ${it.length()} bytes")
            }
            appendLine("Firmware download active: $firmwareDownloadActive")
            appendLine("Privacy: ECID and Apple serial number are redacted from shared logs")
            appendLine("---")
            appendLine("=== Activity Log ===")
            append(logBuffer.toString())
            if (logBuffer.isNotEmpty() && logBuffer.last() != '\n') appendLine()
            appendLine("=== Probe Log ===")
            append(probeLogBuffer.toString())
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "iDeviceRestore diagnostic log")
            putExtra(Intent.EXTRA_TEXT, redactForSharing(report))
        }
        startActivity(Intent.createChooser(share, "Share verbose logs"))
    }

    private fun redactForSharing(text: String): String = text
        .replace(Regex("(?i)(ECID[:=])(?:0x)?[0-9a-f]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(SRNM:)\\[[^]]*]"), "$1[REDACTED]")

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

    private fun probeLog(message: String) {
        val clean = message.trimEnd()
        probeLogBuffer.append(clean).append('\n')
        binding.probeLogView.append(clean + "\n")
    }

    private fun logUi(message: String) {
        val routeToProbe = probeLogging
        runOnUiThread {
            if (routeToProbe) probeLog(message) else log(message)
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 4108
    }
}
