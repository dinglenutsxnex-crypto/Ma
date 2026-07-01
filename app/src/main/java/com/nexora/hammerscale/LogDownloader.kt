package com.nexora.hammerscale

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nexora.hammerscale.model.LiveMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogDownloader {

    /**
     * Export only battle-flow (UDP port 7015) packets.
     *
     * File naming:
     *   client_<type>_<n>.bin   — outbound (client → game server)
     *   server_<type>_<n>.bin   — inbound  (game server → client)
     *
     * Where <type> is the classified opcode label:
     *   ping / ping_ack / clock_sync / tick_idle / tick_action / world_state / unknown_0xNN
     *
     * Consecutive packets of the same direction+type are grouped:
     *   client_ping_1.bin, client_ping_2.bin …
     *   client_tick_action_1.bin, client_tick_action_2.bin …
     */
    fun downloadAndShare(context: Context, messages: List<LiveMessage>) {
        if (messages.isEmpty()) {
            Toast.makeText(context, "No battle packets to export", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val logsDir = File(context.cacheDir, "logs").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(logsDir, "mech_battle_$ts.zip")

            // Per-type counters so names are unique
            val typeCounters = mutableMapOf<String, Int>()

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                // Write a human-readable manifest first
                zos.putNextEntry(ZipEntry("_manifest.txt"))
                val manifestLines = buildString {
                    appendLine("HAMMERSCALE — Mech Arena Battle Flow Export")
                    appendLine("Generated: $ts")
                    appendLine("Total packets: ${messages.size}")
                    appendLine()
                    appendLine("Packet types (from Mech Arena Lidgren UDP protocol):")
                    appendLine("  ping        0x81  9 B   client heartbeat  → server")
                    appendLine("  ping_ack    0x81  9 B   server heartbeat ACK")
                    appendLine("  clock_sync  0x82  13 B  client clock-sync → server")
                    appendLine("  tick_idle   0x01  35-37 B  per-tick input (no movement)")
                    appendLine("  tick_action 0x01  38-79 B  per-tick input (movement/shooting active)")
                    appendLine("  world_state 0x01  variable server world-state update")
                    appendLine("  unknown_0xNN       uncharacterised opcode")
                    appendLine()
                    appendLine("Index:")
                    var clientN = 0; var serverN = 0
                    for (msg in messages) {
                        val dir = if (msg.direction == LiveMessage.Direction.OUTBOUND) { clientN++; "client[$clientN]" } else { serverN++; "server[$serverN]" }
                        appendLine("  $dir  ${msg.commandName ?: "unknown"}  ${msg.data.size} B  ${
                            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(msg.timestamp))
                        }")
                    }
                }
                zos.write(manifestLines.toByteArray())
                zos.closeEntry()

                // Write each packet as its own .bin file
                for (msg in messages) {
                    val dirPrefix = if (msg.direction == LiveMessage.Direction.OUTBOUND) "client" else "server"
                    val typeTag   = msg.commandName?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "unknown"
                    val key       = "${dirPrefix}_${typeTag}"
                    val n         = typeCounters.merge(key, 1, Int::plus)!!
                    zos.putNextEntry(ZipEntry("${key}_${n}.bin"))
                    zos.write(msg.data)
                    zos.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Mech Arena battle flow — HAMMERSCALE")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Export battle packets"
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )

        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
