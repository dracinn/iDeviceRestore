package com.idevicerestore.android

import android.app.Dialog
import android.content.Context
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Human-guided DFU entry assistant. This intentionally does not send USB commands:
 * entering DFU on supported Apple hardware requires the physical button sequence.
 */
class DfuModeGuide(
    context: Context,
    private val deviceName: String?,
    private val onRescan: () -> Unit
) {
    private val dialog = Dialog(context)
    private val title = TextView(context)
    private val instruction = TextView(context)
    private val timer = TextView(context)
    private val next = Button(context)
    private val cancel = Button(context)
    private var step = 0
    private var countdown: CountDownTimer? = null

    private data class Step(val title: String, val text: String, val seconds: Int? = null)

    // Apple-silicon portable Macs use the power/Touch ID key plus the left-side
    // Control, Option, and Shift keys for the DFU sequence.
    private val steps = listOf(
        Step("Prepare the Mac", "Keep the Mac connected to this Android device with a data-capable USB cable. Disconnect unnecessary USB accessories. The Mac is currently in Recovery; the next steps will power it down."),
        Step("Shut down", "On the Mac, choose Shut Down from Recovery if available. If it will not shut down, hold the power/Touch ID button until the display turns fully black. Wait for the Mac to be completely off."),
        Step("Start the DFU key sequence", "Press and hold the power/Touch ID button. While continuing to hold it, also press and hold LEFT Control + LEFT Option + RIGHT Shift."),
        Step("Hold all four keys", "Keep holding power/Touch ID + LEFT Control + LEFT Option + RIGHT Shift.", 10),
        Step("Release three keys", "Release Control, Option, and Shift, but KEEP holding the power/Touch ID button.", 10),
        Step("Release power", "Release the power/Touch ID button. The Mac display should remain black in DFU mode. Tap Check for DFU and iDeviceRestore will rescan USB."),
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
        next.text = "Next"
        cancel.text = "Cancel"
        root.addView(title)
        root.addView(instruction)
        root.addView(timer)
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

    private fun advance() {
        countdown?.cancel()
        if (step == steps.lastIndex) {
            dialog.dismiss()
            onRescan()
            return
        }
        step++
        render()
    }

    private fun render() {
        val current = steps[step]
        title.text = "${step + 1}/${steps.size} — ${current.title}"
        instruction.text = current.text + (deviceName?.let { "\n\nDetected device: $it" } ?: "")
        next.text = if (step == steps.lastIndex) "Check for DFU" else "Next"
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
