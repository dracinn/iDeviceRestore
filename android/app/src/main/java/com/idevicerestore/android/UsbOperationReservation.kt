package com.idevicerestore.android

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide reservation for exclusive USB state-changing sequences.
 *
 * Automatic probes/watchdogs must remain read-only and back off while a reservation is held so
 * they cannot claim the same short-lived DFU/Recovery personality as an explicitly authorized
 * transition.
 */
object UsbOperationReservation {
    data class Lease internal constructor(
        val owner: String,
        internal val token: Any = Any()
    )

    private val active = AtomicReference<Lease?>(null)

    fun tryAcquire(owner: String): Lease? {
        require(owner.isNotBlank()) { "USB reservation owner is required" }
        val lease = Lease(owner)
        return if (active.compareAndSet(null, lease)) lease else null
    }

    fun release(lease: Lease) {
        check(active.compareAndSet(lease, null)) {
            "USB operation reservation is not owned by ${lease.owner}"
        }
    }

    fun isReserved(): Boolean = active.get() != null

    fun owner(): String? = active.get()?.owner
}
