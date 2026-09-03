package com.idevicerestore.android

import android.app.Dialog
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Human-guided DFU entry assistant.
 *
 * The guide never sends a USB command. It only presents the physical Apple-silicon key sequence
 * and observes USB re-enumeration supplied by the host activity/view. When the same Apple device
 * appears in DFU mode, the guide switches immediately to a success state.
 */
class DfuModeGuide(
    context: Context,
    recoveryDevice: UsbDevice,
    private val onDfuDetected: (UsbDevice) -> Unit,
    private val onRescan: () -> Unit
) {
    private val dialog = Dialog(context)
    private val title = TextView(context)
    private val instruction = TextView(context)
    private val timer = TextView(context)
    private val usbState = TextView(context)
    private val next = Button(context)
    private val cancel = Button(context)
    private var step = 0
    private var countdown: CountDownTimer? = null
    private var completed = false

    private val expectedEcid = AppleUsb.bootIdentifiers(recoveryDevice)?.ecidHex
    private val expectedCpid = AppleUsb.bootIdentifiers(recoveryDevice)?.cpidHex
    private val expectedBdid = AppleUsb.bootIdentifiers(recoveryDevice)?.bdidHex
    private val deviceName = runCatching { recoveryDevice.productName }.getOrNull() ?: "Apple device"

    private data class Step(val title: String, val text: String, val seconds: Int? = null)

    private val steps = listOf(
        Step(
            "Prepare the Mac",
            "Keep the Mac connected to this Android device with a data-capable USB cable. " +
                "Disconnect unnecessary USB accessories. iDeviceRestore will watch for Recovery " +
                "to disconnect and DFU to appear automatically."
        ),
        Step(
            "Shut down",
            "On the Mac, choose Shut Down from Recovery if available. If it will not shut down, " +
                "hold the power/Touch ID button until the display turns fully black."
        ),
        Step(
            "Start the DFU key sequence",
            "Press and hold the power/Touch ID button. While continuing to hold it, also press " +
                "and hold LEFT Control + LEFT Option + RIGHT Shift."
        ),
        Step(
            "Hold all four keys",
            "Keep holding power/Touch ID + LEFT Control + LEFT Option + RIGHT Shift.",
            10
        ),
        Step(
            "Release three keys",
            "Release Control, Option, and Shift, but KEEP holding the power/Touch ID button.",
            10
        ),
        Step(
            "Release power",
            "Release the power/Touch ID button. The Mac display should remain black. " +
                "iDeviceRestore is watching USB and will report DFU as soon as it appears."
        )
    )

    init {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        title.textSize = 20f
        instruction.textSize = 16f
        timer.textSize = 28f
        timer.visibility = View.GONE
        usbState.textSize = 15f
        usbState.text = "USB status: Recovery device connected"
        next.text = "Next"
        cancel.text = "Cancel"
        root.addView(title)
        root.addView(instruction)
        root.addView(timer)
        root.addView(usbState)
        root.addView(next)
        root.addView(cancel)
        dialog.setContentView(root)
        dialog.setTitle("Enter DFU Mode")
        dialog.setOnDismissListener { countdown?.cancel() }
        next.setOnClickListener { advance() }
        cancel.setOnClickListener { dialog.dismiss() }
        render()
    }

    fun show() = dialog.show()

    /** Called whenever Android reports a USB attach/detach or after a rescan. */
    fun onUsbDevicesChanged(devices: Collection<UsbDevice>) {
        if (completed) return

        val apple = devices.filter { it.vendorId == AppleUsb.APPLE_VID }
        val matchingDfu = apple.firstOrNull { device ->
            AppleUsb.mode(device) == AppleUsb.Mode.DFU && identityMatches(device)
        }
        if (matchingDfu != null) {
            showSuccess(matchingDfu)
            return
        }

        val matchingRecovery = apple.firstOrNull { device ->
            AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY && identityMatches(device)
        }
        usbState.text = when {
            matchingRecovery != null -> "USB status: Recovery device connected — waiting for DFU"
            apple.any { AppleUsb.mode(it) == AppleUsb.Mode.DFU } ->
                "USB status: a DFU device appeared, but its identity does not match the Recovery device"
            else -> "USB status: Recovery disconnected — waiting for DFU enumeration"
        }
    }

    private fun identityMatches(device: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(device)
        if (expectedEcid != null && ids?.ecidHex != null) {
            return expectedEcid.equals(ids.ecidHex, ignoreCase = true)
        }
        if (expectedCpid != null && ids?.cpidHex != null &&
            !expectedCpid.equals(ids.cpidHex, ignoreCase = true)) return false
        if (expectedBdid != null && ids?.bdidHex != null &&
            !expectedBdid.equals(ids.bdidHex, ignoreCase = true)) return false
        return true
    }

    private fun showSuccess(device: UsbDevice) {
        completed = true
        countdown?.cancel()
        title.text = "DFU mode detected"
        instruction.text =
            "iDeviceRestore detected the Apple device in DFU mode. The Mac display should remain black. " +
                "You can close this guide and continue with DFU communication."
        timer.visibility = View.GONE
        usbState.text = "USB status: DFU connected (VID=%04x PID=%04x)".format(device.vendorId, device.productId)
        next.text = "Done"
        next.isEnabled = true
        next.setOnClickListener { dialog.dismiss() }
        cancel.visibility = View.GONE
        onDfuDetected(device)
    }

    private fun advance() {
        if (completed) {
            dialog.dismiss()
            return
        }
        countdown?.cancel()
        if (step == steps.lastIndex) {
            onRescan()
            onUsbDevicesChanged(emptyList())
            next.text = "Check again"
            return
        }
        step++
        render()
    }

    private fun render() {
        if (completed) return
        val current = steps[step]
        title.text = "${step + 1}/${steps.size} — ${current.title}"
        instruction.text = current.text + "\n\nDetected device: $deviceName"
        next.text = if (step == steps.lastIndex) "Check again" else "Next"
        countdown?.cancel()
        val seconds = current.seconds
        if (seconds == null) {
            timer.visibility = View.GONE
            next.isEnabled = true
            return
        }
        timer.visibility = View.VISIBLE
        next.isEnabled = false
        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                timer.text = "${(ms + 999) / 1000} s"
            }

            override fun onFinish() {
                timer.text = "Done"
                next.isEnabled = true
            }
        }.start()
    }
}
