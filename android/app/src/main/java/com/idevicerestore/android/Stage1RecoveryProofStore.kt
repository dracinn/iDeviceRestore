package com.idevicerestore.android

import android.os.SystemClock

/** Process-local proof that the currently connected Recovery device reported boot-stage=1. */
object Stage1RecoveryProofStore {
    enum class State { UNKNOWN, PROBING, PROVEN, FAILED }

    data class Snapshot(
        val state: State,
        val deviceIdentityKey: String? = null,
        val bootStage: String? = null,
        val buildVersion: String? = null,
        val message: String? = null,
        val provenAtElapsedMs: Long = 0L,
        val lastVerifiedAtElapsedMs: Long = 0L
    )

    @Volatile private var current = Snapshot(State.UNKNOWN)

    fun snapshot(): Snapshot = current

    @Synchronized fun begin(deviceIdentityKey: String) {
        current = Snapshot(State.PROBING, deviceIdentityKey = deviceIdentityKey)
    }

    @Synchronized fun prove(deviceIdentityKey: String, bootStage: String, buildVersion: String?) {
        val now = SystemClock.elapsedRealtime()
        current = Snapshot(
            state = State.PROVEN,
            deviceIdentityKey = deviceIdentityKey,
            bootStage = bootStage,
            buildVersion = buildVersion,
            message = "Stage-1 Recovery proven by read-only iBoot getenv",
            provenAtElapsedMs = now,
            lastVerifiedAtElapsedMs = now
        )
    }

    @Synchronized fun refresh(deviceIdentityKey: String, bootStage: String, buildVersion: String?) {
        val previous = current
        if (previous.state != State.PROVEN || previous.deviceIdentityKey != deviceIdentityKey) return
        current = previous.copy(
            bootStage = bootStage,
            buildVersion = buildVersion ?: previous.buildVersion,
            lastVerifiedAtElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    @Synchronized fun fail(deviceIdentityKey: String, message: String) {
        val previous = current
        current = Snapshot(
            state = State.FAILED,
            deviceIdentityKey = deviceIdentityKey,
            bootStage = previous.bootStage,
            buildVersion = previous.buildVersion,
            message = message,
            provenAtElapsedMs = previous.provenAtElapsedMs,
            lastVerifiedAtElapsedMs = previous.lastVerifiedAtElapsedMs
        )
    }

    @Synchronized fun reset() {
        current = Snapshot(State.UNKNOWN)
    }
}
