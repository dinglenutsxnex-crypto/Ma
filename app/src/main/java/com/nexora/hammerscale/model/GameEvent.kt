package com.nexora.hammerscale.model

import java.text.SimpleDateFormat
import java.util.*

sealed class GameEvent {
    val timestamp: Long = System.currentTimeMillis()
    val timeStr: String get() =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    data class GameServerDetected(
        val ip: String,
        val port: Int,
        val proto: String = "UDP"
    ) : GameEvent()

    data class HostDetected(
        val host: String,
        val ip: String,
        val proto: String
    ) : GameEvent()
}
