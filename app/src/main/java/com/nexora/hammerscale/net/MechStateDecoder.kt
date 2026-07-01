package com.nexora.hammerscale.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Mech Arena (com.plarium.mechlegion) Lidgren UDP packet decoder.
 *
 * Confirmed layout (opcode 0x01 packets, both client→server and server→client):
 *   byte[0]    = 0x01  opcode
 *   byte[1:3]  = LE u16 sequence number (increments +2 per tick)
 *   byte[3:5]  = LE u16 load field (~256 idle, >272 active)
 *   byte[5:]   = Lidgren bit-packed payload (bits read LSB-first)
 *
 * ActorMovementState starts at bit-offset +10 from byte 5:
 *   X   : ReadRangedSingle(-2000, 2000, 24 bits)   from dump.cs PositionXZRange/Bits
 *   Y   : ReadRangedSingle( -300,  300, 16 bits)   from dump.cs PositionYRange/Bits
 *   Z   : ReadRangedSingle(-2000, 2000, 24 bits)
 *   VelX: ReadRangedSingle( -200,  200, 16 bits)   VelocityXZRange/Bits
 *   VelY: ReadRangedSingle( -200,  200, 16 bits)   VelocityYRange/Bits
 *   VelZ: ReadRangedSingle( -200,  200, 16 bits)
 *   Yaw : ReadRangedSingle(    0,  360, 12 bits)   empirically determined
 *
 * Client-only: byte[7] also carries a simple 8-bit yaw:
 *   rot_deg = byte[7] * 360 / 256
 */
data class TickState(
    val seq: Int,           // bytes[1:3] LE u16
    val tick: Int,          // seq / 2
    val load: Int,          // bytes[3:5] LE u16
    val isActive: Boolean,  // load > 272
    val payloadSize: Int,
    // Lidgren-decoded position (null if decoding failed or out-of-range)
    val posX: Float?,
    val posY: Float?,
    val posZ: Float?,
    // Velocity
    val velX: Float?,
    val velY: Float?,
    val velZ: Float?,
    // Yaw from Lidgren bit buffer (0–360°)
    val yawLidgren: Float?,
    // Simple byte yaw from client packets (byte[7] * 360/256), null for server pkts
    val yawSimple: Float?,
)

// ── Lidgren bit-buffer ──────────────────────────────────────────────────────

private class BitBuffer(private val data: ByteArray, startBit: Int = 0) {
    private var pos = startBit

    fun readBits(n: Int): Long {
        var val_ = 0L
        for (i in 0 until n) {
            val bi = pos / 8
            val bo = pos % 8
            if (bi < data.size) val_ = val_ or (((data[bi].toLong() and 0xFF) shr bo and 1L) shl i)
            pos++
        }
        return val_
    }

    fun readRangedSingle(min: Float, max: Float, nBits: Int): Float {
        val raw = readBits(nBits)
        val maxRaw = (1L shl nBits) - 1L
        return min + raw.toFloat() * (max - min) / maxRaw.toFloat()
    }

    val hasRoom: Boolean get() = pos < data.size * 8
}

// ── Constants from BinarySerializationExtension in dump.cs ─────────────────

private const val POS_XZ_RANGE = 2000f
private const val POS_Y_RANGE  = 300f
private const val POS_XZ_BITS  = 24
private const val POS_Y_BITS   = 16

private const val VEL_XZ_RANGE = 200f
private const val VEL_Y_RANGE  = 200f
private const val VEL_XZ_BITS  = 16
private const val VEL_Y_BITS   = 16

private const val YAW_BITS     = 12   // empirically determined; gives 0.088° precision

/** Bit offset from byte 5 where ActorMovementState data begins. */
private const val PAYLOAD_BIT_OFFSET = 10

object MechStateDecoder {

    fun decode(data: ByteArray): TickState? {
        if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x01) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val seq  = buf.short.toInt() and 0xFFFF
        val load = buf.short.toInt() and 0xFFFF

        // ── Lidgren position / velocity / yaw ─────────────────────────────
        val startBit = 5 * 8 + PAYLOAD_BIT_OFFSET
        var posX: Float? = null; var posY: Float? = null; var posZ: Float? = null
        var velX: Float? = null; var velY: Float? = null; var velZ: Float? = null
        var yawLidgren: Float? = null

        if (data.size >= 16) {
            try {
                val bb = BitBuffer(data, startBit)
                val x = bb.readRangedSingle(-POS_XZ_RANGE, POS_XZ_RANGE, POS_XZ_BITS)
                val y = bb.readRangedSingle(-POS_Y_RANGE,  POS_Y_RANGE,  POS_Y_BITS)
                val z = bb.readRangedSingle(-POS_XZ_RANGE, POS_XZ_RANGE, POS_XZ_BITS)

                // Sanity: reject if coordinates are at the extreme boundary
                if (kotlin.math.abs(x) < 1990f && kotlin.math.abs(z) < 1990f && kotlin.math.abs(y) < 295f) {
                    posX = x; posY = y; posZ = z

                    val vx = bb.readRangedSingle(-VEL_XZ_RANGE, VEL_XZ_RANGE, VEL_XZ_BITS)
                    val vy = bb.readRangedSingle(-VEL_Y_RANGE,  VEL_Y_RANGE,  VEL_Y_BITS)
                    val vz = bb.readRangedSingle(-VEL_XZ_RANGE, VEL_XZ_RANGE, VEL_XZ_BITS)
                    velX = vx; velY = vy; velZ = vz

                    yawLidgren = bb.readRangedSingle(0f, 360f, YAW_BITS)
                }
            } catch (_: Exception) { }
        }

        // ── Simple byte-encoded yaw (client packets only, byte[7]) ─────────
        val yawSimple: Float? = if (data.size > 7) {
            val rotRaw = data[7].toInt() and 0xFF
            rotRaw.toFloat() * 360f / 256f
        } else null

        return TickState(
            seq          = seq,
            tick         = seq / 2,
            load         = load,
            isActive     = load > 272,
            payloadSize  = data.size,
            posX         = posX,
            posY         = posY,
            posZ         = posZ,
            velX         = velX,
            velY         = velY,
            velZ         = velZ,
            yawLidgren   = yawLidgren,
            yawSimple    = yawSimple,
        )
    }

    /** Load field → 0.0–1.0 fill fraction for a progress bar. */
    fun loadFraction(load: Int): Float = ((load - 240).coerceAtLeast(0) / 360f).coerceIn(0f, 1f)

    fun stateLabel(state: TickState): String = when {
        !state.isActive  -> "IDLE  (load ${state.load})"
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
