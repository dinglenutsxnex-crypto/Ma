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
 * ActorMovementState at payload bit-offset +10 (confirmed empirically):
 *   X   ReadRangedSingle(-2000, 2000, 24)  — PositionXZRange/Bits from dump.cs
 *   Y   ReadRangedSingle( -300,  300, 16)  — PositionYRange/Bits
 *   Z   ReadRangedSingle(-2000, 2000, 24)
 *   VX  ReadRangedSingle( -200,  200, 16)  — VelocityXZRange/Bits
 *   VY  ReadRangedSingle( -200,  200, 16)  — VelocityYRange/Bits
 *   VZ  ReadRangedSingle( -200,  200, 16)
 *   Yaw ReadRangedSingle(    0,  360, 12)  — empirically confirmed
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

// Lidgren ReadRangedSingle: reads nBits LSB-first and maps to [min, max]
private fun lidgrenRanged(data: ByteArray, bitPos: Int, min: Float, max: Float, nBits: Int): Float {
    var raw = 0L
    for (i in 0 until nBits) {
        val b = bitPos + i
        if (b / 8 < data.size) raw = raw or (((data[b / 8].toLong() and 0xFF) shr (b % 8) and 1L) shl i)
    }
    return min + raw.toFloat() * (max - min) / ((1L shl nBits) - 1L).toFloat()
}

// Constants from BinarySerializationExtension in dump.cs
private const val XZ = 2000f; private const val XZ_B = 24
private const val Y  = 300f;  private const val Y_B  = 16
private const val V  = 200f;  private const val V_B  = 16
private const val YAW_B = 12  // empirically determined; 0.088° precision

object MechStateDecoder {

    fun decode(data: ByteArray): TickState? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x01) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val seq  = buf.short.toInt() and 0xFFFF
        val load = buf.short.toInt() and 0xFFFF

        var posX: Float? = null; var posY: Float? = null
        var posZ: Float? = null; var yaw:  Float? = null

        if (data.size >= 14) {
            var bp = 5 * 8 + 10   // payload starts at byte 5, position at +10 bits
            val x = lidgrenRanged(data, bp, -XZ, XZ, XZ_B); bp += XZ_B
            val y = lidgrenRanged(data, bp,  -Y,  Y,  Y_B); bp += Y_B
            val z = lidgrenRanged(data, bp, -XZ, XZ, XZ_B); bp += XZ_B
            if (kotlin.math.abs(x) < 1990f && kotlin.math.abs(z) < 1990f && kotlin.math.abs(y) < 295f) {
                posX = x; posY = y; posZ = z
                bp += V_B + V_B + V_B   // skip velocity
                yaw = (lidgrenRanged(data, bp, 0f, 360f, YAW_B) * PI / 180.0).toFloat()
            }
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
