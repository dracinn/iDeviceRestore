package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden automatic coordinator that resolves, validates, and when possible locally personalizes
 * restore components. It performs no USB I/O and never sends an iBoot command or image upload.
 */
class RestoreComponentPrepareButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var completedKey: String? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshAutomaticState()
            if (isAttachedToWindow) postDelayed(this, REFRESH_MS)
        }
    }

    init {
        visibility = View.GONE
        isEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refreshRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRunnable)
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun refreshAutomaticState() {
        if (inFlight.get()) return
        val activity = activity() ?: return
        val firmwareStatus = activity.findViewById<TextView?>(R.id.firmwareStatus)?.text?.toString().orEmpty()
        if (!firmwareStatus.startsWith("Ready")) return

        val firmware = reflected<FirmwareCatalog.Firmware>(activity, "latestSignedFirmware") ?: return
        val device = reflected<FirmwareCatalog.Device>(activity, "identifiedDevice") ?: return
        val ipsw = reflected<File>(activity, "firmwareDestination") ?: return
        if (!ipsw.isFile) return

        val ticket = TssTicketStore.get()?.takeIf {
            it.buildId.equals(firmware.buildId, ignoreCase = true)
        }
        val ticketKey = ticket?.let { ":tss:${it.identityIndex}:${it.obtainedAtMillis}" } ?: ":no-tss"
        val key = "${firmware.buildId}:${ipsw.length()}:${ipsw.lastModified()}$ticketKey"
        if (completedKey == key) return
        prepare(activity, device, firmware, ipsw, ticket, key)
    }

    private fun prepare(
        activity: AppCompatActivity,
        device: FirmwareCatalog.Device,
        firmware: FirmwareCatalog.Firmware,
        ipsw: File,
        ticket: TssTicketStore.Ticket?,
        key: String
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        log(activity, "Restore component preparation: automatic read-only stage started")
        log(activity, "Restore component preparation: no USB command or image upload will be sent")

        worker.execute {
            try {
                require(firmware.fileSize <= 0L || ipsw.length() == firmware.fileSize) {
                    "IPSW size mismatch: expected=${firmware.fileSize} actual=${ipsw.length()}"
                }

                val preflightRequest = IpswPreflight.Request(
                    ipsw = ipsw,
                    identifier = device.identifier,
                    boardConfig = device.boardConfig,
                    chipId = device.cpid,
                    boardId = device.bdid
                )
                val preflight = IpswPreflightCache.inspect(preflightRequest) { log(activity, it) }
                require(preflight.productBuildVersion.equals(firmware.buildId, ignoreCase = true)) {
                    "BuildManifest build ${preflight.productBuildVersion} does not match selected build ${firmware.buildId}"
                }

                val matchingTicket = ticket?.takeIf { it.identityIndex == preflight.identityIndex }
                if (matchingTicket == null) {
                    log(activity, "Restore component personalization: no matching TSS ticket in this app session; extraction/validation only")
                } else {
                    log(activity, "Restore component personalization: matching TSS ticket available identity=${matchingTicket.identityIndex}")
                }

                val buildDir = ipsw.parentFile ?: error("IPSW build directory is unavailable")
                val componentDir = File(buildDir, "Components/RestorePreparation")
                val personalizedDir = File(buildDir, "Personalized/RestorePreparation")
                val extractor = IpswComponentExtractor(logger = { log(activity, it) })
                val prepared = mutableListOf<RestoreComponentPreparationStore.PreparedComponent>()
                var personalizedCount = 0
                var deferredCount = 0

                COMPONENTS.forEach { name ->
                    val path = preflight.componentPaths[name]
                    if (path == null) {
                        log(activity, "Restore component preparation: $name not present in identity ${preflight.identityIndex}; skipped")
                        return@forEach
                    }

                    val raw = extractor.extract(ipsw, preflight, name, componentDir)
                    require(raw.bytes > 0L) { "$name extracted as an empty file" }

                    val image4Validated = when {
                        name == "RestoreRamDisk" -> runCatching {
                            Image4StructureValidator.validateRawIm4p(raw.file, name)
                            true
                        }.getOrElse { validationError ->
                            log(
                                activity,
                                "Restore component preparation: RestoreRamDisk is not a prewrapped IM4P; " +
                                    "local personalization deferred (${validationError.message ?: validationError.javaClass.simpleName})"
                            )
                            false
                        }
                        name in REQUIRED_IM4P_COMPONENTS -> {
                            Image4StructureValidator.validateRawIm4p(raw.file, name)
                            true
                        }
                        else -> false
                    }

                    val isPersonalizableIm4p = image4Validated && name in PERSONALIZABLE_COMPONENTS
                    var personalizationState = when {
                        name == "RestoreRamDisk" && !image4Validated -> "deferred-non-im4p-ramdisk"
                        matchingTicket == null && isPersonalizableIm4p -> "awaiting-tss"
                        !isPersonalizableIm4p -> "not-applicable"
                        else -> "ready-to-personalize"
                    }
                    var personalizedFile: File? = null

                    if (matchingTicket != null && isPersonalizableIm4p) {
                        val result = RestoreImage4Personalizer.personalizeIfSafe(
                            raw = raw,
                            ticket = matchingTicket,
                            destinationDirectory = personalizedDir,
                            logger = { log(activity, it) }
                        )
                        personalizationState = result.status
                        personalizedFile = result.file
                        if (result.file != null) personalizedCount++
                        if (result.deferred) deferredCount++
                    } else if (matchingTicket != null && name == "RestoreRamDisk" && !image4Validated) {
                        deferredCount++
                    }

                    prepared += RestoreComponentPreparationStore.PreparedComponent(
                        name = name,
                        manifestPath = raw.manifestPath,
                        file = raw.file,
                        bytes = raw.bytes,
                        image4Validated = image4Validated,
                        personalizedFile = personalizedFile,
                        personalizedBytes = personalizedFile?.length(),
                        personalizationState = personalizationState
                    )
                    log(
                        activity,
                        "Restore component preparation: $name READY bytes=${raw.bytes} image4Validated=$image4Validated " +
                            "personalization=$personalizationState"
                    )
                }

                RestoreComponentPreparationStore.put(
                    RestoreComponentPreparationStore.Snapshot(
                        buildId = firmware.buildId,
                        identityIndex = preflight.identityIndex,
                        components = prepared
                    )
                )

                // Mark both extraction-only and personalized passes complete. When a matching TSS
                // ticket arrives the key changes, so exactly one follow-up personalization pass runs.
                completedKey = key

                log(
                    activity,
                    "Restore component preparation: READY build=${firmware.buildId} identity=${preflight.identityIndex} " +
                        "components=${prepared.size} personalized=$personalizedCount deferred=$deferredCount"
                )
                if (matchingTicket == null) {
                    log(activity, "Restore component preparation: personalization remains pending until matching TSS is available")
                }
                log(activity, "Restore component preparation: STOPPED before Recovery upload")
            } catch (t: Throwable) {
                RestoreComponentPreparationStore.clear()
                log(activity, "Restore component preparation FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private inline fun <reified T> reflected(activity: AppCompatActivity, fieldName: String): T? = runCatching {
        val field = activity.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(activity) as? T
    }.getOrNull()

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
        private const val REFRESH_MS = 1000L
        private val COMPONENTS = listOf("iBEC", "RestoreRamDisk", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
        private val REQUIRED_IM4P_COMPONENTS = setOf("iBEC", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
        private val PERSONALIZABLE_COMPONENTS = setOf("iBEC", "RestoreRamDisk", "RestoreDeviceTree", "RestoreSEP", "RestoreKernelCache")
    }
}
