package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, read-only probe for an already-running Apple-silicon Stage-2 iBoot.
 *
 * Safety boundary: this control only enumerates USB descriptors and issues a fixed whitelist of
 * getenv queries. It never uploads a component and never sends setenv/saveenv, bootx, restore,
 * erase, or any restore-OS command.
 */
class Stage2ReadonlyProbeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)

    private val refresh = object : Runnable {
        override fun run() {
            refreshState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = READY_LABEL
        isEnabled = false
        setOnClickListener { confirm() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshState() {
        if (inFlight.get()) {
            isEnabled = false
            text = "Stage-2 probe running…"
            return
        }
        val activity = activity() ?: run {
            isEnabled = false
            return
        }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val recovery = permittedRecovery(usb)
        val ids = recovery?.let(AppleUsb::bootIdentifiers)
        val ready = recovery != null && ids?.cpidHex.equals(M1_CPID, true)
        isEnabled = ready
        text = if (ready) READY_LABEL else "Connect verified M1 Stage-2 Recovery"
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run read-only Stage-2 probe?")
            .setMessage(
                "This test only inspects the connected M1 Recovery USB topology and issues a fixed whitelist of read-only iBoot getenv queries: " +
                    READ_ONLY_VARIABLES.joinToString(", ") + ". " +
                    "It requires boot-stage=2 before accepting the result. It does not upload firmware and does not send setenv, saveenv, bootx, RestoreRamDisk, SEP, DeviceTree, KernelCache, restore, or erase commands."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run read-only probe") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Stage-2 probe running…"
        setOperation(activity, "Read-only M1 Stage-2 probe starting…", true)
        log(
            activity,
            "Stage-2 read-only probe: explicit user confirmation received; boundary=USB-descriptors+whitelisted-getenv-only; " +
                "forbidden=setenv,saveenv,bootx,uploads,restore-os,restore,erase"
        )

        worker.execute {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var reservation: UsbOperationReservation.Lease? = null
            try {
                reservation = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is already active: ${UsbOperationReservation.owner() ?: "unknown"}")
                log(activity, "Stage-2 read-only probe: USB reservation acquired")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedRecovery(usb) ?: error("No permitted Apple Recovery device is connected")
                val ids = AppleUsb.bootIdentifiers(recovery) ?: error("Recovery boot identifiers unavailable")
                require(ids.cpidHex.equals(M1_CPID, true)) { "Stage-2 probe is restricted to M1 CPID 0x$M1_CPID" }

                log(
                    activity,
                    "Stage-2 USB snapshot: path=${recovery.deviceName} vid=0x%04x pid=0x%04x interfaces=${recovery.interfaceCount} cpid=%s bdid=%s ecid=%s"
                        .format(
                            recovery.vendorId,
                            recovery.productId,
                            ids.cpidHex ?: "unknown",
                            ids.bdidHex ?: "unknown",
                            ids.ecidHex ?: "unknown"
                        )
                )
                log(activity, "Stage-2 interface topology: ${singleLineInterfaceSummary(recovery)}")

                connection = usb.openDevice(recovery) ?: error("openDevice failed for Stage-2 Recovery")
                val claimed = AppleUsb.claimBestInterface(recovery, connection)
                    ?: error("Could not claim a Stage-2 Recovery interface")
                log(
                    activity,
                    "Stage-2 interface claimed: id=${claimed.intf.id} alt=${claimed.intf.alternateSetting} " +
                        "bulkIn=${claimed.bulkIn?.let { "0x%02x".format(it.address) } ?: "none"} " +
                        "bulkOut=${claimed.bulkOut?.let { "0x%02x".format(it.address) } ?: "none"}"
                )

                val transport = RecoveryTransport(connection, claimed.bulkIn)
                val values = linkedMapOf<String, String>()
                READ_ONLY_VARIABLES.forEach { variable ->
                    val value = transport.getenv(variable).value.trim()
                    values[variable] = value
                    log(activity, "Stage-2 getenv $variable=${value.ifEmpty { "<empty>" }}")
                }

                val stage = values["boot-stage"]
                val build = values["build-version"]
                require(stage == STAGE_2) { "Expected boot-stage=2, got ${stage ?: "unknown"}" }

                val preparedBuild = RestoreComponentPreparationStore.get()
                    ?.components
                    ?.firstOrNull { it.name == "iBEC" }
                    ?.personalizedFile
                    ?.let(Stage1BuildMetadata::expectedBuild)
                if (preparedBuild != null) {
                    require(build == preparedBuild) {
                        "Stage-2 build mismatch: expected prepared iBEC build=$preparedBuild actual=${build ?: "unknown"}"
                    }
                    log(activity, "Stage-2 build proof: prepared-iBEC-match=true build-version=$build")
                } else {
                    log(activity, "Stage-2 build proof: prepared iBEC metadata unavailable; observed build-version=${build ?: "unknown"}")
                }

                log(
                    activity,
                    "Stage-2 read-only probe PASS: boot-stage=2 build-version=${build ?: "unknown"}; " +
                        "STOP boundary reached with no uploads, persistent environment writes, bootx, restore, or erase"
                )
                setOperation(activity, "Read-only Stage-2 probe complete", false)
            } catch (t: Throwable) {
                log(activity, "Stage-2 read-only probe FAILED: ${t.javaClass.simpleName}: ${t.message}")
                setOperation(activity, "Stage-2 probe stopped: ${t.message ?: t.javaClass.simpleName}", false)
            } finally {
                connection?.close()
                reservation?.let { lease ->
                    runCatching { UsbOperationReservation.release(lease) }
                        .onSuccess { log(activity, "Stage-2 read-only probe: USB reservation released") }
                }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun permittedRecovery(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.vendorId == AppleUsb.APPLE_VID && AppleUsb.mode(it) == AppleUsb.Mode.RECOVERY && usb.hasPermission(it)
    }

    private fun singleLineInterfaceSummary(device: UsbDevice): String =
        AppleUsb.interfaceSummary(device).trim().replace("\r", "").replace("\n", " | ")

    private fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) = activity.runOnUiThread {
        activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
        activity.findViewById<android.widget.ProgressBar?>(R.id.operationProgress)?.apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) isIndeterminate = true
        }
    }

    private fun log(activity: AppCompatActivity, message: String) = activity.runOnUiThread {
        runCatching {
            val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
        }.onFailure {
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
        }
    }

    private tailrec fun activityFrom(context: Context): AppCompatActivity? = when (context) {
        is AppCompatActivity -> context
        is ContextWrapper -> activityFrom(context.baseContext)
        else -> null
    }

    private fun activity(): AppCompatActivity? = activityFrom(context)

    companion object {
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val REFRESH_MS = 750L
        private const val RESERVATION_OWNER = "stage2-readonly-probe"
        private const val READY_LABEL = "Probe M1 Stage-2 (read-only)"
        private val READ_ONLY_VARIABLES = listOf(
            "boot-stage",
            "build-version",
            "build-style",
            "auto-boot",
            "display-timing"
        )
    }
}
