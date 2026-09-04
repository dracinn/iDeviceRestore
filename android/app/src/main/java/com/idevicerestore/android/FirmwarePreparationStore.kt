package com.idevicerestore.android

/**
 * Process-local cache for one freshly verified firmware preparation context.
 *
 * The automatic TSS stage publishes the exact firmware, local IPSW location, device metadata,
 * and BuildIdentity preflight that it used. Downstream non-destructive preparation stages reuse
 * this context instead of repeating catalog verification and BuildManifest parsing.
 */
object FirmwarePreparationStore {
    data class Context(
        val device: FirmwareCatalog.Device,
        val firmware: FirmwareCatalog.Firmware,
        val location: FirmwareStorage.FirmwareLocation,
        val preflight: IpswPreflight.Result,
        val preparedAtMillis: Long = System.currentTimeMillis()
    ) {
        val buildId: String get() = firmware.buildId
        val identifier: String get() = firmware.identifier
        val identityIndex: Int get() = preflight.identityIndex

        fun matches(buildId: String, identityIndex: Int? = null): Boolean =
            this.buildId.equals(buildId, ignoreCase = true) &&
                (identityIndex == null || this.identityIndex == identityIndex)
    }

    @Volatile
    private var current: Context? = null

    fun put(value: Context) {
        current = value
    }

    fun get(): Context? = current

    fun clear() {
        current = null
    }
}
