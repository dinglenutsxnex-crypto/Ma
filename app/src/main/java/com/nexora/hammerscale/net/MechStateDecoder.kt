package com.nexora.hammerscale.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mech Arena (com.plarium.mechlegion) Lidgren UDP opcode 0x01 packet decoder.
 *
 * Confirmed layout:
 *   byte[0]    = 0x01  opcode
 *   byte[1:3]  = LE u16 sequence number  (+2 per tick)
 *   byte[3:5]  = LE u16 load field       (~256 idle, >272 active)
 *   byte[5:]   = Lidgren bit-packed payload  (LSB-first)
 *
 * Position decode: tries two bit offsets (+10 and +18) and picks whichever
 * result is physically consistent with the last known position.
 * This handles variable-length headers before the movement block without
 * needing to fully parse every header field.
 *
 * Max mech speed is ~20 m/s; at 20 ticks/s that is 1 m/tick.
 * We gate at MAX_JUMP_M to suppress teleport artefacts.
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

// Lidgren ReadRangedSingle: reads nBits LSB-first, maps to [min, max]
private fun lidgrenRanged(data: ByteArray, bitPos: Int, min: Float, max: Float, nBits: Int): Float {
    var raw = 0L
    for (i in 0 until nBits) {
        val b = bitPos + i
        if (b / 8 < data.size) raw = raw or (((data[b / 8].toLong() and 0xFF) shr (b % 8) and 1L) shl i)
    }
    return min + raw.toFloat() * (max - min) / ((1L shl nBits) - 1L).toFloat()
}

// Try decoding XYZ+yaw starting at a given bit position; returns null if out of bounds
private fun tryDecode(data: ByteArray, startBit: Int): FloatArray? {
    val need = startBit + 24 + 16 + 24 + 16 + 16 + 16 + 12
    if (need / 8 >= data.size) return null
    var bp = startBit
    val x  = lidgrenRanged(data, bp, -2000f, 2000f, 24); bp += 24
    val y  = lidgrenRanged(data, bp,  -300f,  300f, 16); bp += 16
    val z  = lidgrenRanged(data, bp, -2000f, 2000f, 24); bp += 24
    bp += 16 + 16 + 16   // skip vx, vy, vz
    val yawDeg = lidgrenRanged(data, bp, 0f, 360f, 12)
    if (abs(x) > 1990f || abs(z) > 1990f || abs(y) > 295f) return null
    return floatArrayOf(x, y, z, yawDeg)
}

// Constants from BinarySerializationExtension in dump.cs
private const val XZ_B = 24
private const val Y_B  = 16
private const val V_B  = 16
private const val YAW_B = 12

// Max plausible displacement per tick (generous — ~50 m covers any sprint + lag)
private const val MAX_JUMP_M = 50f

object MechStateDecoder {

    // Last confirmed valid position — kept across decode() calls
    private var lastX: Float? = null
    private var lastZ: Float? = null

    fun decode(data: ByteArray): TickState? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x01) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val seq  = buf.short.toInt() and 0xFFFF
        val load = buf.short.toInt() and 0xFFFF

        var posX: Float? = null; var posY: Float? = null
        var posZ: Float? = null; var yaw:  Float? = null

        if (data.size >= 14) {
            // Try the two known offsets: +10 bits and +18 bits (header varies by 1 byte)
            val candidates = listOf(
                tryDecode(data, 5 * 8 + 10),
                tryDecode(data, 5 * 8 + 18),
            ).filterNotNull()

            val best: FloatArray? = when {
                candidates.isEmpty() -> null
                lastX == null        -> candidates.first()   // no reference yet, take first valid
                else -> {
                    // Pick whichever candidate is closest to last known position
                    candidates.minByOrNull { c ->
                        val dx = c[0] - lastX!!; val dz = c[2] - lastZ!!
                        sqrt(dx * dx + dz * dz)
                    }
                }
            }

            if (best != null) {
                val dx = best[0] - (lastX ?: best[0])
                val dz = best[2] - (lastZ ?: best[2])
                val dist = sqrt(dx * dx + dz * dz)
                if (dist < MAX_JUMP_M || lastX == null) {
                    posX = best[0]; posY = best[1]; posZ = best[2]
                    yaw  = (best[3] * PI / 180.0).toFloat()
                    lastX = posX; lastZ = posZ
                }
                // else: jump too large — leave posX/Z null so overlay holds last value
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

    /** Call this when the VPN session ends so state doesn't bleed into the next battle. */
    fun reset() { lastX = null; lastZ = null }

    /** Load field → 0.0–1.0 fill fraction for a progress bar.
     *  Idle baseline ~256. Active range observed up to ~600. */
    fun loadFraction(load: Int): Float = ((load - 240).coerceAtLeast(0) / 360f).coerceIn(0f, 1f)

    /** Human-readable state string. */
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
