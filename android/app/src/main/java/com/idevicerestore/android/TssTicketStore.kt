package com.idevicerestore.android

/**
 * Process-local handoff from the explicit TSS signing step to future Image4 personalization.
 * Device-bound values are deliberately kept in memory only and are never written to shared logs.
 */
object TssTicketStore {
    data class TbmData(
        val ucon: ByteArray?,
        val ucer: ByteArray?
    ) {
        fun copyDeep(): TbmData = TbmData(ucon?.copyOf(), ucer?.copyOf())
    }

    data class Ticket(
        val buildId: String,
        val identityIndex: Int,
        val foundation: TssRequestFoundation.Parameters,
        val apImg4Ticket: ByteArray,
        val componentTbm: Map<String, TbmData> = emptyMap(),
        val obtainedAtMillis: Long = System.currentTimeMillis()
    )

    @Volatile
    private var current: Ticket? = null

    fun put(ticket: Ticket) {
        current = ticket.copy(
            apImg4Ticket = ticket.apImg4Ticket.copyOf(),
            componentTbm = ticket.componentTbm.mapValues { it.value.copyDeep() }
        )
    }

    fun get(): Ticket? = current?.let {
        it.copy(
            apImg4Ticket = it.apImg4Ticket.copyOf(),
            componentTbm = it.componentTbm.mapValues { entry -> entry.value.copyDeep() }
        )
    }

    fun clear() {
        current = null
    }
}
