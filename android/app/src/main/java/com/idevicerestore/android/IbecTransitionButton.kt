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

/** Explicit second hardware-test boundary: upload and execute only personalized iBEC from M1 Stage-1 Recovery. */
class IbecTransitionButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private val proofInFlight = AtomicBoolean(false)
    private val watchdogInFlight = AtomicBoolean(false)
    @Volatile private var lastLifetimeLogAtElapsedMs = 0L
    private val refresh = object : Runnable { override fun run() { refreshState(); if (isAttachedToWindow) postDelayed(this, REFRESH_MS) } }

    init { text = READY_LABEL; isEnabled = false; setOnClickListener { confirm() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); worker.shutdown(); super.onDetachedFromWindow() }

    private fun refreshState() {
        val activity = activity() ?: return
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        when (IbecTransitionObservationStore.snapshot().state) {
            IbecTransitionObservationStore.State.EXECUTING -> { isEnabled = false; text = "Sending iBEC…"; return }
            IbecTransitionObservationStore.State.WAITING_FOR_RECOVERY -> { observe(activity, usb); return }
            IbecTransitionObservationStore.State.SUCCEEDED -> { isEnabled = false; text = "iBEC Transition Observed"; return }
            IbecTransitionObservationStore.State.FAILED -> IbecTransitionObservationStore.reset()
            IbecTransitionObservationStore.State.IDLE -> Unit
        }
        if (inFlight.get()) { isEnabled = false; text = "Sending iBEC…"; return }

        val recovery = recoveryDevice(usb)
        val proofBeforePreparation = Stage1RecoveryProofStore.snapshot()
        if (proofBeforePreparation.state == Stage1RecoveryProofStore.State.PROVEN) {
            if (recovery == null) {
                val elapsed = proofElapsedMs(proofBeforePreparation)
                Stage1RecoveryProofStore.fail(proofBeforePreparation.deviceIdentityKey ?: "unknown", "Recovery USB device disappeared after Stage-1 proof")
                log(activity, "Stage-1 lifetime LOST: elapsedMs=$elapsed reason=Recovery USB device disappeared")
            } else {
                val identityKey = deviceIdentityKey(recovery)
                if (proofBeforePreparation.deviceIdentityKey != identityKey) {
                    val elapsed = proofElapsedMs(proofBeforePreparation)
                    Stage1RecoveryProofStore.fail(proofBeforePreparation.deviceIdentityKey ?: identityKey, "Recovery device identity changed after Stage-1 proof")
                    log(activity, "Stage-1 lifetime LOST: elapsedMs=$elapsed reason=Recovery device identity changed VID=%04x PID=%04x mode=%s".format(recovery.vendorId, recovery.productId, AppleUsb.mode(recovery)))
                } else if (SystemClock.elapsedRealtime() - proofBeforePreparation.lastVerifiedAtElapsedMs >= STAGE_1_WATCHDOG_MS) {
                    verifyStage1Lifetime(activity, usb, recovery, identityKey)
                }
            }
        }

        val ids = recovery?.let(AppleUsb::bootIdentifiers)
        val ticket = TssTicketStore.get()
        val prepared = RestoreComponentPreparationStore.get()
        val ibec = prepared?.components?.firstOrNull { it.name == "iBEC" }
        val prerequisitesReady = recovery != null && ids?.cpidHex.equals(M1_CPID, true) && ticket != null && foundationMatchesDevice(ticket.foundation, recovery) && prepared != null && prepared.buildId.equals(ticket.buildId, true) && prepared.identityIndex == ticket.identityIndex && ibec?.personalizedFile?.isFile == true && ibec.personalizedBytes == ibec.personalizedFile.length()
        if (!prerequisitesReady) {
            isEnabled = false
            text = if (Stage1RecoveryProofStore.snapshot().state == Stage1RecoveryProofStore.State.FAILED) "Stage-1 Recovery Lost" else READY_LABEL
            return
        }

        val identityKey = deviceIdentityKey(recovery!!)
        val proof = Stage1RecoveryProofStore.snapshot()
        if (proof.deviceIdentityKey != null && proof.deviceIdentityKey != identityKey) {
            Stage1RecoveryProofStore.fail(proof.deviceIdentityKey, "Recovery device identity changed before iBEC readiness")
        }
        val currentProof = Stage1RecoveryProofStore.snapshot()
        when (currentProof.state) {
            Stage1RecoveryProofStore.State.PROVEN -> {
                isEnabled = currentProof.deviceIdentityKey == identityKey && currentProof.bootStage == STAGE_1
                text = if (isEnabled) READY_LABEL else "Stage-1 Recovery Not Proven"
            }
            Stage1RecoveryProofStore.State.PROBING -> {
                isEnabled = false
                text = "Verifying Stage-1 Recovery…"
            }
            Stage1RecoveryProofStore.State.UNKNOWN -> {
                isEnabled = false
                text = "Verifying Stage-1 Recovery…"
                verifyStage1Recovery(activity, usb, recovery, identityKey)
            }
            Stage1RecoveryProofStore.State.FAILED -> {
                isEnabled = false
                text = "Stage-1 Recovery Lost"
            }
        }
    }

    private fun verifyStage1Recovery(activity: AppCompatActivity, usb: UsbManager, device: UsbDevice, identityKey: String) {
        if (!proofInFlight.compareAndSet(false, true)) return
        Stage1RecoveryProofStore.begin(identityKey)
        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                require(AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) { "Device is no longer in Recovery mode" }
                require(deviceIdentityKey(device) == identityKey) { "Recovery device identity changed before Stage-1 verification" }
                connection = usb.openDevice(device) ?: error("openDevice failed for Stage-1 Recovery verification")
                val claimed = AppleUsb.claimBestInterface(device, connection) ?: error("Could not claim Recovery interface for Stage-1 verification")
                val transport = RecoveryTransport(connection, claimed.bulkIn)
                val bootStage = transport.getenv("boot-stage").value.trim()
                val buildVersion = runCatching { transport.getenv("build-version").value.trim() }.getOrNull()
                require(bootStage == STAGE_1) { "Recovery boot-stage=$bootStage; expected Stage-1" }
                Stage1RecoveryProofStore.prove(identityKey, bootStage, buildVersion)
                log(activity, "iBEC transition gate: Stage-1 Recovery proven read-only boot-stage=$bootStage build-version=${buildVersion ?: "unknown"}")
            } catch (t: Throwable) {
                Stage1RecoveryProofStore.fail(identityKey, t.message ?: t.javaClass.simpleName)
                log(activity, "iBEC transition gate: Stage-1 Recovery proof failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                connection?.close()
                proofInFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun verifyStage1Lifetime(activity: AppCompatActivity, usb: UsbManager, device: UsbDevice, identityKey: String) {
        if (!watchdogInFlight.compareAndSet(false, true)) return
        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val before = Stage1RecoveryProofStore.snapshot()
                if (before.state != Stage1RecoveryProofStore.State.PROVEN || before.deviceIdentityKey != identityKey) return@execute
                if (AppleUsb.mode(device) != AppleUsb.Mode.RECOVERY || deviceIdentityKey(device) != identityKey) {
                    Stage1RecoveryProofStore.fail(identityKey, "Recovery personality or identity changed after Stage-1 proof")
                    log(activity, "Stage-1 lifetime LOST: elapsedMs=${proofElapsedMs(before)} reason=Recovery personality-or-identity changed VID=%04x PID=%04x mode=%s".format(device.vendorId, device.productId, AppleUsb.mode(device)))
                    return@execute
                }
                connection = usb.openDevice(device) ?: error("openDevice failed")
                val claimed = AppleUsb.claimBestInterface(device, connection) ?: error("claim Recovery interface failed")
                val transport = RecoveryTransport(connection, claimed.bulkIn)
                val bootStage = transport.getenv("boot-stage").value.trim()
                val buildVersion = runCatching { transport.getenv("build-version").value.trim() }.getOrNull()
                if (bootStage != STAGE_1) {
                    Stage1RecoveryProofStore.fail(identityKey, "Recovery boot-stage changed from 1 to $bootStage")
                    log(activity, "Stage-1 lifetime LOST: elapsedMs=${proofElapsedMs(before)} boot-stage=$bootStage build-version=${buildVersion ?: "unknown"} VID=%04x PID=%04x".format(device.vendorId, device.productId))
                    return@execute
                }
                Stage1RecoveryProofStore.refresh(identityKey, bootStage, buildVersion)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLifetimeLogAtElapsedMs >= STAGE_1_HEARTBEAT_LOG_MS) {
                    lastLifetimeLogAtElapsedMs = now
                    log(activity, "Stage-1 lifetime heartbeat: elapsedMs=${proofElapsedMs(Stage1RecoveryProofStore.snapshot())} boot-stage=1 build-version=${buildVersion ?: before.buildVersion ?: "unknown"}")
                }
            } catch (t: Throwable) {
                log(activity, "Stage-1 lifetime sample unavailable: ${t.javaClass.simpleName}: ${t.message}; proof retained pending next read-only sample")
            } finally {
                connection?.close()
                watchdogInFlight.set(false)
            }
        }
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity).setTitle("Run M1 iBEC transition test?").setMessage("This state-changing hardware test sends only the already-personalized iBEC from the proven Stage-1 Recovery state, executes it using the Apple-silicon 'go' sequence, observes USB re-enumeration, and stops. It will not send RestoreRamDisk, SEP, DeviceTree, KernelCache, or start a filesystem restore/erase stage.").setNegativeButton("Cancel", null).setPositiveButton("Send iBEC Only") { _, _ -> start() }.show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val initialDevice = recoveryDevice(usb) ?: run { inFlight.set(false); return }
        val initialIdentityKey = deviceIdentityKey(initialDevice)
        val stage1Proof = Stage1RecoveryProofStore.snapshot()
        if (stage1Proof.state != Stage1RecoveryProofStore.State.PROVEN || stage1Proof.deviceIdentityKey != initialIdentityKey || stage1Proof.bootStage != STAGE_1) {
            inFlight.set(false)
            log(activity, "iBEC transition test blocked: Stage-1 Recovery proof is missing or stale")
            refreshState()
            return
        }
        IbecTransitionObservationStore.begin(usbKey(initialDevice))
        isEnabled = false; text = "Sending iBEC…"
        log(activity, "iBEC transition test: explicit user confirmation received; boundary=iBEC-only")
        setOperation(activity, "M1 iBEC-only Recovery transition test starting…", true)
        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                val device = recoveryDevice(usb) ?: error("No permitted Apple Recovery device is connected")
                require(usbKey(device) == IbecTransitionObservationStore.snapshot().sourceUsbKey) { "Recovery device changed before iBEC upload" }
                require(deviceIdentityKey(device) == initialIdentityKey) { "Recovery device identity changed before iBEC upload" }
                val ids = AppleUsb.bootIdentifiers(device) ?: error("Recovery boot identifiers unavailable")
                require(ids.cpidHex.equals(M1_CPID, true)) { "iBEC transition test is restricted to M1 CPID 0x$M1_CPID" }
                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable")
                require(foundationMatchesDevice(ticket.foundation, device)) { "Recovery device does not match the ECID/chip/board identity used for the TSS ticket" }
                val prepared = RestoreComponentPreparationStore.get() ?: error("Restore components unavailable")
                require(prepared.buildId.equals(ticket.buildId, true) && prepared.identityIndex == ticket.identityIndex) { "Prepared iBEC/TSS identity mismatch" }
                val ibec = prepared.components.firstOrNull { it.name == "iBEC" } ?: error("Prepared iBEC unavailable")
                val file = ibec.personalizedFile ?: error("Personalized iBEC unavailable")
                require(file.isFile && file.length() == ibec.personalizedBytes) { "Personalized iBEC file validation failed" }
                PersonalizedImage4Validator.validate(file, ticket.apImg4Ticket, "iBEC")
                log(activity, "iBEC transition test: personalized IMG4 and exact TSS ticket revalidated immediately before upload")
                log(activity, "iBEC transition BEFORE: VID=%04x PID=%04x personality=%s mode=%s CPID=%s BDID=%s bytes=%d".format(device.vendorId, device.productId, AppleUsb.personality(device), AppleUsb.mode(device), ids.cpidHex ?: "unknown", ids.bdidHex ?: "unknown", file.length()))
                connection = usb.openDevice(device) ?: error("openDevice failed for iBEC transition test")
                val claimed = AppleUsb.claimBestInterface(device, connection) ?: error("Could not claim Recovery interface")
                val bulkOut = claimed.bulkOut ?: error("Recovery bulk OUT endpoint unavailable")
                val transport = RecoveryTransport(connection, claimed.bulkIn)
                val liveBootStage = transport.getenv("boot-stage").value.trim()
                val liveBuildVersion = runCatching { transport.getenv("build-version").value.trim() }.getOrNull()
                require(liveBootStage == STAGE_1) { "Live Recovery boot-stage=$liveBootStage immediately before iBEC upload; expected Stage-1" }
                Stage1RecoveryProofStore.refresh(initialIdentityKey, liveBootStage, liveBuildVersion)
                log(activity, "iBEC transition pre-upload gate: live boot-stage=$liveBootStage build-version=${liveBuildVersion ?: "unknown"} elapsedMs=${proofElapsedMs(Stage1RecoveryProofStore.snapshot())}")
                val setInterface = connection.setInterface(claimed.intf)
                log(activity, "iBEC transition USB: claimed interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting} class=${claimed.intf.interfaceClass} subclass=${claimed.intf.interfaceSubclass} protocol=${claimed.intf.interfaceProtocol}; setInterface=$setInterface; bulkOut=0x%02x type=${bulkOut.type} maxPacket=${bulkOut.maxPacketSize}".format(bulkOut.address))
                require(setInterface) { "Android could not activate claimed Recovery interface id=${claimed.intf.id} alt=${claimed.intf.alternateSetting}" }
                val session = RecoveryComponentSession(device, transport, connection, bulkOut)
                val uploaded = session.uploadFile(RecoveryComponentSession.Component.IBEC, file) { p -> setProgress(activity, p.percent) }
                log(activity, "iBEC transition test: upload complete bytes=${uploaded.bytes} packets=${uploaded.packets} endpoint=0x%02x".format(uploaded.endpointAddress))
                val execution = session.executeAppleSiliconIbec()
                log(activity, "iBEC transition test: go accepted bytes=${execution.goCommandBytes} followUpAccepted=${execution.followUpAccepted} followUpError=${execution.followUpError?.javaClass?.simpleName ?: "none"}")
                log(activity, "iBEC transition test: STOP boundary armed — no RestoreRamDisk/SEP/DeviceTree/KernelCache payload will be sent")
                IbecTransitionObservationStore.waiting(SystemClock.elapsedRealtime())
                setOperation(activity, "iBEC executed — observing USB re-enumeration; no further payloads will be sent", true)
            } catch (t: Throwable) {
                IbecTransitionObservationStore.fail(t.message ?: t.javaClass.simpleName)
                log(activity, "iBEC transition test FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "iBEC transition test failed: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close(); if (connection != null) log(activity, "iBEC transition test: Recovery USB connection closed")
                inFlight.set(false); activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun observe(activity: AppCompatActivity, usb: UsbManager) {
        isEnabled = false; text = "Observing iBEC Re-enumeration…"
        val state = IbecTransitionObservationStore.snapshot()
        val elapsed = SystemClock.elapsedRealtime() - state.startedAtElapsedMs
        val ticket = TssTicketStore.get()
        for (device in usb.deviceList.values.filter { it.vendorId == AppleUsb.APPLE_VID && usb.hasPermission(it) && usbKey(it) != state.sourceUsbKey }) {
            val key = usbKey(device)
            if (key != state.lastObservedUsbKey) {
                IbecTransitionObservationStore.observed(key)
                val continuity = if (ticket != null && foundationMatchesDevice(ticket.foundation, device)) "matched" else "MISMATCH-or-unknown"
                log(activity, "iBEC transition observe: elapsedMs=$elapsed VID=%04x PID=%04x personality=%s mode=%s identity-continuity=%s".format(device.vendorId, device.productId, AppleUsb.personality(device), AppleUsb.mode(device), continuity))
            }
            val sameDevice = ticket != null && foundationMatchesDevice(ticket.foundation, device)
            if (sameDevice && AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY) {
                IbecTransitionObservationStore.succeed("Recovery re-enumeration accepted")
                log(activity, "iBEC transition RESULT: Recovery re-enumeration accepted elapsedMs=$elapsed; STOPPED before restore-OS component upload")
                text = "iBEC Transition Observed"
                setOperation(activity, "iBEC transition observed in Recovery; stopped before restore-OS payloads", false)
                return
            }
        }
        if (elapsed >= RECOVERY_RECONNECT_TIMEOUT_MS) {
            IbecTransitionObservationStore.fail("Timed out waiting for same-device Recovery after iBEC")
            log(activity, "iBEC transition RESULT: timeout elapsedMs=$elapsed waiting for same-device Recovery; test stopped and may be retried")
            text = READY_LABEL
            setOperation(activity, "iBEC transition timed out waiting for Recovery; test stopped", false)
        }
    }

    private fun proofElapsedMs(snapshot: Stage1RecoveryProofStore.Snapshot): Long = if (snapshot.provenAtElapsedMs > 0L) (SystemClock.elapsedRealtime() - snapshot.provenAtElapsedMs).coerceAtLeast(0L) else 0L
    private fun recoveryDevice(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY && usb.hasPermission(it) }
    private fun foundationMatchesDevice(f: TssRequestFoundation.Parameters, d: UsbDevice): Boolean { val ids = AppleUsb.bootIdentifiers(d) ?: return false; val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false; val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false; val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false; return f.ecid == ecid && f.apChipId == cpid && f.apBoardId == bdid }
    private fun deviceIdentityKey(d: UsbDevice): String { val ids = AppleUsb.bootIdentifiers(d); return "${usbKey(d)}:${ids?.ecidHex ?: "?"}:${ids?.cpidHex ?: "?"}:${ids?.bdidHex ?: "?"}" }
    private fun usbKey(d: UsbDevice) = "${d.deviceName}:${d.productId}"
    private fun setProgress(a: AppCompatActivity, percent: Int) = a.runOnUiThread { a.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply { visibility = View.VISIBLE; isIndeterminate = false; progress = percent.coerceIn(0, 100) }; a.findViewById<TextView?>(R.id.operationStatus)?.text = "Sending personalized iBEC… ${percent.coerceIn(0, 100)}%" }
    private fun setOperation(a: AppCompatActivity, message: String, busy: Boolean) = a.runOnUiThread { a.findViewById<TextView?>(R.id.operationStatus)?.text = message; a.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply { visibility = if (busy) View.VISIBLE else View.GONE; if (busy) isIndeterminate = true } }
    private fun log(a: AppCompatActivity, message: String) = a.runOnUiThread { val delivered = runCatching { val m = a.javaClass.getDeclaredMethod("log", String::class.java); m.isAccessible = true; m.invoke(a, message); true }.getOrDefault(false); if (!delivered) a.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n") }
    private fun activity(): AppCompatActivity? { var c: Context? = context; while (c is ContextWrapper) { if (c is AppCompatActivity) return c; c = c.baseContext }; return c as? AppCompatActivity }
    companion object { private const val REFRESH_MS = 1000L; private const val STAGE_1_WATCHDOG_MS = 5000L; private const val STAGE_1_HEARTBEAT_LOG_MS = 15000L; private const val RECOVERY_RECONNECT_TIMEOUT_MS = 120_000L; private const val M1_CPID = "8103"; private const val STAGE_1 = "1"; private const val READY_LABEL = "Test M1 iBEC Recovery Transition" }
}
