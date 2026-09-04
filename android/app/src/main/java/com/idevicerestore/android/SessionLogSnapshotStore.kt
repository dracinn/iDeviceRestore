package com.idevicerestore.android

/**
 * Process-local snapshot used to hand the current diagnostic logs to LogsActivity without putting
 * potentially large strings into an Intent/Bundle and risking Android's Binder transaction limit.
 */
object SessionLogSnapshotStore {
    data class Snapshot(
        val activityLog: String,
        val probeLog: String
    )

    @Volatile
    private var current = Snapshot("", "")

    fun update(activityLog: CharSequence?, probeLog: CharSequence?) {
        current = Snapshot(
            activityLog = activityLog?.toString().orEmpty(),
            probeLog = probeLog?.toString().orEmpty()
        )
    }

    fun snapshot(): Snapshot = current
}
