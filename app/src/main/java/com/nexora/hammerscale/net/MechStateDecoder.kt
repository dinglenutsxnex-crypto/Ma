package com.nexora.hammerscale.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Best-effort decoder for Mech Arena Lidgren UDP opcode 0x01 packets.
 *
 * Confirmed layout (from ma.pcapng analysis):
 *   byte[0]    = 0x01 (opcode)
 *   byte[1:3]  = LE u16 sequence number (increments +2 per tick)
 *   byte[3:5]  = LE u16 load field (~256 idle, >272 active)
 *   byte[5..]  = input/state batch — structure UNCONFIRMED
 *
 * The est. (estimated) fields read bytes[5+] as LE float32.
 * They will be NaN / ±Inf when the bytes don't represent a valid float,
 * which indicates they are not a position/rotation field at that offset.
 * These are labelled "est." in the UI and should not be treated as ground truth
 * until a labeled capture isolates individual actions.
 */
data class TickState(
    val seq: Int,           // bytes[1:3] — confirmed
    val tick: Int,          // seq / 2
    val load: Int,          // bytes[3:5] — confirmed
    val isActive: Boolean,  // load > 272
    val payloadSize: Int,   // total bytes
    val estF0: Float?,      // bytes[5:9]  as LE float32
    val estF1: Float?,      // bytes[9:13] as LE float32
    val estF2: Float?,      // bytes[13:17] as LE float32
    val estF3: Float?,      // bytes[17:21] as LE float32
)

object MechStateDecoder {

    fun decode(data: ByteArray): TickState? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x01) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val seq  = buf.short.toInt() and 0xFFFF
        val load = buf.short.toInt() and 0xFFFF

        fun readFloat(offset: Int): Float? {
            if (data.size < offset + 4) return null
            val f = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
            return if (f.isNaN() || f.isInfinite() || f < -100_000f || f > 100_000f) null else f
        }

        return TickState(
            seq         = seq,
            tick        = seq / 2,
            load        = load,
            isActive    = load > 272,
            payloadSize = data.size,
            estF0       = readFloat(5),
            estF1       = readFloat(9),
            estF2       = readFloat(13),
            estF3       = readFloat(17),
        )
    }

    /** Load field → 0.0–1.0 fill fraction for a progress bar.
     *  Idle baseline ~256. Active range observed up to ~600. */
    fun loadFraction(load: Int): Float = ((load - 240).coerceAtLeast(0) / 360f).coerceIn(0f, 1f)

    /** Human-readable state string. */
    fun stateLabel(state: TickState): String = when {
        !state.isActive -> "IDLE  (load ${state.load})"
        state.load < 320 -> "ACTIVE — light input  (load ${state.load})"
        state.load < 450 -> "ACTIVE — movement  (load ${state.load})"
        else             -> "ACTIVE — heavy input  (load ${state.load})"
    }

    fun stateColor(state: TickState): Int = when {
        !state.isActive   -> android.graphics.Color.parseColor("#FF6E7681")
        state.load < 320  -> android.graphics.Color.parseColor("#FFD29922")
        state.load < 450  -> android.graphics.Color.parseColor("#FF3FB950")
        else              -> android.graphics.Color.parseColor("#FFFF7B72")
    }

    fun barColor(state: TickState): Int = when {
        !state.isActive   -> android.graphics.Color.parseColor("#FF444C56")
        state.load < 320  -> android.graphics.Color.parseColor("#FFD29922")
        state.load < 450  -> android.graphics.Color.parseColor("#FF3FB950")
        else              -> android.graphics.Color.parseColor("#FFFF7B72")
    }
}
