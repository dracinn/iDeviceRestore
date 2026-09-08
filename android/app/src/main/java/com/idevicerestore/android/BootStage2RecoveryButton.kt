package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostics-only Stage-2 boot action. The proven PrearmedStage1IbecButton remains the
 * implementation backing this control, but this button must never be treated as a prerequisite
 * for the cumulative hardware test. Run Current Stage-2 Test owns the automated DFU-to-current
 * boundary path.
 *
 * After the bounded DFU -> Stage-2 transition releases its USB reservation, this wrapper also
 * normalizes a stale persisted auto-boot=false left by an interrupted restore-entry attempt. The
 * current cumulative Stage-2 test intentionally expects auto-boot=true while replaying its proven
 * prerequisites, so leaving a stale false value would otherwise permanently gate the next run.
 */
class BootStage2RecoveryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val repairWorker = Executors.newSingleThreadExecutor()
    private val repairInFlight = AtomicBoolean(false)

    private val refresh = object : Runnable {
        override fun run() {
            val delegate = rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
            isEnabled = delegate?.isEnabled == true
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        text = LABEL
        contentDescription = "Diagnostics only: boot Stage 2 Recovery"
        isEnabled = false
        setOnClickListener {
            val activity = activity()
            if (activity != null) {
                log(
                    activity,
                    "Stage-2 diagnostic only: this action is not a prerequisite for Run Current Stage-2 Test; cumulative tests automate DFU through the current boundary"
                )
            }
            val started = rootView.findViewById<PrearmedStage1IbecButton?>(R.id.prearmedStage1IbecButton)
                ?.performClick() == true
            if (started) armAutoBootRepair()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        repairWorker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun armAutoBootRepair() {
        if (!repairInFlight.compareAndSet(false, true)) return
        repairWorker.execute {
            try {
                val activity = activity() ?: return@execute
                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val deadline = SystemClock.elapsedRealtime() + REPAIR_WINDOW_MS
                var lastFailure: Throwable? = null

                while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                    if (UsbOperationReservation.isReserved()) {
                        Thread.sleep(REPAIR_POLL_MS)
                        continue
                    }

                    val recovery = permittedM1Recovery(usb)
                    if (recovery == null) {
                        Thread.sleep(REPAIR_POLL_MS)
                        continue
                    }

                    var connection: android.hardware.usb.UsbDeviceConnection? = null
                    try {
                        connection = usb.openDevice(recovery) ?: error("Could not open Stage-2 Recovery device")
                        val claimed = AppleUsb.claimBestInterface(recovery, connection)
                            ?: error("Could not claim Stage-2 Recovery interface")
                        val command = RecoveryTransport(connection, claimed.bulkIn)
                        val stage = command.getenv("boot-stage").value.trim()
                        if (stage != STAGE_2) {
                            Thread.sleep(REPAIR_POLL_MS)
                            continue
                        }

                        val autoBoot = command.getenv("auto-boot").value.trim()
                        if (autoBoot.equals("true", ignoreCase = true)) {
                            log(activity, "Boot Stage 2 Recovery: auto-boot normalization not needed; boot-stage=2 auto-boot=true")
                            return@execute
                        }
                        require(autoBoot.equals("false", ignoreCase = true)) {
                            "Unexpected auto-boot value at Stage-2: '$autoBoot'"
                        }

                        log(activity, "Boot Stage 2 Recovery: stale auto-boot=false detected; repairing persistent environment before current test")
                        val setBytes = command.sendCommand("setenv auto-boot true")
                        val saveBytes = command.sendCommand("saveenv")
                        val verifiedStage = command.getenv("boot-stage").value.trim()
                        val verifiedAutoBoot = command.getenv("auto-boot").value.trim()
                        require(verifiedStage == STAGE_2) {
                            "boot-stage changed during auto-boot repair: '$verifiedStage'"
                        }
                        require(verifiedAutoBoot.equals("true", ignoreCase = true)) {
                            "auto-boot repair verification failed: '$verifiedAutoBoot'"
                        }
                        log(
                            activity,
                            "Boot Stage 2 Recovery: stale auto-boot repair COMPLETE setenvBytes=$setBytes saveenvBytes=$saveBytes boot-stage=2 auto-boot=true"
                        )
                        return@execute
                    } catch (t: Throwable) {
                        lastFailure = t
                    } finally {
                        connection?.close()
                    }

                    Thread.sleep(REPAIR_POLL_MS)
                }

                lastFailure?.let {
                    log(activity, "Boot Stage 2 Recovery: auto-boot normalization did not complete: ${it.javaClass.simpleName}: ${it.message}")
                }
            } finally {
                repairInFlight.set(false)
            }
        }
    }

    private fun permittedM1Recovery(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { device ->
        device.vendorId == AppleUsb.APPLE_VID &&
            AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY &&
            usb.hasPermission(device) &&
            AppleUsb.bootIdentifiers(device)?.cpidHex.equals(M1_CPID, ignoreCase = true)
    }

    private fun activity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    private fun log(activity: AppCompatActivity, message: String) = activity.runOnUiThread {
        val delivered = runCatching {
            val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
            true
        }.getOrDefault(false)
        if (!delivered) activity.findViewById<TextView?>(R.id.logView)?.append(message.trimEnd() + "\n")
    }

    companion object {
        private const val REFRESH_MS = 500L
        private const val REPAIR_POLL_MS = 250L
        private const val REPAIR_WINDOW_MS = 130_000L
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val LABEL = "Diagnostic: Boot Stage 2 Only"
    }
}
