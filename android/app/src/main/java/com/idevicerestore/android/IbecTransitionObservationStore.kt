package com.idevicerestore.android

/** Process-local state for the guarded iBEC transition so activity recreation cannot lose observation. */
object IbecTransitionObservationStore {
    enum class State { IDLE, EXECUTING, WAITING_FOR_RECOVERY, SUCCEEDED, FAILED }
    enum class Boundary { IBEC_EXECUTION, UPLOAD_INIT_ONLY }

    data class Snapshot(
        val state: State,
        val boundary: Boundary = Boundary.IBEC_EXECUTION,
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

    @Synchronized fun begin(sourceUsbKey: String, boundary: Boundary = Boundary.IBEC_EXECUTION) {
        current = Snapshot(State.EXECUTING, boundary = boundary, sourceUsbKey = sourceUsbKey)
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

    /**
     * Consumes exactly one successful upload-init re-enumeration proof.
     *
     * The caller must re-prove the current Recovery boot-stage/build before invoking this method.
     * A successful consume moves back to EXECUTING so the existing transition/watchdog UI remains
     * suppressed while the bulk transfer owns Recovery. The caller must invoke
     * [finishConsumedUpload] only after the upload connection is closed.
     */
    @Synchronized fun consumeUploadInitProof(
        currentUsbKey: String,
        bootStage: String,
        buildVersion: String
    ): Boolean {
        val s = current
        val matches = s.state == State.SUCCEEDED &&
            s.boundary == Boundary.UPLOAD_INIT_ONLY &&
            s.lastObservedUsbKey == currentUsbKey &&
            s.postBootStage?.trim() == bootStage.trim() &&
            s.postBuildVersion?.trim() == buildVersion.trim() &&
            bootStage.trim() == "1" &&
            buildVersion.isNotBlank()
        if (!matches) return false
        current = s.copy(
            state = State.EXECUTING,
            message = CONSUMED_UPLOAD_MESSAGE
        )
        return true
    }

    @Synchronized fun finishConsumedUpload() {
        if (
            current.state == State.EXECUTING &&
            current.boundary == Boundary.UPLOAD_INIT_ONLY &&
            current.message == CONSUMED_UPLOAD_MESSAGE
        ) {
            current = Snapshot(State.IDLE)
        }
    }

    @Synchronized fun reset() {
        current = Snapshot(State.IDLE)
    }

    private const val CONSUMED_UPLOAD_MESSAGE =
        "Upload-init proof consumed; post-init iBEC bulk upload owns Recovery transport"
}
