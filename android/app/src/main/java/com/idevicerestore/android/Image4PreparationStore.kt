package com.idevicerestore.android

/**
 * Process-local handoff from validated Image4 personalization to the future explicit DFU upload step.
 * No device-bound ticket material is written here; only metadata and the generated file reference are retained.
 */
object Image4PreparationStore {
    data class PreparedIbss(
        val result: Image4Personalizer.PersonalizedIbss,
        val sourceManifestPath: String,
        val preparedAtMillis: Long = System.currentTimeMillis()
    )

    @Volatile
    private var current: PreparedIbss? = null

    fun put(value: PreparedIbss) {
        current = value
    }

    fun get(): PreparedIbss? = current

    fun clear() {
        current = null
    }
}
