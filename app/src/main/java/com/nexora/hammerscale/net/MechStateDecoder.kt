package com.nexora.hammerscale.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

/**
 * Mech Arena (com.plarium.mechlegion) Lidgren UDP opcode 0x01 packet decoder.
 *
 * Confirmed layout:
 *   byte[0]    = 0x01  opcode
 *   byte[1:3]  = LE u16 sequence number  (+2 per tick)
 *   byte[3:5]  = LE u16 load field       (~256 idle, >272 active)
 *   byte[5:]   = Lidgren bit-packed payload  (LSB-first)
 *
 * Two message subtypes share opcode 0x01.  Only movement packets carry XYZ:
 *   byte[13] == 0x50  →  movement packet   (decode position)
 *   byte[13] == 0x06  →  non-movement type (skip — no XYZ here)
 *
 * For movement packets:
 *   yaw  = byte[7] * 360 / 256  (plain byte, 0–360°)
 *   XYZ  = Lidgren ReadRangedSingle at bit offset 50:
 *     X   24 bits  [-2000, 2000]  m
 *     Y   16 bits  [ -300,  300]  m
 *     Z   24 bits  [-2000, 2000]  m
 *
 * estF0=X(m)  estF1=Z(m)  estF2=yaw(rad, for overlay fmtAngle)  estF3=Y(m)
 */
data class TickState(
    val seq: Int,
    val tick: Int,
    val load: Int,
    val isActive: Boolean,
    val payloadSize: Int,
    val estF0: Float?,   // X position (metres)
    val estF1: Float?,   // Z position (metres)
    val estF2: Float?,   // yaw        (radians)
    val estF3: Float?,   // Y position (metres)
)

private fun lidgrenRanged(data: ByteArray, bitPos: Int, min: Float, max: Float, nBits: Int): Float {
    var raw = 0L
    for (i in 0 until nBits) {
        val b = bitPos + i
        if (b / 8 < data.size) raw = raw or (((data[b / 8].toLong() and 0xFF) shr (b % 8) and 1L) shl i)
    }
    return min + raw.toFloat() * (max - min) / ((1L shl nBits) - 1L).toFloat()
}

object MechStateDecoder {

    fun decode(data: ByteArray): TickState? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x01) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val seq  = buf.short.toInt() and 0xFFFF
        val load = buf.short.toInt() and 0xFFFF

        var posX: Float? = null; var posY: Float? = null
        var posZ: Float? = null; var yaw:  Float? = null

        val isMovementPacket = data.size > 13 && (data[13].toInt() and 0xFF) == 0x50
        if (isMovementPacket) {
            yaw = ((data[7].toInt() and 0xFF) * 2.0 * PI / 256.0).toFloat()

            var bp = 50
            val x = lidgrenRanged(data, bp, -2000f, 2000f, 24); bp += 24
            val y = lidgrenRanged(data, bp,  -300f,  300f, 16); bp += 16
            val z = lidgrenRanged(data, bp, -2000f, 2000f, 24)

            posX = x; posY = y; posZ = z
        }

        return TickState(
            seq         = seq,
            tick        = seq / 2,
            load        = load,
            isActive    = load > 272,
            payloadSize = data.size,
            estF0       = posX,
            estF1       = posZ,
            estF2       = yaw,
            estF3       = posY,
        )
    }

    fun loadFraction(load: Int): Float = ((load - 240).coerceAtLeast(0) / 360f).coerceIn(0f, 1f)

    fun stateLabel(state: TickState): String = when {
        !state.isActive  -> "IDLE  (load ${state.load})"
        state.load < 320 -> "ACTIVE — light input  (load ${state.load})"
        state.load < 450 -> "ACTIVE — movement  (load ${state.load})"
        else             -> "ACTIVE — heavy input  (load ${state.load})"
    }

    fun stateColor(state: TickState): Int = when {
        !state.isActive  -> android.graphics.Color.parseColor("#FF6E7681")
        state.load < 320 -> android.graphics.Color.parseColor("#FFD29922")
        state.load < 450 -> android.graphics.Color.parseColor("#FF3FB950")
        else             -> android.graphics.Color.parseColor("#FFFF7B72")
    }

    fun barColor(state: TickState): Int = when {
        !state.isActive  -> android.graphics.Color.parseColor("#FF444C56")
        state.load < 320 -> android.graphics.Color.parseColor("#FFD29922")
        state.load < 450 -> android.graphics.Color.parseColor("#FF3FB950")
        else             -> android.graphics.Color.parseColor("#FFFF7B72")
    }
}
