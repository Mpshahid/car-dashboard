package com.cardash.dashboard.obd

/** A duplex text channel to an ELM327 adapter (Bluetooth in production). */
interface ObdConnection {
    val name: String
    suspend fun open()
    /** Send one command and read the reply up to the ELM '>' prompt. */
    suspend fun send(command: String): String
    fun close()
}
