package com.idevicerestore.android

/** Process-local proof that the currently connected Recovery device reported boot-stage=1. */
object Stage1RecoveryProofStore {
    enum class State { UNKNOWN, PROBING, PROVEN, FAILED }

    data class Snapshot(
        val state: State,
        val deviceIdentityKey: String? = null,
        val bootStage: String? = null,
        val buildVersion: String? = null,
        val message: String? = null
    )

    @Volatile private var current = Snapshot(State.UNKNOWN)

    fun snapshot(): Snapshot = current

    @Synchronized fun begin(deviceIdentityKey: String) {
        current = Snapshot(State.PROBING, deviceIdentityKey = deviceIdentityKey)
    }

    @Synchronized fun prove(deviceIdentityKey: String, bootStage: String, buildVersion: String?) {
        current = Snapshot(
            state = State.PROVEN,
            deviceIdentityKey = deviceIdentityKey,
            bootStage = bootStage,
            buildVersion = buildVersion,
            message = "Stage-1 Recovery proven by read-only iBoot getenv"
        )
    }

    @Synchronized fun fail(deviceIdentityKey: String, message: String) {
        current = Snapshot(State.FAILED, deviceIdentityKey = deviceIdentityKey, message = message)
    }

    @Synchronized fun reset() {
        current = Snapshot(State.UNKNOWN)
    }
}
