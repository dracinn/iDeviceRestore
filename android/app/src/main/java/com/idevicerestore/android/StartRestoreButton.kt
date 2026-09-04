package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit hardware-test gate for the first state-changing M1 restore transition.
 *
 * This control sends only the already-personalized iBSS image, performs the DFU finish/reset used by
 * libirecovery, and then stops while observing USB re-enumeration. It never sends iBEC, Recovery
 * payloads, or restore-OS components. The first hardware test is intentionally restricted to the
 * hardware-validated M1 CPID 0x8103 path.
 */
class StartRestoreButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var recoveryLogged = false
    private var transitionStartedAtMs = 0L
    private var lastObservedUsbKey: String? = null
    private var sourceUsbKey: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = READY_LABEL
        isEnabled = false
        setOnClickListener { confirmStart() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(refreshRunnable)
        post(refreshRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRunnable)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        val activity = activity() ?: run {
            isEnabled = false
            return
        }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val snapshot = RestoreSessionStore.transitions.snapshot()

        if (snapshot.state == RestoreUsbStateMachine.State.WAITING_FOR_RECOVERY) {
            isEnabled = false
            text = "Waiting for USB Re-enumeration…"
            observeReenumeration(activity, usb, snapshot)
            return
        }

        if (snapshot.state == RestoreUsbStateMachine.State.RECOVERY_CONNECTED) {
            isEnabled = false
            text = "iBSS Transition Observed"
            return
        }

        if (inFlight.get()) {
            isEnabled = false
            text = "Sending iBSS…"
            return
        }

        val buildId = selectedBuildId(activity)
        val prepared = Image4PreparationStore.get()
        val context = FirmwarePreparationStore.get()
        val ticket = TssTicketStore.get()
        val dfu = usb.deviceList.values.firstOrNull {
            it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
        }
        val ids = dfu?.let(AppleUsb::bootIdentifiers)
        val m1ClassicDfu = ids?.cpidHex.equals(M1_CPID, ignoreCase = true)
        val ticketMatchesDevice = dfu != null && ticket != null && foundationMatchesDevice(ticket.foundation, dfu)
        val ready = buildId != null && dfu != null && m1ClassicDfu && ticketMatchesDevice && prepared != null && context != null && ticket != null &&
            prepared.result.buildId.equals(buildId, ignoreCase = true) &&
            context.matches(buildId, prepared.result.identityIndex) &&
            ticket.buildId.equals(buildId, ignoreCase = true) &&
            ticket.identityIndex == prepared.result.identityIndex &&
            prepared.result.file.isFile && prepared.result.file.length() == prepared.result.personalizedBytes

        text = READY_LABEL
        isEnabled = ready
    }

    private fun observeReenumeration(
        activity: AppCompatActivity,
        usb: UsbManager,
        snapshot: RestoreUsbStateMachine.Snapshot
    ) {
        val elapsed = if (transitionStartedAtMs > 0L) SystemClock.elapsedRealtime() - transitionStartedAtMs else -1L
        if (elapsed >= REENUMERATION_TIMEOUT_MS) {
            val failed = RestoreSessionStore.transitions.fail("Timed out waiting for Recovery after iBSS transition")
            log(activity, "iBSS transition RESULT: timeout elapsedMs=$elapsed state=${failed.state}; test stopped and may be retried")
            setOperation(activity, "iBSS transition timed out waiting for Recovery; test stopped", false)
            text = READY_LABEL
            return
        }

        val appleDevices = usb.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID && usb.hasPermission(it) }
        for (device in appleDevices) {
            val key = usbKey(device)
            if (key != lastObservedUsbKey) {
                lastObservedUsbKey = key
                val ids = AppleUsb.bootIdentifiers(device)
                val expectedEcid = snapshot.identity?.ecidHex
                val incomingEcid = ids?.ecidHex
                val ecidContinuity = when {
                    expectedEcid == null || incomingEcid == null -> "unknown"
                    expectedEcid.equals(incomingEcid, ignoreCase = true) -> "matched"
                    else -> "MISMATCH"
                }
                log(
                    activity,
                    "iBSS transition observe: elapsedMs=$elapsed VID=%04x PID=%04x personality=%s mode=%s ECID-continuity=%s"
                        .format(
                            device.vendorId,
                            device.productId,
                            AppleUsb.personality(device),
                            AppleUsb.mode(device),
                            ecidContinuity
                        )
                )
            }

            if (AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) {
                val accepted = RestoreSessionStore.transitions.onAttached(device)
                if (accepted.state == RestoreUsbStateMachine.State.RECOVERY_CONNECTED && !recoveryLogged) {
                    recoveryLogged = true
                    log(activity, "iBSS transition RESULT: Recovery re-enumeration accepted elapsedMs=$elapsed; stopping before iBEC")
                    setOperation(activity, "iBSS transition succeeded — Recovery observed; stopped before iBEC", false)
                    text = "iBSS Transition Observed"
                }
                return
            }

            val sameTrackedDevice = identityMatchesSnapshot(snapshot, device)
            val isFreshEnumeration = key != sourceUsbKey
            if (elapsed >= UNEXPECTED_PERSONALITY_GRACE_MS && sameTrackedDevice && isFreshEnumeration) {
                val personality = AppleUsb.personality(device)
                val mode = AppleUsb.mode(device)
                val failed = RestoreSessionStore.transitions.fail(
                    "Unexpected Apple USB personality after iBSS: personality=$personality mode=$mode"
                )
                log(
                    activity,
                    "iBSS transition RESULT: unexpected re-enumeration elapsedMs=$elapsed personality=$personality mode=$mode state=${failed.state}; test stopped and may be retried"
                )
                setOperation(activity, "iBSS transition stopped: unexpected $personality/$mode re-enumeration", false)
                text = READY_LABEL
                return
            }
        }
    }

    private fun confirmStart() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run M1 iBSS transition test?")
            .setMessage(
                "This hardware test changes the Mac's boot state. It will send only the validated personalized iBSS to the connected M1 Mac in classic DFU, finish the DFU transfer, reset the USB connection, and observe what reconnects. " +
                    "It will stop before iBEC, Recovery uploads, or any filesystem restore/erase stage."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send iBSS Only") { _, _ -> startRestore() }
            .show()
    }

    private fun startRestore() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Sending iBSS…"
        recoveryLogged = false
        lastObservedUsbKey = null
        sourceUsbKey = null
        transitionStartedAtMs = SystemClock.elapsedRealtime()
        setOperation(activity, "M1 iBSS-only DFU transition test starting…", true)
        log(activity, "iBSS transition test: explicit user confirmation received; boundary=iBSS-only")

        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val device = usb.deviceList.values.firstOrNull {
                    it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.DFU && usb.hasPermission(it)
                } ?: error("No permitted Apple DFU device is connected")
                val ids = AppleUsb.bootIdentifiers(device)
                require(ids?.cpidHex.equals(M1_CPID, ignoreCase = true)) {
                    "iBSS transition test is restricted to hardware-validated M1 CPID 0x$M1_CPID"
                }

                val prepared = Image4PreparationStore.get() ?: error("Personalized iBSS is not prepared")
                val context = FirmwarePreparationStore.get() ?: error("Verified firmware context is unavailable")
                val ticket = TssTicketStore.get() ?: error("TSS ticket is unavailable")
                val buildId = selectedBuildId(activity) ?: error("Selected build could not be determined")

                require(foundationMatchesDevice(ticket.foundation, device)) {
                    "Connected DFU device does not match the ECID/chip/board identity used for the TSS ticket"
                }
                require(prepared.result.buildId.equals(buildId, ignoreCase = true)) { "Prepared iBSS build mismatch" }
                require(context.matches(buildId, prepared.result.identityIndex)) { "Firmware context mismatch" }
                require(ticket.buildId.equals(buildId, ignoreCase = true) && ticket.identityIndex == prepared.result.identityIndex) {
                    "TSS ticket mismatch"
                }
                Image4Personalizer.validatePersonalizedIbss(prepared.result.file, ticket.apImg4Ticket)
                log(
                    activity,
                    "iBSS transition test: personalized iBSS revalidated bytes=${prepared.result.personalizedBytes} " +
                        "identity=${prepared.result.identityIndex} build=$buildId"
                )
                log(
                    activity,
                    "iBSS transition BEFORE: VID=%04x PID=%04x personality=%s mode=%s CPID=%s BDID=%s"
                        .format(
                            device.vendorId,
                            device.productId,
                            AppleUsb.personality(device),
                            AppleUsb.mode(device),
                            ids?.cpidHex ?: "unknown",
                            ids?.bdidHex ?: "unknown"
                        )
                )

                connection = usb.openDevice(device) ?: error("openDevice failed for iBSS transition test")
                val claimed = AppleUsb.claimBestInterface(device, connection)
                    ?: error("Could not claim DFU interface for iBSS transition test")
                val liveNonces = DfuNonceInfo.fromConnection(connection)
                require(liveNonces.apNonce?.contentEquals(ticket.foundation.apNonce) == true) {
                    "Connected DFU ApNonce no longer matches the TSS ticket"
                }
                val expectedSepNonce = ticket.foundation.apSepNonce
                require(expectedSepNonce == null || liveNonces.sepNonce?.contentEquals(expectedSepNonce) == true) {
                    "Connected DFU ApSepNonce no longer matches the TSS ticket"
                }
                log(activity, "iBSS transition test: live ECID/chip/board and nonce binding verified against TSS ticket")

                val resetCapability = AndroidUsbReset.capability(connection)
                check(resetCapability.available) { resetCapability.reason }
                log(activity, "iBSS transition test: Android host USB reset capability verified")

                sourceUsbKey = usbKey(device)
                RestoreSessionStore.begin(buildId)
                val image = DfuStage1Session.PersonalizedIbss(
                    file = prepared.result.file,
                    identityIndex = prepared.result.identityIndex,
                    sourceManifestPath = prepared.sourceManifestPath,
                    personalizationId = "$buildId:identity-${prepared.result.identityIndex}"
                )
                val result = DfuStage1Session(
                    device = device,
                    connection = connection,
                    interfaceId = claimed.intf.id,
                    transitions = RestoreSessionStore.transitions,
                    logger = { log(activity, it) }
                ).uploadPersonalizedIbss(image) { progress ->
                    setProgress(activity, progress.percent)
                }
                log(
                    activity,
                    "iBSS transition test: payload sent bytes=${result.bytes} blocks=${result.blocks} " +
                        "finalDfuState=${DfuTransport.stateName(result.finalDfuState)} " +
                        "manifestationState=${DfuTransport.stateName(result.manifestationState)} transition=${result.transition.state}"
                )
                log(activity, "iBSS transition test: STOP boundary armed — no iBEC or Recovery payload will be sent")
                setOperation(activity, "iBSS sent — observing USB re-enumeration; no further payloads will be sent", true)
            } catch (t: Throwable) {
                RestoreSessionStore.transitions.fail("iBSS transition test failed: ${t.message ?: t.javaClass.simpleName}")
                log(activity, "iBSS transition test FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "iBSS transition test failed: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close()
                if (connection != null) log(activity, "iBSS transition test: DFU USB connection closed")
                inFlight.set(false)
                activity.runOnUiThread { refreshState() }
            }
        }
    }

    private fun foundationMatchesDevice(
        foundation: TssRequestFoundation.Parameters,
        device: UsbDevice
    ): Boolean {
        val ids = AppleUsb.bootIdentifiers(device) ?: return false
        val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false
        val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false
        val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false
        return foundation.ecid == ecid && foundation.apChipId == cpid && foundation.apBoardId == bdid
    }

    private fun identityMatchesSnapshot(snapshot: RestoreUsbStateMachine.Snapshot, device: UsbDevice): Boolean {
        val expected = snapshot.identity ?: return true
        val ids = AppleUsb.bootIdentifiers(device) ?: return expected.ecidHex == null
        if (expected.ecidHex != null && ids.ecidHex != null) {
            return expected.ecidHex.equals(ids.ecidHex, ignoreCase = true)
        }
        if (expected.cpidHex != null && ids.cpidHex != null && !expected.cpidHex.equals(ids.cpidHex, ignoreCase = true)) return false
        if (expected.bdidHex != null && ids.bdidHex != null && !expected.bdidHex.equals(ids.bdidHex, ignoreCase = true)) return false
        return true
    }

    private fun usbKey(device: UsbDevice): String = "${device.deviceName}:${device.productId}"

    private fun selectedBuildId(activity: AppCompatActivity): String? {
        val title = activity.findViewById<TextView?>(R.id.firmwareTitle)?.text?.toString().orEmpty()
        return Regex("\\(([0-9]{2}[A-Za-z][A-Za-z0-9]{3,12})\\)\\s*$")
            .find(title)?.groupValues?.getOrNull(1)
    }

    private fun setProgress(activity: AppCompatActivity, percent: Int) {
        activity.runOnUiThread {
            activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
                visibility = View.VISIBLE
                isIndeterminate = false
                progress = percent.coerceIn(0, 100)
            }
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = "Sending personalized iBSS… ${percent.coerceIn(0, 100)}%"
        }
    }

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
            activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
                visibility = if (busy) View.VISIBLE else View.GONE
                if (busy) isIndeterminate = true
            }
        }
    }

    private fun log(activity: AppCompatActivity, message: String) {
        activity.runOnUiThread {
            val delivered = runCatching {
                val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
                method.isAccessible = true
                method.invoke(activity, message)
                true
            }.getOrDefault(false)
            if (!delivered) activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
        }
    }

    private fun activity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    companion object {
        private const val REFRESH_MS = 500L
        private const val M1_CPID = "8103"
        private const val READY_LABEL = "Test M1 iBSS DFU Transition"
        private const val UNEXPECTED_PERSONALITY_GRACE_MS = 2_000L
        private const val REENUMERATION_TIMEOUT_MS = 15_000L
    }
}
