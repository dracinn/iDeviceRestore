package com.idevicerestore.android

import java.io.File

/** Process-local cache of restore components prepared without USB writes. */
object RestoreComponentPreparationStore {
    data class PreparedComponent(
        val name: String,
        val manifestPath: String,
        val file: File,
        val bytes: Long,
        val image4Validated: Boolean
    )

    data class Snapshot(
        val buildId: String,
        val identityIndex: Int,
        val components: List<PreparedComponent>,
        val preparedAtMillis: Long = System.currentTimeMillis()
    )

    @Volatile private var current: Snapshot? = null

    fun put(snapshot: Snapshot) { current = snapshot }
    fun get(): Snapshot? = current
    fun clear() { current = null }
}
