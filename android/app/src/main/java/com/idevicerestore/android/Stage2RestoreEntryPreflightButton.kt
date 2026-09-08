package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded post-Stage-2 preflight that mirrors the read-only portion of upstream
 * recovery_enter_restore() and stops before recovery_set_autoboot(false)/saveenv.
 *
 * USB operations are getenv-only. Restore-entry components are inspected locally from the selected
 * BuildIdentity and the existing preparation cache; no image payload or iBoot mutation command is sent.
 */
class Stage2RestoreEntryPreflightButton @JvmOverloads constructor(
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
            text = "Stage-2 restore-entry preflight running…"
            return
        }
        val activity = activity() ?: run { isEnabled = false; return }
        val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val recovery = permittedM1Recovery(usb)
        val firmware = FirmwarePreparationStore.get()
        val ticket = TssTicketStore.get()
        val prepared = RestoreComponentPreparationStore.get()
        isEnabled = recovery != null && firmware != null && ticket != null && prepared != null &&
            firmware.matches(ticket.buildId, ticket.identityIndex) &&
            prepared.buildId.equals(ticket.buildId, ignoreCase = true) &&
            prepared.identityIndex == ticket.identityIndex &&
            foundationMatchesDevice(ticket.foundation, recovery)
        text = READY_LABEL
    }

    private fun confirm() {
        val activity = activity() ?: return
        if (!isEnabled || inFlight.get()) return
        AlertDialog.Builder(activity)
            .setTitle("Run Stage-2 restore-entry preflight?")
            .setMessage(
                "This diagnostic requires an M1 already at boot-stage=2. It performs only read-only getenv queries and local BuildIdentity/component inspection. " +
                    "It deliberately stops immediately before upstream would send 'setenv auto-boot false' and 'saveenv'. No RestoreLogo, firmware, RestoreRamDisk, DeviceTree, SEP, KernelCache, bootx, restore, or erase command is sent."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run read-only preflight") { _, _ -> start() }
            .show()
    }

    private fun start() {
        val activity = activity() ?: return
        if (!inFlight.compareAndSet(false, true)) return
        isEnabled = false
        text = "Stage-2 restore-entry preflight running…"
        log(activity, "Stage-2 restore-entry preflight: START boundary=getenv+local-manifest-only; stop-before=setenv-auto-boot-false/saveenv")

        worker.execute {
            var lease: UsbOperationReservation.Lease? = null
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                lease = UsbOperationReservation.tryAcquire(RESERVATION_OWNER)
                    ?: error("Another USB operation is active: ${UsbOperationReservation.owner() ?: "unknown"}")

                val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
                val recovery = permittedM1Recovery(usb) ?: error("No permitted M1 Recovery device is connected")
                val firmware = FirmwarePreparationStore.get() ?: error("Firmware preparation context unavailable")
                val ticket = TssTicketStore.get() ?: error("TSS ticket unavailable")
                val prepared = RestoreComponentPreparationStore.get() ?: error("Restore component preparation unavailable")
                require(firmware.matches(ticket.buildId, ticket.identityIndex)) { "Firmware/TSS identity mismatch" }
                require(prepared.buildId.equals(ticket.buildId, ignoreCase = true) && prepared.identityIndex == ticket.identityIndex) {
                    "Prepared restore components/TSS identity mismatch"
                }
                require(foundationMatchesDevice(ticket.foundation, recovery)) { "Recovery device does not match TSS foundation" }

                connection = usb.openDevice(recovery) ?: error("Could not open M1 Recovery device")
                val claimed = AppleUsb.claimBestInterface(recovery, connection) ?: error("Could not claim Recovery interface")
                val transport = RecoveryTransport(connection, claimed.bulkIn)

                val stage = transport.getenv("boot-stage").value.trim()
                require(stage == STAGE_2) { "Expected boot-stage=2, got '$stage'" }
                val build = transport.getenv("build-version").value.trim()
                val style = transport.getenv("build-style").value.trim()
                val autoBootBefore = transport.getenv("auto-boot").value.trim()
                val ramdiskSize = runCatching { transport.getenv("ramdisk-size").value.trim() }.getOrNull()
                val radioError = runCatching { transport.getenv("radio-error").value.trim() }.getOrNull()
                val radioErrorString = if (radioError != null && radioError != "0") {
                    runCatching { transport.getenv("radio-error-string").value.trim() }.getOrNull()
                } else null

                log(activity, "Stage-2 restore-entry environment: boot-stage=$stage build-version=$build build-style=$style auto-boot=$autoBootBefore ramdisk-size=${ramdiskSize ?: "unavailable"} radio-error=${radioError ?: "unavailable"}")
                if (!radioErrorString.isNullOrBlank()) log(activity, "Stage-2 restore-entry environment: radio-error-string=$radioErrorString")

                val identity = IpswBuildIdentityReader { message -> log(activity, "Stage-2 restore-entry $message") }
                    .read(firmware.location.file, ticket.identityIndex)
                    .identity
                val manifest = identity.dict("Manifest") ?: error("Selected BuildIdentity has no Manifest dictionary")

                val loadedByIboot = manifest.values.mapNotNull { (name, node) ->
                    val entry = node as? PlistNode.Dict ?: return@mapNotNull null
                    val info = entry.dict("Info") ?: return@mapNotNull null
                    val stage1 = info.bool("IsLoadedByiBootStage1") == true
                    val loaded = info.bool("IsLoadedByiBoot") == true
                    name.takeIf { loaded && !stage1 }
                }.sorted()
                log(activity, "Stage-2 restore-entry manifest: IsLoadedByiBoot(non-Stage1)=${loadedByIboot.ifEmpty { listOf("none") }.joinToString(",")}")

                val upstreamOrder = buildList {
                    if (manifest.values.containsKey("RestoreLogo")) add("RestoreLogo")
                    addAll(loadedByIboot)
                    add("RestoreRamDisk")
                    add("RestoreDeviceTree")
                    if (manifest.values.containsKey("RestoreSEP")) add("RestoreSEP")
                    add("RestoreKernelCache")
                }.distinct()

                val preparedByName = prepared.components.associateBy { it.name }
                upstreamOrder.forEachIndexed { index, name ->
                    val manifestEntry = manifest.values[name] as? PlistNode.Dict
                        ?: error("Required upstream restore-entry component '$name' is missing from BuildIdentity")
                    val path = manifestEntry.dict("Info")?.string("Path")
                        ?: manifestEntry.string("Path")
                        ?: firmware.preflight.componentPaths[name]
                        ?: "(path unavailable)"
                    val local = preparedByName[name]
                    val localState = if (local == null) {
                        "not-yet-materialized"
                    } else {
                        "bytes=${local.bytes} image4Validated=${local.image4Validated} personalization=${local.personalizationState} personalizedBytes=${local.personalizedBytes ?: 0}"
                    }
                    log(activity, "Stage-2 restore-entry plan ${index + 1}/${upstreamOrder.size}: $name path=$path local=$localState SEND=NO")
                }

                val autoBootAfter = transport.getenv("auto-boot").value.trim()
                require(autoBootAfter == autoBootBefore) {
                    "Read-only invariant failed: auto-boot changed from '$autoBootBefore' to '$autoBootAfter'"
                }
                val finalStage = transport.getenv("boot-stage").value.trim()
                require(finalStage == STAGE_2) { "Stage changed during read-only preflight: $finalStage" }

                log(activity, "Stage-2 restore-entry preflight: PASS auto-boot unchanged=$autoBootAfter boot-stage=2")
                log(activity, "Stage-2 restore-entry preflight: STOP boundary reached immediately before upstream setenv auto-boot false + saveenv; zero restore-entry payloads sent")
            } catch (t: Throwable) {
                log(activity, "Stage-2 restore-entry preflight FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                connection?.close()
                lease?.let { runCatching { UsbOperationReservation.release(it) } }
                inFlight.set(false)
                activity.runOnUiThread { if (isAttachedToWindow) refreshState() }
            }
        }
    }

    private fun permittedM1Recovery(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { device ->
        device.vendorId == AppleUsb.APPLE_VID &&
            AppleUsb.mode(device) == AppleUsb.Mode.RECOVERY &&
            usb.hasPermission(device) &&
            AppleUsb.bootIdentifiers(device)?.cpidHex.equals(M1_CPID, ignoreCase = true)
    }

    private fun foundationMatchesDevice(f: TssRequestFoundation.Parameters, d: UsbDevice): Boolean {
        val ids = AppleUsb.bootIdentifiers(d) ?: return false
        val ecid = ids.ecidHex?.toULongOrNull(16) ?: return false
        val cpid = ids.cpidHex?.toLongOrNull(16) ?: return false
        val bdid = ids.bdidHex?.toLongOrNull(16) ?: return false
        return f.ecid == ecid && f.apChipId == cpid && f.apBoardId == bdid
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

    private fun activity(): AppCompatActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val M1_CPID = "8103"
        private const val STAGE_2 = "2"
        private const val RESERVATION_OWNER = "stage2-restore-entry-preflight"
        private const val READY_LABEL = "Preflight M1 Stage-2 Restore Entry (read-only)"
    }
}
