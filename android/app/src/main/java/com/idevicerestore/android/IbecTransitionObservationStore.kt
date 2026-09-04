package com.idevicerestore.android

/** Process-local state for the guarded iBEC transition so activity recreation cannot lose observation. */
object IbecTransitionObservationStore {
    enum class State { IDLE, EXECUTING, WAITING_FOR_RECOVERY, SUCCEEDED, FAILED }

    data class Snapshot(
        val state: State,
        val sourceUsbKey: String? = null,
        val startedAtElapsedMs: Long = 0L,
        val lastObservedUsbKey: String? = null,
        val preBootStage: String? = null,
        val preBuildVersion: String? = null,
        val postBootStage: String? = null,
        val postBuildVersion: String? = null,
        val classification: String? = null,
        val message: String? = null
    )

    @Volatile private var current = Snapshot(State.IDLE)

    fun snapshot(): Snapshot = current

    @Synchronized fun begin(sourceUsbKey: String) {
        current = Snapshot(State.EXECUTING, sourceUsbKey = sourceUsbKey)
    }

    @Synchronized fun recordPreUpload(bootStage: String, buildVersion: String?) {
        current = current.copy(preBootStage = bootStage, preBuildVersion = buildVersion)
    }

    @Synchronized fun waiting(startedAtElapsedMs: Long) {
        current = current.copy(state = State.WAITING_FOR_RECOVERY, startedAtElapsedMs = startedAtElapsedMs, lastObservedUsbKey = null, postBootStage = null, postBuildVersion = null, classification = null, message = null)
    }

    @Synchronized fun observed(usbKey: String) {
        current = current.copy(lastObservedUsbKey = usbKey)
    }

    @Synchronized fun recordPostState(bootStage: String?, buildVersion: String?, classification: String) {
        current = current.copy(postBootStage = bootStage, postBuildVersion = buildVersion, classification = classification)
    }

    @Synchronized fun succeed(message: String) {
        current = current.copy(state = State.SUCCEEDED, message = message)
    }

    @Synchronized fun fail(message: String) {
        current = current.copy(state = State.FAILED, message = message)
    }

    @Synchronized fun reset() {
        current = Snapshot(State.IDLE)
    }
}
