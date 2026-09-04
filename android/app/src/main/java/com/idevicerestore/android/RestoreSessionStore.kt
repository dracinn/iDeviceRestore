package com.idevicerestore.android

/** Process-local restore transition state shared across DFU/Recovery re-enumeration. */
object RestoreSessionStore {
    val transitions = RestoreUsbStateMachine()

    @Volatile
    var activeBuildId: String? = null
        private set

    fun begin(buildId: String) {
        transitions.reset()
        activeBuildId = buildId
    }

    fun clear() {
        transitions.reset()
        activeBuildId = null
    }
}
